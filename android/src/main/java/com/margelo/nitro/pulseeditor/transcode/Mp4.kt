package com.margelo.nitro.pulseeditor.transcode

import java.io.File
import java.io.RandomAccessFile

/** One top-level box: its type, where it starts and its size. */
private class Box(val type: String, val offset: Long, val size: Long)

/** The file's top-level boxes, in order. */
private fun boxes(f: RandomAccessFile): List<Box> {
  val boxes = mutableListOf<Box>()
  var offset = 0L
  val header = ByteArray(16)
  while (offset + 8 <= f.length()) {
    f.seek(offset)
    f.readFully(header, 0, 8)
    var size = ((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
      ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff)
    if (size == 1L) {
      f.readFully(header, 8, 8)
      size = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (header[i].toLong() and 0xff) }
    } else if (size == 0L) {
      size = f.length() - offset
    }
    if (size < 8) break
    boxes += Box(String(header, 4, 4, Charsets.US_ASCII), offset, size)
    offset += size
  }
  return boxes
}

/** True when the top-level `moov` box comes before `mdat` (progressive playback). */
internal fun isFaststart(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
  val order = boxes(f).map { it.type }
  order.indexOf("moov").let { it >= 0 && (order.indexOf("mdat") < 0 || it < order.indexOf("mdat")) }
}

/**
 * When the index (`moov`) ended up after the samples, moves it into the free space before `mdat`
 * (Transcode keeps enough there) and cuts it off the end. `mdat` doesn't move, so the sample
 * offsets inside `moov` stay right. Returns whether the file is now faststart.
 */
internal fun moveMoovToFront(file: File): Boolean = RandomAccessFile(file, "rw").use { f ->
  val all = boxes(f)
  val mdat = all.indexOfFirst { it.type == "mdat" }
  val moov = all.indexOfFirst { it.type == "moov" }
  if (mdat < 0 || moov < 0) return@use false
  if (moov < mdat) return@use true
  // Everything between ftyp and mdat must be free space, and the index must fit in it (with any
  // remainder big enough for a free box's 8-byte header).
  if (all.first().type != "ftyp" || all.subList(1, mdat).any { it.type != "free" } ||
    all.subList(moov + 1, all.size).any { it.type != "free" }) return@use false
  val start = all.first().size
  val space = all[mdat].offset - start
  val index = all[moov]
  val rest = space - index.size
  if (rest < 0 || rest in 1..7) return@use false
  val bytes = ByteArray(index.size.toInt())
  f.seek(index.offset)
  f.readFully(bytes)
  f.seek(start)
  f.write(bytes)
  if (rest > 0) {
    f.writeInt(rest.toInt())
    f.write("free".toByteArray(Charsets.US_ASCII))
  }
  f.setLength(index.offset)
  true
}

/**
 * Adds the AAC "roll" sample group (`sgpd` + `sbgp`, roll distance -1) to the audio track, as
 * FFmpeg's muxer does; Media3's doesn't write it. Without it AVFoundation (Safari, Photos, iOS
 * players) trims Apple's default 2112 priming samples on top of the edit list, so the audio plays
 * 44 ms early there while FFmpeg-based players are in sync. The boxes go at the end of the audio
 * `stbl`, inside a front `moov`, taking their bytes from the `free` box after it: `mdat` doesn't
 * move, so the sample offsets stay right. Returns whether the file now has the group.
 */
internal fun addAacRollGroup(file: File): Boolean = RandomAccessFile(file, "rw").use { f ->
  val all = boxes(f)
  val moov = all.indexOfFirst { it.type == "moov" }
  val mdat = all.indexOfFirst { it.type == "mdat" }
  if (moov < 0 || mdat < 0 || moov > mdat || moov + 1 >= all.size) return@use false
  val free = all[moov + 1]
  if (free.type != "free") return@use false
  val bytes = ByteArray(all[moov].size.toInt())
  f.seek(all[moov].offset)
  f.readFully(bytes)
  val patched = MoovPatch(bytes).withAacRoll() ?: return@use false
  val grown = patched.size - bytes.size
  if (grown == 0) return@use true
  val rest = free.size - grown
  if (rest < 0 || rest in 1..7) return@use false
  f.seek(all[moov].offset)
  f.write(patched)
  if (rest > 0) {
    f.writeInt(rest.toInt())
    f.write("free".toByteArray(Charsets.US_ASCII))
  }
  true
}

/** In-memory edit of a `moov` box (32-bit box sizes, as Media3 writes them). */
private class MoovPatch(private val moov: ByteArray) {
  private fun u32(at: Int) = ((moov[at].toInt() and 0xff) shl 24) or ((moov[at + 1].toInt() and 0xff) shl 16) or
    ((moov[at + 2].toInt() and 0xff) shl 8) or (moov[at + 3].toInt() and 0xff)
  private fun type(at: Int) = String(moov, at + 4, 4, Charsets.US_ASCII)

  /** Children of the box at `at` whose payload starts `skip` bytes after its header. */
  private fun children(at: Int, skip: Int = 0): List<Int> {
    val end = at + u32(at)
    val out = mutableListOf<Int>()
    var p = at + 8 + skip
    while (p + 8 <= end) {
      val size = u32(p)
      if (size < 8 || p + size > end) return emptyList()
      out += p
      p += size
    }
    return out
  }

  private fun child(at: Int, t: String) = children(at).firstOrNull { type(it) == t }

  /** The moov with the roll group added to its AAC track, the same moov when it already has one,
   * or null when the layout isn't the expected one. */
  fun withAacRoll(): ByteArray? {
    if (type(0) != "moov" || u32(0) != moov.size) return null
    for (trak in children(0).filter { type(it) == "trak" }) {
      val mdia = child(trak, "mdia") ?: continue
      val hdlr = child(mdia, "hdlr") ?: continue
      if (String(moov, hdlr + 16, 4, Charsets.US_ASCII) != "soun") continue
      val stbl = child(mdia, "minf")?.let { child(it, "stbl") } ?: return null
      val stsd = child(stbl, "stsd") ?: return null
      if (children(stsd, 8).none { type(it) == "mp4a" }) return null
      if (children(stbl).any { type(it) == "sgpd" || type(it) == "sbgp" }) return moov
      val stsz = child(stbl, "stsz") ?: return null
      val samples = u32(stsz + 16)
      val boxes = java.nio.ByteBuffer.allocate(SGPD_SIZE + SBGP_SIZE)
        // sgpd v1: grouping 'roll', default_length 2, one entry: roll_distance -1.
        .putInt(SGPD_SIZE).put("sgpd".toByteArray()).putInt(0x01000000).put("roll".toByteArray())
        .putInt(2).putInt(1).putShort(-1)
        // sbgp v0: every sample in group 1.
        .putInt(SBGP_SIZE).put("sbgp".toByteArray()).putInt(0).put("roll".toByteArray())
        .putInt(1).putInt(samples).putInt(1)
        .array()
      val insertAt = stbl + u32(stbl)
      val out = moov.copyOfRange(0, insertAt) + boxes + moov.copyOfRange(insertAt, moov.size)
      // Grow every box that now contains the new ones: moov, trak, mdia, minf, stbl.
      val minf = child(mdia, "minf")!!
      for (at in listOf(0, trak, mdia, minf, stbl)) {
        val size = u32(at) + boxes.size
        out[at] = (size ushr 24).toByte(); out[at + 1] = (size ushr 16).toByte()
        out[at + 2] = (size ushr 8).toByte(); out[at + 3] = size.toByte()
      }
      return out
    }
    return null
  }

  private companion object {
    const val SGPD_SIZE = 26
    const val SBGP_SIZE = 28
  }
}
