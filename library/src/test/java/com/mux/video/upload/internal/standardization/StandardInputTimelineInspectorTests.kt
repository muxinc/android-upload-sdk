package com.mux.video.upload.internal.standardization

import android.media.MediaExtractor
import android.os.Build
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
    try { read(tracks, scale, maximum); fail("Unproven timeline was published") } catch (_: IllegalArgumentException) { } catch (stop: SampleScanStop) { assertEquals(SampleScanStatus.LimitExceeded, stop.status) }
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
      try { IsoTimelineReader.read(file, 250000); fail("Fragment accepted") } catch (_: IllegalArgumentException) { } catch (stop: SampleScanStop) { assertEquals(SampleScanStatus.LimitExceeded, stop.status) }
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
    legacyBufferFailure: Boolean = false, apiLevel: Int = 23, expectedSize: Long = 6, readSize: Int = 6,
    videoEdit: Long? = null, audioEdit: Long? = null, platformMode: String = "shifted",
    mixedConvention: Boolean = false, byteLimit: Long = 1024L * 1024 * 1024,
    sourcePath: Boolean = false, videoDeltas: List<Int> = listOf(1000,1000,1000), editDuration: Long = 2000,
    input: MediaMetadataInspection = metadata(audio).copy(facts = metadata(audio).facts.copy(videoCodec = known(VideoCodec.H264)))
  ): MediaSampleInspection {
    val file = file(track(deltas = videoDeltas, edits = videoEdit?.let { edit(editDuration, it) } ?: byteArrayOf()) +
      if (audio) track(kind = "soun", offsets = List(3) { audioOffset },
        edits = audioEdit?.let { edit(2000, it) } ?: byteArrayOf()) else byteArrayOf())
    mockkConstructor(MediaExtractor::class)
    var position = 0; var cancelled = false; var releases = 0; var sources = 0; var reads = 0
    val selected = mutableSetOf<Int>()
    val rawTimes = videoDeltas.runningFold(0L) { time, delta -> time + delta * 1000L }.dropLast(1)
    val count = rawTimes.size - if (missingTail) 1 else 0
    // Interleaved tracks preserve decode order within each track.
    val sequence = (0 until count).flatMap { index -> (0..if (audio) 1 else 0).map { it to index } }
    every { anyConstructed<MediaExtractor>().setDataSource(any<String>()) } answers { sources++ }
    every { anyConstructed<MediaExtractor>().selectTrack(any()) } answers { selected.add(firstArg()) }
    every { anyConstructed<MediaExtractor>().sampleTrackIndex } answers { sequence.getOrNull(position)?.first ?: -1 }
    every { anyConstructed<MediaExtractor>().sampleTime } answers {
      val (track, index) = sequence[position]
      val start = if (track == 0) videoEdit ?: 0L else audioEdit ?: 0L
      val raw = rawTimes[index] + if (track == 1) audioOffset * 1000L else 0L
      if (platformMode == "raw" || (mixedConvention && index == 1)) raw
      else if (platformMode == "clamped") maxOf(0L, raw - start * 1000L) else raw - start * 1000L
    }
    if (Build.VERSION.SDK_INT >= 28) every { anyConstructed<MediaExtractor>().sampleSize } returns expectedSize
    every { anyConstructed<MediaExtractor>().sampleFlags } answers { if (partial) 4 else if (sequence[position].second == 0) 1 else 0 }
    every { anyConstructed<MediaExtractor>().readSampleData(any(), 0) } answers {
      reads++
      if (legacyBufferFailure) throw IllegalArgumentException("Capped buffer")
      if (cancelAfterRead) cancelled = true
      if (readSize == 6) {
        if (firstArg<ByteBuffer>().capacity() < 6) throw IllegalArgumentException("Capped buffer")
        firstArg<ByteBuffer>().put(byteArrayOf(0,0,0,1,0x65,0x80.toByte()))
      }
      readSize
    }
    every { anyConstructed<MediaExtractor>().advance() } answers { ++position < sequence.size }
    every { anyConstructed<MediaExtractor>().release() } answers { releases++ }
    return try {
      val limits = SampleScanLimits(maximumSamples = sampleLimit, maximumReadBytes = byteLimit)
      val inspector = if (sourcePath) MediaSampleInspector(limits, apiLevel)::inspect
        else StandardInputTimelineInspector(limits, apiLevel)::inspect
      inspector(file, input) { cancelled }.also {
        if (it.status == SampleScanStatus.Complete) {
          assertEquals(1, sources); assertEquals(1, releases)
          assertEquals(if (audio) setOf(0,1) else setOf(0), selected)
          assertEquals(sequence.size, reads)
        } else assertEquals(input.facts, it.facts)
      }
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

  @Test @Config(sdk = [28]) fun rawShiftedAndClampedEditTimesUseTheSameEffectiveTimeline() {
    for (api in listOf(23,28,29,36)) for (mode in listOf("raw","shifted","clamped")) {
      val result = inspect(audio = true, videoEdit = 1000, audioEdit = 1000, apiLevel = api, platformMode = mode)
      assertEquals("$api $mode", SampleScanStatus.Complete, result.status)
      assertEquals(known(2.0), result.timeline.durationSeconds)
      assertEquals(known(listOf(0.0,1.0)), result.timeline.videoPresentationSeconds)
      assertEquals(known(48L), result.facts.averageBitrate)
      assertEquals(known(2.0), result.facts.maximumKeyframeIntervalSeconds)
    }
    assertEquals(SampleScanStatus.Unsupported, inspect(videoEdit = 1000, mixedConvention = true).status)
  }

  @Test fun extractorConventionMustAgreeForEverySampleIncludingBFrameReordering() {
    val time = read(track(offsets = listOf(500,1500,-500), edits = edit(2000,1000)))
    val shifted = PlatformTimestampMatcher(time)
    assertTrue(time.presentationSeconds.indices.all { shifted.matches(it, time.presentationSeconds[it]) })
    val capped = PlatformTimestampMatcher(time)
    val raw = time.presentationSeconds.map { it + time.mediaStartSeconds }
    assertTrue(raw.indices.all { capped.matches(it, maxOf(0.0, raw[it] - 0.5)) })
    val wrong = PlatformTimestampMatcher(time)
    assertTrue(wrong.matches(0, raw[0]))
    assertFalse(wrong.matches(1, raw[1] + 0.001))
  }

  @Test @Config(sdk = [28]) fun modernSizeChecksHaveTheSameBoundsAsLegacyReads() {
    assertEquals(SampleScanStatus.Complete, inspect(apiLevel = 28).status)
    assertEquals(SampleScanStatus.Unreadable, inspect(apiLevel = 28, expectedSize = 7).status)
    assertEquals(SampleScanStatus.LimitExceeded, inspect(apiLevel = 28, expectedSize = 0).status)
    assertEquals(SampleScanStatus.LimitExceeded, inspect(apiLevel = 28, expectedSize = 4194305).status)
    assertEquals(SampleScanStatus.LimitExceeded, inspect(apiLevel = 23, readSize = 4194305).status)
    assertEquals(SampleScanStatus.Unreadable, inspect(apiLevel = 23, readSize = -1).status)
  }

  @Test fun combinedTracksShareOneBudgetAndExactEofLimitsAreAllowed() {
    assertEquals(SampleScanStatus.Complete, inspect(audio = true, sampleLimit = 6, byteLimit = 36).status)
    assertEquals(SampleScanStatus.LimitExceeded, inspect(audio = true, sampleLimit = 5).status)
    assertEquals(SampleScanStatus.LimitExceeded, inspect(audio = true, byteLimit = 35).status)
  }

  @Test fun editedVfrEndpointAndTimescaleAreCarriedToSampleFacts() {
    val time = read(track(deltas = listOf(1000,1000,1000,1000,2000), edits = edit(4500,1000)))
    assertEquals(1000L, time.timescale)
    assertEquals(4.5, time.endSeconds, 0.0)
    assertEquals(5.0, time.fullEndSeconds, 0.0)
    val samples = List(5) { CompressedVideoSample((it - 1) * 1000000L, 100, it == 0 || it == 2,
      known(GopStructure.ClosedWithIdr)) }
    assertEquals(known(3.5), VideoSampleFactsReader.read(samples, MediaFact.Unknown, timeline = time).maximumKeyframeIntervalSeconds)
  }

  @Test fun editedIsoSourceCadencePlansAndValidatesOnlyThePresentedWindow() {
    val input = metadata(false).copy(
      isoTracks = known(listOf(IsoTrackMetadata("vide", known(1), listOf("avc1"), false,
        known(0), true, known(EditList.Simple)))),
      facts = compliantFacts(dimensions = Dimensions(2560,1440)).copy(audioTracks = known(emptyList())))
    val source = inspect(sourcePath = true, videoDeltas = listOf(25,200,200,200), videoEdit = 25,
      editDuration = 600, platformMode = "raw", input = input)
    assertEquals(SampleScanStatus.Complete, source.status)
    assertEquals(known(5.0), source.facts.frameRate)
    assertEquals(known(Cadence.Constant), source.facts.cadence)
    val conversion = (StandardInputPlanner().plan(source.facts, options(), fullCapabilities())
      .action as StandardInputAction.Convert).conversion
    assertEquals(5.0, conversion.outputFrameRate, 0.0)
    assertEquals(OutputCadence.PreserveSourceTimestamps, conversion.outputCadence)
    val time = StandardInputTimelineFacts(known(0.6), known(AudioVideoStartOffset.NotApplicable),
      known(listOf(0.0,0.2,0.4)), known(0.6), videoTimescale = known(1000L))
    assertEquals(time.copy(videoPresentationSeconds = source.timeline.videoPresentationSeconds), source.timeline)
    time.videoPresentationSeconds.valueOrNull!!.zip(source.timeline.videoPresentationSeconds.valueOrNull!!).forEach {
      (expected, actual) -> assertEquals(expected, actual, 1e-12)
    }
    assertEquals(4, source.sampleCount)
    assertEquals(24L, source.bytesRead)
    assertEquals(6, source.largestSampleBytes)
    assertTrue(source.elapsedNanos > 0)
    val output = source.facts.copy(displayDimensions = known(conversion.outputDimensions),
      encodedDimensions = known(conversion.outputDimensions), rotationDegrees = known(0))
    val result = StandardInputOutputValidator().validateFacts(output, source.facts, source.timeline, time, conversion)
    assertTrue("Correctly trimmed output rejected: $result", result is StandardInputOutputValidation.Accepted)
  }

  @Test fun isoSourceSharesTheCombinedBudgetAndNeverFallsBackToRawPartialFacts() {
    val complete = inspect(audio = true, sourcePath = true, sampleLimit = 6, byteLimit = 36)
    assertEquals(SampleScanStatus.Complete, complete.status)
    assertEquals(6, complete.sampleCount)
    assertEquals(36L, complete.bytesRead)
    for ((result, status) in listOf(
      inspect(audio = true, sourcePath = true, sampleLimit = 5) to SampleScanStatus.LimitExceeded,
      inspect(sourcePath = true, cancelAfterRead = true) to SampleScanStatus.Cancelled,
      inspect(sourcePath = true, missingTail = true) to SampleScanStatus.Unreadable,
      inspect(sourcePath = true, videoEdit = 1000, mixedConvention = true) to SampleScanStatus.Unsupported)) {
      assertEquals(status, result.status)
      assertEquals(StandardInputTimelineFacts(), result.timeline)
      assertTrue(result.elapsedNanos > 0)
    }
  }
}
