package com.margelo.nitro.pulseeditor

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise

/** One merge run. Android's merge (Media3 Transformer) is not built yet. */
@DoNotStrip
class MergeJob(
  private val clips: Array<MergeClip>,
  private val options: MergeOptions,
) : HybridMergeJobSpec() {
  override fun start(onProgress: (progress: Double) -> Unit): Promise<MergeResult> =
    Promise.rejected(UnsupportedOperationException("merge isn't built on Android yet"))

  override fun cancel() {}
}
