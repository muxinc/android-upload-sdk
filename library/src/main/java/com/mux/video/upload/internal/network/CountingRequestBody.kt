package com.mux.video.upload.internal.network

import android.util.Log
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * RequestBody based on an InputStream that reports the number of bytes written until either
 * [contentLength] bytes are transferred or the end of the input is reached. This RequestBody
 * doesn't close
 */
internal fun ByteArray.asCountingRequestBody(
  mediaType: MediaType?,
  contentLength: Long,
  callback: (Long) -> Unit
): RequestBody = CountingRequestBody(this, mediaType, contentLength, callback)

/**
 * A RequestBody that reads its content from a file and reports its progress (in bytes) as it goes
 */
private class CountingRequestBody constructor(

  private val bodyData: ByteArray,
  private val mediaType: MediaType?,
  private val contentLength: Long,
  private val callback: (Long) -> Unit,
) : RequestBody() {
  // TODO <em>: this doesn't need to be atomic
  private var dead = false

  companion object {
    const val READ_LENGTH = 256 * 1024
  }

  override fun contentLength(): Long = contentLength//bodyData.size.toLong()

  override fun contentType(): MediaType? = mediaType

  override fun isOneShot(): Boolean = true

  override fun writeTo(sink: BufferedSink) {
    Log.w("fuck", "writeTo called on thread ${Thread.currentThread().name}")
    var totalBytes = 0
    if (dead) {
      Log.d("fuck", "writeTo called on dead object");
      Thread.dumpStack()
      return
    }
    sink.use { output ->
        val readBuf = ByteArray(size = READ_LENGTH)
        do {
          val bytesReadThisTime: Int
          val realReadLength = if (totalBytes + READ_LENGTH > contentLength) {
            // There's not enough data left for the whole READ_LENGTH, so read the remainder
            Log.d("fuck2", "readRedLength is ${(contentLength - totalBytes).toInt()}")
            (contentLength - totalBytes).toInt()
          } else {
            // There's enough to fill the entire read buffer
            READ_LENGTH
          }

          bodyData.copyInto(
            destination = readBuf,
            startIndex = totalBytes,
            endIndex = totalBytes + realReadLength,
            destinationOffset = 0,
          )

          output.write(readBuf, 0, realReadLength)
          bytesReadThisTime = realReadLength
          if (bytesReadThisTime >= 0) {
            totalBytes += bytesReadThisTime
            callback(totalBytes.toLong())
            output.flush()
          }
          //Log.v("fuck", "${tmpcnt++} writeTo() read $readBytes from src")
          //Log.v("fuck", "(That's ${totalBytes.get()} / $contentLength btw)")
        } while (bytesReadThisTime >= 0 && totalBytes < contentLength)
        Log.v("fuck", "total read: $totalBytes (compare with contentLength $contentLength")

        dead = true
    }
  }
}
