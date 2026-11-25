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
  /** The display transform also mirrors the frame (its matrix has a negative determinant). */
  mirrored: boolean;
  /** Average frame rate, -1 when unknown. */
  fps: number;
  /** Average bitrate in bits per second, -1 when unknown. */
  bitrate: number;
  /** Bits per luma sample: 8, or 10 for HDR sources. */
  bitDepth: number;
  transfer: Transfer;
  /** The video track's own duration. */
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

/** A draft-level narration take, independent of video clip boundaries. */
export interface VoiceoverAudio {
  uri: string;
  timelineStartMs: number;
  sourceStartMs: number;
  sourceEndMs: number;
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
  /** Narration is mixed with the unmuted source audio, without ducking. */
  voiceover?: VoiceoverAudio[];
  sourceMuted?: boolean;
  voiceoverMuted?: boolean;
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

/** What `conform` writes: one clip in the recorder's format. */
export interface ConformOptions {
  /** Display canvas the picture is fitted onto, letterboxed and centered, e.g. 1080×1920. */
  width: number;
  height: number;
  /** Clockwise rotation the output is tagged with: 0, 90, 180 or 270. At 90 or 270 the frames
   * are coded sideways (1920×1080 for a 1080×1920 canvas), the way phone cameras write portrait
   * video, so the result can join the camera's recordings without re-encoding. */
  rotation: number;
  /** Frame rate of the output; faster sources drop frames to it. */
  fps: number;
  /** Video bitrate in bits per second. */
  bitrate: number;
  /** Audio layout of the output (the recorder's). */
  audio: MergeAudio;
  /** Keep the video samples exactly as they are and only re-encode the audio into `audio`. The
   * canvas, rotation, fps and bitrate are then not used. */
  copyVideo: boolean;
}

export interface ConformResult {
  /** `file://` URI of the conformed MP4 (faststart), in the caches directory. */
  uri: string;
  durationMs: number;
  /** `false` when the video was copied (`copyVideo`). */
  encoded: boolean;
  /** Average video bitrate of the output, bits per second. */
  bitrate: number;
}

/** What `createImageClip` writes: a still image as one clip in the recorder's format. */
export interface ImageClipOptions {
  /** Display canvas the image is fitted onto, letterboxed and centered, e.g. 1080×1920: even,
   * 2–4096 a side. */
  width: number;
  height: number;
  /** Clockwise rotation the output is tagged with: 0, 90, 180 or 270. At 90 or 270 the frames
   * are coded sideways (1920×1080 for a 1080×1920 canvas), as in `ConformOptions`. */
  rotation: number;
  /** Constant frame rate of the output: every frame is written (90 frames for 3 s at 30). */
  fps: number;
  /** Video bitrate in bits per second. */
  bitrate: number;
  /** Layout of the output's silent sound track (the recorder's): a whole sample rate, 1 or 2
   * channels. */
  audio: MergeAudio;
  /** How long the clip lasts, ms; rounded to whole frames, at least 2, at most 60 000 ms. */
  durationMs: number;
  /** Colour around the image and behind its transparent parts, `#RRGGBB`. */
  background: string;
}

/** Which frame `thumbnail` takes, how big, and the clip's edit. */
export interface ThumbnailOptions {
  /** Source time in ms: the frame shown at that moment (clamped to the video). */
  timeMs: number;
  /** The picture is scaled down to fit inside maxWidth × maxHeight, never up. 0 = no limit. */
  maxWidth: number;
  maxHeight: number;
  /** JPEG quality, 0–1. */
  quality: number;
  /** The clip's edit, as in `MergeClip`: clockwise rotation, then a horizontal flip, then the
   * crop (normalized, in the rotated + flipped frame). */
  rotation: number;
  flipped: boolean;
  crop?: MergeCrop;
}

/** One frame as a JPEG file. */
export interface Thumbnail {
  /** `file://` URI of the JPEG, in the caches directory. */
  uri: string;
  width: number;
  height: number;
  /** When the frame starts in the source, ms. */
  timeMs: number;
}

/** Which frames `frames` takes, how big, and how close to the times asked. */
export interface FramesOptions {
  /** Source times in ms, in any order (each clamped to the video). */
  timesMs: number[];
  /** Each picture is scaled down to fit inside maxWidth × maxHeight, never up. 0 = no limit. */
  maxWidth: number;
  maxHeight: number;
  /** JPEG quality, 0–1. */
  quality: number;
  /** How far from its time a frame may come, ms: within it the nearest keyframe is taken, which
   * skips decoding the frames between. 0 = the exact frame. */
  toleranceMs: number;
}

/** One frame from `frames`, as JPEG bytes. */
export interface Frame {
  /** The time it was asked for, ms (as passed). */
  timeMs: number;
  /** When the frame shown starts in the source, ms. */
  actualMs: number;
  width: number;
  height: number;
  /** The JPEG file's bytes. */
  data: ArrayBuffer;
}

/** A file's audio as 16-bit signed little-endian mono PCM. */
export interface AudioPCM {
  /** The samples; empty when the file has no audio track. */
  data: ArrayBuffer;
  sampleRate: number;
  durationMs: number;
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

/** One conform run: start it once, cancel it any time. */
export interface ConformJob extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /** Run the conform. `onProgress` gets 0–1. Rejects with "Conform cancelled" after `cancel()`. */
  start(onProgress: (progress: number) => void): Promise<ConformResult>;
  /** Stop the conform and delete its partial output. Safe to call at any time. */
  cancel(): void;
}

/** Bench only: how a `SeekBench` drag treats its seek targets. `exact`: Media3's
 * `ScrubbingModeParameters.DEFAULT` (exact seeks); `fractional`: the default parameters plus a
 * fractional seek tolerance (`toleranceBeforeMs` / `toleranceAfterMs` of the clip's duration);
 * `closestSync`: the default parameters with the player's `SeekParameters.CLOSEST_SYNC` for the
 * drag only (scrubbing mode uses the player's seek parameters when it has no tolerance of its own). */
export type SeekBenchDragMode = 'exact' | 'fractional' | 'closestSync';

/** Bench only: one simulated finger drag of a `SeekBench` run, in scrubbing mode. */
export interface SeekBenchDrag {
  /** Name for the rows and the log lines, e.g. `fwd-exact`. */
  name: string;
  /** Source time the drag starts at and ends at, ms (backward when `toMs < fromMs`). */
  fromMs: number;
  toMs: number;
  /** How long the drag lasts and how often it seeks (e.g. 2000 ms at 60 Hz = 120 seeks). */
  durationMs: number;
  hz: number;
  mode: SeekBenchDragMode;
  /** For `fractional` only: the tolerance before and after each target, ms of source. */
  toleranceBeforeMs: number;
  toleranceAfterMs: number;
}

/** Bench only: what one `SeekBench` pass measures (one fresh player per pass). */
export interface SeekBenchOptions {
  /** Name of the pass in the rows and the log lines. */
  label: string;
  /** Paused exact seeks, in order, source ms: each goes to the start of the frame showing at that time. */
  seekTargetsMs: number[];
  /** Paused seeks run with Media3's scrubbing mode on (exact, default parameters). */
  pausedScrubbing: boolean;
  /** Idle time after each landed seek and each drag, ms. */
  restMs: number;
  drags: SeekBenchDrag[];
  /** Ask the decoder for low-latency output (`KEY_LOW_LATENCY` = 1 through the player's video
   * codec parameters, set before the codec is configured). */
  lowLatency: boolean;
  /** Size of the bench's TextureView, dp. */
  viewWidth: number;
  viewHeight: number;
}

/** Bench only: one seek (paused, to a drag's start, or the exact settle seek after a drag). */
export interface SeekBenchSeek {
  /** `paused`, `pre-<drag name>` or `settle-<drag name>`. */
  kind: string;
  /** Where the seek was asked to go (a frame start) and what the player was given (1 ms earlier
   * when the player already sat at that position, so the seek isn't skipped; at 0, 0 after a seek
   * to 1 ms). */
  targetMs: number;
  seekedMs: number;
  /** Presentation time of the frame that answered it, ms; -1 when none came (timed out). */
  ptsMs: number;
  /** Seek → the release time Media3 gave that frame (VideoFrameMetadataListener), ms. */
  releasedMs: number;
  /** Seek → that release callback arriving on the playback thread, ms. */
  callbackMs: number;
  /** Seek → the frame reaching the TextureView (onSurfaceTextureUpdated), ms; -1 = timed out. */
  shownMs: number;
}

/** Bench only: one picture that reached the view during a drag. */
export interface SeekBenchPicture {
  /** Presentation time of the frame, ms; -1 when it couldn't be matched to a release. */
  ptsMs: number;
  /** When it reached the view, ms since the drag's first seek. */
  atMs: number;
  /** From the drag first asking for that frame to it reaching the view, ms; -1 when no seek asked
   * for exactly that frame (a tolerant seek landed on a keyframe instead). */
  latencyMs: number;
}

/** Bench only: one drag's outcome. */
export interface SeekBenchDragResult {
  name: string;
  mode: SeekBenchDragMode;
  /** Seeks given to the player, and how many distinct frame targets they were. */
  seeksIssued: number;
  distinctTargets: number;
  /** The measured window: from the first seek to one period after the last, ms. */
  windowMs: number;
  /** Pictures that reached the view in the window, how many distinct frames they were, and the
   * distinct frames per second of window. */
  pictures: number;
  distinctFrames: number;
  picturesPerSecond: number;
  /** The final exact seek after the drag (scrubbing off): -1 when it timed out. */
  settleMs: number;
  frames: SeekBenchPicture[];
}

/** Bench only: one `SeekBench` pass. Times are ms. */
export interface SeekBenchResult {
  label: string;
  /** The decoder the player initialised (AnalyticsListener), its init time, whether low-latency
   * decoding was applied (asked for, on API 30+), and what the decoder supports. */
  decoder: string;
  decoderInitMs: number;
  lowLatency: boolean;
  lowLatencySupported: boolean;
  maxDecoderInstances: number;
  /** TextureView added → its surface available (not part of the player's cost). */
  viewMs: number;
  /** Player build start → `build()` returned, → STATE_READY, → first frame on the view
   * (`firstFrameMs` is the prewarm cost: create + prepare + first frame). */
  buildMs: number;
  readyMs: number;
  firstFrameMs: number;
  /** `release()` of the player. */
  releaseMs: number;
  durationMs: number;
  fps: number;
  seeks: SeekBenchSeek[];
  drags: SeekBenchDragResult[];
  /** Frames that reached the view without a release whose time matched the texture's
   * timestamp (matched by order instead). */
  matchedByOrder: number;
}

/** Bench only (Android): a plain Media3 ExoPlayer on one file, no effects, drawing into a
 * TextureView added over the app for the run: paused exact seeks, drags, prewarm cost. The gate
 * measurement for a plain-player drag layer in `<PulsePreview>`. Rejects on iOS. */
export interface SeekBench extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /** One pass on `uri` with a fresh player, released at the end. */
  run(uri: string, options: SeekBenchOptions): Promise<SeekBenchResult>;
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

  /**
   * Decode a local file's audio track to 16-bit mono PCM at `sampleRate`, downmixed and
   * band-limited resampled, in memory. Turned down as a whole only if the downmix would clip.
   */
  extractAudio(uri: string, sampleRate: number): Promise<AudioPCM>;

  /**
   * The frame at `options.timeMs` as a JPEG, with the clip's edit drawn as merge renders it,
   * scaled to fit the maximum size; HDR is tone-mapped to SDR. Rejects when the file has no
   * video or can't be decoded.
   */
  thumbnail(uri: string, options: ThumbnailOptions): Promise<Thumbnail>;

  /**
   * Several frames of one file as JPEG bytes, in the order asked, from one decoder (a timeline's
   * filmstrip): the file's own pictures, upright and unedited (the caller draws an edit on them),
   * as 8-bit SDR JPEGs (an HDR source is converted by the platform's frame reader: Media3's
   * FrameExtractor tone-maps it, as `thumbnail` does; on iOS AVAssetImageGenerator converts it).
   * Within `toleranceMs` the nearest keyframe is taken instead of the exact frame. A time that
   * can't be decoded is left out; rejects when the file has no video, can't be opened, or no
   * frame decoded.
   */
  frames(uri: string, options: FramesOptions): Promise<Frame[]>;

  /** Prepare a merge of `clips`, in order, applying each clip's edit. Call `start` to run it. */
  createMerge(clips: MergeClip[], options: MergeOptions): MergeJob;

  /** Prepare a conform of one file into `options`' format. Call `start` to run it. */
  createConform(uri: string, options: ConformOptions): ConformJob;

  /**
   * Prepare a clip of a still image (JPEG, PNG, HEIC, …) in `options`' format: the image upright
   * (EXIF orientation applied), decoded straight to its size fitted on the canvas, converted to
   * SDR sRGB, placed on the canvas filled with `background`, held for `durationMs` at a constant
   * frame rate, with a silent AAC track. Call `start` to run it; it rejects when the image can't
   * be decoded here, and below Android 9. `imageClip` checks the options; this only guards.
   */
  createImageClip(uri: string, options: ImageClipOptions): ConformJob;

  /** Bench only: a plain-ExoPlayer seek bench (Android; rejects on iOS). */
  createSeekBench(): SeekBench;
}
