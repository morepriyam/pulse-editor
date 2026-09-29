package com.margelo.nitro.pulseeditor

import androidx.media3.common.MediaLibraryInfo
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun hello(): String {
    return "Hello from PulseEditor (Android, Media3 ${MediaLibraryInfo.VERSION})"
  }
}
