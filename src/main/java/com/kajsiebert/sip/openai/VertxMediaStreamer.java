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
import java.util.PriorityQueue;

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
    private static final int BATCH_MS = 250;
    private static final int BATCH_BYTES = 8000 * BATCH_MS / 1000;
    private final PriorityQueue<JitterPacket> jitterBuffer = new PriorityQueue<>();
    private int jitterBufferBytes = 0;
    private long audioFlushTimerId = -1;

    private static String readInstructions() {
        try {
            return new String(VertxMediaStreamer.class.getResourceAsStream("/bohr_instructions.txt").readAllBytes());
        } catch (Exception e) {
            LOG.error("Failed to read instructions file", e);
            return "You are playing the role of the famous scientist Niels Bohr.";
        }
    }

    private static class JitterPacket implements Comparable<JitterPacket> {
        final long timestamp;
        byte[] payload;

        JitterPacket(long timestamp, byte[] payload) {
            this.timestamp = timestamp;
            this.payload = payload;
        }

        @Override
        public int compareTo(JitterPacket other) {
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

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
                    if (webSocket != null && readyToSend) {
                        Buffer buf = packet.data();
                        if (buf.length() > RTP_HEADER_SIZE) {
                            long ts = buf.getInt(4) & 0xffffffffL;
                            byte[] payload = buf.getBytes(RTP_HEADER_SIZE, buf.length());
                            jitterBuffer.offer(new JitterPacket(ts, payload));
                            jitterBufferBytes += payload.length;
                            if (jitterBufferBytes >= BATCH_BYTES) {
                                flushAudioBuffer();
                            }
                        }
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
                        startAudioFlush();
                        break;
                    case "response.audio.delta":
                        String deltaB64 = msg.getString("delta");
                        if (deltaB64 != null) {
                            byte[] audio = Base64.getDecoder().decode(deltaB64);
                            audioBuffer.appendBytes(audio);
                            
                            // Queue complete RTP packets
                            while (audioBuffer.length() >= RTP_PACKET_SIZE) {
                                byte[] payload = audioBuffer.getBytes(0, RTP_PACKET_SIZE);
                                // Create a new buffer with remaining data
                                Buffer remaining = Buffer.buffer(audioBuffer.length() - RTP_PACKET_SIZE);
                                if (audioBuffer.length() > RTP_PACKET_SIZE) {
                                    remaining.appendBytes(audioBuffer.getBytes(RTP_PACKET_SIZE, audioBuffer.length()));
                                }
                                audioBuffer = remaining;
                                
                                // Queue the packet instead of sending directly
                                Buffer rtpPacket = createRtpPacket(payload);
                                rtpQueue.offer(rtpPacket);
                            }
                        }
                        break;
                    case "response.audio_transcript.delta":
                        String responseAudioTranscriptDelta = msg.getString("delta");
                        LOG.info("WebSocket message type: {} - delta: {}", type, responseAudioTranscriptDelta);
                        break;
                    case "response.audio.done":
                        LOG.info("WebSocket message type: {} - done", type);
                        // Process any remaining audio data
                        if (audioBuffer.length() > 0) {
                            // Pad the remaining data to RTP_PACKET_SIZE with silence (0xFF for G.711 u-law)
                            byte[] payload = new byte[RTP_PACKET_SIZE];
                            byte[] remaining = audioBuffer.getBytes();
                            System.arraycopy(remaining, 0, payload, 0, remaining.length);
                            // Fill the rest with silence (0xFF for G.711 u-law)
                            for (int i = remaining.length; i < RTP_PACKET_SIZE; i++) {
                                payload[i] = (byte) 0xFF;
                            }
                            Buffer rtpPacket = createRtpPacket(payload);
                            rtpQueue.offer(rtpPacket);
                            audioBuffer = Buffer.buffer(); // Clear the buffer
                        }
                        break;
                    case "input_audio_buffer.speech_started":
                        LOG.info("WebSocket message type: {} - speech started", type);
                        rtpQueue.clear();
                        break;
                    case "input_audio_buffer.speech_stopped":
                        LOG.info("WebSocket message type: {} - speech stopped", type);
                        break;
                    case "conversation.item.input_audio_transcription.delta":
                        String conversationItemInputAudioTranscriptDelta = msg.getString("delta");
                        LOG.info("WebSocket message type: {} - delta: {}", type, conversationItemInputAudioTranscriptDelta);
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
            .put("instructions", readInstructions())
            .put("voice", "echo")
            .put("input_audio_format", "g711_ulaw")
            .put("output_audio_format", "g711_ulaw")
            .put("input_audio_transcription", new JsonObject()
                .put("model", "whisper-1")
            )
            .put("turn_detection", new JsonObject()
                .put("type", "server_vad")
                .put("create_response", true)
                .put("interrupt_response", true)
            )
            .put("modalities", new io.vertx.core.json.JsonArray().add("audio").add("text"));
        JsonObject cfg = new JsonObject().put("type", "session.update").put("session", session);
        LOG.info("Sending session config: {}", cfg.encodePrettily());
        webSocket.writeTextMessage(cfg.encode());
    }

    private void sendCreateResponse() {
        JsonObject msg = new JsonObject()
            .put("type", "response.create")
            .put("response", new JsonObject()
                .put("instructions", "Ring, ring. The phone is ringing. You pick it up and say: 'Hej, dette er Niels Bohr. Hvad kan jeg hjælpe dig med?'."));
        LOG.info("Sending response create: {}", msg.encodePrettily());
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

    private void startAudioFlush() {
        if (audioFlushTimerId == -1) {
            audioFlushTimerId = vertx.setPeriodic(BATCH_MS, id -> {
                if (webSocket != null && readyToSend) {
                    flushAudioBuffer();
                }
            });
        }
    }

    private void flushAudioBuffer() {
        while (jitterBufferBytes >= BATCH_BYTES) {
            Buffer combined = Buffer.buffer();
            int sentBytes = 0;
            while (sentBytes < BATCH_BYTES && !jitterBuffer.isEmpty()) {
                JitterPacket pkt = jitterBuffer.peek();
                int len = pkt.payload.length;
                if (sentBytes + len <= BATCH_BYTES) {
                    pkt = jitterBuffer.poll();
                    combined.appendBytes(pkt.payload);
                    sentBytes += len;
                    jitterBufferBytes -= len;
                } else {
                    int remaining = BATCH_BYTES - sentBytes;
                    combined.appendBytes(pkt.payload, 0, remaining);
                    byte[] rem = new byte[len - remaining];
                    System.arraycopy(pkt.payload, remaining, rem, 0, rem.length);
                    pkt.payload = rem;
                    sentBytes += remaining;
                    jitterBufferBytes -= remaining;
                }
            }
            String audioB64 = Base64.getEncoder().encodeToString(combined.getBytes());
            JsonObject msg = new JsonObject().put("type", "input_audio_buffer.append").put("audio", audioB64);
            webSocket.writeTextMessage(msg.encode());
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
    /**
     * Warm up an OpenAI realtime session and wait until the first audio delta is received.
     * Returns a Future that completes when the initial response audio is ready.
     */
    public static io.vertx.core.Future<Void> warmup(Vertx vertx) {
        io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
        io.vertx.core.http.HttpClientOptions clientOpts = new HttpClientOptions()
            .setProtocolVersion(HttpVersion.HTTP_1_1)
            .setSsl(true);
        io.vertx.core.http.HttpClient httpClient = vertx.createHttpClient(clientOpts);
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
                WebSocket ws = wsRes.result();
                ws.frameHandler(frame -> {
                    if (frame.isText()) {
                        JsonObject msg = new JsonObject(frame.textData());
                        String type = msg.getString("type");
                        switch (type) {
                            case "session.created":
                                JsonObject session = new JsonObject()
                                    .put("instructions", readInstructions())
                                    .put("voice", "echo")
                                    .put("input_audio_format", "g711_ulaw")
                                    .put("output_audio_format", "g711_ulaw")
                                    .put("input_audio_transcription", new JsonObject().put("model", "whisper-1"))
                                    .put("turn_detection", new JsonObject().put("type", "server_vad").put("create_response", true).put("interrupt_response", true))
                                    .put("modalities", new io.vertx.core.json.JsonArray().add("audio").add("text"));
                                JsonObject cfg = new JsonObject().put("type", "session.update").put("session", session);
                                ws.writeTextMessage(cfg.encode());
                                break;
                            case "session.updated":
                                JsonObject createResp = new JsonObject()
                                    .put("type", "response.create")
                                    .put("response", new JsonObject().put("instructions", "Ring, ring. The phone is ringing. You pick it up and say: 'Hej, dette er Niels Bohr. Hvad kan jeg hjælpe dig med?'"));
                                ws.writeTextMessage(createResp.encode());
                                break;
                            case "response.audio.delta":
                                promise.tryComplete();
                                ws.close();
                                break;
                            default:
                                // ignore
                        }
                    }
                });
                ws.exceptionHandler(promise::tryFail);
                ws.closeHandler(v -> promise.tryComplete());
            } else {
                promise.tryFail(wsRes.cause());
            }
        });
        return promise.future();
    }
}