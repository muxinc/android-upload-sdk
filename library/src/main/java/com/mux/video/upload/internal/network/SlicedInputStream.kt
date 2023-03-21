package com.mux.video.upload.internal.network

import java.io.FilterInputStream
import java.io.InputStream

/**
 * InputStream that reads only a slice of the Stream that it decorates, starting from its current
 * read position. Reading, skipping, etc on this stream advances the stream that it decorates.
 * The first byte of this stream is the current byte of the source stream, and the last is [length]
 * bytes forward in the stream
 *
 * @param length The length of the slice to take. If longer than the backing data, all is consumed
 * @param closeParentWhenClosed Closes the decorated InputStream if true. If false, the parent
 *   stream will be left open at the end of the slice (ie, advanced by [length] bytes)
 */
@JvmSynthetic
internal fun InputStream.sliceOf(length: Int, closeParentWhenClosed: Boolean = true): InputStream {
  return SlicedInputStream(this, closeParentWhenClosed, length);
}

/**
 * InputStream that reads only a slice of the Stream that it decorates, starting from its current
 * read position. Reading, skipping, etc on this stream advances the stream that it decorates.
 */
private class SlicedInputStream(
  originalStream: InputStream?,
  private val closeParent: Boolean = true,
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
      data
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
        return readBytes
      } else {
        return super.read(bytes)
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
        return readBytes
      } else {
        return super.read(b, off, len)
      }
    }
  }

  override fun skip(n: Long): Long {
    return if(n + readPos >= sliceLen) {
      // Don't skip past the end of the slice
      readPos = sliceLen.toLong()
      // Skip to exactly the end of the slice
      super.skip(sliceLen - readPos)
    } else {
      super.skip(n)
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

  private fun atEnd(): Boolean = readPos >= sliceLen

}
