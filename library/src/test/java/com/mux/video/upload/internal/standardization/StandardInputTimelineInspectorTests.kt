package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import com.mux.exoplayeradapter.AbsRobolectricTest
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@Config(sdk = [23])
class StandardInputTimelineInspectorTests : AbsRobolectricTest() {
  private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
  private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
  private fun box(type: String, payload: ByteArray) = int(payload.size + 8) + type.toByteArray() + payload
  private fun header(scale: Int = 1000, version: Int = 0) =
    byteArrayOf(version.toByte(), 0, 0, 0) + ByteArray(if (version == 0) 8 else 16) + int(scale) + int(999999)
  private fun edit(duration: Long, start: Long, version: Int = 0, rate: Int = 65536) =
    box("edts", box("elst", byteArrayOf(version.toByte(), 0, 0, 0) + int(1) +
      (if (version == 0) int(duration.toInt()) + int(start.toInt()) else long(duration) + long(start)) + int(rate)))
  private fun track(deltas: List<Int> = listOf(1000, 1000, 1000), offsets: List<Int>? = null,
    edits: ByteArray = byteArrayOf(), kind: String = "vide", scale: Int = 1000,
    timingVersion: Int = 0, headerVersion: Int = 0): ByteArray {
    val stts = box("stts", ByteArray(4) + int(deltas.size) + deltas.flatMap { (int(1) + int(it)).toList() }.toByteArray())
    val ctts = offsets?.let { box("ctts", byteArrayOf(timingVersion.toByte(), 0, 0, 0) + int(it.size) +
      it.flatMap { offset -> (int(1) + int(offset)).toList() }.toByteArray()) } ?: byteArrayOf()
    return box("trak", edits + box("mdia", box("hdlr", ByteArray(8) + kind.toByteArray()) +
      box("mdhd", header(scale, headerVersion)) + box("minf", box("stbl", stts + ctts))))
  }
  private fun file(tracks: ByteArray = track(), scale: Int = 1000, extra: ByteArray = byteArrayOf()): File =
    File.createTempFile("timeline", ".mp4").apply { writeBytes(box("moov", box("mvhd", header(scale)) + tracks) + extra) }
  private fun read(tracks: ByteArray = track(), scale: Int = 1000, maximum: Int = 250000): IsoTrackTimeline {
    val file = file(tracks, scale)
    return try { IsoTimelineReader.read(file, maximum).getValue(0) } finally { file.delete() }
  }
  private fun invalid(tracks: ByteArray, scale: Int = 1000, maximum: Int = 250000) {
    try { read(tracks, scale, maximum); fail("Unproven timeline was published") } catch (_: IllegalArgumentException) { } catch (stop: TimelineStop) { assertEquals(SampleScanStatus.LimitExceeded, stop.status) }
  }
  @Test fun provesFinalVfrSampleDurationWithoutUsingReportedHeaderDuration() {
    val time = read(track(deltas = listOf(100, 200, 700)))
    assertEquals(1.0, time.endSeconds, 0.0)
    assertEquals(listOf(0.0, 0.1, 0.3), time.effectivePresentationSeconds)
  }
  @Test fun maxPresentationEndpointHandlesReorderingAndSignedCompositionOffsets() {
    for (version in 0..1) {
      val time = read(track(offsets = listOf(0, 1000, -1000), timingVersion = version))
      assertEquals(3.0, time.endSeconds, 0.0)
      assertEquals(listOf(0.0, 1.0, 2.0), time.effectivePresentationSeconds)
      assertEquals(listOf(0.0, 2.0, 1.0), time.presentationSeconds.toList())
    }
  }
  @Test fun appliesPrimingEditAndKeepsRawPlatformSamplesSeparateFromPresentedSamples() {
    for (version in 0..1) {
      val time = read(track(edits = edit(2000, 1000, version), headerVersion = version))
      assertEquals(listOf(-1.0, 0.0, 1.0), time.presentationSeconds.toList())
      assertEquals(listOf(0.0, 1.0), time.effectivePresentationSeconds)
      assertEquals(0.0, time.startSeconds, 0.0)
      assertEquals(2.0, time.endSeconds, 0.0)
    }
  }
  @Test fun movieAndMediaTimescalesAreIndependentAndTrimsCannotClaimMissingTail() {
    assertEquals(2.0, read(track(edits = edit(180000, 1000)), scale = 90000).endSeconds, 0.0)
    invalid(track(edits = edit(4000, 0)))
    invalid(track(edits = edit(2000, 5000)))
  }
  @Test fun zeroTimescalesZeroDurationsDuplicateTimesAndMismatchedRunsStayUnproven() {
    invalid(track(scale = 0))
    invalid(track(), scale = 0)
    invalid(track(deltas = listOf(0)))
    invalid(track(offsets = listOf(0, -1000, 0), timingVersion = 1))
    invalid(track(offsets = listOf(0)))
    invalid(track(edits = edit(0, 0)))
    invalid(track(edits = edit(3000, -1)))
    invalid(track(edits = edit(3000, 0, rate = 0)))
    invalid(track(edits = edit(3000, 0) + edit(3000, 0)))
  }
  @Test fun oversizedSampleTablesAndFragmentedTimelinesStayUnproven() {
    invalid(track(), maximum = 2)
    val file = file(extra = box("moof", byteArrayOf()))
    try {
      try { IsoTimelineReader.read(file, 250000); fail("Fragment accepted") } catch (_: IllegalArgumentException) { } catch (stop: TimelineStop) { assertEquals(SampleScanStatus.LimitExceeded, stop.status) }
    } finally { file.delete() }
  }

  private fun metadata(audio: Boolean): MediaMetadataInspection {
    fun track(index: Int, kind: TrackKind) = TrackMetadata(index, known(index), known(index + 1), kind,
      MediaFact.Unknown, known(999.0), MediaFact.Unknown, MediaFact.Unknown)
    return MediaMetadataInspection(known(ContainerKind.IsoBaseMedia),
      listOf(track(0, TrackKind.Video)) + if (audio) listOf(track(1, TrackKind.Audio)) else emptyList(),
      MediaFact.Unknown, MediaFacts(videoTrackCount = known(1), audioTracks = known(if (audio) listOf(AudioTrack()) else emptyList())), 0)
  }
  private fun inspect(audio: Boolean = false, audioOffset: Int = 0, sampleLimit: Int = 250000,
    missingTail: Boolean = false, partial: Boolean = false, cancelAfterRead: Boolean = false,
    legacyBufferFailure: Boolean = false): TimelineInspection {
    val file = file(track() + if (audio) track(kind = "soun", offsets = List(3) { audioOffset }) else byteArrayOf())
    mockkConstructor(MediaExtractor::class)
    var selected = 0; var index = 0; var cancelled = false; var releases = 0
    every { anyConstructed<MediaExtractor>().setDataSource(any<String>()) } just Runs
    every { anyConstructed<MediaExtractor>().selectTrack(any()) } answers { selected = firstArg(); index = 0 }
    every { anyConstructed<MediaExtractor>().sampleTrackIndex } answers { if (index < if (missingTail) 2 else 3) selected else -1 }
    every { anyConstructed<MediaExtractor>().sampleTime } answers { index * 1000000L + if (selected == 1) audioOffset * 1000L else 0L }
    every { anyConstructed<MediaExtractor>().sampleFlags } returns if (partial) 4 else 0
    every { anyConstructed<MediaExtractor>().readSampleData(any(), 0) } answers {
      if (legacyBufferFailure) throw IllegalArgumentException("Capped buffer")
      if (cancelAfterRead) cancelled = true
      4
    }
    every { anyConstructed<MediaExtractor>().advance() } answers { ++index < 3 }
    every { anyConstructed<MediaExtractor>().release() } answers { releases++ }
    return try {
      StandardInputTimelineInspector(SampleScanLimits(maximumSamples = sampleLimit), 23).inspect(file, metadata(audio)) { cancelled }
        .also { if (sampleLimit >= 3) assertTrue(releases > 0) }
    } finally { unmockkConstructor(MediaExtractor::class); file.delete() }
  }
  @Test fun noAudioHasExplicitNotApplicableOffsetAndEffectiveEndpoint() {
    val result = inspect()
    assertEquals(SampleScanStatus.Complete, result.status)
    assertEquals(known(3.0), result.timeline.durationSeconds)
    assertEquals(known(AudioVideoStartOffset.NotApplicable), result.timeline.audioVideoStartOffset)
  }
  @Test fun offsetComesFromEffectiveAudioAndVideoStarts() {
    val result = inspect(audio = true, audioOffset = 100)
    assertEquals(SampleScanStatus.Complete, result.status)
    assertEquals(known(3.1), result.timeline.durationSeconds)
    assertEquals(known(AudioVideoStartOffset.Seconds(0.1)), result.timeline.audioVideoStartOffset)
  }
  @Test fun truncatedPartialCancelledOrFailedLegacyReadsNeverPublishTimeline() {
    for ((result, status) in listOf(inspect(missingTail = true) to SampleScanStatus.Unreadable,
      inspect(partial = true) to SampleScanStatus.Unsupported,
      inspect(cancelAfterRead = true) to SampleScanStatus.Cancelled,
      inspect(legacyBufferFailure = true) to SampleScanStatus.LimitExceeded)) {
      assertEquals(status, result.status)
      assertEquals(StandardInputTimelineFacts(), result.timeline)
    }
  }
  @Test fun cancellationWinsBeforeUnsupportedMetadata() {
    val result = StandardInputTimelineInspector(apiLevel = 23).inspect(File("unused"),
      metadata(false).copy(container = known(ContainerKind.Matroska))) { true }
    assertEquals(SampleScanStatus.Cancelled, result.status)
  }
}
