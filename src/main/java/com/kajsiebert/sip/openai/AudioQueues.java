package com.kajsiebert.sip.openai;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioQueues {

    private static final int QUEUE_SIZE = 100;

    private final BlockingQueue<byte[]> inputAudioQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private final BlockingQueue<byte[]> outputAudioQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

    public AudioQueues() {
    }

    public BlockingQueue<byte[]> getInputAudioQueue() {
        return inputAudioQueue;
    }

    public BlockingQueue<byte[]> getOutputAudioQueue() {
        return outputAudioQueue;
    }
}
