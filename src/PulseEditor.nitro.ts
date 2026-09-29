import type { HybridObject } from 'react-native-nitro-modules';

export interface PulseEditor extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  hello(): string;
}
