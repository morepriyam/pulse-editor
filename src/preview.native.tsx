import { useEffect, useMemo, useRef } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import {
  callback,
  getHostComponent,
  type HybridRef,
} from 'react-native-nitro-modules';
import PulsePreviewConfig from '../nitrogen/generated/shared/json/PulsePreviewConfig.json';
import type { MergeClip, MergeOptions } from './PulseEditor.nitro';
import type {
  PreviewStatus,
  PulsePreviewMethods,
  PulsePreviewProps,
} from './PulsePreview.nitro';

const NativePreview = getHostComponent<PulsePreviewProps, PulsePreviewMethods>(
  'PulsePreview',
  () => PulsePreviewConfig
);

/** Handle for calling the preview's methods (play, pause, seek, …). */
export type PulsePreviewRef = HybridRef<PulsePreviewProps, PulsePreviewMethods>;

export interface PulsePreviewViewProps {
  /** Keep the array's identity stable while the clips don't change (useMemo): a new array
   * rebuilds the timeline. */
  clips: MergeClip[];
  /** The options the draft will be merged with (keep the object stable, like `clips`). */
  options: MergeOptions;
  onTime?: (timeMs: number, playing: boolean) => void;
  onStatus?: (status: PreviewStatus) => void;
  /** Receives the handle once the native view exists. */
  onReady?: (ref: PulsePreviewRef) => void;
  style?: StyleProp<ViewStyle>;
}

/**
 * Plays a draft's clips, edits applied, exactly as `merge` would export them. Styling goes on a
 * wrapping View: on Android, Nitro views don't take base view props (backgroundColor, opacity…).
 */
export function PulsePreview({
  clips,
  options,
  onTime,
  onStatus,
  onReady,
  style,
}: PulsePreviewViewProps) {
  const handlers = useRef({ onTime, onStatus, onReady });
  useEffect(() => {
    handlers.current = { onTime, onStatus, onReady };
  });
  // Function props must be wrapped with callback(); stable wrappers keep Nitro from re-sending
  // them on every render.
  const native = useMemo(
    () => ({
      onTime: callback((timeMs: number, playing: boolean) =>
        handlers.current.onTime?.(timeMs, playing)
      ),
      onStatus: callback((status: PreviewStatus) =>
        handlers.current.onStatus?.(status)
      ),
      hybridRef: callback((ref: PulsePreviewRef) =>
        handlers.current.onReady?.(ref)
      ),
    }),
    []
  );
  return (
    <View style={style}>
      <NativePreview
        style={StyleSheet.absoluteFill}
        clips={clips}
        options={options}
        onTime={native.onTime}
        onStatus={native.onStatus}
        hybridRef={native.hybridRef}
      />
    </View>
  );
}
