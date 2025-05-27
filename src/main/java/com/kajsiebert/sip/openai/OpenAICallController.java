package com.kajsiebert.sip.openai;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.ua.MediaAgent;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.streamer.StreamerFactory;

import io.vertx.core.Vertx;



public class OpenAICallController implements StreamerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAICallController.class);

    private static final OpenAIMediaOptions mediaOptions = new OpenAIMediaOptions();

    private final CompletableFuture<Void> callHandledFuture = new CompletableFuture<>();
    private final UserAgent ua;
    private final OpenAIRealtimeBridge bridge;

    public OpenAICallController(Vertx vertx, UserAgent ua, ExtensionConfig extensionConfig) {
        this.ua = ua;

        bridge = new OpenAIRealtimeBridge(vertx, extensionConfig);
        bridge.onAudioReceived(state -> {
            MediaAgent mediaAgent = new MediaAgent(mediaOptions.getMediaDescs(), this);
            ua.accept(mediaAgent);
            callHandledFuture.complete(null);
        });
    }

    @Override
    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flow_spec) {
        bridge.setFlowSpec(flow_spec);
        return bridge;
    }

    public void awaitCallHandled(int timeoutSeconds) {
        try {
            callHandledFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            ua.hangup();
        }
    }

    
    
}