package com.mux.video.upload.internal.standardization

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [SupportedFormatShadow::class])
class SdrMedia3ConfigurationTests {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val facts = compliantFacts().copy(cadence = known(Cadence.Constant))
  private val conversion = (StandardInputPlanner().plan(facts.copy(averageBitrate = known(9_000_000L)),
    options(), fullCapabilities()).action as StandardInputAction.Convert).conversion
  private val targets = SdrEncodingTargets("video/avc", VideoProfile.H264Baseline,
    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, 6_000_000, 18, null, null, false, null)
  private val format = Format.Builder().setSampleMimeType("video/avc").setWidth(conversion.outputDimensions.width)
    .setHeight(conversion.outputDimensions.height).setFrameRate(30f).setAverageBitrate(targets.bitrate)
    .setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
  private val video = MediaTrackMetadataReader.read(0, MediaFormat.createVideoFormat("video/avc", 1920, 1080))
  private val metadata = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), listOf(video), MediaFact.Unknown, facts, 0)

  @Test fun strictFactoryRejectsMimeDimensionsRotationAndLinearBeforeCodecCreation() {
    val factory = StrictSdrEncoderFactory(context, conversion, targets, SdrEncoderCapability("chosen", 4))
    val configuration = SdrVideoEncoderConfiguration.create(format, targets, 4)
    assertEquals(targets.platformProfile, configuration.getInteger(MediaFormat.KEY_PROFILE))
    assertEquals(4, configuration.getInteger(MediaFormat.KEY_LEVEL))
    assertEquals(18, configuration.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
    assertEquals(1, configuration.getInteger(MediaFormat.KEY_PRIORITY))
    for (bad in listOf(format.buildUpon().setSampleMimeType("video/hevc").build(),
      format.buildUpon().setWidth(640).build(), format.buildUpon().setRotationDegrees(90).build(),
      format.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED.buildUpon().setColorTransfer(C.COLOR_TRANSFER_LINEAR).build()).build())) {
      assertThrows(IllegalStateException::class.java) { factory.createForVideoEncoding(bad, null) }
    }
  }

  @Test fun performanceSettingsRetainApiAndChipsetExceptionsWithoutChangingProfile() {
    val old = SdrVideoEncoderConfiguration.create(format, targets, 4, sdk = 23)
    assertFalse(old.containsKey(MediaFormat.KEY_PRIORITY)); assertFalse(old.containsKey(MediaFormat.KEY_OPERATING_RATE))
    assertEquals(30, SdrVideoEncoderConfiguration.create(format, targets, 4, sdk = 26).getInteger(MediaFormat.KEY_OPERATING_RATE))
    val affected = SdrVideoEncoderConfiguration.create(format, targets, 4, sdk = 31, soc = "SM8550")
    assertEquals(1000, affected.getInteger(MediaFormat.KEY_OPERATING_RATE))
    assertEquals(targets.platformProfile, affected.getInteger(MediaFormat.KEY_PROFILE))
    assertEquals(0, affected.getInteger(MediaFormat.KEY_MAX_B_FRAMES))
    assertEquals(Int.MAX_VALUE, SdrVideoEncoderConfiguration.create(format, targets, 4, sdk = 35, soc = "SM8550")
      .getInteger(MediaFormat.KEY_OPERATING_RATE))
  }

  @Test fun redmiFrameRateWorkaroundDoesNotIncreaseLowRateGopOrResampleTimestamps() {
    val high = format.buildUpon().setFrameRate(120f).build()
    assertEquals(30f, SdrVideoEncoderConfiguration.create(high, targets, 4, sdk = 29, device = "joyeuse")
      .getFloat(MediaFormat.KEY_FRAME_RATE), 0f)
    val low = format.buildUpon().setFrameRate(5f).build()
    assertEquals(5f, SdrVideoEncoderConfiguration.create(low, targets, 4, sdk = 29, device = "joyeuse")
      .getFloat(MediaFormat.KEY_FRAME_RATE), 0f)
    assertEquals(120f, high.frameRate, 0f)
  }

  private fun codec(encoder: Boolean, mime: String): MediaCodecInfo {
    val capabilityFormat = if (mime.startsWith("video/")) MediaFormat.createVideoFormat(mime, 1920, 1080)
      else MediaFormat.createAudioFormat(mime, 48_000, 2)
    val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(capabilityFormat)
      .setIsEncoder(encoder).apply {
        if (mime.startsWith("video/")) setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
      }
      .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
        profile = targets.platformProfile; level = MediaCodecInfo.CodecProfileLevel.AVCLevel51
      })).build()
    return MediaCodecInfoBuilder.newBuilder().setName(if (encoder) "chosen" else "decoder")
      .setIsEncoder(encoder).setCapabilities(capabilities).build()
  }

  @Test fun coldEnumerationHasSeparateBoundAndUnrelatedCodecsDoNotExhaustQueries() {
    var now = 0L
    val unrelated = codec(false, "audio/mpeg")
    val encoder = codec(true, "video/avc")
    val decoder = codec(false, "video/avc")
    val infos = Array(200) { unrelated } + arrayOf(decoder, encoder)
    val queried = mutableListOf<MediaFormat>()
    SupportedFormatShadow.supported = { queried.add(it); true }
    val preflight = SdrCapabilityPreflight(codecInfos = { now += 800_000_000L; infos }, nanoTime = { now })
    assertEquals(SdrEncoderCapability("chosen", MediaCodecInfo.CodecProfileLevel.AVCLevel51),
      preflight.videoEncoder(conversion, metadata, targets) { false })
    assertEquals(2, queried.size)
    assertEquals(18, queried.last().getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
    assertEquals(1, queried.last().getInteger(MediaFormat.KEY_PRIORITY))
  }

  @Test fun preflightStillBoundsColdInitializationQueriesAndCancellation() {
    var now = 0L
    val encoder = codec(true, "video/avc")
    val slowDecoder = codec(false, "video/avc")
    var queries = 0
    SupportedFormatShadow.supported = { queries++; now += 500_000_000L; true }
    val infos = arrayOf(slowDecoder, encoder)
    assertNull(SdrCapabilityPreflight({ now += 2_000_000_000L; infos }, { now })
      .videoEncoder(conversion, metadata, targets) { false })
    now = 0
    assertNull(SdrCapabilityPreflight({ infos }, { now }).videoEncoder(conversion, metadata, targets) { false })
    assertEquals(1, queries)
    var enumerated = false
    assertNull(SdrCapabilityPreflight({ enumerated = true; infos }, { 0L }).videoEncoder(conversion, metadata, targets) { true })
    assertFalse(enumerated)
    val rejected = codec(false, "video/avc")
    queries = 0
    SupportedFormatShadow.supported = { queries++; false }
    assertNull(SdrCapabilityPreflight({ Array(200) { rejected } }, { 0L }).videoEncoder(conversion, metadata, targets) { false })
    assertEquals(128, queries)
  }

  @Test fun firstTrackSelectorIgnoresPreferredSecondaryAndFailsOnMissingIdentity() {
    val first = Format.Builder().setId("2").setSampleMimeType("audio/mp4a-latm").setChannelCount(2).build()
    val secondary = first.buildUpon().setId("3").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setChannelCount(1).build()
    val groups = TrackGroupArray(TrackGroup("secondary", secondary), TrackGroup("first", first))
    val mapped = mockk<MappingTrackSelector.MappedTrackInfo> {
      every { rendererCount } returns 1
      every { getRendererType(0) } returns C.TRACK_TYPE_AUDIO
      every { getTrackGroups(0) } returns groups
    }
    val selection = selectAudioTrack(FirstAudioTrackSelector(context, "2"), mapped)!!
    assertEquals("2", selection.first.group.getFormat(selection.first.tracks.single()).id)
    assertNull(selectAudioTrack(FirstAudioTrackSelector(context, null), mapped))
    assertThrows(IllegalStateException::class.java) { selectAudioTrack(FirstAudioTrackSelector(context, "missing"), mapped) }
  }

  private fun selectAudioTrack(selector: FirstAudioTrackSelector,
    mapped: MappingTrackSelector.MappedTrackInfo): Pair<ExoTrackSelection.Definition, Int>? {
    val method = FirstAudioTrackSelector::class.java.declaredMethods.single { it.name == "selectAudioTrack" }
    method.isAccessible = true
    try {
      @Suppress("UNCHECKED_CAST")
      return method.invoke(selector, mapped, emptyArray<Array<IntArray>>(), intArrayOf(), selector.parameters)
        as Pair<ExoTrackSelection.Definition, Int>?
    } catch (error: InvocationTargetException) {
      throw error.cause!!
    }
  }

  @Test fun exportListenerRoutesMuxerWatchdogAndFallbackToFailureAndChecksCopiedAudio() {
    val composition = Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(
      EditedMediaItem.Builder(MediaItem.fromUri("file:///test.mp4")).build()))).build()
    val failures = mutableListOf<SdrConversionFailure>()
    var completed = false
    val listener = SdrExportListener(targets.copy(audioChannels = 2, audioSampleRate = 48_000, copyAac = true),
      { _, _ -> completed = true }, failures::add)
    listener.onError(composition, ExportResult.Builder().build(), muxerTimeoutException())
    listener.onFallbackApplied(composition, TransformationRequest.Builder().build(), TransformationRequest.Builder().build())
    listener.onCompleted(composition, ExportResult.Builder().setChannelCount(1).setSampleRate(48_000).build())
    assertEquals(listOf(SdrConversionFailure.Export, SdrConversionFailure.ForbiddenFallback, SdrConversionFailure.OutputInvalid), failures)
    assertFalse(completed)
    listener.onCompleted(composition, ExportResult.Builder().setChannelCount(2).setSampleRate(48_000).build())
    assertTrue(completed)
  }
}

internal fun muxerTimeoutException(): ExportException = ExportException::class.java
  .getDeclaredMethod("createForMuxer", Throwable::class.java, Int::class.javaPrimitiveType)
  .apply { isAccessible = true }.invoke(null, IllegalStateException("No samples"), ExportException.ERROR_CODE_MUXING_TIMEOUT) as ExportException

/** Only advertised format answers are faked; codec roles, MIME and profiles use Android objects. */
@Implements(MediaCodecInfo.CodecCapabilities::class)
class SupportedFormatShadow {
  @Implementation fun isFormatSupported(format: MediaFormat): Boolean = supported(format)
  companion object { var supported: (MediaFormat) -> Boolean = { true } }
}
