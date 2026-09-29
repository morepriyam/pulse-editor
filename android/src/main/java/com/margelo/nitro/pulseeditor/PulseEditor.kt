package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun probe(uri: String): Promise<ProbeResult> {
    return Promise.async { Probe.read(context(), uri) }
  }

  override fun createMerge(clips: Array<MergeClip>, options: MergeOptions): HybridMergeJobSpec =
    MergeJob(clips, options)

  private fun context() =
    NitroModules.applicationContext ?: throw IllegalStateException("React context not ready")
}
