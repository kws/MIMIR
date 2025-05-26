package com.kajsiebert.sip.openai;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.Base64;

/**
 * MediaStreamer implementation using Vert.x for non-blocking UDP and WebSocket.
 */
public class VertxMediaStreamer implements MediaStreamer {
    private static final Logger LOG = LoggerFactory.getLogger(VertxMediaStreamer.class);
    private final Vertx vertx;
    private final FlowSpec flowSpec;
    private DatagramSocket udpSocket;
    private WebSocket webSocket;
    private boolean sessionCreated;
    private boolean readyToSend;
    private static final int RTP_PACKET_SIZE = 160; // 20ms of G.711 audio at 8kHz
    private static final int RTP_HEADER_SIZE = 12;
    private static final long PACKET_INTERVAL_MS = 20; // 20ms between packets
    private Buffer audioBuffer = Buffer.buffer(2048);
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private static final int SSRC = 0x12345678; // Random SSRC identifier
    private final Queue<Buffer> rtpQueue = new ConcurrentLinkedQueue<>();
    private long periodicTimerId = -1;

    private Buffer createRtpPacket(byte[] payload) {
        Buffer packet = Buffer.buffer(RTP_HEADER_SIZE + payload.length);
        
        // RTP Header (12 bytes)
        // Version 2, no padding, no extension, no CSRC
        packet.appendByte((byte) 0x80);
        // Payload type 0 for PCMU
        packet.appendByte((byte) 0x00);
        // Sequence number (16 bits)
        packet.appendShort((short) sequenceNumber);
        // Timestamp (32 bits)
        packet.appendInt((int) timestamp);
        // SSRC (32 bits)
        packet.appendInt(SSRC);
        
        // Payload
        packet.appendBytes(payload);
        
        // Update sequence number and timestamp
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        timestamp += payload.length;
        
        return packet;
    }

    private void sendRtpPacket(byte[] payload) {
        Buffer rtpPacket = createRtpPacket(payload);
        rtpQueue.offer(rtpPacket);
    }

    public VertxMediaStreamer(Vertx vertx, FlowSpec flowSpec) {
        this.vertx = vertx;
        this.flowSpec = flowSpec;
    }

    @Override
    public boolean start() {
        // Setup UDP socket for receiving from SIP and sending to OpenAI
        DatagramSocketOptions options = new DatagramSocketOptions();
        udpSocket = vertx.createDatagramSocket(options);
        udpSocket.listen(flowSpec.getLocalPort(), "0.0.0.0", ar -> {
            if (ar.succeeded()) {
                LOG.info("Vert.x UDP listening on port {}", flowSpec.getLocalPort());
                udpSocket.handler(packet -> {
                    byte[] data = packet.data().getBytes();
                    if (webSocket != null && readyToSend) {
                        String audioB64 = Base64.getEncoder().encodeToString(data);
                        JsonObject msg = new JsonObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", audioB64);
                        webSocket.writeTextMessage(msg.encode());
                    }
                });
            } else {
                LOG.error("Failed to bind UDP socket on {}", flowSpec.getLocalPort(), ar.cause());
            }
        });

        // Setup WebSocket client for OpenAI
        HttpClientOptions clientOpts = new HttpClientOptions()
            .setProtocolVersion(HttpVersion.HTTP_1_1)
            .setSsl(true);
        HttpClient httpClient = vertx.createHttpClient(clientOpts);
        String host = "api.openai.com";
        int port = 443;
        String uri = "/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17";
        WebSocketConnectOptions wsOpts = new WebSocketConnectOptions()
            .setHost(host)
            .setPort(port)
            .setURI(uri)
            .addHeader("Authorization", "Bearer " + System.getenv("OPENAI_API_KEY"))
            .addHeader("OpenAI-Beta", "realtime=v1");
        httpClient.webSocket(wsOpts, wsRes -> {
            if (wsRes.succeeded()) {
                webSocket = wsRes.result();
                LOG.info("WebSocket connected to OpenAI");
                setupWebSocketHandlers();
            } else {
                LOG.error("WebSocket connection failed", wsRes.cause());
            }
        });

        return true;
    }

    private void setupWebSocketHandlers() {
        webSocket.frameHandler(frame -> {
            if (frame.isText()) {
                JsonObject msg = new JsonObject(frame.textData());
                String type = msg.getString("type");
                switch (type) {
                    case "session.created":
                        if (!sessionCreated) {
                            sessionCreated = true;
                            sendSessionConfig();
                        }
                        break;
                    case "session.updated":
                        readyToSend = true;
                        sendCreateResponse();
                        startPeriodicSend();
                        break;
                    case "response.audio.delta":
                        String deltaB64 = msg.getString("delta");
                        if (deltaB64 != null) {
                            byte[] audio = Base64.getDecoder().decode(deltaB64);
                            audioBuffer.appendBytes(audio);
                            
                            // Send complete RTP packets
                            while (audioBuffer.length() >= RTP_PACKET_SIZE) {
                                byte[] payload = audioBuffer.getBytes(0, RTP_PACKET_SIZE);
                                // Create a new buffer with remaining data
                                Buffer remaining = Buffer.buffer(audioBuffer.length() - RTP_PACKET_SIZE);
                                if (audioBuffer.length() > RTP_PACKET_SIZE) {
                                    remaining.appendBytes(audioBuffer.getBytes(RTP_PACKET_SIZE, audioBuffer.length()));
                                }
                                audioBuffer = remaining;
                                
                                sendRtpPacket(payload);
                            }
                        }
                        break;
                    default:
                        LOG.debug("WebSocket message type: {}", type);
                }
            }
        });
        webSocket.exceptionHandler(err -> LOG.error("WebSocket error", err));
        webSocket.closeHandler(v -> LOG.info("WebSocket closed"));
    }

    private void sendSessionConfig() {
        JsonObject session = new JsonObject()
            .put("instructions", "You're a friendly assistant. Say hi immediately in a cheerful way.")
            .put("voice", "alloy")
            .put("input_audio_format", "g711_ulaw")
            .put("output_audio_format", "g711_ulaw")
            .put("turn_detection", new JsonObject()
                .put("type", "server_vad")
                .put("create_response", true))
            .put("modalities", new io.vertx.core.json.JsonArray().add("audio").add("text"));
        JsonObject cfg = new JsonObject().put("type", "session.update").put("session", session);
        webSocket.writeTextMessage(cfg.encode());
    }

    private void sendCreateResponse() {
        JsonObject msg = new JsonObject().put("type", "response.create");
        webSocket.writeTextMessage(msg.encode());
    }

    private void startPeriodicSend() {
        if (periodicTimerId == -1) {
            periodicTimerId = vertx.setPeriodic(PACKET_INTERVAL_MS, id -> {
                Buffer pkt = rtpQueue.poll();
                if (pkt != null) {
                    udpSocket.send(pkt, flowSpec.getRemotePort(), flowSpec.getRemoteAddress(), snd -> {});
                }
            });
        }
    }

    @Override
    public boolean halt() {
        if (periodicTimerId != -1) {
            vertx.cancelTimer(periodicTimerId);
            periodicTimerId = -1;
        }
        if (webSocket != null) webSocket.close();
        if (udpSocket != null) udpSocket.close();
        // do not close shared Vert.x instance here
        return true;
    }
}