package com.kajsiebert.sip;


import java.util.concurrent.Executor;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.ua.streamer.StreamerFactory;

/**
 * {@link StreamerFactory} creating {@link DelayedMediaStreamer}s with configurable delay.
 */
public final class DelayedStreamerFactory implements StreamerFactory {
    
    private final long delayMs;
    
    /**
     * Creates a {@link DelayedStreamerFactory} with the specified delay.
     * @param delayMs the delay in milliseconds
     */
    public DelayedStreamerFactory(long delayMs) {
        this.delayMs = delayMs;
    }
    
    @Override
    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flow_spec) {
        return new DelayedMediaStreamer(flow_spec, delayMs);
    }
} 