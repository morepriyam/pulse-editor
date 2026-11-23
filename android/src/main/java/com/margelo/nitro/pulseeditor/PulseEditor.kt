package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@DoNotStrip
class PulseEditor : HybridPulseEditorSpec() {
  override fun probe(uri: String): Promise<ProbeResult> {
    return Promise.async { Probe.read(context(), uri) }
  }

  override fun extractAudio(uri: String, sampleRate: Double): Promise<AudioPCM> {
    return Promise.async {
      // Decoding a long file takes seconds of blocking MediaCodec calls: off the CPU pool.
      val pcm = withContext(Dispatchers.IO) { ExtractAudio.read(context(), uri, sampleRate.toInt()) }
      AudioPCM(ArrayBuffer.wrap(pcm.data), sampleRate, pcm.durationMs)
    }
  }

  override fun thumbnail(uri: String, options: ThumbnailOptions): Promise<Thumbnail> {
    return Promise.async { FrameGrab.run(context(), uri, options) }
  }

  override fun frames(uri: String, options: FramesOptions): Promise<Array<Frame>> {
    return Promise.async { FrameGrab.frames(context(), uri, options) }
  }

  override fun createMerge(clips: Array<MergeClip>, options: MergeOptions): HybridMergeJobSpec =
    com.margelo.nitro.pulseeditor.merge.MergeJob(clips, options)

  override fun createConform(uri: String, options: ConformOptions): HybridConformJobSpec =
    com.margelo.nitro.pulseeditor.conform.ConformJob(uri, options)

  override fun createImageClip(uri: String, options: ImageClipOptions): HybridConformJobSpec =
    com.margelo.nitro.pulseeditor.conform.ConformJob("Image clip") { context, progress ->
      com.margelo.nitro.pulseeditor.conform.ImageClip.run(context, uri, options, progress)
    }

  /** Bench only: see [SeekBench]. */
  override fun createSeekBench(): HybridSeekBenchSpec = SeekBench()

  private fun context() =
    NitroModules.applicationContext ?: throw IllegalStateException("React context not ready")
}
