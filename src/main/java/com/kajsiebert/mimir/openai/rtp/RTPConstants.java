package com.kajsiebert.mimir.openai.rtp;

/** Constants for RTP packets. */
public class RTPConstants {
  public static final long PACKET_INTERVAL_MS = 20; // 20ms between packets
  public static final int RTP_PACKET_SIZE = 160; // 20ms of G.711 audio at 8kHz
  public static final int RTP_HEADER_SIZE =
      12; // 12 bytes for the RTP header - version, payload type, sequence number, timestamp, SSRC
  public static final int SSRC = 0x12345678; // Random SSRC identifier
}
