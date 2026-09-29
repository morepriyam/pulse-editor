import type { PulseEditor } from './PulseEditor.nitro';

export const editor = new Proxy({} as PulseEditor, {
  get() {
    throw new Error(
      "'@mieweb/pulse-editor' is only supported on native platforms."
    );
  },
});
