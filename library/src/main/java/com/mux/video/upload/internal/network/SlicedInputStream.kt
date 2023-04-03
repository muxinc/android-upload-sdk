package com.mux.video.upload.internal.network

import android.util.Log
import java.io.FilterInputStream
import java.io.InputStream

/**
 * InputStream that reads only a slice of the Stream that it decorates, starting from its current
 * read position. Reading, skipping, etc on this stream advances the stream that it decorates.
 * The first byte of this stream is the current byte of the source stream, and the last is [length]
 * bytes forward in the stream.
 *
 * If there is less available data than the [length] of the slice, all of the available data will
 * be read and the stream will be advanced to the end
 *
 * @param length The length of the slice to take. If longer than the backing data, all is consumed
 */
@JvmSynthetic
internal fun InputStream.sliceOf(length: Int): InputStream {
  return SlicedInputStream(this, false, length);
}

/**
 * InputStream that reads only a slice of the Stream that it decorates, starting from its current
 * read position. Reading, skipping, etc on this stream advances the stream that it decorates.
 */
private class SlicedInputStream(
  originalStream: InputStream?,
  private val closeParent: Boolean = false,
  private val sliceLen: Int
) : FilterInputStream(originalStream) {

  private var readPos: Long = 0

  override fun mark(readlimit: Int) {
    if (markSupported()) {
      if (readlimit > sliceLen) {
        throw IndexOutOfBoundsException()
      } else {
        super.mark(readlimit)
      }
    }
  }

  override fun read(): Int {
    return if (atEnd()) {
      -1
    } else {
      val data = super.read()
      readPos++
      return data
    }
  }

  override fun read(bytes: ByteArray): Int {
    return if (atEnd()) {
      -1
    } else {
      if (bytes.size + readPos > sliceLen) {
        // The caller wants more data than the size of the slice
        val smallerBuf = ByteArray((sliceLen - readPos).toInt())
        val readBytes = super.read(smallerBuf)
        smallerBuf.copyInto(bytes)
        readPos += readBytes
        return readBytes
      } else {
        return super.read(bytes).also { readPos += it }
      }
    }
  }

  override fun read(b: ByteArray?, off: Int, len: Int): Int {
    return if (b == null) {
      0
    } else if (atEnd()) {
      -1
    } else {
      if (len + readPos > sliceLen) {
        // The caller wants more data than the size of the slice
        val smallerBuf = ByteArray((sliceLen - readPos).toInt())
        val readBytes = super.read(smallerBuf)
        smallerBuf.copyInto(b, off)
        readPos += readBytes
        return readBytes
      } else {
        return super.read(b, off, len).also { readPos += it }
      }
    }
  }

  override fun skip(n: Long): Long {
    return if(n + readPos >= sliceLen) {
      // Don't skip past the end of the slice
      // Skip to exactly the end of the slice
      super.skip(sliceLen - readPos).also { readPos += it }
    } else {
      super.skip(n).also { readPos += it }
    }
  }

  override fun available(): Int {
    val superAvailable = super.available()
    return if (superAvailable > sliceLen) {
      sliceLen
    } else {
      superAvailable
    }
  }

  override fun close() {
    if (closeParent) {
      super.close()
    }
  }

  private fun atEnd(): Boolean = readPos >= sliceLen // TODO: Is this off by one?
}
