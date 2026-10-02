import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules';
import type { MergeClip, MergeOptions } from './PulseEditor.nitro';

/** What the preview is showing: the timeline's length once it's loaded, or why it couldn't. */
export interface PreviewStatus {
  /** The timeline's length in ms (0 while loading or empty). */
  durationMs: number;
  /** True once the first frame of the current timeline is on screen. */
  ready: boolean;
  /** Set when the timeline couldn't be loaded or played. */
  error?: string;
}

/** Timings of the preview's last operations, in ms (-1 when there hasn't been one). For tests. */
export interface PreviewStats {
  /** New timeline → its first frame on screen. */
  firstFrameMs: number;
  /** Last seek request → its frame on screen. */
  lastSeekMs: number;
  /** Last timeline change (an edit) → the changed frame on screen. */
  lastSwapMs: number;
  /** Frames shown and frames dropped since the timeline was set. */
  framesShown: number;
  framesDropped: number;
}

export interface PulsePreviewProps extends HybridViewProps {
  /** The draft's clips, in order, with their edits (as `merge` takes them). */
  clips: MergeClip[];
  /** The options the draft will be merged with: the preview shows what that merge would make
   * (its canvas, frame rate, and which clips it copies or renders). It draws at the view's size. */
  options: MergeOptions;
  /** The timeline time on screen in ms and whether it's playing: about 12 times a second while
   * playing, and after every seek, play and pause. */
  onTime: (timeMs: number, playing: boolean) => void;
  /** Loading, ready, the timeline's length, errors. */
  onStatus: (status: PreviewStatus) => void;
}

export interface PulsePreviewMethods extends HybridViewMethods {
  play(): void;
  pause(): void;
  /** Show the frame at `timeMs` on the timeline. Resolves with that frame's time once it's on
   * screen; a newer seek replaces a pending one (which then resolves with the newer frame). */
  seek(timeMs: number): Promise<number>;
  /** Faster, approximate seeks while the person drags; turn it off when they let go. */
  setScrubbing(scrubbing: boolean): void;
  /** The frame on screen as a JPEG file (`file://` URI). For tests. */
  snapshot(): Promise<string>;
  /** Timings of the last operations. For tests. */
  stats(): PreviewStats;
}

/** A native view that plays a draft's clips, edits applied, as `merge` would export them. */
export type PulsePreview = HybridView<PulsePreviewProps, PulsePreviewMethods>;
