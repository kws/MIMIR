package com.kajsiebert.sip.openai;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAIMediaStreamer implements MediaStreamer {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIMediaStreamer.class);

    private final OpenAIRealtimeReceiver openaiReceiver;
    private final OpenAIRealtimeSender openaiSender;
    private final AudioQueues audioQueues;
    private final UdpReceiver udpReceiver;
    private final UdpSender udpSender;
    private final UdpSocketContext socketContext;

    public OpenAIMediaStreamer(FlowSpec flow_spec) {
        try {
            // Create the audio queues for passing data between components
            audioQueues = new AudioQueues();

            // Create the OpenAI components
            openaiReceiver = new OpenAIRealtimeReceiver(audioQueues);
            openaiSender = new OpenAIRealtimeSender(openaiReceiver, audioQueues);

            // Create UDP socket context using the flow spec
            socketContext = new UdpSocketContext(
                flow_spec.getLocalPort(),
                flow_spec.getRemoteAddress(),
                flow_spec.getRemotePort(),
                1500 // Max packet size
            );

            // Create UDP components
            udpReceiver = new UdpReceiver(socketContext, audioQueues);
            udpSender = new UdpSender(socketContext, audioQueues);

            openaiReceiver.start();
            udpReceiver.start();
            openaiSender.start();
            udpSender.start();

            LOG.info("Created OpenAIMediaStreamer with flow spec: {}", flow_spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OpenAIMediaStreamer", e);
        }
    }

    @Override
    public boolean start() {
        // Components are already started in constructor
        return true;
    }

    @Override
    public boolean halt() {
        try {
            if (udpReceiver != null) udpReceiver.stopReceiver();
            if (udpSender != null) udpSender.stopSender();
            if (openaiReceiver != null) openaiReceiver.shutdown();
            if (openaiSender != null) openaiSender.stopSender();
            if (socketContext != null) socketContext.close();
            return true;
        } catch (Exception e) {
            LOG.error("Error halting media streamer", e);
            return false;
        }
    }
}
