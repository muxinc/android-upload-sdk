package com.mux.video.upload.internal.network

import android.util.Log
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal fun ByteArray.asCountingRequestBody(
  mediaType: MediaType?,
  contentLength: Long,
  callback: (Long) -> Unit
): RequestBody = CountingRequestBody(
  bodyData = this,
  mediaType = mediaType,
  contentLength = contentLength,
  callback = callback
)

internal fun ByteArray.asCountingRequestBody(
  mediaType: MediaType?,
  contentLength: Long,
  readSize: Int = CountingRequestBody.DEFAULT_READ_LENGTH,
  callback: (Long) -> Unit
): RequestBody = CountingRequestBody(
  bodyData = this,
  mediaType = mediaType,
  contentLength = contentLength,
  readLength = readSize,
  callback = callback
)

/**
 * A RequestBody that reads its content from a file and reports its progress (in bytes) as it goes
 */
private class CountingRequestBody constructor(

  private val bodyData: ByteArray,
  private val mediaType: MediaType?,
  private val contentLength: Long,
  private val readLength: Int = DEFAULT_READ_LENGTH,
  private val callback: (Long) -> Unit,
) : RequestBody() {
  // TODO <em>: this doesn't need to be atomic
  private var dead = false

  companion object {
    const val DEFAULT_READ_LENGTH = 256 * 1024
  }

  override fun contentLength(): Long = contentLength//bodyData.size.toLong()

  override fun contentType(): MediaType? = mediaType

  override fun isOneShot(): Boolean = true

  override fun writeTo(sink: BufferedSink) {
    var totalBytes = 0
    if (dead) {
      Thread.dumpStack()
      return
    }
    sink.use { output ->
      val readBuf = ByteArray(size = readLength)
      do {
        val bytesReadThisTime: Int
        val realReadLength = if (totalBytes + readLength > contentLength) {
          // There's not enough data left for the whole READ_LENGTH, so read the remainder
          (contentLength - totalBytes).toInt()
        } else {
          // There's enough to fill the entire read buffer
          readLength
        }

        bodyData.copyInto(
          destination = readBuf,
          startIndex = totalBytes,
          endIndex = totalBytes + realReadLength, /*- 1*/
          destinationOffset = 0,
        )

        output.buffer.write(readBuf, 0, realReadLength)
        bytesReadThisTime = realReadLength
        if (bytesReadThisTime >= 0) {
          totalBytes += bytesReadThisTime
          callback(totalBytes.toLong())
          output.flush()
        }
      } while (bytesReadThisTime >= 0 && totalBytes < contentLength)

      dead = true
    }
  }
}
