package com.margelo.nitro.pulseeditor

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Where [Resampler] writes its output samples. */
internal fun interface SampleSink {
  fun add(value: Float)
}

/**
 * Streaming band-limited resampler for a mono float signal: a Blackman-windowed sinc low-pass
 * (cutoff just under the lower Nyquist, so downsampling doesn't alias) evaluated polyphase from
 * a precomputed table. Media3's Sonic interpolates linearly, which folds everything above the
 * new Nyquist back into the band.
 */
internal class Resampler(inputRate: Int, outputRate: Int) {
  private val up: Int
  private val down: Int
  private val phases: Int
  private val half: Int
  private val table: Array<FloatArray>

  // Input not yet consumed, with `half` samples of history before the next output's centre.
  private var history = FloatArray(4096)
  private var length = 0
  // Next output's position in `history`: base index and phase (0 until up).
  private var base = 0
  private var phase = 0
  private var inputCount = 0L
  private var outputCount = 0L

  init {
    val g = gcd(inputRate, outputRate)
    up = outputRate / g
    down = inputRate / g
    // Cutoff in cycles per input sample, 5% under the lower Nyquist.
    val cutoff = 0.5 * min(1.0, outputRate.toDouble() / inputRate) * 0.95
    half = ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
    phases = min(up, MAX_PHASES)
    table = Array(phases) { p ->
      val frac = p.toDouble() / phases
      val taps = FloatArray(2 * half)
      var sum = 0.0
      for (k in 0 until 2 * half) {
        val t = (k - half + 1) - frac
        val x = 2 * cutoff * t
        val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
        val w = (t / half).let { if (it <= -1 || it >= 1) 0.0 else 0.42 + 0.5 * cos(PI * it) + 0.08 * cos(2 * PI * it) }
        taps[k] = (sinc * w).toFloat()
        sum += taps[k]
      }
      for (k in taps.indices) taps[k] = (taps[k] / sum).toFloat()  // unity gain at DC
      taps
    }
    // Leading silence so the first output is centred on the first input sample.
    length = half - 1
  }

  /** Feed `count` samples of `input` from `offset`, appending every output they complete. */
  fun process(input: FloatArray, offset: Int, count: Int, out: SampleSink) {
    if (up == down) {
      for (i in offset until offset + count) out.add(input[i])
      return
    }
    ensure(length + count)
    System.arraycopy(input, offset, history, length, count)
    length += count
    inputCount += count
    drain(out, length)
  }

  /** Flush the tail: outputs up to the input's end, filtered against trailing silence. */
  fun finish(out: SampleSink) {
    if (up == down) return
    val total = (inputCount * up + down - 1) / down
    ensure(length + half)
    java.util.Arrays.fill(history, length, length + half, 0f)
    drain(out, length + half, total)
  }

  private fun drain(out: SampleSink, available: Int, limit: Long = Long.MAX_VALUE) {
    while (base + 2 * half <= available && outputCount < limit) {
      val taps = table[if (phases == up) phase else (phase.toLong() * phases / up).toInt()]
      var acc = 0f
      for (k in 0 until 2 * half) acc += history[base + k] * taps[k]
      out.add(acc)
      outputCount++
      phase += down
      base += phase / up
      phase %= up
    }
    // Drop what no later output needs.
    if (base > 0) {
      val keep = length - base
      if (keep > 0) System.arraycopy(history, base, history, 0, keep)
      length = maxOf(keep, 0)
      base = 0
    }
  }

  private fun ensure(size: Int) {
    if (size > history.size) history = history.copyOf(maxOf(size, history.size * 2))
  }

  private companion object {
    const val ZERO_CROSSINGS = 12
    const val MAX_PHASES = 1024

    tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
  }
}
