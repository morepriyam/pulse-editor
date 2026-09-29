package com.margelo.nitro.pulseeditor
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }
}
