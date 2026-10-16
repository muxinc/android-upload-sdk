package com.mux.video.upload.internal.standardization

import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.media3.container.NalUnitUtil
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.mux.exoplayeradapter.AbsRobolectricTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@Config(sdk = [23])
class MediaMetadataReaderTests : AbsRobolectricTest() {
  private fun video(mime: String = "video/avc", rotation: Int? = 0) =
    MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
      rotation?.let { setInteger(MediaFormat.KEY_ROTATION, it) }
    }
  private fun audio(channels: Int, mask: Int? = null) =
    MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, channels).apply {
      mask?.let { setInteger(MediaFormat.KEY_CHANNEL_MASK, it) }
    }
  private fun facts(format: MediaFormat, api: Int = 24) =
    MediaTrackMetadataReader.facts(listOf(MediaTrackMetadataReader.read(0, format, api)))

  @Test fun readsPlatformMetadataWithoutCodecInitializationData() {
    val format = video().apply {
      setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
      setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
      setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUV420Flexible)
    }
    val f = facts(format)
    assertEquals(MediaFact.Known(VideoProfile.H264High), f.videoProfile)
    assertEquals(MediaFact.Known(PixelFormat(8, ChromaSubsampling.Yuv420)), f.pixelFormat)
    assertEquals(MediaFact.Known(DynamicRange.Sdr), f.dynamicRange)
    assertEquals(MediaFact.Known(Dimensions(1920, 1080)), f.displayDimensions)
    // Initialization bytes are not a second metadata source and are never parsed.
    val bytes = ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x67))
    format.setByteBuffer("csd-0", bytes)
    format.setByteBuffer("csd-1", ByteBuffer.allocate(128 * 1024))
    assertEquals(f, facts(format))
    assertEquals(0, bytes.position())
  }

  @Test fun missingPixelAndColorFactsUseHeadersWithoutOverridingPlatformValues() {
    val avc = "000000016742c028da01e0089f97016a02020280000003008000001e078c1950"
      .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val format = video().apply { setByteBuffer("csd-0", ByteBuffer.wrap(avc)) }
    assertEquals(MediaFact.Known(PixelFormat(8, ChromaSubsampling.Yuv420)), facts(format).pixelFormat)
    assertEquals(MediaFact.Known(DynamicRange.Sdr), facts(format, 23).dynamicRange)
    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
    format.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUVP010)
    assertEquals(MediaFact.Known(DynamicRange.Hlg), facts(format, 33).dynamicRange)
    assertEquals(MediaFact.Known(VideoProfile.H264High), facts(format, 33).videoProfile)
    assertEquals(MediaFact.Known(PixelFormat(10, ChromaSubsampling.Yuv420)), facts(format, 33).pixelFormat)
  }

  @Test fun missingFactFallbackSurvivesIncompatibleMedia3() {
    mockkStatic(NalUnitUtil::class)
    try {
      every { NalUnitUtil.findNalUnit(any(), any(), any(), any()) } throws NoSuchMethodError()
      val f = facts(video().apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x67)))
        setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
      })
      assertEquals(MediaFact.Unknown, f.pixelFormat)
      assertEquals(MediaFact.Unknown, f.dynamicRange)
      assertEquals(MediaFact.Known(VideoProfile.H264High), f.videoProfile)
    } finally { unmockkStatic(NalUnitUtil::class) }
  }

  @Test fun readsHdrAndProfileFromPlatformProperties() {
    for ((transfer, expected) in listOf(MediaFormat.COLOR_TRANSFER_HLG to DynamicRange.Hlg,
      MediaFormat.COLOR_TRANSFER_ST2084 to DynamicRange.Pq)) {
      val format = video("video/hevc").apply {
        setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer)
      }
      assertEquals(MediaFact.Known(VideoProfile.HevcMain10), facts(format).videoProfile)
      assertEquals(MediaFact.Known(expected), facts(format).dynamicRange)
      assertEquals(MediaFact.Unknown, facts(format).pixelFormat)
    }
  }

  @Test fun api23AndMissingPlatformColorRemainUnknownWithoutHeaderEvidence() {
    val format = video("video/hevc").apply {
      setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
      setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
    }
    assertEquals(MediaFact.Unknown, facts(format, 23).dynamicRange)
    assertEquals(MediaFact.Unknown, facts(format, 23).videoProfile)
    assertEquals(MediaFact.Unknown, facts(video()).dynamicRange)
    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)
    assertEquals(MediaFact.Unknown, facts(format).dynamicRange)
  }

  @Test fun pixelFormatRequiresAnExplicitRecognizedPlatformValue() {
    val format = video().apply { setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUVP010) }
    assertEquals(MediaFact.Known(PixelFormat(10, ChromaSubsampling.Yuv420)), facts(format, 33).pixelFormat)
    assertEquals(MediaFact.Unknown, facts(format, 32).pixelFormat)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
    assertEquals(MediaFact.Unknown, facts(format).pixelFormat)
    assertEquals(MediaFact.Unknown, facts(video()).pixelFormat)
  }

  @Test fun metadataRateBitrateAndDurationDoNotBecomeSampleFacts() {
    val track = MediaTrackMetadataReader.read(0, video().apply {
      setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f)
      setInteger(MediaFormat.KEY_BIT_RATE, 123456)
      setLong(MediaFormat.KEY_DURATION, 2000000)
    })
    assertEquals(29.97, track.reportedFrameRate.valueOrNull!!, 0.001)
    assertEquals(MediaFact.Known(123456), track.reportedBitrate)
    assertEquals(MediaFact.Known(2.0), track.reportedDurationSeconds)
    val f = MediaTrackMetadataReader.facts(listOf(track))
    assertEquals(MediaFact.Unknown, f.frameRate)
    assertEquals(MediaFact.Unknown, f.averageBitrate)
    assertEquals(MediaFact.Unknown, f.durationSeconds)
    assertEquals(MediaFact.Unknown, f.audioVideoStartOffsetSeconds)
    assertEquals(MediaFact.Unknown, f.gopStructure)
    assertEquals(MediaFact.Unknown, f.editList)
  }

  @Test fun platformRotationDeterminesDisplayAxesUnderSharedConvention() {
    for ((raw, normalized) in listOf(0 to 0, 90 to 270, 180 to 180, 270 to 90, -90 to 90)) {
      val v = MediaTrackMetadataReader.read(0, video(rotation = raw)).video!!
      assertEquals(MediaFact.Known(raw.let { (it % 360 + 360) % 360 }), v.reportedPlatformRotationDegrees)
      assertEquals(MediaFact.Known(normalized), v.rotationDegrees)
      val expected = if (normalized == 90 || normalized == 270) Dimensions(1080, 1920) else Dimensions(1920, 1080)
      assertEquals(MediaFact.Known(expected), v.displayDimensions)
    }
    assertEquals(MediaFact.Known(0), facts(video(rotation = null)).rotationDegrees)
    assertEquals(MediaFact.Unknown, facts(video(rotation = 45)).displayDimensions)
  }

  @Test fun appliesPlatformCropAndRejectsIncompleteOrNonSquareGeometry() {
    val format = video(rotation = 90).apply {
      setInteger("crop-left", 10); setInteger("crop-right", 1909)
      setInteger("crop-top", 0); setInteger("crop-bottom", 1079)
      setInteger("sar-width", 1); setInteger("sar-height", 1)
    }
    assertEquals(MediaFact.Known(Dimensions(1080, 1900)), facts(format).displayDimensions)
    format.setInteger("sar-width", 2)
    assertEquals(MediaFact.Unknown, facts(format).displayDimensions)
    assertEquals(MediaFact.Unknown, facts(video().apply { setInteger("sar-width", 1) }).displayDimensions)
    assertEquals(MediaFact.Unknown, facts(video().apply { setInteger("crop-left", 1) }).displayDimensions)
  }

  @Test fun aacLayoutUsesChannelCountAndAvailableChannelMask() {
    for ((channels, mask, expected) in listOf(Triple(1, null, AudioChannelLayout.Mono),
      Triple(2, null, AudioChannelLayout.Stereo), Triple(6, PlatformAudioFormat.CHANNEL_OUT_5POINT1, AudioChannelLayout.FivePointOne))) {
      assertEquals(MediaFact.Known(AudioFormat.Aac(expected)), MediaTrackMetadataReader.read(0, audio(channels, mask)).audio!!.format)
    }
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(0, audio(6)).audio!!.format)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(0, audio(2, PlatformAudioFormat.CHANNEL_OUT_MONO)).audio!!.format)
  }

  @Test fun audioOrderUsesExtractorOrderAndRetainsTrackIds() {
    val first = MediaTrackMetadataReader.read(1, audio(1).apply { setInteger("track-id", 3) })
    val second = MediaTrackMetadataReader.read(2, audio(2).apply { setInteger("track-id", 2) })
    val f = MediaTrackMetadataReader.facts(listOf(MediaTrackMetadataReader.read(0, video()), first, second))
    assertEquals(MediaFact.Known(3), first.sourceTrackId)
    assertEquals(MediaFact.Known(1), first.containerIndex)
    assertEquals(listOf(AudioChannelLayout.Mono, AudioChannelLayout.Stereo), f.audioTracks.valueOrNull!!.map {
      (it.format.valueOrNull as AudioFormat.Aac).layout })
    assertEquals(MediaFact.Unknown, facts(MediaFormat()).videoTrackCount)
  }

  @Test fun multipleOrAmbiguousDolbyViewsKeepOriginalFallback() {
    val dolby = MediaTrackMetadataReader.read(0, video("video/dolby-vision").apply {
      setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
    })
    val f = MediaTrackMetadataReader.facts(listOf(dolby, MediaTrackMetadataReader.read(1, video("video/hevc"))))
    assertEquals(MediaFact.Known(DynamicRange.DolbyVision), f.dynamicRange)
    assertEquals(MediaFact.Unknown, f.videoTrackCount)
    assertEquals(StandardInputAction.Fallback(FallbackReason.UnsupportedHdr(DynamicRange.DolbyVision)), StandardInputPlanner().plan(f).action)
    val multiple = MediaTrackMetadataReader.facts(listOf(MediaTrackMetadataReader.read(0, video()), MediaTrackMetadataReader.read(1, video())))
    assertEquals(MediaFact.Known(2), multiple.videoTrackCount)
    assertEquals(MediaFact.Unknown, multiple.videoCodec)
  }

  @Test fun emptyAndExcessiveTrackCountsHaveDifferentFailures() {
    assertEquals(MetadataFailure.Malformed, MediaMetadataInspector.trackCountFailure(0))
    assertEquals(MetadataFailure.LimitExceeded, MediaMetadataInspector.trackCountFailure(65))
    assertNull(MediaMetadataInspector.trackCountFailure(64))
  }
  @Test fun boundsAllocationDrivingHevcReferenceCounts() {
    fun ue(value: Int): String {
      val binary = (value + 1).toString(2)
      return "0".repeat(binary.length - 1) + binary
    }
    val bits = "00000001" + "00000001" + "0".repeat(88) + ue(0) + ue(1) +
      ue(1920) + ue(1080) + "0" + ue(0).repeat(3) + "0" + ue(0).repeat(9) +
      "0000" + ue(1) + ue(100000000) + ue(0)
    val raw = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }
    val escaped = mutableListOf<Byte>(0x42, 1)
    var zeroCount = 0
    for (byte in raw) {
      if (zeroCount == 2 && byte.toInt() and 255 <= 3) { escaped.add(3); zeroCount = 0 }
      escaped.add(byte)
      zeroCount = if (byte == 0.toByte()) zeroCount + 1 else 0
    }
    val csd = byteArrayOf(0, 0, 0, 1) + escaped.toByteArray()
    assertEquals(CodecMetadata(), CodecMetadataReader.video(VideoCodec.Hevc, listOf(csd)))
  }

}
