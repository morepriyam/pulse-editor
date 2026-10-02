import { editor } from './editor';
import type {
  AudioPCM,
  ConformOptions,
  ConformResult,
  MergeClip,
  MergeOptions,
  MergeResult,
  ProbeResult,
} from './PulseEditor.nitro';

export type {
  AudioPCM,
  ConformOptions,
  ConformResult,
  MergeAudio,
  MergeClip,
  MergeCrop,
  MergeOptions,
  MergeResult,
  ProbeAudio,
  ProbeResult,
  ProbeVideo,
  Transfer,
} from './PulseEditor.nitro';

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
