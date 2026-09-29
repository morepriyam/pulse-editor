import { NitroModules } from 'react-native-nitro-modules';
import type { PulseEditor } from './PulseEditor.nitro';

const PulseEditorHybridObject =
  NitroModules.createHybridObject<PulseEditor>('PulseEditor');

export function hello(): string {
  return PulseEditorHybridObject.hello();
}
