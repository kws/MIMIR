package com.kajsiebert.sip.openai.websocket;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Base64;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kajsiebert.sip.openai.ExtensionConfig;
import com.kajsiebert.sip.openai.rtp.RTPAudioQueue;
import com.kajsiebert.sip.openai.util.ConsumerArray;

public class WebsocketSession extends WebsocketMessageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebsocketSession.class);

    private final Vertx vertx;
    private final ExtensionConfig extensionConfig;
    protected WebsocketSessionState state = WebsocketSessionState.NEW;
    private final RTPAudioQueue audioQueue = new RTPAudioQueue();
    private final ConsumerArray<WebsocketSessionState> audioReceivedCallbacks = new ConsumerArray<>();

    private WebSocket webSocket;

    public WebsocketSession(Vertx vertx, ExtensionConfig extensionConfig) {
        this.vertx = vertx;
        this.extensionConfig = extensionConfig;
    }

    public boolean start() {
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
            WebSocket webSocket;
            if (wsRes.succeeded()) {
                webSocket = wsRes.result();
                LOG.info("WebSocket connected to OpenAI");

                this.webSocket = webSocket;
                webSocket.frameHandler(this::handleFrame);
                webSocket.exceptionHandler(this::handleException);
                webSocket.closeHandler(this::handleClose);

                this.state = WebsocketSessionState.CONNECTED;
                this.webSocket.writeTextMessage(this.getSessionConfig().encode());
            } else {
                LOG.error("WebSocket connection failed", wsRes.cause());
            }
        });

        return true;
    }

    public void close() {
        this.webSocket.close();
    }

    protected void handleFrame(WebSocketFrame frame) {
        if (frame.isText()) {
            JsonObject msg = new JsonObject(frame.textData());
            String type = msg.getString("type");
            if (!this.handle(type, msg)) {
                LOG.debug("Ignored message type: {}", type);
            }
        }
    };

    protected void handleException(Throwable err) {
        LOG.error("WebSocket error", err);
    }

    protected void handleClose(Void v) {
        LOG.info("WebSocket closed");
    }

    public JsonObject getSessionConfig() {
        JsonObject session = new JsonObject()
            .put("instructions", this.extensionConfig.getInstructions())
            .put("voice", this.extensionConfig.getVoice())
            .put("input_audio_format", "g711_ulaw")
            .put("output_audio_format", "g711_ulaw")
            .put("turn_detection", new JsonObject()
                .put("type", "server_vad")
                .put("create_response", true)
                .put("interrupt_response", true)
            )
            .put("modalities", JsonArray.of("audio", "text"));

        return JsonObject.of("type", "session.update", "session", session);
    }

    /**
     * Used to force the model to respond with a greeting. This is because we want the model 
     * to 'answer' the phone when it rings.
     * @return
     */
    public JsonObject getCreateResponse() {
        JsonObject msg = new JsonObject()
            .put("type", "response.create")
            .put("response", JsonObject.of("instructions", this.extensionConfig.getGreeting())
        );
        return msg;
    }
    
    @WebsocketMessage("session.created")
    public void handleSessionCreated(JsonObject msg) {
        this.state = WebsocketSessionState.SESSION_CREATED;
    }

    @WebsocketMessage("session.updated")
    public void handleSessionUpdated(JsonObject msg) {
        if (this.state == WebsocketSessionState.SESSION_CREATED) {
            this.webSocket.writeTextMessage(this.getCreateResponse().encode());
            this.state = WebsocketSessionState.ANSWERED;
        } else {
            LOG.warn("Received session.updated message in state: {}", this.state);
        }
    }

    @WebsocketMessage("response.audio.delta")
    public void handleResponseAudioDelta(JsonObject msg) {
        String deltaB64 = msg.getString("delta");
        if (deltaB64 != null) {

            if (this.state.compareTo(WebsocketSessionState.ANSWERED) < 0) {
                this.state = WebsocketSessionState.AUDIO_RECEIVED;
                audioReceivedCallbacks.accept(this.state);
            }

            byte[] audio = Base64.getDecoder().decode(deltaB64);
            audioQueue.appendAudio(audio);   
        }
    }

    public void onAudioReceived(Consumer<WebsocketSessionState> callback) {
        this.audioReceivedCallbacks.add(callback);
    }

    public void sendAudio(byte[] audio) {
        String audioB64 = Base64.getEncoder().encodeToString(audio);
        JsonObject msg = new JsonObject().put("type", "input_audio_buffer.append").put("audio", audioB64);
        webSocket.writeTextMessage(msg.encode());
    }

    public Buffer getNextRtpPacket() {
        return audioQueue.getNextRtpPacket();
    }



}

// switch (type) {
//     case "session.created":
//         if (!sessionCreated) {
//             sessionCreated = true;
//             sendSessionConfig();
//         }
//         break;
//     case "session.updated":
//         readyToSend = true;
//         sendCreateResponse();
//         startPeriodicSend();
//         startAudioFlush();
//         break;
//     case "response.audio.delta":
//         String deltaB64 = msg.getString("delta");
//         if (deltaB64 != null) {
//             byte[] audio = Base64.getDecoder().decode(deltaB64);
//             audioBuffer.appendBytes(audio);
            
//             // Queue complete RTP packets
//             while (audioBuffer.length() >= RTP_PACKET_SIZE) {
//                 byte[] payload = audioBuffer.getBytes(0, RTP_PACKET_SIZE);
//                 // Create a new buffer with remaining data
//                 Buffer remaining = Buffer.buffer(audioBuffer.length() - RTP_PACKET_SIZE);
//                 if (audioBuffer.length() > RTP_PACKET_SIZE) {
//                     remaining.appendBytes(audioBuffer.getBytes(RTP_PACKET_SIZE, audioBuffer.length()));
//                 }
//                 audioBuffer = remaining;
                
//                 // Queue the packet instead of sending directly
//                 Buffer rtpPacket = createRtpPacket(payload);
//                 rtpQueue.offer(rtpPacket);
//             }
//         }
//         break;
//     case "response.audio_transcript.delta":
//         String responseAudioTranscriptDelta = msg.getString("delta");
//         LOG.info("WebSocket message type: {} - delta: {}", type, responseAudioTranscriptDelta);
//         break;
//     case "response.audio.done":
//         LOG.info("WebSocket message type: {} - done", type);
//         // Process any remaining audio data
//         if (audioBuffer.length() > 0) {
//             // Pad the remaining data to RTP_PACKET_SIZE with silence (0xFF for G.711 u-law)
//             byte[] payload = new byte[RTP_PACKET_SIZE];
//             byte[] remaining = audioBuffer.getBytes();
//             System.arraycopy(remaining, 0, payload, 0, remaining.length);
//             // Fill the rest with silence (0xFF for G.711 u-law)
//             for (int i = remaining.length; i < RTP_PACKET_SIZE; i++) {
//                 payload[i] = (byte) 0xFF;
//             }
//             Buffer rtpPacket = createRtpPacket(payload);
//             rtpQueue.offer(rtpPacket);
//             audioBuffer = Buffer.buffer(); // Clear the buffer
//         }
//         break;
//     case "input_audio_buffer.speech_started":
//         LOG.info("WebSocket message type: {} - speech started", type);
//         rtpQueue.clear();
//         break;
//     case "input_audio_buffer.speech_stopped":
//         LOG.info("WebSocket message type: {} - speech stopped", type);
//         break;
//     case "conversation.item.input_audio_transcription.delta":
//         String conversationItemInputAudioTranscriptDelta = msg.getString("delta");
//         LOG.info("WebSocket message type: {} - delta: {}", type, conversationItemInputAudioTranscriptDelta);
//         break;
//     case "error":
//         LOG.error("WebSocket message type: {} - error: {}", type, msg);
//     default:
//         LOG.debug("WebSocket message type: {}", type);
// }