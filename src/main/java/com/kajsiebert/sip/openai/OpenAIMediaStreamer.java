package com.kajsiebert.sip.openai;

import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;

public class OpenAIMediaStreamer implements MediaStreamer {

    private final OpenAIRealtimeClient client;

    public OpenAIMediaStreamer(OpenAIRealtimeClient client, FlowSpec flow_spec) {
        this.client = client;
    }

    @Override
    public boolean start() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'start'");
    }

    @Override
    public boolean halt() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'halt'");
    }
    
}
