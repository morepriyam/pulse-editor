package com.margelo.nitro.pulseeditor.merge

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.pulseeditor.HybridMergeJobSpec
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.MergeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One merge run. `start` runs it once in a coroutine; `cancel` cancels that coroutine, which
 * cancels the export in flight and deletes its partial output. */
@DoNotStrip
class MergeJob(
  private val clips: Array<MergeClip>,
  private val options: MergeOptions,
) : HybridMergeJobSpec() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var job: Deferred<MergeResult>? = null
  private var cancelled = false

  override fun start(onProgress: (progress: Double) -> Unit): Promise<MergeResult> {
    val context = NitroModules.applicationContext
      ?: return Promise.rejected(IllegalStateException("React context not ready"))
    val job = synchronized(this) {
      if (job != null) return Promise.rejected(IllegalStateException("This merge was already started."))
      scope.async { Merge.run(context, clips.toList(), options, MergeProgress(onProgress)) }.also {
        job = it
        if (cancelled) it.cancel()
      }
    }
    return Promise.async {
      try {
        job.await()
      } catch (e: CancellationException) {
        throw MergeException("Merge cancelled")
      }
    }
  }

  override fun cancel() {
    synchronized(this) {
      cancelled = true
      job?.cancel()
    }
  }
}

/** Progress that only ever moves forward, so a later phase can never make the bar jump back. */
class MergeProgress(private val report: (Double) -> Unit) {
  private var last = -1.0

  operator fun invoke(value: Double) {
    val clamped = value.coerceIn(0.0, 1.0)
    val send = synchronized(this) {
      if (clamped > last) { last = clamped; true } else false
    }
    if (send) report(clamped)
  }
}

class MergeException(message: String) : Exception(message)
