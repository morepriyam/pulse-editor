package com.margelo.nitro.pulseeditor.transcode

import java.io.File
import java.io.RandomAccessFile

/** True when the top-level `moov` box comes before `mdat` (progressive playback). */
internal fun isFaststart(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
  var offset = 0L
  val header = ByteArray(16)
  while (offset + 8 <= f.length()) {
    f.seek(offset)
    f.readFully(header, 0, 8)
    var size = ((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
      ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff)
    val type = String(header, 4, 4, Charsets.US_ASCII)
    if (size == 1L) {
      f.readFully(header, 8, 8)
      size = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (header[i].toLong() and 0xff) }
    } else if (size == 0L) {
      size = f.length() - offset
    }
    if (type == "moov") return@use true
    if (type == "mdat") return@use false
    if (size < 8) return@use false
    offset += size
  }
  false
}
