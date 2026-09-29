import { NitroModules } from 'react-native-nitro-modules';
import type { PulseEditor } from './PulseEditor.nitro';

const PulseEditorHybridObject =
  NitroModules.createHybridObject<PulseEditor>('PulseEditor');

export function multiply(a: number, b: number): number {
  return PulseEditorHybridObject.multiply(a, b);
}
