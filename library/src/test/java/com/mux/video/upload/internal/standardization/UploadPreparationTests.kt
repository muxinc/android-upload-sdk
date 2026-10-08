package com.mux.video.upload.internal.standardization

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.internal.InputStandardization
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.UploadAttempt
import com.mux.video.upload.internal.UploadPreparationState
import com.mux.video.upload.api.MuxUpload
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [28])
class UploadPreparationTests : AbsRobolectricTest() {
  private val context get() = RuntimeEnvironment.getApplication()
  private val timeline = StandardInputTimelineFacts(durationSeconds = known(1.0),
    audioVideoStartOffset = known(AudioVideoStartOffset.NotApplicable),
    videoPresentationSeconds = known((0 until 30).map { it / 30.0 }), videoDurationSeconds = known(1.0))
  private fun source(codec: VideoCodec = VideoCodec.H264) = compliantFacts(codec).copy(
    audioTracks = known(emptyList()), cadence = known(Cadence.Constant))
  private fun metadata(facts: MediaFacts) = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia),
    emptyList(), MediaFact.Unknown, facts, 0)
  private fun upload() = UploadInfo(remoteUri = Uri.parse("https://example.invalid/upload"),
    inputFile = File("unused"), chunkSize = 4, retriesPerChunk = 3, optOut = true, uploadJob = null, statusFlow = null)

  @Test fun disabledPreparationDoesNoInspectionOrExport() = runBlocking {
    val preparation = UploadPreparation(inspectMetadata = { error("Must not inspect") },
      convert = { _, _, _, _, _ -> error("Must not export") })
    assertEquals(PreparedUpload.Original(), preparation.prepare(upload().copy(
      inputStandardization = InputStandardization(false)), context))
  }

  @Test fun compliantH264AndHevcSelectOriginalWithoutExport() = runBlocking {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val facts = source(codec)
      val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
        inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete, timeline = timeline) },
        convert = { _, _, _, _, _ -> error("Compliant input must pass through") })
      assertEquals(PreparedUpload.Original(), preparation.prepare(upload(), context))
    }
  }

  @Test fun unverifiedGeneratedResumeGatesConversionBeforeExportForBothFamilies() = runBlocking {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val facts = source(codec).copy(averageBitrate = known(9_000_000L))
      val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
        inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete, timeline = timeline) },
        convert = { _, _, _, _, _ -> error("Unverified generated resume must select original") })
      assertEquals(PreparedUpload.Original(PreparationDiagnostic.GeneratedResumeUnavailable),
        preparation.prepare(upload(), context))
    }
  }

  @Test fun adapterReceivesPlannedCodecAndOnlyValidatedCompletionCanSelectGenerated() = runBlocking {
    for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
      val facts = source(codec).copy(averageBitrate = known(9_000_000L))
      var result: SdrConversionResult = SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid)
      val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
        inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete, timeline = timeline) },
        convert = { _, _, _, _, plan ->
          assertEquals(codec, plan.outputCodec)
          assertFalse(plan.toneMapsToSdr)
          result
        })
      assertEquals(PreparedUpload.Original(PreparationDiagnostic.ConversionFailed(SdrConversionFailure.OutputInvalid)),
        preparation.prepare(upload(), context, generatedResumeVerified = true))
      val owned = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
      try {
        result = SdrConversionResult.Completed(owned, source(codec))
        assertEquals(PreparedUpload.Generated(owned), preparation.prepare(upload(), context, generatedResumeVerified = true))
      } finally { owned.delete() }
    }
  }

  @Test fun unsupportedHdrAndUnknownColorStayOutsideSdrExport() = runBlocking {
    for (range in listOf(MediaFact.Unknown, known(DynamicRange.Hlg), known(DynamicRange.Pq), known(DynamicRange.DolbyVision))) {
      val facts = source(VideoCodec.Hevc).copy(dynamicRange = range, averageBitrate = known(9_000_000L))
      val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
        inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete, timeline = timeline) },
        convert = { _, _, _, _, _ -> error("Unproven HDR must stay outside SDR export") })
      assertTrue(preparation.prepare(upload(), context, generatedResumeVerified = true) is PreparedUpload.Original)
    }
  }

  @Test fun inspectionFailuresAndMissingTimelineSelectOriginalWithoutRawDiagnosticText() = runBlocking {
    val failed = UploadPreparation(inspectMetadata = { throw NoSuchMethodError("https://secret.invalid/path") })
    assertEquals(PreparedUpload.Original(PreparationDiagnostic.InspectionFailed), failed.prepare(upload(), context))
    val facts = source().copy(averageBitrate = known(9_000_000L))
    val incomplete = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
      inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.LimitExceeded) },
      convert = { _, _, _, _, _ -> error("Incomplete inspection must not export") })
    assertEquals(PreparedUpload.Original(PreparationDiagnostic.UnsupportedPlan), incomplete.prepare(upload(), context, true))
  }

  @Test fun adapterCompletionRacingPauseTransfersVerifiedOwnershipBeforeCoroutineDispatch() = bridgeRace(cancel = false)
  @Test fun adapterCompletionRacingCancelDeletesOnlyItsOwnedFile() = bridgeRace(cancel = true)

  private fun bridgeRace(cancel: Boolean) = runBlocking {
    val facts = source().copy(averageBitrate = known(9_000_000L))
    val callback = slot<(SdrConversionResult) -> Unit>()
    val started = CompletableDeferred<Unit>()
    val export = mockk<SdrConversionAdapter.Attempt> { every { cancel() } just Runs }
    mockkConstructor(SdrConversionAdapter::class)
    every { anyConstructed<SdrConversionAdapter>().start(any(), any(), any(), any(), capture(callback)) } answers {
      started.complete(Unit); export
    }
    val owned = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
    owned.file.writeText("validated output"); assertTrue(owned.recordValidation())
    val state = UploadPreparationState()
    val attempt = UploadAttempt(state, MuxUpload.Progress())
    try {
      val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata(facts)) },
        inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete, timeline = timeline) })
      val job = launch(Dispatchers.Default) { preparation.prepare(upload().copy(attempt = attempt), context, true) }
      withTimeout(5000) { started.await() }
      if (cancel) attempt.cancel {} else attempt.pause {}
      job.cancelAndJoin()
      callback.captured(SdrConversionResult.Completed(owned, facts))
      verify(exactly = 1) { export.cancel() }
      assertEquals(!cancel, owned.file.exists())
      if (!cancel) assertSame(owned, state.verified)
    } finally {
      owned.delete()
      unmockkConstructor(SdrConversionAdapter::class)
    }
  }
}
