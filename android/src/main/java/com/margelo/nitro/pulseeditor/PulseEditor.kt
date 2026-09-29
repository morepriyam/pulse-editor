package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun hello(): String {
    return "Hello from PulseEditor (Android)"
  }
}
