package com.margelo.nitro.pulseeditor.conform

import android.content.Context
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.pulseeditor.ConformOptions
import com.margelo.nitro.pulseeditor.ConformResult
import com.margelo.nitro.pulseeditor.HybridConformJobSpec
import com.margelo.nitro.pulseeditor.merge.MergeException
import com.margelo.nitro.pulseeditor.merge.MergeProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One conform run, like MergeJob: `start` runs it once in a coroutine; `cancel` cancels that
 * coroutine, which cancels the export in flight and deletes its partial output. Image clips use it
 * too (`name` "Image clip"), so callers get the same progress, cancel and result. */
@DoNotStrip
class ConformJob(
  private val name: String,
  private val work: suspend (Context, MergeProgress) -> ConformResult,
) : HybridConformJobSpec() {
  constructor(uri: String, options: ConformOptions) :
    this("Conform", { context, progress -> Conform.run(context, uri, options, progress) })

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var job: Deferred<ConformResult>? = null
  private var cancelled = false

  override fun start(onProgress: (progress: Double) -> Unit): Promise<ConformResult> {
    val context = NitroModules.applicationContext
      ?: return Promise.rejected(IllegalStateException("React context not ready"))
    val job = synchronized(this) {
      if (job != null) return Promise.rejected(IllegalStateException("This ${name.lowercase()} was already started."))
      scope.async { work(context, MergeProgress(onProgress)) }.also {
        job = it
        if (cancelled) it.cancel()
      }
    }
    return Promise.async {
      try {
        job.await()
      } catch (e: CancellationException) {
        throw MergeException("$name cancelled")
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
