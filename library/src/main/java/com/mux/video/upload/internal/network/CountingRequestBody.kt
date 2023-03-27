package com.mux.video.upload.internal.network

import android.util.Log
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
  private var totalBytes = AtomicInteger(0)
  private var dead = false

  companion object {
    const val READ_LENGTH = 256 * 1024
  }

  override fun contentLength(): Long = bodyData.size.toLong()

  override fun contentType(): MediaType? = mediaType

  //override fun isOneShot(): Boolean = true

  override fun writeTo(sink: BufferedSink) {
    Log.w("fuck", "writeTo called")
//    sink.use { output ->
//      inputStream.source().use { fileStream ->
//        output.w
//      }
//    }
    var tmpcnt = 0
    if (dead) {
      Log.d("fuck", "writeTo called on dead object");
      Thread.dumpStack()
      return
    }
    sink.use { output ->
      //.use { source ->
        var readBytes: Int = 0
        val readBuf = ByteArray(size = READ_LENGTH)
        do {
          //readBytes = source.read(sink.buffer, READ_LENGTH)
          output.write(readBuf, 0, READ_LENGTH)
          readBytes += READ_LENGTH
          if (readBytes >= 0) {
            val newTotal = totalBytes.addAndGet(readBytes)
            callback(newTotal.toLong())
            //sink.flush()
          }
          //Log.v("fuck", "${tmpcnt++} writeTo() read $readBytes from src")
          //Log.v("fuck", "(That's ${totalBytes.get()} / $contentLength btw)")
        } while (readBytes >= 0 && totalBytes.get() < contentLength)
        Log.v("fuck", "total read: ${totalBytes.get()} (compare with contentLength $contentLength")

        dead = true
//      }
    }
  }
}
