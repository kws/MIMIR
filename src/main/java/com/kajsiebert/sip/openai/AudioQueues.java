package com.kajsiebert.sip.openai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.mjsip.media.MediaSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.org.hentai.acodec.AudioCodec;
import cn.org.hentai.acodec.CodecFactory;

public class AudioQueues {
    private static final Logger LOG = LoggerFactory.getLogger(AudioQueues.class);

    private static final int QUEUE_SIZE = 100;

    private final BlockingQueue<byte[]> inputAudioQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private final BlockingQueue<byte[]> outputAudioQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private long rtpTimestamp = 0;

    private final AudioCodec codec;

    public AudioQueues(MediaSpec mediaSpec) throws UnsupportedEncodingException {
        if (mediaSpec.getCodec().equals("PCMU")) {
            this.codec = CodecFactory.getCodec("g711u");
        } else if (mediaSpec.getCodec().equals("PCMA")) {
            this.codec = CodecFactory.getCodec("g711a");
        } else {
            throw new IllegalArgumentException("Unsupported codec: " + mediaSpec.getCodec());
        }
    }

    public BlockingQueue<byte[]> getInputAudioQueue() {
        return inputAudioQueue;
    }

    public BlockingQueue<byte[]> getOutputAudioQueue() {
        return outputAudioQueue;
    }

    public void putRTPFrame(byte[] receivedData) throws InterruptedException {
        // byte[] data = codec.toPCM(receivedData);
        inputAudioQueue.put(receivedData);
    }

    public void putOpenAIFrame(byte[] receivedData) throws InterruptedException, IOException {
        LOG.debug("Received data length: {}", receivedData.length);

        // 2) Append to our rolling buffer
        buffer.write(receivedData);

        byte[] buf = buffer.toByteArray();
        int offset = 0;

        // 3) While we have at least 160 bytes (20 ms @8 kHz), slice out one RTP packet
        while (buf.length - offset >= 160) {
            byte[] frame = new byte[160];
            System.arraycopy(buf, offset, frame, 0, 160);
            offset += 160;

            // 4) Send that 160-byte frame in your RTP packet
            // sendRtpPacket(frame, rtpTimestamp);
            outputAudioQueue.put(frame);

            // 5) Advance RTP timestamp by number of samples
            rtpTimestamp += 160;
        }

        // 6) Preserve any leftover bytes for next time
        buffer.reset();
        if (offset < buf.length) {
            buffer.write(buf, offset, buf.length - offset);
        }

        // // Step 1: Downsample 16kHz to 8kHz PCM (still 16-bit signed LE)
        // byte[] pcm8kHz = naiveDownsample16kTo8k(receivedData);
    
        // // Optional: Log first sample for sanity check
        // if (pcm8kHz.length >= 2) {
        //     short s = ByteBuffer.wrap(pcm8kHz, 0, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
        //     LOG.debug("First sample value (16-bit signed LE): {}", s);
        // }
    
        // // Step 2: Chunk PCM into 320-byte (20ms) segments and encode each
        // for (int i = 0; i + 320 <= pcm8kHz.length; i += 320) {
        //     byte[] pcmChunk = Arrays.copyOfRange(pcm8kHz, i, i + 320);  // 160 samples
        //     byte[] g711Frame = codec.fromPCM(pcmChunk);                 // Should be 160 bytes
    
        //     if (g711Frame.length != 160) {
        //         LOG.warn("Unexpected G.711 frame size: {}", g711Frame.length);
        //         continue;
        //     }
    
        //     // Step 3: Add to queue for RTP sending
        //     outputAudioQueue.put(g711Frame);
        // }
    }

    public static byte[] resample16kTo8k(byte[] pcm16BitMono16kHz) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(pcm16BitMono16kHz);

        AudioFormat inFormat = new AudioFormat(16000, 16, 1, true, false);
        AudioInputStream inputAIS = new AudioInputStream(bais, inFormat, pcm16BitMono16kHz.length / 2);
    
        AudioFormat outFormat = new AudioFormat(8000, 16, 1, true, false);
        AudioInputStream outputAIS = AudioSystem.getAudioInputStream(outFormat, inputAIS);
    
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = outputAIS.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
    
        return baos.toByteArray();
    }

    public static byte[] naiveDownsample16kTo8k(byte[] pcm16k) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i + 4 <= pcm16k.length; i += 4) {
            out.write(pcm16k[i]);     // LSB
            out.write(pcm16k[i + 1]); // MSB
            // Drop the next 2 bytes (next sample)
        }
        return out.toByteArray();
    }

}
