package com.mux.video.upload.internal.standardization

import android.media.MediaFormat
import com.mux.exoplayeradapter.AbsRobolectricTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@Config(sdk = [23])
class MediaMetadataReaderTests : AbsRobolectricTest() {
  private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  // SPS initialization bytes from checksum-verified shared synthetic fixtures, not media samples.
  private val avc = hex("000000016742c028da01e0089f97016a02020280000003008000001e078c1950")
  private val hevcSdr = hex("000001420101016000000300900000030000030078a003c0801107cb965654a4c2f016a020202080000003008000000f04")
  private val hevcHlg = hex("00000001420101022000000300900000030000030078a003c0801107cad965654a4c2f016a12241208000003000800000300f040")
  private val hevcPq = hex("00000001420101022000000300900000030000030078a003c0801107cad965654a4c2f016a12201208000003000800000300f040")

  private fun video(mime: String = "video/avc", csd: ByteArray? = avc, rotation: Int? = 0): MediaFormat =
    MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
      rotation?.let { setInteger(MediaFormat.KEY_ROTATION, it) }
      csd?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
    }

  private fun facts(format: MediaFormat, api: Int = 23) =
    MediaTrackMetadataReader.facts(listOf(MediaTrackMetadataReader.read(0, format, api)))

  @Test fun readsAvcConfigurationAndGeometryOnApi23() {
    val f = facts(video())
    assertEquals(MediaFact.Known(VideoProfile.H264Baseline), f.videoProfile)
    assertEquals(MediaFact.Known(PixelFormat(8, ChromaSubsampling.Yuv420)), f.pixelFormat)
    assertEquals(MediaFact.Known(DynamicRange.Sdr), f.dynamicRange)
    assertEquals(MediaFact.Known(Dimensions(1920, 1080)), f.displayDimensions)
  }

  @Test fun readsHevcMainMain10AndHdrFromConfigurationOnApi23() {
    for ((csd, profile, depth, range) in listOf(
      listOf(hevcSdr, VideoProfile.HevcMain, 8, DynamicRange.Sdr),
      listOf(hevcHlg, VideoProfile.HevcMain10, 10, DynamicRange.Hlg),
      listOf(hevcPq, VideoProfile.HevcMain10, 10, DynamicRange.Pq))) {
      val f = facts(video("video/hevc", csd as ByteArray))
      assertEquals(MediaFact.Known(profile), f.videoProfile)
      assertEquals(MediaFact.Known(PixelFormat(depth as Int, ChromaSubsampling.Yuv420)), f.pixelFormat)
      assertEquals(MediaFact.Known(range), f.dynamicRange)
    }
  }

  @Test fun missingColorIsUnknownAndApi23IgnoresPlatformColor() {
    val format = video(csd = null).apply { setInteger("color-transfer", 3) }
    assertEquals(MediaFact.Unknown, facts(format).dynamicRange)
    assertEquals(MediaFact.Known(DynamicRange.Sdr), facts(format, 24).dynamicRange)
    format.setInteger("color-transfer", 0)
    assertEquals(MediaFact.Unknown, facts(format, 24).dynamicRange)
  }

  @Test fun conflictingColorAndProfileRemainUnknown() {
    val format = video().apply {
      setInteger("color-transfer", 7)
      setInteger(MediaFormat.KEY_PROFILE, 8) // High contradicts the baseline SPS.
    }
    val f = facts(format, 24)
    assertEquals(MediaFact.Unknown, f.dynamicRange)
    assertEquals(MediaFact.Unknown, f.videoProfile)
  }

  @Test fun dolbyVisionMimeOverridesBaseTransfer() {
    val format = video("video/dolby-vision").apply { setInteger("color-transfer", 6) }
    assertEquals(MediaFact.Known(DynamicRange.DolbyVision), facts(format, 24).dynamicRange)
  }

  @Test fun metadataRateBitrateAndDurationDoNotBecomeSampleFacts() {
    val format = video().apply {
      setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f)
      setInteger(MediaFormat.KEY_BIT_RATE, 123456)
      setLong(MediaFormat.KEY_DURATION, 2000000)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val track = MediaTrackMetadataReader.read(0, format)
    assertEquals(29.97, track.reportedFrameRate.valueOrNull!!, 0.001)
    assertEquals(MediaFact.Known(123456), track.reportedBitrate)
    assertEquals(MediaFact.Known(2.0), track.reportedDurationSeconds)
    val f = MediaTrackMetadataReader.facts(listOf(track))
    assertEquals(MediaFact.Unknown, f.frameRate)
    assertEquals(MediaFact.Unknown, f.averageBitrate)
    assertEquals(MediaFact.Unknown, f.durationSeconds)
    assertEquals(MediaFact.Unknown, f.maximumKeyframeIntervalSeconds)
    assertEquals(MediaFact.Unknown, f.gopStructure)
    assertEquals(MediaFact.Unknown, f.editList)
    assertEquals(MediaFact.Unknown, f.timestamps)
  }

  @Test fun normalizesSignedRotationAndSwapsPortraitAxes() {
    val format = video().apply { setInteger(MediaFormat.KEY_ROTATION, -90) }
    val f = facts(format)
    assertEquals(MediaFact.Known(90), f.rotationDegrees)
    assertEquals(MediaFact.Known(Dimensions(1080, 1920)), f.displayDimensions)
  }

  @Test fun absentRotationMeansZeroButNonQuarterRotationStaysUnknown() {
    val missing = video(rotation = null)
    assertEquals(MediaFact.Known(0), facts(missing).rotationDegrees)
    assertEquals(MediaFact.Known(Dimensions(1920, 1080)), facts(missing).displayDimensions)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(0, missing).video!!.reportedPlatformRotationDegrees)
    val format = video().apply { setInteger(MediaFormat.KEY_ROTATION, 45) }
    assertEquals(MediaFact.Unknown, facts(format).displayDimensions)
  }

  @Test fun appliesOrdinaryCropAndRejectsNonSquarePixels() {
    val format = video(csd = null).apply {
      setInteger("crop-left", 10); setInteger("crop-right", 1909)
      setInteger("crop-top", 0); setInteger("crop-bottom", 1079)
      setInteger("sar-width", 1); setInteger("sar-height", 1)
      setInteger(MediaFormat.KEY_ROTATION, 90)
    }
    assertEquals(MediaFact.Known(Dimensions(1080, 1900)), facts(format).displayDimensions)
    format.setInteger("sar-width", 2)
    assertEquals(MediaFact.Unknown, facts(format).displayDimensions)
    format.setInteger("sar-width", 1)
    format.setInteger("crop-right", 1920)
    assertEquals(MediaFact.Unknown, facts(format).displayDimensions)
  }

  @Test fun conflictingAspectRatioIsUnknown() {
    val format = video().apply { setInteger("sar-width", 2); setInteger("sar-height", 1) }
    assertEquals(MediaFact.Unknown, facts(format).displayDimensions)
  }

  @Test fun malformedAndOversizedCsdStayUnknown() {
    for (data in listOf(hex("00000167"), ByteArray(CodecMetadataReader.MAX_CONFIGURATION_BYTES + 1))) {
      val f = facts(video(csd = data))
      assertEquals(MediaFact.Unknown, f.pixelFormat)
      assertEquals(MediaFact.Unknown, f.dynamicRange)
    }
    assertEquals(CodecMetadata(), CodecMetadataReader.video(VideoCodec.H264, listOf(avc, ByteArray(65536))))
  }

  @Test fun disagreeingSpsDescriptionsStayUnknownAndBuffersAreNotMutated() {
    val format = video("video/hevc", hevcHlg)
    format.setByteBuffer("csd-1", ByteBuffer.wrap(hevcPq))
    val before = format.getByteBuffer("csd-0")!!.position()
    assertEquals(MediaFact.Unknown, facts(format).pixelFormat)
    assertEquals(before, format.getByteBuffer("csd-0")!!.position())
  }

  private fun audio(channels: Int, config: String? = null) =
    MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, channels).apply {
      config?.let { setByteBuffer("csd-0", ByteBuffer.wrap(hex(it))) }
    }

  @Test fun aacLayoutRequiresConfigurationEvidence() {
    for ((count, config, layout) in listOf(
      Triple(1, "1188", AudioChannelLayout.Mono),
      Triple(2, "1190", AudioChannelLayout.Stereo),
      Triple(6, "11b0", AudioChannelLayout.FivePointOne))) {
      val track = MediaTrackMetadataReader.read(1, audio(count, config))
      assertEquals(MediaFact.Known(AudioFormat.Aac(layout)), track.audio!!.format)
    }
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(1, audio(6)).audio!!.format)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(1, audio(6, "1190")).audio!!.format)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(1, audio(6, "1180")).audio!!.format)
  }

  @Test fun audioTracksRetainContainerOrderAndMultipleVideoDoesNotSelectOne() {
    val tracks = listOf(MediaTrackMetadataReader.read(0, video()),
      MediaTrackMetadataReader.read(1, audio(2, "1190")), MediaTrackMetadataReader.read(2, audio(1, "1188")))
    assertEquals(MediaFact.Known(listOf(AudioTrack(MediaFact.Known(AudioFormat.Aac(AudioChannelLayout.Stereo))),
      AudioTrack(MediaFact.Known(AudioFormat.Aac(AudioChannelLayout.Mono))))),
      MediaTrackMetadataReader.facts(tracks).audioTracks)
    val f = MediaTrackMetadataReader.facts(tracks + MediaTrackMetadataReader.read(3, video("video/hevc", hevcSdr)))
    assertEquals(MediaFact.Known(2), f.videoTrackCount)
    assertEquals(MediaFact.Unknown, f.videoCodec)
    assertEquals(MediaFact.Unknown, f.displayDimensions)
  }

  @Test fun readsExplicitAacFrequencyAndRejectsZeroFrequency() {
    assertEquals(MediaFact.Known(AudioChannelLayout.Stereo),
      CodecMetadataReader.aacLayout(hex("17805dc010"), 2))
    assertEquals(MediaFact.Unknown, CodecMetadataReader.aacLayout(hex("1780000010"), 2))
    // AAC configuration is not NAL-escaped; this byte sequence must not lose its 0x03 byte.
    assertEquals(MediaFact.Known(AudioChannelLayout.Stereo),
      CodecMetadataReader.aacLayout(hex("1780000310"), 2))
  }

  @Test fun audioDelayAndPaddingRemainObservationsIncludingExplicitZero() {
    val format = audio(2, "1190").apply {
      setInteger(MediaFormat.KEY_ENCODER_DELAY, 1024)
      setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
    }
    val track = MediaTrackMetadataReader.read(0, format)
    assertEquals(MediaFact.Known(1024), track.audio!!.encoderDelaySamples)
    assertEquals(MediaFact.Known(0), track.audio.encoderPaddingSamples)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.facts(listOf(track)).audioVideoStartOffsetSeconds)
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

  private fun isoTrack(handler: String, entry: String, id: Int, dolby: Boolean = false) =
    IsoTrackMetadata(handler, MediaFact.Known(id), listOf(entry), dolby, MediaFact.Known(0), true)

  @Test fun dolbyAliasesKeepPhysicalTrackCountAndOriginalFallbackWithoutSelectingAView() {
    val iso = MediaFact.Known(listOf(isoTrack("vide", "hvc1", 1, true), isoTrack("soun", "mp4a", 2)))
    val views = listOf(
      MediaTrackMetadataReader.read(0, video("video/dolby-vision", null)).copy(containerIndex = MediaFact.Known(0)),
      MediaTrackMetadataReader.read(1, video("video/hevc", hevcHlg)).copy(containerIndex = MediaFact.Known(0)),
      MediaTrackMetadataReader.read(2, audio(2, "1190")).copy(containerIndex = MediaFact.Known(1)))
    val f = MediaContainerMetadataReader.facts(MediaFact.Known(ContainerKind.IsoBaseMedia), iso, views)
    assertEquals(MediaFact.Known(1), f.videoTrackCount)
    assertEquals(MediaFact.Unknown, f.videoCodec)
    assertEquals(MediaFact.Unknown, f.displayDimensions)
    assertEquals(StandardInputAction.Fallback(FallbackReason.UnsupportedHdr(DynamicRange.DolbyVision)),
      StandardInputPlanner().plan(f).action)
    assertEquals(MediaFact.Known(DynamicRange.DolbyVision), f.dynamicRange)
    assertEquals(MediaFact.Known(listOf(AudioTrack(MediaFact.Known(AudioFormat.Aac(AudioChannelLayout.Stereo))))), f.audioTracks)
  }

  @Test fun normalizesAudioByContainerIdentityAndKeepsMissingTracksUnknown() {
    val iso = MediaFact.Known(listOf(isoTrack("vide", "avc1", 1), isoTrack("soun", "mp4a", 2), isoTrack("soun", "mp4a", 3)))
    val views = listOf(MediaTrackMetadataReader.read(0, video()).copy(containerIndex = MediaFact.Known(0)),
      MediaTrackMetadataReader.read(1, audio(1, "1188")).copy(containerIndex = MediaFact.Known(2)),
      MediaTrackMetadataReader.read(2, audio(2, "1190")).copy(containerIndex = MediaFact.Known(1)))
    val f = MediaContainerMetadataReader.facts(MediaFact.Known(ContainerKind.IsoBaseMedia), iso, views)
    assertEquals(listOf(AudioChannelLayout.Stereo, AudioChannelLayout.Mono),
      f.audioTracks.valueOrNull!!.map { (it.format.valueOrNull as AudioFormat.Aac).layout })
    assertEquals(MediaFact.Unknown, MediaContainerMetadataReader.facts(
      MediaFact.Known(ContainerKind.IsoBaseMedia), iso, views.dropLast(1)).audioTracks)
  }

  @Test fun unknownMimePreventsClaimingCompleteTrackStructure() {
    val f = MediaTrackMetadataReader.facts(listOf(MediaTrackMetadataReader.read(0, video()),
      MediaTrackMetadataReader.read(1, MediaFormat())))
    assertEquals(MediaFacts(), f)
  }

  @Test fun normalizesAndroidQuarterTurnsBeforeReconcilingContainerRotation() {
    for ((raw, normalized) in listOf(0 to 0, 90 to 270, 180 to 180, 270 to 90)) {
      val format = video().apply { setInteger(MediaFormat.KEY_ROTATION, raw) }
      val track = MediaTrackMetadataReader.read(0, format, 23, MediaFact.Known(normalized))
      assertEquals(MediaFact.Known(raw), track.video!!.reportedPlatformRotationDegrees)
      assertEquals(MediaFact.Known(normalized), track.video.rotationDegrees)
      val conflict = MediaTrackMetadataReader.read(0, format, 23, MediaFact.Known((normalized + 180) % 360))
      assertEquals(MediaFact.Unknown, conflict.video!!.rotationDegrees)
      assertEquals(MediaFact.Unknown, conflict.video.displayDimensions)
    }
  }

  @Test fun absentPlatformRotationCanUseContainerButInvalidRotationCannot() {
    val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
    assertEquals(MediaFact.Known(270), MediaTrackMetadataReader.read(0, format, 23, MediaFact.Known(270)).video!!.rotationDegrees)
    format.setInteger(MediaFormat.KEY_ROTATION, 45)
    assertEquals(MediaFact.Unknown, MediaTrackMetadataReader.read(0, format, 23, MediaFact.Known(270)).video!!.rotationDegrees)
  }

  @Test fun failedIsoReadRetainsResolutionButDoesNotGuessMultipleAudioOrder() {
    for (rotation in listOf(0, null)) {
      val tracks = listOf(MediaTrackMetadataReader.read(0, video(rotation = rotation), containerRotation = MediaFact.Unknown),
        MediaTrackMetadataReader.read(1, audio(2, "1190")), MediaTrackMetadataReader.read(2, audio(1, "1188")))
      val f = MediaContainerMetadataReader.facts(MediaFact.Known(ContainerKind.IsoBaseMedia), MediaFact.Unknown, tracks)
      assertEquals(MediaFact.Known(VideoCodec.H264), f.videoCodec)
      assertEquals(MediaFact.Known(Dimensions(1920, 1080)), f.encodedDimensions)
      assertEquals(if (rotation == null) MediaFact.Unknown else MediaFact.Known(0), f.rotationDegrees)
      assertEquals(if (rotation == null) MediaFact.Unknown else MediaFact.Known(Dimensions(1920, 1080)), f.displayDimensions)
      assertEquals(MediaFact.Unknown, f.audioTracks)
      assertEquals(MediaFact.Unknown, f.durationSeconds)
    }
  }

  @Test fun matroskaWithoutRotationRetainsKnownDisplayDimensions() {
    val track = MediaTrackMetadataReader.read(0, video(rotation = null))
    val f = MediaContainerMetadataReader.facts(MediaFact.Known(ContainerKind.Matroska), MediaFact.Unknown, listOf(track))
    assertEquals(MediaFact.Known(0), f.rotationDegrees)
    assertEquals(MediaFact.Known(Dimensions(1920, 1080)), f.displayDimensions)
  }

  @Test fun dolbyEvidenceSurvivesNonIsoMissingIsoUnknownHandlerAndUnknownMime() {
    val dolby = MediaTrackMetadataReader.read(0, video("video/dolby-vision", null))
    val base = MediaTrackMetadataReader.read(1, video("video/hevc", hevcHlg))
    for (container in listOf(ContainerKind.Matroska, ContainerKind.Other, ContainerKind.IsoBaseMedia)) {
      val f = MediaContainerMetadataReader.facts(MediaFact.Known(container), MediaFact.Unknown, listOf(dolby, base))
      assertEquals(MediaFact.Known(DynamicRange.DolbyVision), f.dynamicRange)
      assertEquals(MediaFact.Unknown, f.videoTrackCount)
      assertEquals(StandardInputAction.Fallback(FallbackReason.UnsupportedHdr(DynamicRange.DolbyVision)), StandardInputPlanner().plan(f).action)
    }
    val unknownHandler = MediaFact.Known(listOf(isoTrack("vide", "dvh1", 1, true).copy(handler = null)))
    assertEquals(MediaFact.Known(DynamicRange.DolbyVision), MediaContainerMetadataReader.facts(
      MediaFact.Known(ContainerKind.IsoBaseMedia), unknownHandler, listOf(dolby)).dynamicRange)
    assertEquals(MediaFact.Known(DynamicRange.DolbyVision), MediaTrackMetadataReader.facts(
      listOf(dolby, MediaTrackMetadataReader.read(1, MediaFormat()))).dynamicRange)
  }

  @Test fun distinctDolbyTrackIdentitiesStillProveUnsafeMultipleTracks() {
    val tracks = listOf(MediaTrackMetadataReader.read(0, video("video/dolby-vision", null)).copy(sourceTrackId = MediaFact.Known(1)),
      MediaTrackMetadataReader.read(1, video("video/hevc", hevcHlg)).copy(sourceTrackId = MediaFact.Known(2)))
    assertEquals(MediaFact.Known(2), MediaTrackMetadataReader.facts(tracks).videoTrackCount)
  }

  @Test fun otherCodecWithoutSquarePixelEvidenceKeepsOriginalFallback() {
    val format = video("video/x-vnd.on2.vp9", null).apply { setInteger("color-transfer", 3) }
    val f = facts(format, 24).copy(frameRate = MediaFact.Known(30.0))
    assertEquals(MediaFact.Unknown, f.displayDimensions)
    assertTrue(StandardInputPlanner().plan(f, capabilities = fullCapabilities()).action is StandardInputAction.Fallback)
  }

  @Test fun predictedHevcReferenceSetsRetainMain10HlgEvidence() {
    val nal = hex("420101022000000300900000030000030078a003c0801107cad965654a4c1af7780b509120904000000300400000078200")
    val f = facts(video("video/hevc", byteArrayOf(0, 0, 0, 1) + nal))
    assertEquals(MediaFact.Known(VideoProfile.HevcMain10), f.videoProfile)
    assertEquals(MediaFact.Known(PixelFormat(10, ChromaSubsampling.Yuv420)), f.pixelFormat)
    assertEquals(MediaFact.Known(DynamicRange.Hlg), f.dynamicRange)
    assertTrue(StandardInputPlanner().plan(f).action is StandardInputAction.UploadOriginal)
  }

  @Test fun supportsMixedAnnexBPrefixesAndKeepsAacExtensionsUnknown() {
    val mixed = avc + byteArrayOf(0, 0, 1, 0x68, 1)
    assertEquals(CodecMetadataReader.video(VideoCodec.H264, listOf(avc)), CodecMetadataReader.video(VideoCodec.H264, listOf(mixed)))
    for (config in listOf("2b9208", "eb9208", "1180", "11f0"))
      assertEquals(MediaFact.Unknown, CodecMetadataReader.aacLayout(hex(config), 2))
  }

  @Test fun emptyAndExcessiveTrackCountsHaveDifferentFailures() {
    assertEquals(MetadataFailure.Malformed, MediaMetadataInspector.trackCountFailure(0))
    assertEquals(MetadataFailure.LimitExceeded, MediaMetadataInspector.trackCountFailure(65))
    assertNull(MediaMetadataInspector.trackCountFailure(1))
    assertNull(MediaMetadataInspector.trackCountFailure(64))
  }

  @Test fun complexPredictedHevcChainsStayUnknown() {
    val csd = hex("00000001420101022000000300900000030000030078a003c0801107cad965654a4c08bdec780b509120904000000300400000078200")
    assertEquals(CodecMetadata(), CodecMetadataReader.video(VideoCodec.Hevc, listOf(csd)))
  }
}
