package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import android.os.Build
import androidx.media3.container.NalUnitUtil
import com.mux.exoplayeradapter.AbsRobolectricTest
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@Config(sdk = [23])
class MediaSampleInspectorTests : AbsRobolectricTest() {
  private val idr = MediaFact.Known(GopStructure.ClosedWithIdr)
  private fun sample(time: Long, sync: Boolean = false, bytes: Int = 100,
    kind: MediaFact<GopStructure> = idr) = CompressedVideoSample(time, bytes, sync, kind)
  private fun facts(samples: List<CompressedVideoSample>, duration: Double = 3.0) =
    VideoSampleFactsReader.read(samples, MediaFact.Known(duration))
  private fun nal(vararg bytes: Int) = byteArrayOf(0, 0, 0, 1) + bytes.map { it.toByte() }.toByteArray()

  @Test fun measuresWholeVideoSeparatelyFromMaximumGopAndIncludesFinalTail() {
    val facts = facts(listOf(sample(0, true, 100), sample(1_000_000, bytes = 300),
      sample(2_000_000, true, 500)))
    assertEquals(MediaFact.Known(1.0), facts.frameRate)
    assertEquals(MediaFact.Known(Cadence.Constant), facts.cadence)
    assertEquals(MediaFact.Known(2400L), facts.averageBitrate)
    assertEquals(MediaFact.Known(4000L), facts.maximumGopBitrate)
    assertEquals(MediaFact.Known(500L), facts.maximumGopByteSize)
    assertEquals(MediaFact.Known(2.0), facts.maximumKeyframeIntervalSeconds)
    assertEquals(idr, facts.gopStructure)
    assertEquals(MediaFact.Unknown, facts.durationSeconds)
    assertEquals(MediaFact.Unknown, facts.audioVideoStartOffsetSeconds)
  }

  @Test fun reorderedPresentationTimesDoNotInventVariableCadence() {
    val facts = facts(listOf(sample(0, true), sample(2_000_000), sample(1_000_000)))
    assertEquals(MediaFact.Known(Cadence.Constant), facts.cadence)
    assertEquals(MediaFact.Known(TimestampFacts(0.0, 2.0, 3, false)), facts.timestamps)
  }

  @Test fun variableIntervalsRemainVariableAndNegativePrerollIsObserved() {
    val facts = facts(listOf(sample(-100_000, true), sample(100_000), sample(400_000)), 0.8)
    assertEquals(MediaFact.Known(Cadence.Variable), facts.cadence)
    assertEquals(MediaFact.Known(4.0), facts.frameRate)
    assertEquals(-0.1, facts.timestamps.valueOrNull!!.firstPresentationSeconds, 0.0)
  }

  @Test fun noSyncAtStartCannotProveGops() {
    val facts = facts(listOf(sample(0), sample(1_000_000, true), sample(2_000_000)))
    assertEquals(MediaFact.Unknown, facts.gopStructure)
    assertEquals(MediaFact.Unknown, facts.maximumGopBitrate)
    assertEquals(MediaFact.Known(800L), facts.averageBitrate)
  }

  @Test fun singleGopTailCountsForLongKeyframeViolations() {
    val facts = facts((0..20).map { sample(it * 1_000_000L, it == 0) }, 21.0)
    assertEquals(MediaFact.Known(21.0), facts.maximumKeyframeIntervalSeconds)
  }

  @Test fun openGopDoesNotProveByteAllocationAndOverridesUnknownBoundary() {
    val facts = facts(listOf(sample(0, true, kind = MediaFact.Unknown), sample(1_000_000),
      sample(2_000_000, true, kind = MediaFact.Known(GopStructure.Open))))
    assertEquals(MediaFact.Known(GopStructure.Open), facts.gopStructure)
    assertEquals(MediaFact.Unknown, facts.maximumGopBitrate)
    assertEquals(MediaFact.Unknown, facts.maximumGopByteSize)
    assertEquals(MediaFact.Known(2.0), facts.maximumKeyframeIntervalSeconds)
  }

  @Test fun contradictoryOrMissingDurationDoesNotExtrapolateVfrTail() {
    val samples = listOf(sample(0, true), sample(1_000_000), sample(2_000_000))
    for (duration in listOf(MediaFact.Unknown, MediaFact.Known(1.0), MediaFact.Known(100.0))) {
      val facts = VideoSampleFactsReader.read(samples, duration)
      assertEquals(MediaFact.Known(1.0), facts.frameRate)
      assertEquals(MediaFact.Unknown, facts.averageBitrate)
      assertEquals(MediaFact.Unknown, facts.maximumKeyframeIntervalSeconds)
    }
  }

  @Test fun duplicateTimesEmptyOrInvalidBytesCannotInventRates() {
    for (samples in listOf(emptyList(), listOf(sample(0, true)),
      listOf(sample(0, true), sample(0)), listOf(sample(0, true), sample(1_000_000, bytes = 0))))
      assertEquals(MediaFacts(), facts(samples))
  }

  @Test fun avcIdrRequiresEverySliceToBeIdrAndRecognizesRecoveryPoint() {
    assertEquals(idr, SampleRandomAccessReader.read(VideoCodec.H264, nal(0x65, 0x80), 6))
    val mixed = nal(0x65, 0x80) + nal(0x41, 0x80)
    assertEquals(MediaFact.Unknown, SampleRandomAccessReader.read(VideoCodec.H264, mixed, mixed.size))
    val recovery = nal(6, 6, 1, 0x80, 0x80) + nal(0x41, 0x80)
    assertEquals(MediaFact.Known(GopStructure.Open),
      SampleRandomAccessReader.read(VideoCodec.H264, recovery, recovery.size))
  }

  @Test fun hevcSyncRequiresIdrAndCraOrBlaAreOpen() {
    for (type in 16..21) {
      val data = nal(type shl 1, 1, 0x80)
      assertEquals(if (type in 19..20) idr else MediaFact.Known(GopStructure.Open),
        SampleRandomAccessReader.read(VideoCodec.Hevc, data, data.size))
    }
    val ordinary = nal(2, 1, 0x80)
    assertEquals(MediaFact.Unknown, SampleRandomAccessReader.read(VideoCodec.Hevc, ordinary, ordinary.size))
  }

  @Test fun malformedFramingHeadersAndMultilayerStayUnknown() {
    for (data in listOf(byteArrayOf(), byteArrayOf(0, 0, 0, 2, 0x26, 1),
      nal(0xa6, 1, 0x80), nal(0x26, 0, 0x80), nal(0x27, 1, 0x80), nal(0x26, 9, 0x80), nal(0x26)))
      assertEquals(MediaFact.Unknown, SampleRandomAccessReader.read(VideoCodec.Hevc, data, data.size))
  }

  @Test fun threeBytePrefixesAndSeveralSlicesAreSupported() {
    val data = byteArrayOf(0, 0, 1, 0x65, 0x80.toByte(), 0, 0, 1, 0x65, 0x80.toByte())
    assertEquals(idr, SampleRandomAccessReader.read(VideoCodec.H264, data, data.size))
  }

  private fun metadata() = MediaMetadataInspection(MediaFact.Known(ContainerKind.Other), listOf(
    TrackMetadata(0, MediaFact.Unknown, MediaFact.Unknown, TrackKind.Video, MediaFact.Known("video/avc"),
      MediaFact.Known(3.0), MediaFact.Unknown, MediaFact.Unknown)), MediaFact.Unknown,
    MediaFacts(videoCodec = MediaFact.Known(VideoCodec.H264), videoTrackCount = MediaFact.Known(1)), 0)

  private fun scan(limits: SampleScanLimits = SampleScanLimits(), cancel: () -> Boolean = { false },
    reader: ((ByteBuffer) -> Int)? = null, reportedSize: Long = 6, flagsOverride: Int? = null): MediaSampleInspection {
    mockkConstructor(MediaExtractor::class)
    var index = 0
    var released = false
    val bytes = nal(0x65, 0x80)
    every { anyConstructed<MediaExtractor>().setDataSource(any<String>()) } just Runs
    every { anyConstructed<MediaExtractor>().selectTrack(0) } just Runs
    every { anyConstructed<MediaExtractor>().sampleTrackIndex } answers { if (index < 3) 0 else -1 }
    every { anyConstructed<MediaExtractor>().sampleFlags } answers { flagsOverride ?: if (index == 0) 1 else 0 }
    if (Build.VERSION.SDK_INT >= 28) every { anyConstructed<MediaExtractor>().sampleSize } returns reportedSize
    every { anyConstructed<MediaExtractor>().sampleTime } answers { index * 1_000_000L }
    every { anyConstructed<MediaExtractor>().readSampleData(any(), 0) } answers {
      reader?.invoke(firstArg()) ?: if (firstArg<ByteBuffer>().capacity() < bytes.size) -1
        else bytes.size.also { firstArg<ByteBuffer>().put(bytes) }
    }
    every { anyConstructed<MediaExtractor>().advance() } answers { ++index < 3 }
    every { anyConstructed<MediaExtractor>().release() } answers { released = true }
    return try {
      MediaSampleInspector(limits).inspect(File("unused"), metadata(), cancel).also {
        assertTrue(released)
      }
    } finally { unmockkConstructor(MediaExtractor::class) }
  }

  @Test fun api23UsesBoundedReadsAndReleasesExtractor() {
    val result = scan(SampleScanLimits(maximumSampleBytes = 16))
    assertEquals(SampleScanStatus.Complete, result.status)
    assertEquals(18L, result.bytesRead)
    assertEquals(3, result.sampleCount)
    assertEquals(idr, result.facts.gopStructure)
  }

  @Test fun limitsAndCancellationDiscardPartialProof() {
    for (limits in listOf(SampleScanLimits(maximumSamples = 1), SampleScanLimits(maximumReadBytes = 8),
      SampleScanLimits(maximumElapsedNanos = 1))) {
      val result = scan(limits)
      assertEquals(SampleScanStatus.LimitExceeded, result.status)
      assertEquals(metadata().facts, result.facts)
    }
    var calls = 0
    val result = scan(cancel = { ++calls >= 3 })
    assertEquals(SampleScanStatus.Cancelled, result.status)
    assertEquals(metadata().facts, result.facts)
  }

  @Test fun oversizedAndFailedApi23ReadsNeverGrowBufferOrPublishPartialFacts() {
    var capacity = 0
    val result = scan(SampleScanLimits(maximumSampleBytes = 16), reader = {
      capacity = it.capacity(); 17
    })
    assertEquals(16, capacity)
    assertEquals(SampleScanStatus.LimitExceeded, result.status)
    assertEquals(metadata().facts, result.facts)
    assertEquals(SampleScanStatus.Unreadable, scan(reader = { throw IllegalArgumentException() }).status)
  }

  @Test @Config(sdk = [28]) fun api28ChecksSizeBeforeReadAndRejectsInconsistentCount() {
    var read = false
    assertEquals(SampleScanStatus.LimitExceeded,
      scan(SampleScanLimits(maximumSampleBytes = 16), reader = { read = true; 6 }, reportedSize = 17).status)
    assertFalse(read)
    assertEquals(SampleScanStatus.LimitExceeded, scan(reportedSize = 7).status)
    assertEquals(SampleScanStatus.Complete, scan().status)
  }

  @Test fun encryptedAndPartialFramesCannotSupplySampleFacts() {
    for (flags in listOf(2, 4)) {
      val result = scan(flagsOverride = flags)
      assertEquals(SampleScanStatus.Unsupported, result.status)
      assertEquals(metadata().facts, result.facts)
    }
  }

  @Test fun cancellationWinsBeforeUnsupportedInputFallback() {
    val result = MediaSampleInspector().inspect(File("unused"), metadata().copy(tracks = emptyList())) { true }
    assertEquals(SampleScanStatus.Cancelled, result.status)
    assertEquals(metadata().facts, result.facts)
  }

  @Test fun incompatibleMedia3HelperLeavesRandomAccessUnknown() {
    mockkStatic(NalUnitUtil::class)
    try {
      every { NalUnitUtil.findNalUnit(any(), any(), any(), any()) } throws NoSuchMethodError()
      val data = nal(0x65, 0x80)
      assertEquals(MediaFact.Unknown, SampleRandomAccessReader.read(VideoCodec.H264, data, data.size))
    } finally { unmockkStatic(NalUnitUtil::class) }
  }
}
