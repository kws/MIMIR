package com.kajsiebert.sip.openai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class OpenAIRealtimeClient {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeClient.class);

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17";
    
    private final BlockingQueue<byte[]> inputAudioQueue = new LinkedBlockingQueue<>(100);
    private final BlockingQueue<byte[]> outputAudioQueue = new LinkedBlockingQueue<>(100);

    private final ObjectMapper mapper = new ObjectMapper();

    public void start() {
        if (API_KEY == null) {
            LOG.error("Please set the OPENAI_API_KEY environment variable.");
            System.exit(1);
        }

        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
            .header("Authorization", "Bearer " + API_KEY)
            .header("OpenAI-Beta", "realtime=v1")
            .buildAsync(URI.create(WS_URL), new WSListener())
            .join();
    }

    private class WSListener implements WebSocket.Listener {
        private WebSocket ws;
        private boolean sessionCreated = false;

        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.info("Connected to OpenAI real-time API.");
            this.ws = webSocket;
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

                    // Start audio sender
                    Executors.newSingleThreadExecutor().submit(() -> sendAudioLoop());
                }

                if ("response.audio.delta".equals(type) && msg.has("delta")) {
                    byte[] audioBytes = Base64.getDecoder().decode(msg.get("delta").asText());
                    outputAudioQueue.put(audioBytes);  // Use for playback
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

            ws.sendText(config.toString(), true);
        }

        private void sendAudioLoop() {
            try {
                while (true) {
                    byte[] audio = inputAudioQueue.take();
                    String encoded = Base64.getEncoder().encodeToString(audio);

                    ObjectNode audioMsg = mapper.createObjectNode();
                    audioMsg.put("type", "input_audio_buffer.append");
                    audioMsg.put("audio", encoded);

                    ws.sendText(audioMsg.toString(), true);
                }
            } catch (InterruptedException e) {
                LOG.info("Audio sending loop interrupted.");
            }
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket closed: {}", reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            LOG.error("WebSocket error: {}", error.getMessage());
        }
    }
}
