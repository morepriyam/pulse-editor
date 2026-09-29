import type { HybridObject } from 'react-native-nitro-modules';

/** Transfer function of the video: SDR, or one of the two HDR curves. */
export type Transfer = 'sdr' | 'hlg' | 'pq';

/** The video track, as the platform decoder sees it. */
export interface ProbeVideo {
  /** `h264`, `hevc`, or the platform's own id for anything else. */
  codec: string;
  /** Coded (pre-rotation) width in pixels. */
  width: number;
  /** Coded (pre-rotation) height in pixels. */
  height: number;
  /** Clockwise rotation to display it upright: 0, 90, 180 or 270. */
  rotation: number;
  /** The display transform also mirrors the frame (iOS only; always false on Android). */
  mirrored: boolean;
  /** Average frame rate, -1 when unknown. */
  fps: number;
  /** Average bitrate in bits per second, -1 when unknown. */
  bitrate: number;
  /** Bits per luma sample: 8, or 10 for HDR sources. */
  bitDepth: number;
  transfer: Transfer;
  /** The video track's own duration (the container's on Android). */
  durationMs: number;
}

/** The audio track. */
export interface ProbeAudio {
  /** `aac`, `opus`, or the platform's own id for anything else. */
  codec: string;
  sampleRate: number;
  channels: number;
}

export interface ProbeResult {
  /** Container duration in milliseconds. */
  durationMs: number;
  /** Absent when the file has no video track. */
  video?: ProbeVideo;
  /** Absent when the file has no audio track. */
  audio?: ProbeAudio;
}

export interface PulseEditor extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /**
   * Read a local media file's container and track metadata with the platform's own media stack
   * (AVFoundation / Media3). Rejects when the platform can't open the file.
   */
  probe(uri: string): Promise<ProbeResult>;
}
