package com.margelo.nitro.pulseeditor

import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/** A `file://` / `content://` URI, or a bare path, as a [Uri]. */
internal fun mediaUri(uri: String): Uri = if (uri.startsWith("/")) Uri.fromFile(File(uri)) else uri.toUri()
