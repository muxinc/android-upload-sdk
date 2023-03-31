package com.mux.video.upload.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * RequestBody based on a file that reports the number of bytes written at regular intervals until
 * the file has been fully uploaded.
 */
internal fun File.asCountingFileBody(
  mediaType: MediaType?,
  callback: (Long) -> Unit
): RequestBody {
  return CountingFileBody(this@asCountingFileBody, mediaType, callback)
}

/**
 * RequestBody based on a file that reports the number of bytes written at regular intervals until
 * the file has been fully uploaded.
 */
internal fun File.asCountingFileBody(
  contentType: String?,
  callback: (Long) -> Unit
): RequestBody =
  asCountingFileBody(contentType?.toMediaType(), callback)

/**
 * A RequestBody that reads its content from a file and reports its progress (in bytes) as it goes
 */
private class CountingFileBody constructor(
  private val file: File,
  private val mediaType: MediaType?,
  private val callback: (Long) -> Unit,
) : RequestBody() {
  private var totalBytes = AtomicLong(0)

  companion object {
    const val READ_LENGTH: Long = 256 * 1024
  }

  override fun contentLength(): Long {
    return file.length()
  }

  override fun contentType(): MediaType? = mediaType

  override fun writeTo(sink: BufferedSink) {
    file.source().use {
      var readBytes: Long
      do {
        readBytes = it.read(sink.buffer, READ_LENGTH)
        if (readBytes >= 0) {
          val newTotal = totalBytes.addAndGet(readBytes)
          // TODO: Why double bytes??
          callback(newTotal / 2)
          sink.flush()
        }
      } while(readBytes >= 0)
    }
  }
}