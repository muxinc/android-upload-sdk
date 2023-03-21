package com.mux.video.upload.internal.network

import android.renderscript.ScriptGroup.Input
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * RequestBody based on an InputStream that reports the number of bytes written until either
 * [contentLength] bytes are transferred or the end of the input is reached. This RequestBody
 * doesn't close
 */
internal fun InputStream.asCountingRequestBody(
  mediaType: MediaType?,
  contentLength: Long,
  callback: (Long) -> Unit
): RequestBody = CountingRequestBody(this, mediaType, contentLength, callback)

/**
 * RequestBody based on a file that reports the number of bytes written at regular intervals until
 * the file has been fully uploaded.
 */
internal fun File.asCountingRequestBody(
  mediaType: MediaType?,
  callback: (Long) -> Unit
): RequestBody {
  return CountingRequestBody(FileInputStream(this), mediaType, length(), callback)
}

/**
 * RequestBody based on a file that reports the number of bytes written at regular intervals until
 * the file has been fully uploaded.
 */
internal fun File.asCountingRequestBody(
  contentType: String?,
  callback: (Long) -> Unit
): RequestBody =
  asCountingRequestBody(contentType?.toMediaType(), callback)

/**
 * A RequestBody that reads its content from a file and reports its progress (in bytes) as it goes
 */
private class CountingRequestBody constructor(
  private val inputStream: InputStream,
  private val mediaType: MediaType?,
  private val contentLength: Long,
  private val callback: (Long) -> Unit,
) : RequestBody() {
  // TODO <em>: this doesn't need to be atomic
  private var totalBytes = AtomicLong(0)

  companion object {
    const val READ_LENGTH: Long = 256 * 1024
  }

  override fun contentLength(): Long = contentLength

  override fun contentType(): MediaType? = mediaType

  override fun writeTo(sink: BufferedSink) {
    inputStream.source().use { source ->
      var readBytes: Long
      do {
        val totalBytesLocal = totalBytes.get()
        val readLen: Long = if (totalBytesLocal + READ_LENGTH < contentLength) {
          contentLength - totalBytesLocal
        } else {
          READ_LENGTH
        }

        readBytes = source.read(sink.buffer, readLen)
        if (readBytes >= 0) {
          val newTotal = totalBytes.addAndGet(readBytes)
          // TODO: Why double bytes??
          callback(newTotal / 2)
          sink.flush()
        }
      } while(readBytes >= 0 && totalBytesLocal < contentLength)
    }
  }
}
