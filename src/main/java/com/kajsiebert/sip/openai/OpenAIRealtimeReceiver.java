package com.kajsiebert.sip.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class OpenAIRealtimeReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeReceiver.class);
    private static final String WS_URL = "wss://api.openai.com/v1/audio/transcriptions";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AudioQueues audioQueues;
    private WebSocket webSocket;
    private boolean sessionCreated = false;

    public OpenAIRealtimeReceiver(AudioQueues audioQueues) {
        this.audioQueues = audioQueues;
    }

    public void start() {
        if (API_KEY == null) {
            LOG.error("Please set the OPENAI_API_KEY environment variable.");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + API_KEY)
            .header("OpenAI-Beta", "realtime=v1")
            .buildAsync(URI.create(WS_URL), new WSListener())
            .join();
    }

    public void stop() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down");
        }
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    private class WSListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.info("WebSocket connection opened");
            OpenAIRealtimeReceiver.this.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                ObjectNode msg = (ObjectNode) mapper.readTree(data.toString());
                String type = msg.get("type").asText();

                if ("session.created".equals(type) && !sessionCreated) {
                    sessionCreated = true;
                    sendSessionConfig();
                }

                if ("response.audio.delta".equals(type) && msg.has("delta")) {
                    byte[] audioBytes = Base64.getDecoder().decode(msg.get("delta").asText());
                    audioQueues.getInputAudioQueue().put(audioBytes);
                }

                if ("response.audio_transcript.delta".equals(type)) {
                    LOG.info("AI: {}", msg.get("delta").asText());
                }

                if ("error".equals(type)) {
                    LOG.error("Error: {}", msg.toPrettyString());
                }

            } catch (Exception e) {
                LOG.error("Failed to handle message: {}", e.getMessage());
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            LOG.debug("Received binary data of length: {}", data.remaining());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket connection closed: {} - {}", statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.error("WebSocket error", error);
        }
    }

    private void sendSessionConfig() {
        ObjectNode config = mapper.createObjectNode();
        config.put("type", "session.update");
        ObjectNode session = config.putObject("session");
        session.put("instructions", "You're a friendly assistant. Say hi immediately in a cheerful way.");
        session.put("voice", "alloy");
        session.put("output_audio_format", "pcm16");

        ObjectNode turnDetection = session.putObject("turn_detection");
        turnDetection.put("type", "server_vad");
        turnDetection.put("create_response", true);

        session.putArray("modalities").add("audio").add("text");

        webSocket.sendText(config.toString(), true);
    }
} 