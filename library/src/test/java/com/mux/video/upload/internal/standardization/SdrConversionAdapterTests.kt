package com.mux.video.upload.internal.standardization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SdrConversionAdapterTests {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val facts = compliantFacts().copy(cadence = known(Cadence.Constant), videoProfile = known(VideoProfile.H264Baseline))
  private val conversion = (StandardInputPlanner().plan(facts.copy(averageBitrate = known(9_000_000L)),
    options(), fullCapabilities()).action as StandardInputAction.Convert).conversion
  private val timeline = StandardInputTimelineFacts(known(3.0), known(AudioVideoStartOffset.Seconds(0.0)),
    known(listOf(0.0, 1.0, 2.0)), known(3.0), known(3.0), known(90_000L))
  private fun audio(channels: Int = 2, format: MediaFact<AudioFormat> = known(AudioFormat.Aac(AudioChannelLayout.Stereo)),
    id: Int = 2, index: Int = 1) = TrackMetadata(index, known(index), known(id), TrackKind.Audio,
    known(if (format.valueOrNull is AudioFormat.Aac) "audio/mp4a-latm" else "audio/mpeg"), known(3.0),
    MediaFact.Unknown, MediaFact.Unknown, audio = AudioTrackMetadata(format, known(channels), known(48_000), known(0), known(0)))
  private fun metadata(tracks: List<TrackMetadata> = listOf(audio())) =
    MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), tracks, MediaFact.Unknown, facts, 0)
  private fun samples(input: MediaFacts = facts) = MediaSampleInspection(input, SampleScanStatus.Complete, timeline = timeline)
  private fun targets(plan: StandardInputConversion = conversion, input: MediaFacts = facts,
    tracks: List<TrackMetadata> = listOf(audio())) = SdrEncodingTargets.from(plan, metadata(tracks), samples(input))

  @Test fun pinsCodecFamilyAndTargetsBelowPublishedCeilings() {
    assertEquals("video/avc", targets()!!.videoMime)
    assertEquals(6_000_000, targets()!!.bitrate)
    assertEquals(18, targets()!!.keyframeIntervalSeconds)
    assertEquals(VideoProfile.H264Baseline, targets()!!.profile)
    val hevc = facts.copy(videoCodec = known(VideoCodec.Hevc))
    assertEquals("video/hevc", targets(conversion.copy(sourceCodec = VideoCodec.Hevc, outputCodec = VideoCodec.Hevc), hevc)!!.videoMime)
    assertNull(targets(conversion.copy(sourceCodec = VideoCodec.Hevc), hevc))
    val other = facts.copy(videoCodec = known(VideoCodec.Other))
    assertEquals("video/avc", targets(conversion.copy(sourceCodec = VideoCodec.Other), other)!!.videoMime)
  }
  @Test fun rejectsHdrUnknownTimelineUpscalingAndUnprovenTenBitPath() {
    assertNull(targets(conversion.copy(toneMapsToSdr = true)))
    assertNull(targets(input = facts.copy(dynamicRange = MediaFact.Unknown)))
    assertNull(targets(conversion.copy(outputDimensions = Dimensions(1922, 1080))))
    assertNull(targets(conversion.copy(outputPixelFormat = PixelFormat(10, ChromaSubsampling.Yuv420))))
    assertNull(SdrEncodingTargets.from(conversion, metadata(), samples().copy(timeline = StandardInputTimelineFacts())))
  }
  @Test fun preservesMonoStereoForNonAacWithoutInventingSurroundDownmix() {
    for (channels in listOf(1, 2)) {
      val nonAac = facts.copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.OtherCodec)))))
      val target = targets(input = nonAac, tracks = listOf(audio(channels, known(AudioFormat.OtherCodec))))!!
      assertEquals(channels, target.audioChannels)
      assertFalse(target.copyAac)
    }
    assertNull(targets(tracks = listOf(audio(6, known(AudioFormat.OtherCodec)))))
    assertNull(targets(tracks = listOf(audio(2, MediaFact.Unknown))))
    assertTrue(targets(tracks = listOf(audio(6, known(AudioFormat.Aac(AudioChannelLayout.FivePointOne)))))!!.copyAac)
  }
  @Test fun choosesContainerFirstTrackEvenWhenExtractorOrderDiffers() {
    val multi = facts.copy(audioTracks = known(listOf(AudioTrack(), AudioTrack())))
    assertEquals("42", targets(input = multi, tracks = listOf(audio(id = 99, index = 3), audio(id = 42, index = 1)))!!.firstAudioTrackId)
    assertNull(targets(tracks = listOf(audio().copy(sourceTrackId = MediaFact.Unknown))))
  }
  @Test fun permitsOnlyProvenIntegralCadenceReductionAndKeepsVfrTimestamps() {
    assertNotNull(targets(input = facts.copy(cadence = known(Cadence.Variable))))
    val cfr = conversion.copy(outputCadence = OutputCadence.ConstantFrameRate)
    assertNotNull(targets(cfr, facts.copy(frameRate = known(240.0))))
    assertNotNull(targets(cfr, facts.copy(frameRate = known(240.00004008351402))))
    assertNull(targets(cfr, facts.copy(frameRate = known(240.0), cadence = known(Cadence.Variable))))
    assertNull(targets(cfr, facts.copy(frameRate = known(2.0))))
    assertNull(targets(cfr, facts.copy(frameRate = known(239.76))))
  }

  private class FakeEngine : SdrExportEngine {
    var output: File? = null
    var completions: ((String?, String?) -> Unit)? = null
    var failures: ((SdrConversionFailure) -> Unit)? = null
    var starts = 0
    var cancellations = 0
    override fun start(input: File, output: File, completed: (String?, String?) -> Unit, failed: (SdrConversionFailure) -> Unit) {
      starts++; this.output = output; completions = completed; failures = failed
      output.writeText("owned partial bytes")
    }
    override fun cancel() { cancellations++ }
    fun complete(video: String? = "video/avc", audio: String? = "audio/mp4a-latm") { completions!!(video, audio) }
  }
  private inner class Run(
    validation: StandardInputOutputValidation = StandardInputOutputValidation.Accepted(facts,
      StandardInputPolicyEvaluator().evaluate(facts, selection())),
    capability: SdrEncoderCapability? = SdrEncoderCapability("fake", 1),
    duringValidation: (() -> Unit)? = null,
  ) {
    val sourceFile = File.createTempFile("customer-", ".mp4", context.cacheDir).apply { writeText("original") }
    val unrelated = File(context.cacheDir, "mux-upload/customer-copy.mp4").apply { parentFile!!.mkdirs(); writeText("customer") }
    val engine = FakeEngine()
    val results = mutableListOf<SdrConversionResult>()
    val adapter = SdrConversionAdapter(context, preflight = { _, _, _, _ -> capability },
      validate = { _, _, _, _, _ -> duringValidation?.invoke(); validation }, createEngine = { _, _, _, _, _ -> engine })
    val attempt = adapter.start(sourceFile, metadata(), samples(), conversion, results::add)
    fun idle() { shadowOf(attempt.looper).idle() }
    fun assertInputSafe() { assertEquals("original", sourceFile.readText()); assertEquals("customer", unrelated.readText()) }
  }
  @Test fun successRetainsVerifiedFileUntilOwnerDeletesItAndIgnoresLateEvents() {
    val run = Run(); run.idle(); run.engine.complete(); run.idle()
    val result = run.results.single() as SdrConversionResult.Completed
    assertTrue(result.output.file.exists()); run.assertInputSafe()
    run.engine.failures!!(SdrConversionFailure.Export); run.attempt.cancel()
    assertEquals(1, run.results.size)
    assertTrue(result.output.delete()); assertTrue(result.output.delete())
    assertEquals(1, run.engine.cancellations)
  }
  @Test fun exportFailureDeletesPartialAndPublishesExactlyOnce() {
    val run = Run(); run.idle(); run.engine.failures!!(SdrConversionFailure.Export); run.idle()
    assertEquals(listOf(SdrConversionResult.Failed(SdrConversionFailure.Export)), run.results)
    assertFalse(run.engine.output!!.exists()); run.assertInputSafe()
    run.engine.complete(); assertEquals(1, run.results.size)
  }
  @Test fun cancelTerminatesWithoutMedia3CallbackAndSuppressesQueuedCompletion() {
    val run = Run(); run.idle(); run.engine.complete(); run.attempt.cancel(); run.idle()
    assertEquals(listOf(SdrConversionResult.Cancelled), run.results)
    assertFalse(run.engine.output!!.exists()); run.assertInputSafe()
  }
  @Test fun cancelBeforeStartAllocatesNothing() {
    val run = Run(); run.attempt.cancel(); run.idle()
    assertEquals(listOf(SdrConversionResult.Cancelled), run.results)
    assertEquals(0, run.engine.starts); run.assertInputSafe()
  }
  @Test fun forbiddenFallbackWinsOverLaterCompletion() {
    val run = Run(); run.idle(); run.engine.failures!!(SdrConversionFailure.ForbiddenFallback); run.engine.complete(); run.idle()
    assertEquals(listOf(SdrConversionResult.Failed(SdrConversionFailure.ForbiddenFallback)), run.results)
    assertFalse(run.engine.output!!.exists())
  }
  @Test fun codecChangeAndInvalidOutputCannotProduceCompletedResult() {
    val changed = Run(); changed.idle(); changed.engine.complete("video/hevc"); changed.idle()
    assertEquals(SdrConversionResult.Failed(SdrConversionFailure.CodecChanged), changed.results.single())
    assertFalse(changed.engine.output!!.exists())
    val invalid = Run(StandardInputOutputValidation.Rejected(OutputRejection.EmptyOrUnreadable))
    invalid.idle(); invalid.engine.complete(); invalid.idle()
    assertEquals(SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid), invalid.results.single())
    assertFalse(invalid.engine.output!!.exists())
  }
  @Test fun unavailableCapabilityDoesNotStartOrAllocate() {
    val run = Run(capability = null); run.idle()
    assertEquals(SdrConversionResult.Failed(SdrConversionFailure.CapabilityUnavailable), run.results.single())
    assertEquals(0, run.engine.starts); run.assertInputSafe()
  }
  @Test fun sourceChangeDuringValidationRejectsGeneratedBytes() {
    lateinit var run: Run
    run = Run(duringValidation = { run.sourceFile.appendText("changed") })
    run.idle(); run.engine.complete(); run.idle()
    assertEquals(SdrConversionResult.Failed(SdrConversionFailure.SourceChanged), run.results.single())
    assertFalse(run.engine.output!!.exists())
  }
  @Test fun cancellationDuringValidationWinsOverAcceptedOutput() {
    lateinit var run: Run
    run = Run(duringValidation = { run.attempt.cancel() })
    run.idle(); run.engine.complete(); run.idle()
    assertEquals(SdrConversionResult.Cancelled, run.results.single())
    assertFalse(run.engine.output!!.exists()); run.assertInputSafe()
  }
  @Test fun wrongProfileOrAudioLayoutRejectsEvenAnAcceptedSharedVerdict() {
    for (wrong in listOf(facts.copy(videoProfile = known(VideoProfile.H264High)),
      facts.copy(audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Mono)))))))) {
      val run = Run(StandardInputOutputValidation.Accepted(wrong, StandardInputPolicyEvaluator().evaluate(wrong, selection())))
      run.idle(); run.engine.complete(); run.idle()
      assertEquals(SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid), run.results.single())
      assertFalse(run.engine.output!!.exists())
    }
  }
  @Test fun failedDeletionRetainsExactCleanupAuthorityAndCannotSweepCustomerParent() {
    val run = Run(); run.idle()
    val path = run.engine.output!!
    assertTrue(path.delete()); assertTrue(path.mkdir())
    val obstacle = File(path, "obstacle").apply { writeText("locked") }
    run.attempt.cancel(); run.idle()
    assertEquals(SdrConversionResult.Cancelled, run.results.single())
    assertEquals(path, run.attempt.pendingCleanup!!.file)
    run.assertInputSafe()
    assertTrue(obstacle.delete()); assertTrue(run.attempt.pendingCleanup!!.delete())
  }
  @Test fun diskEstimateIsBoundedAndOwnershipCannotDeleteOtherFiles() {
    assertNull(targets()!!.estimatedOutputBytes(Double.POSITIVE_INFINITY))
    assertNull(SdrGeneratedFile.allocate(context.cacheDir, Long.MAX_VALUE))
    val customer = File(context.cacheDir, "mux-upload/source.mp4").apply { parentFile!!.mkdirs(); writeText("customer") }
    val generated = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
    assertEquals("standard-input", generated.file.parentFile!!.name)
    assertTrue(generated.delete()); assertTrue(customer.exists())
  }
}
