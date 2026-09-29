import { editor } from './editor';
import type {
  MergeClip,
  MergeOptions,
  MergeResult,
  ProbeResult,
} from './PulseEditor.nitro';

export type {
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
 * Read a local media file's metadata (codec, coded size, rotation, fps, HDR, audio layout,
 * durations). Accepts a `file://` URI or a bare path. Rejects when the file can't be opened.
 */
export function probe(uri: string): Promise<ProbeResult> {
  if (!uri?.trim().length) {
    return Promise.reject(new Error('File path cannot be empty.'));
  }
  return editor.probe(uri);
}

export interface MergeControls {
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
  { onProgress, signal }: MergeControls = {}
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
  } finally {
    signal?.removeEventListener('abort', cancel);
  }
}
