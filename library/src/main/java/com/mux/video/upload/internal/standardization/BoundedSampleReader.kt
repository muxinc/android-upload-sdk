package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import java.nio.ByteBuffer

internal class SampleScanStop(val status: SampleScanStatus) : RuntimeException()

/** One capped buffer and cooperative budget shared by source and effective-timeline scans. */
internal class BoundedSampleReader(
  private val limits: SampleScanLimits,
  private val apiLevel: Int,
  private val isCancelled: () -> Boolean,
) {
  private val started = System.nanoTime()
  private val data = ByteArray(limits.maximumSampleBytes)
  private val buffer = ByteBuffer.wrap(data)
  var sampleCount = 0; private set
  var bytesRead = 0L; private set
  var largestSampleBytes = 0; private set
  val elapsedNanos get() = System.nanoTime() - started

  fun checkBudget(beforeRead: Boolean = false) {
    if (isCancelled()) throw SampleScanStop(SampleScanStatus.Cancelled)
    if (elapsedNanos >= limits.maximumElapsedNanos ||
      (if (beforeRead) sampleCount >= limits.maximumSamples else sampleCount > limits.maximumSamples) ||
      (if (beforeRead) bytesRead >= limits.maximumReadBytes else bytesRead > limits.maximumReadBytes))
      throw SampleScanStop(SampleScanStatus.LimitExceeded)
  }

  /** The byte array is reused: consume relevant NAL evidence before advancing the extractor. */
  fun read(extractor: MediaExtractor, consume: (Long, Int, Int, ByteArray) -> Unit) {
    checkBudget(beforeRead = true)
    val flags = extractor.sampleFlags
    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() != 0) throw SampleScanStop(SampleScanStatus.Unsupported)
    val time = extractor.sampleTime
    val expected = if (apiLevel >= 28) extractor.sampleSize else null
    val remaining = minOf(data.size.toLong(), limits.maximumReadBytes - bytesRead).toInt()
    if (expected != null && (expected <= 0 || expected > remaining)) throw SampleScanStop(SampleScanStatus.LimitExceeded)
    buffer.clear()
    val capped = if (remaining < data.size) ByteBuffer.wrap(data, 0, remaining).slice() else buffer
    val size = try { extractor.readSampleData(capped, 0) } catch (_: IllegalArgumentException) {
      throw SampleScanStop(if (apiLevel < 28) SampleScanStatus.LimitExceeded else SampleScanStatus.Unreadable)
    }
    if (size > remaining) throw SampleScanStop(SampleScanStatus.LimitExceeded)
    if (size <= 0) throw SampleScanStop(if (remaining < data.size) SampleScanStatus.LimitExceeded else SampleScanStatus.Unreadable)
    if (expected != null && size.toLong() != expected) throw SampleScanStop(SampleScanStatus.Unreadable)
    bytesRead += size; sampleCount++; largestSampleBytes = maxOf(largestSampleBytes, size)
    consume(time, size, flags, data)
    checkBudget()
  }
}
