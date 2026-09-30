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
