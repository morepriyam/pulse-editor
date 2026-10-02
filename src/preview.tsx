import type { StyleProp, ViewStyle } from 'react-native';
import type { MergeClip, MergeOptions } from './PulseEditor.nitro';
import type {
  PreviewStatus,
  PulsePreviewMethods,
  PulsePreviewProps,
} from './PulsePreview.nitro';
import type { HybridRef } from 'react-native-nitro-modules';

export type PulsePreviewRef = HybridRef<PulsePreviewProps, PulsePreviewMethods>;

export interface PulsePreviewViewProps {
  clips: MergeClip[];
  /** The options the draft will be merged with (keep the object stable, like `clips`). */
  options: MergeOptions;
  onTime?: (timeMs: number, playing: boolean) => void;
  onStatus?: (status: PreviewStatus) => void;
  onReady?: (ref: PulsePreviewRef) => void;
  style?: StyleProp<ViewStyle>;
}

/** Native platforms only. */
export function PulsePreview(_: PulsePreviewViewProps): null {
  throw new Error(
    "'@mieweb/pulse-editor' is only supported on native platforms."
  );
}
