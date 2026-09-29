package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun probe(uri: String): Promise<ProbeResult> {
    return Promise.parallel { Probe.read(context(), uri) }
  }

  private fun context() =
    NitroModules.applicationContext ?: throw IllegalStateException("React context not ready")
}
