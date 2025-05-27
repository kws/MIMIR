package com.kajsiebert.sip.openai;

import java.util.ArrayList;

import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;

import com.kajsiebert.sip.openai.rtp.RTPConstants;

public class OpenAIMediaOptions {
  public static enum AudioCodecOptions {
    PCMU,
    PCMA,
    BOTH
  }

  private final MediaDesc[] mediaDescs;

  public OpenAIMediaOptions(AudioCodecOptions audioCodec) {
    ArrayList<MediaSpec> mediaSpecs = new ArrayList<>();
    if (audioCodec == AudioCodecOptions.PCMU || audioCodec == AudioCodecOptions.BOTH) {
      mediaSpecs.add(new MediaSpec(0, "PCMU", 8000, 1, RTPConstants.RTP_PACKET_SIZE));
    }
    if (audioCodec == AudioCodecOptions.PCMA || audioCodec == AudioCodecOptions.BOTH) {
      mediaSpecs.add(new MediaSpec(8, "PCMA", 8000, 1, RTPConstants.RTP_PACKET_SIZE));
    }
    mediaDescs =
        new MediaDesc[] {
          new MediaDesc("audio", 0, "RTP/AVP", mediaSpecs.toArray(new MediaSpec[0]))
        };
  }

  public OpenAIMediaOptions() {
    this(AudioCodecOptions.BOTH);
  }

  public MediaDesc[] getMediaDescs() {
    return mediaDescs;
  }
}
