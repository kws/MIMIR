package com.kajsiebert.sip.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.net.http.WebSocket;

public class OpenAIRealtimeSender extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeSender.class);
    
    private final OpenAIRealtimeReceiver receiver;
    private final AudioQueues audioQueues;
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean running = true;

    public OpenAIRealtimeSender(OpenAIRealtimeReceiver receiver, AudioQueues audioQueues) {
        this.receiver = receiver;
        this.audioQueues = audioQueues;
    }

    public void stopSender() {
        running = false;
        this.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                byte[] audio = audioQueues.getOutputAudioQueue().take();
                String encoded = Base64.getEncoder().encodeToString(audio);

                ObjectNode audioMsg = mapper.createObjectNode();
                audioMsg.put("type", "input_audio_buffer.append");
                audioMsg.put("audio", encoded);

                WebSocket webSocket = receiver.getWebSocket();
                if (webSocket != null) {
                    webSocket.sendText(audioMsg.toString(), true);
                    LOG.debug("Sent audio data of length: {}", audio.length);
                } else {
                    LOG.warn("WebSocket not connected, dropping audio data");
                }
            } catch (InterruptedException e) {
                if (!running) break;
                LOG.error("Error processing audio data", e);
            }
        }
    }
}