import { editor } from './editor';
import type { ProbeResult } from './PulseEditor.nitro';

export type {
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
