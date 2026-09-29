package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun probe(uri: String): Promise<ProbeResult> {
    return Promise.async { Probe.read(context(), uri) }
  }

  override fun extractAudio(uri: String, sampleRate: Double): Promise<AudioPCM> {
    return Promise.async {
      val pcm = ExtractAudio.read(context(), uri, sampleRate.toInt())
      AudioPCM(ArrayBuffer.wrap(pcm.data), sampleRate, pcm.durationMs)
    }
  }

  override fun createMerge(clips: Array<MergeClip>, options: MergeOptions): HybridMergeJobSpec =
    com.margelo.nitro.pulseeditor.merge.MergeJob(clips, options)

  private fun context() =
    NitroModules.applicationContext ?: throw IllegalStateException("React context not ready")
}
