import { NitroModules } from 'react-native-nitro-modules';
import type { PulseEditor } from './PulseEditor.nitro';

export const editor =
  NitroModules.createHybridObject<PulseEditor>('PulseEditor');
