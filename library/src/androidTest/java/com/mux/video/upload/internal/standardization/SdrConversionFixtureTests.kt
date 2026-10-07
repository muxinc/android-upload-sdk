package com.mux.video.upload.internal.standardization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mux.video.upload.internal.InputStandardization
import com.mux.video.upload.internal.MaximumResolution
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Stage external fixtures as documented in the adapter README; never package media in the APK. */
@RunWith(AndroidJUnit4::class)
class SdrConversionFixtureTests {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val fixtures = File(instrumentation.context.filesDir, "sdr-fixtures")

  private fun source(name: String): File {
    assumeTrue("Stage SDR fixtures before running this suite", fixtures.isDirectory)
    val fixture = File(fixtures, name)
    assertTrue("Missing fixture $name", fixture.isFile)
    return File(context.cacheDir, "mux-upload/source-$name").apply {
      parentFile!!.mkdirs(); fixture.copyTo(this, overwrite = true)
    }
  }
  private fun inspect(file: File): Triple<MediaMetadataInspection, MediaSampleInspection, StandardInputConversion> {
    val metadata = (MediaMetadataInspector().inspect(file) as MetadataInspectionResult.Success).inspection
    val samples = StandardInputTimelineInspector().inspect(file, metadata)
    assertEquals(SampleScanStatus.Complete, samples.status)
    // Direct adapter exercise: these test flags do not establish hardware capability proof.
    val capabilities = PlanningCapabilities(true, true, setOf(VideoCodec.H264, VideoCodec.Hevc), PolicyRequirement.entries.toSet(),
      canPrepareCompliantAacAudio = true)
    val action = StandardInputPlanner().plan(samples.facts,
      InputStandardization(true, MaximumResolution.Preset1280x720), capabilities).action
    assertTrue("${file.name}: $action\n${samples.facts}\n$metadata", action is StandardInputAction.Convert)
    return Triple(metadata, samples, (action as StandardInputAction.Convert).conversion)
  }
  private fun ownedFiles(): Set<String> = File(context.cacheDir, "mux-upload/standard-input").listFiles()
    ?.map { it.name }?.toSet() ?: emptySet()

  private fun export(name: String): SdrConversionResult {
    val file = source(name)
    val original = file.readBytes()
    val (metadata, samples, conversion) = inspect(file)
    val before = ownedFiles()
    val done = CountDownLatch(1)
    val calls = AtomicInteger()
    var result: SdrConversionResult? = null
    val attempt = SdrConversionAdapter(context).start(file, metadata, samples, conversion) {
      result = it; calls.incrementAndGet(); done.countDown()
    }
    try {
      assertTrue("Timed out $name", done.await(90, TimeUnit.SECONDS))
      File(instrumentation.context.filesDir, "sdr-evidence").apply { mkdirs() }.let { area ->
        File(area, "$name.txt").writeText("$metadata\n${samples.facts}\n${samples.timeline}\n$conversion\n$result")
      }
      assertEquals(1, calls.get())
      assertArrayEquals(original, file.readBytes())
      if (result is SdrConversionResult.Completed) {
        val completed = result as SdrConversionResult.Completed
        assertEquals(MediaFact.Known(conversion.outputCodec), completed.facts.videoCodec)
        assertEquals(MediaFact.Known(conversion.outputDimensions), completed.facts.encodedDimensions)
        assertEquals(MediaFact.Known(0), completed.facts.rotationDegrees)
        assertTrue(StandardInputOutputValidator().validateGeneratedOutput(completed.output.file, samples.facts,
          samples.timeline, conversion) is StandardInputOutputValidation.Accepted)
        assertTrue(completed.output.delete())
      }
      assertEquals(before, ownedFiles())
      return checkNotNull(result)
    } finally {
      attempt.cancel(); (result as? SdrConversionResult.Completed)?.output?.delete(); file.delete()
    }
  }
  @Test fun h264ResizeSilentPortraitNonAacAndCadenceExportsPassReinspection() {
    for (name in listOf("multi-audio.mp4", "no-audio.mp4", "rotated.mp4", "mp3-stereo.mp4", "high-rate.mp4", "vfr.mp4")) {
      val result = export(name)
      assertTrue("$name: $result", result is SdrConversionResult.Completed)
      if (name == "multi-audio.mp4") assertEquals(MediaFact.Known(listOf(AudioTrack(MediaFact.Known(
        AudioFormat.Aac(AudioChannelLayout.Stereo))))), (result as SdrConversionResult.Completed).facts.audioTracks)
    }
  }
  @Test fun hevcEitherPassesFullValidationOrRejectsSafelyWithoutChangingCodecFamily() {
    for (name in listOf("hevc-small-open-gop.mp4", "hevc-long-open-gop.mp4")) {
      val result = export(name)
      assertTrue("$name: $result", result is SdrConversionResult.Completed || result in listOf(
        SdrConversionResult.Failed(SdrConversionFailure.CapabilityUnavailable),
        SdrConversionResult.Failed(SdrConversionFailure.Export), SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid)))
    }
  }
  @Test fun linearTaggedInputFallsBackBeforeExport() {
    assertEquals(SdrConversionResult.Failed(SdrConversionFailure.UnsupportedPlan), export("linear.mp4"))
  }
  @Test fun cancellationRemovesOnlyOwnedPartialAndDoesNotPublishLateResults() {
    val file = source("multi-audio.mp4")
    val original = file.readBytes()
    val (metadata, samples, conversion) = inspect(file)
    val before = ownedFiles()
    val done = CountDownLatch(1)
    val calls = AtomicInteger()
    var result: SdrConversionResult? = null
    val attempt = SdrConversionAdapter(context).start(file, metadata, samples, conversion) {
      result = it; calls.incrementAndGet(); done.countDown()
    }
    try {
      attempt.cancel()
      assertTrue(done.await(30, TimeUnit.SECONDS))
      assertEquals(SdrConversionResult.Cancelled, result)
      Thread.sleep(500)
      assertEquals(1, calls.get())
      assertEquals(before, ownedFiles())
      assertArrayEquals(original, file.readBytes())
    } finally { attempt.cancel(); file.delete() }
  }
}
