package com.mux.video.upload.internal.standardization

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import java.io.File

@UnstableApi
internal class Media3SdrExportEngine(
  private val context: Context, private val looper: Looper,
  private val conversion: StandardInputConversion, private val targets: SdrEncodingTargets,
  private val capability: SdrEncoderCapability,
) : SdrExportEngine {
  private var transformer: Transformer? = null

  override fun start(input: File, output: File, completed: (String?, String?) -> Unit,
    failed: (SdrConversionFailure) -> Unit) {
    check(Looper.myLooper() == looper)
    val decoder = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(false).build()
    val assetLoader = ExoPlayerAssetLoader.Factory(context, decoder, Clock.DEFAULT, null,
      { ctx -> FirstAudioTrackSelector(ctx, targets.firstAudioTrackId) }, null, null)
    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
      .setRemoveAudio(targets.audioChannels == null)
      .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
        conversion.outputDimensions.width, conversion.outputDimensions.height, Presentation.LAYOUT_SCALE_TO_FIT))))
    if (conversion.outputCadence == OutputCadence.ConstantFrameRate) item.setFrameRate(conversion.outputFrameRate.toInt())
    val items = listOf(item.build())
    val sequence = if (targets.audioChannels == null) EditedMediaItemSequence.withVideoFrom(items)
      else EditedMediaItemSequence.withAudioAndVideoFrom(items)
    val composition = Composition.Builder(sequence)
      .setHdrMode(Composition.HDR_MODE_KEEP_HDR).build()
    val exporter = Transformer.Builder(context)
      .setLooper(looper)
      .setPortraitEncodingEnabled(true)
      .setVideoMimeType(targets.videoMime)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setAssetLoaderFactory(assetLoader)
      .setEncoderFactory(StrictSdrEncoderFactory(context, conversion, targets, capability))
      // Retain Media3's default no-output-sample watchdog (10s; 25s on emulators).
      .addListener(SdrExportListener(targets, completed, failed)).build()
    transformer = exporter
    exporter.start(composition, output.absolutePath)
  }

  override fun cancel() {
    check(Looper.myLooper() == looper)
    transformer?.cancel()
    transformer = null
  }
}

@UnstableApi
internal class SdrExportListener(
  private val targets: SdrEncodingTargets, private val completed: (String?, String?) -> Unit,
  private val failed: (SdrConversionFailure) -> Unit,
) : Transformer.Listener {
  override fun onCompleted(composition: Composition, exportResult: ExportResult) {
    if (targets.audioChannels != null && (exportResult.channelCount != targets.audioChannels ||
        exportResult.sampleRate != targets.audioSampleRate)) failed(SdrConversionFailure.OutputInvalid)
    else completed(exportResult.videoMimeType, exportResult.audioMimeType)
  }
  override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
    failed(SdrConversionFailure.Export)
  }
  override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest,
    fallbackTransformationRequest: TransformationRequest) {
    failed(SdrConversionFailure.ForbiddenFallback)
  }
}

/** MP4 Format.id is the container track ID. Never choose a preferred/default secondary track. */
@UnstableApi
internal class FirstAudioTrackSelector(context: Context, private val trackId: String?) : DefaultTrackSelector(context) {
  init {
    setParameters(buildUponParameters().setForceHighestSupportedBitrate(true)
      .setExceedAudioConstraintsIfNecessary(true).setExceedRendererCapabilitiesIfNecessary(true))
  }
  override fun selectAudioTrack(mappedTrackInfo: MappedTrackInfo, rendererFormatSupports: Array<Array<IntArray>>,
    rendererMixedMimeTypeAdaptationSupports: IntArray, params: Parameters): Pair<ExoTrackSelection.Definition, Int>? {
    if (trackId == null) return null
    for (renderer in 0 until mappedTrackInfo.rendererCount) {
      if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
      val groups = mappedTrackInfo.getTrackGroups(renderer)
      for (groupIndex in 0 until groups.length) {
        val group = groups[groupIndex]
        for (track in 0 until group.length) {
          if (group.getFormat(track).id == trackId) return Pair(ExoTrackSelection.Definition(group, track), renderer)
        }
      }
    }
    error("First audio track identity unavailable")
  }
}

/**
 * Media3's default video factory overrides H.264 profile requests. Supply its DefaultCodec with
 * exact platform settings instead; Transformer still owns the entire decode/effect/encode pipeline.
 * No alternative encoder, size, profile, MIME, or HDR mode is attempted on configuration failure.
 */
@UnstableApi
internal class StrictSdrEncoderFactory(
  private val context: Context, private val conversion: StandardInputConversion,
  private val targets: SdrEncodingTargets, private val capability: SdrEncoderCapability,
) : Codec.EncoderFactory {
  private val audioDelegate = DefaultEncoderFactory.Builder(context)
    .setEnableFallback(false).setEnableFormatFallback(false)
    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(SdrEncodingTargets.AAC_BITRATE)
      .setProfile(SdrEncodingTargets.AAC_PROFILE).build()).build()

  override fun videoNeedsEncoding() = true
  override fun audioNeedsEncoding() = targets.audioChannels != null && !targets.copyAac

  override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
    check(format.sampleMimeType == MimeTypes.AUDIO_AAC && format.channelCount == targets.audioChannels &&
      format.sampleRate == targets.audioSampleRate)
    return audioDelegate.createForAudioEncoding(format, logSessionId)
  }

  override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
    val exact = requestedVideoFormat(format)
    val media = SdrVideoEncoderConfiguration.create(exact, targets, capability.level)
    return DefaultCodec(context, exact, media, capability.name, false, null)
  }

  private fun requestedVideoFormat(format: Format): Format {
    check(format.sampleMimeType == targets.videoMime && format.width == conversion.outputDimensions.width &&
      format.height == conversion.outputDimensions.height && format.rotationDegrees == 0 &&
      format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR)
    return format.buildUpon().setFrameRate(conversion.outputFrameRate.toFloat()).setAverageBitrate(targets.bitrate).build()
  }
}

/** Shared preflight/export keys; capability metadata remains only an advertised-format guard. */
@UnstableApi
internal object SdrVideoEncoderConfiguration {
  fun create(format: Format, targets: SdrEncodingTargets, level: Int,
    sdk: Int = Build.VERSION.SDK_INT, device: String = Build.DEVICE,
    soc: String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""): MediaFormat {
    // Media3 forces a 30-fps hint on this device. Other rates have unproven GOP/rate
    // control here; preflight rejects them instead of changing the planned settings.
    check(sdk >= 30 || device != "joyeuse" || format.frameRate == 30f)
    val media = MediaFormatUtil.createMediaFormatFromFormat(format)
    media.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    media.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
    media.setInteger(MediaFormat.KEY_PROFILE, targets.platformProfile)
    media.setInteger(MediaFormat.KEY_LEVEL, level)
    media.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, targets.keyframeIntervalSeconds)
    if (sdk >= 29) media.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    // Media3 1.11.1's empirical encoder performance settings, retaining its exceptions.
    if (sdk >= 25) {
      media.setInteger(MediaFormat.KEY_PRIORITY, 1)
      val overflow = sdk in 31..34 && soc in setOf("SM8550", "SM7450", "SM6450", "SC9863A", "T612", "T606", "T603")
      media.setInteger(MediaFormat.KEY_OPERATING_RATE, if (sdk == 26) 30 else if (overflow) 1000 else Int.MAX_VALUE)
    }
    return media
  }
}
