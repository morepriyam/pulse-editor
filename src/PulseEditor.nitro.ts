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

/** A crop rectangle, normalized 0–1, in the clip's frame after its edit rotation and flip. */
export interface MergeCrop {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** One clip of a merge and its edit. */
export interface MergeClip {
  /** `file://` URI or bare path. */
  uri: string;
  /** Trim window in the source, ms. `endMs <= startMs` means the whole clip. */
  startMs: number;
  endMs: number;
  /** Playback speed, 0.25–4 (1 = unchanged). Pitch is kept natural. */
  speed: number;
  muted: boolean;
  /** Edit rotation, clockwise: 0, 90, 180 or 270. */
  rotation: number;
  /** Mirror horizontally, after the rotation. */
  flipped: boolean;
  crop?: MergeCrop;
}

export interface MergeAudio {
  sampleRate: number;
  channels: number;
}

export interface MergeOptions {
  /** Output canvas (display size) and frame rate, e.g. 1080×1920 @ 30. */
  width: number;
  height: number;
  fps: number;
  /** Video bitrate in bits per second for anything that gets encoded. Clips already at or
   * below it can join without re-encoding. */
  bitrate: number;
  /** Audio layout for anything that gets encoded (the recorder's). */
  audio: MergeAudio;
}

export interface MergeResult {
  /** `file://` URI of the merged MP4 (faststart), in the caches directory. */
  uri: string;
  durationMs: number;
  /** `false` when the clips were joined without re-encoding. */
  encoded: boolean;
  /** Average video bitrate of the output, bits per second. */
  bitrate: number;
}

/** One merge run: start it once, cancel it any time. */
export interface MergeJob extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /** Run the merge. `onProgress` gets 0–1. Rejects with "Merge cancelled" after `cancel()`. */
  start(onProgress: (progress: number) => void): Promise<MergeResult>;
  /** Stop the merge and delete its partial output. Safe to call at any time. */
  cancel(): void;
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

  /** Prepare a merge of `clips`, in order, applying each clip's edit. Call `start` to run it. */
  createMerge(clips: MergeClip[], options: MergeOptions): MergeJob;
}
