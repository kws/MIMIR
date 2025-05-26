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

public class OpenAIRealtimeReceiver extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeReceiver.class);
    private static final String WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AudioQueues audioQueues;
    private WebSocket webSocket;
    private boolean sessionCreated = false;
    private boolean readyToSend = false;

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

    public void shutdown() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down");
        }
        this.interrupt();
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public boolean isReadyToSend() {
        return readyToSend;
    }

    private class WSListener implements WebSocket.Listener {
        private StringBuilder messageBuilder = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.info("WebSocket connection opened");
            OpenAIRealtimeReceiver.this.webSocket = webSocket;
            webSocket.request(10);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                messageBuilder.append(data);
                
                if (last) {
                    String completeMessage = messageBuilder.toString();
                    LOG.debug("Received complete message length: {}", completeMessage.length());
                    
                    ObjectNode msg = (ObjectNode) mapper.readTree(completeMessage);
                    String type = msg.get("type").asText();
                    LOG.debug("Received message type: {}", type);

                    if ("session.created".equals(type) && !sessionCreated) {
                        sessionCreated = true;
                        sendSessionConfig();
                    }

                    if ("session.updated".equals(type)) {
                        readyToSend = true;
                        sendCreateResponse();
                    }

                    if ("response.created".equals(type)) {
                        LOG.info("Response created: {}", msg.toPrettyString());
                    }

                    if ("response.done".equals(type)) {
                        LOG.info("Response done: {}", msg.toPrettyString());
                    }

                    if ("response.audio.delta".equals(type) && msg.has("delta")) {
                        byte[] audioBytes = Base64.getDecoder().decode(msg.get("delta").asText());
                        audioQueues.putOpenAIFrame(audioBytes);
                    }

                    if ("response.audio_transcript.delta".equals(type)) {
                        LOG.info("AI: {}", msg.get("delta").asText());
                    }

                    if ("error".equals(type)) {
                        LOG.error("Error: {}", msg.toPrettyString());
                    }

                    // Clear the builder for the next message
                    messageBuilder.setLength(0);
                } else {
                    LOG.debug("Received partial message: {}", data);
                }

            } catch (Exception e) {
                LOG.error("Failed to handle message: {}", e.getMessage());
                LOG.debug("Message content: {}", data);
                // Clear the builder on error
                messageBuilder.setLength(0);
            }

            webSocket.request(10);
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
        session.put("input_audio_format", "g711_ulaw");
        session.put("output_audio_format", "g711_ulaw");

        ObjectNode turnDetection = session.putObject("turn_detection");
        turnDetection.put("type", "server_vad");
        turnDetection.put("create_response", true);

        session.putArray("modalities").add("audio").add("text");

        LOG.debug("Sending session config: {}", config.toString());
        webSocket.sendText(config.toString(), true);
    }

    private void sendCreateResponse() {
        ObjectNode createResponse = mapper.createObjectNode();
        createResponse.put("type", "response.create");

        LOG.debug("Sending create response: {}", createResponse.toString());
        webSocket.sendText(createResponse.toString(), true);
    }
} 