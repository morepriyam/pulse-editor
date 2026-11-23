import { editor } from './editor';
import type {
  AudioPCM,
  ConformOptions,
  ConformResult,
  ImageClipOptions,
  MergeClip,
  MergeOptions,
  MergeResult,
  ProbeResult,
  SeekBenchOptions,
  SeekBenchResult,
  Thumbnail,
  ThumbnailOptions,
  FramesOptions,
  Frame,
} from './PulseEditor.nitro';

export type {
  AudioPCM,
  ConformOptions,
  ConformResult,
  ImageClipOptions,
  MergeAudio,
  MergeClip,
  MergeCrop,
  MergeOptions,
  MergeResult,
  ProbeAudio,
  ProbeResult,
  ProbeVideo,
  SeekBenchDrag,
  SeekBenchDragMode,
  SeekBenchDragResult,
  SeekBenchOptions,
  SeekBenchPicture,
  SeekBenchResult,
  SeekBenchSeek,
  Thumbnail,
  ThumbnailOptions,
  Transfer,
  FramesOptions,
  Frame,
} from './PulseEditor.nitro';
export type { PreviewStats, PreviewStatus } from './PulsePreview.nitro';
export {
  PulsePreview,
  type PulsePreviewRef,
  type PulsePreviewViewProps,
} from './preview';

/**
 * A native failure as a plain Error with just its message. Android errors reach JS as the Kotlin
 * exception's class name and stack trace ("com.….MergeException: The merged … at …(Merge.kt:70)").
 */
function nativeError(error: unknown): Error {
  let message = error instanceof Error ? error.message : String(error);
  message = message.split(/\n\s*at /)[0]!.trim();
  // Qualified exception class names in front of the message, e.g. "java.io.IOException: ".
  while (/^([A-Za-z_$][\w$]*\.)+[\w$]+: /.test(message)) {
    message = message.replace(/^([A-Za-z_$][\w$]*\.)+[\w$]+: /, '');
  }
  return new Error(message);
}

/**
 * Read a local media file's metadata (codec, coded size, rotation, fps, HDR, audio layout,
 * durations). Accepts a `file://` URI or a bare path. Rejects when the file can't be opened.
 */
export function probe(uri: string): Promise<ProbeResult> {
  if (!uri?.trim().length) {
    return Promise.reject(new Error('File path cannot be empty.'));
  }
  return editor.probe(uri).catch((e) => {
    throw nativeError(e);
  });
}

/**
 * Decode a local file's audio to 16-bit signed little-endian mono PCM, in memory, at
 * `sampleRate` (default 16 kHz, what whisper.cpp takes): ready for whisper.rn's
 * `transcribeData` / `detectSpeechData`. `data` is empty when the file has no audio track.
 */
export function extractAudio(
  uri: string,
  { sampleRate = 16000 }: { sampleRate?: number } = {}
): Promise<AudioPCM> {
  if (!uri?.trim().length) {
    return Promise.reject(new Error('File path cannot be empty.'));
  }
  return editor.extractAudio(uri, sampleRate).catch((e) => {
    throw nativeError(e);
  });
}

/**
 * The frame at `timeMs` as a JPEG file (in the caches directory), with the clip's edit drawn as
 * merge renders it, scaled to fit `maxWidth` × `maxHeight` (never up). HDR is tone-mapped to SDR.
 * Defaults: the first frame, no size limit, quality 0.8, no edit.
 */
export function thumbnail(
  uri: string,
  options: Partial<ThumbnailOptions> = {}
): Promise<Thumbnail> {
  if (!uri?.trim().length) {
    return Promise.reject(new Error('File path cannot be empty.'));
  }
  const full: ThumbnailOptions = {
    timeMs: 0,
    maxWidth: 0,
    maxHeight: 0,
    quality: 0.8,
    rotation: 0,
    flipped: false,
    ...options,
  };
  return editor.thumbnail(uri, full).catch((e) => {
    throw nativeError(e);
  });
}

/**
 * Several frames of one file as JPEG bytes, from one decoder: the file's own pictures, upright and
 * unedited. With `toleranceMs`, the nearest keyframe within it is taken instead of the exact frame
 * (much faster for a filmstrip). A time that can't be decoded is left out. Defaults: no size
 * limit, quality 0.7, exact frames.
 */
export function frames(
  uri: string,
  options: Partial<FramesOptions> & { timesMs: number[] }
): Promise<Frame[]> {
  if (!uri?.trim().length) {
    return Promise.reject(new Error('File path cannot be empty.'));
  }
  // Defaults for what's left out or passed as undefined (a spread would keep the undefined).
  const full: FramesOptions = {
    timesMs: options.timesMs,
    maxWidth: options.maxWidth ?? 0,
    maxHeight: options.maxHeight ?? 0,
    quality: options.quality ?? 0.7,
    toleranceMs: options.toleranceMs ?? 0,
  };
  if (!full.timesMs.every(Number.isFinite)) {
    return Promise.reject(new Error('Frame times must be finite numbers.'));
  }
  const { maxWidth, maxHeight, quality, toleranceMs } = full;
  if (![maxWidth, maxHeight, quality, toleranceMs].every(Number.isFinite)) {
    return Promise.reject(new Error('Frame options must be finite numbers.'));
  }
  return editor.frames(uri, full).catch((e) => {
    throw nativeError(e);
  });
}

export interface JobControls {
  /** Called with 0–1 as the merge advances; never goes backwards. */
  onProgress?: (progress: number) => void;
  /** Aborting cancels the merge: it stops, deletes its partial output and rejects. */
  signal?: AbortSignal;
}

/**
 * Merge `clips`, in order, into one faststart MP4, applying each clip's edit. Clips that share
 * one format and need no rendering are joined without re-encoding (`encoded: false`).
 * Rejects with "Merge cancelled" when `signal` aborts.
 */
export async function merge(
  clips: MergeClip[],
  options: MergeOptions,
  { onProgress, signal }: JobControls = {}
): Promise<MergeResult> {
  if (clips.length === 0) {
    throw new Error('No clips to merge.');
  }
  if (signal?.aborted) {
    throw new Error('Merge cancelled');
  }
  const job = editor.createMerge(clips, options);
  const cancel = () => job.cancel();
  signal?.addEventListener('abort', cancel);
  try {
    return await job.start(onProgress ?? (() => {}));
  } catch (e) {
    throw nativeError(e);
  } finally {
    signal?.removeEventListener('abort', cancel);
  }
}

/**
 * Conform one local video file into the recorder's format (`options`): H.264 8-bit SDR at the
 * canvas, rotation tag, frame rate and bitrate asked for, AAC in the given layout, faststart.
 * HDR is tone-mapped to SDR; with `copyVideo` the video samples are kept and only the audio is
 * re-encoded. Rejects when the file can't be read, when its sound can't be decoded on this device,
 * and with "Conform cancelled" when `signal` aborts.
 */
export async function conform(
  uri: string,
  options: ConformOptions,
  { onProgress, signal }: JobControls = {}
): Promise<ConformResult> {
  if (!uri?.trim().length) {
    throw new Error('File path cannot be empty.');
  }
  if (signal?.aborted) {
    throw new Error('Conform cancelled');
  }
  const job = editor.createConform(uri, options);
  const cancel = () => job.cancel();
  signal?.addEventListener('abort', cancel);
  try {
    return await job.start(onProgress ?? (() => {}));
  } catch (e) {
    throw nativeError(e);
  } finally {
    signal?.removeEventListener('abort', cancel);
  }
}

/** `#RGB` or `#RRGGBB` (with or without the `#`) → `#rrggbb`; null for anything else. */
function hexColor(value: string): string | null {
  const match = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim());
  if (!match) return null;
  const digits = match[1]!.toLowerCase();
  return `#${digits.length === 3 ? [...digits].map((d) => d + d).join('') : digits}`;
}

/**
 * Make a clip of a still image (JPEG, PNG, HEIC, a screenshot) in the recorder's format
 * (`options`, as for `conform`): H.264 8-bit SDR at the canvas, rotation tag, frame rate and
 * bitrate asked for, every frame present (90 for 3 s at 30 fps), a silent AAC track in the given
 * layout, faststart. The image is turned upright (EXIF orientation), decoded at most canvas-sized,
 * converted to SDR sRGB, and fitted (letterboxed, centered) onto the canvas filled with
 * `background`, which also shows through transparent parts. Defaults: `durationMs` 3000,
 * `background` '#000000'. Rejects when the image can't be decoded on this device ("This image
 * can't be read on this device.") and with "Image clip cancelled" when `signal` aborts.
 */
export async function imageClip(
  uri: string,
  options: Omit<ImageClipOptions, 'durationMs' | 'background'> &
    Partial<Pick<ImageClipOptions, 'durationMs' | 'background'>>,
  { onProgress, signal }: JobControls = {}
): Promise<ConformResult> {
  if (!uri?.trim().length) {
    throw new Error('File path cannot be empty.');
  }
  const durationMs = options.durationMs ?? 3000;
  if (!Number.isFinite(durationMs) || durationMs <= 0) {
    throw new Error("The image clip's duration must be more than 0 ms.");
  }
  const background = hexColor(options.background ?? '#000000');
  if (background === null) {
    throw new Error('The background must be a colour like #000000.');
  }
  if (signal?.aborted) {
    throw new Error('Image clip cancelled');
  }
  const { width, height, rotation, fps, bitrate, audio } = options;
  const job = editor.createImageClip(uri, {
    width,
    height,
    rotation,
    fps,
    bitrate,
    audio,
    durationMs,
    background,
  });
  const cancel = () => job.cancel();
  signal?.addEventListener('abort', cancel);
  try {
    return await job.start(onProgress ?? (() => {}));
  } catch (e) {
    throw nativeError(e);
  } finally {
    signal?.removeEventListener('abort', cancel);
  }
}

/**
 * Bench only (Android): one pass of the plain-ExoPlayer seek bench on `uri`. A fresh Media3
 * ExoPlayer with no effects draws the file into a TextureView laid over the app for the pass;
 * it measures the player's create + prepare + first frame, paused exact seeks and scrubbing-mode
 * drags (to on screen), then releases the player. Rejects on iOS and while another pass runs.
 */
export async function seekBench(
  uri: string,
  options: SeekBenchOptions
): Promise<SeekBenchResult> {
  if (!uri?.trim().length) {
    throw new Error('File path cannot be empty.');
  }
  const bench = editor.createSeekBench();
  try {
    return await bench.run(uri, options);
  } catch (e) {
    throw nativeError(e);
  }
}
