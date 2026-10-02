package com.mux.video.upload.internal.standardization

import androidx.media3.common.util.ParsableBitArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray

/** Color codes use Media3/Android's normalized constants, not ISO code points. */
internal data class ColorMetadata(
  val standard: MediaFact<Int> = MediaFact.Unknown,
  val transfer: MediaFact<Int> = MediaFact.Unknown,
  val range: MediaFact<Int> = MediaFact.Unknown,
)

internal data class CodecMetadata(
  val profile: MediaFact<VideoProfile> = MediaFact.Unknown,
  val pixelFormat: MediaFact<PixelFormat> = MediaFact.Unknown,
  val color: ColorMetadata = ColorMetadata(),
  val pixelAspectRatio: MediaFact<Double> = MediaFact.Unknown,
)

/** Only codec initialization data is read here. No compressed media samples are inspected. */
internal object CodecMetadataReader {
  const val MAX_CONFIGURATION_BYTES = 64 * 1024

  fun video(codec: VideoCodec?, configurations: List<ByteArray>): CodecMetadata {
    if (configurations.sumOf { it.size.toLong() } > MAX_CONFIGURATION_BYTES) return CodecMetadata()
    val results = configurations.flatMap(::annexBNalUnits).mapNotNull { nal ->
      try {
        when {
          codec == VideoCodec.H264 && nal.isNotEmpty() && nal[0].toInt() and 31 == 7 -> avc(nal)
          codec == VideoCodec.Hevc && nal.size >= 2 && (nal[0].toInt() and 126) shr 1 == 33 -> hevc(nal)
          else -> null
        }
      } catch (_: RuntimeException) {
        // Truncated/unsupported initialization data never becomes compliant evidence.
        CodecMetadata()
      }
    }
    // Several SPS descriptions may signal changing formats; do not choose an arbitrary one.
    return results.distinct().singleOrNull() ?: CodecMetadata()
  }

  private fun avc(nal: ByteArray): CodecMetadata {
    val sps = NalUnitUtil.parseSpsNalUnit(nal, 0, nal.size)
    val bits = ParsableNalUnitBitArray(nal, 1, nal.size)
    val profile = bits.readBits(8)
    bits.skipBits(16)
    bits.readUnsignedExpGolombCodedInt() // seq_parameter_set_id
    val chroma = when (profile) {
      66, 77, 88 -> 1
      100, 110, 122, 244, 44, 83, 86, 118, 128, 138 -> bits.readUnsignedExpGolombCodedInt()
      else -> return CodecMetadata()
    }
    return CodecMetadata(
      profile = MediaFact.Known(when (profile) {
        66 -> VideoProfile.H264Baseline
        77 -> VideoProfile.H264Main
        100 -> VideoProfile.H264High
        else -> VideoProfile.Other
      }),
      pixelFormat = pixels(sps.bitDepthLumaMinus8, sps.bitDepthChromaMinus8, chroma),
      color = color(sps.colorSpace, sps.colorTransfer, sps.colorRange),
      pixelAspectRatio = positive(sps.pixelWidthHeightRatio.toDouble()),
    )
  }

  private fun hevc(nal: ByteArray): CodecMetadata {
    // Single-layer SPS only; multilayer configuration needs separate evidence.
    if ((nal[0].toInt() and 1) != 0 || (nal[1].toInt() and 248) != 0) return CodecMetadata()
    if (!boundedHevcSyntax(nal)) return CodecMetadata()
    val sps = NalUnitUtil.parseH265SpsNalUnit(nal, 0, nal.size, null)
    val profile = sps.profileTierLevel ?: return CodecMetadata()
    return CodecMetadata(
      profile = MediaFact.Known(when (if (profile.generalProfileSpace == 0) profile.generalProfileIdc else -1) {
        1 -> VideoProfile.HevcMain
        2 -> VideoProfile.HevcMain10
        else -> VideoProfile.Other
      }),
      pixelFormat = pixels(sps.bitDepthLumaMinus8, sps.bitDepthChromaMinus8, sps.chromaFormatIdc),
      color = color(sps.colorSpace, sps.colorTransfer, sps.colorRange),
      pixelAspectRatio = positive(sps.pixelWidthHeightRatio.toDouble()),
    )
  }

  /** Bound allocation-driving SPS fields before calling the shared parser. */
  private fun boundedHevcSyntax(nal: ByteArray): Boolean {
    val bits = ParsableNalUnitBitArray(nal, 2, nal.size)
    bits.skipBits(4)
    val layers = bits.readBits(3)
    if (layers > 6) return false
    bits.skipBit()
    bits.skipBits(96) // general profile/tier/level
    val profilePresent = BooleanArray(layers)
    val levelPresent = BooleanArray(layers)
    for (i in 0 until layers) {
      profilePresent[i] = bits.readBit()
      levelPresent[i] = bits.readBit()
    }
    if (layers > 0) bits.skipBits((8 - layers) * 2)
    for (i in 0 until layers) {
      if (profilePresent[i]) bits.skipBits(88)
      if (levelPresent[i]) bits.skipBits(8)
    }
    if (bits.readUnsignedExpGolombCodedInt() !in 0..15) return false
    val chroma = bits.readUnsignedExpGolombCodedInt()
    if (chroma !in 0..3) return false
    if (chroma == 3) bits.skipBit()
    repeat(2) { if (bits.readUnsignedExpGolombCodedInt() !in 1..65535) return false }
    if (bits.readBit()) repeat(4) { bits.readUnsignedExpGolombCodedInt() }
    repeat(2) { if (bits.readUnsignedExpGolombCodedInt() !in 0..8) return false }
    val pocBits = bits.readUnsignedExpGolombCodedInt()
    if (pocBits !in 0..12) return false
    val start = if (bits.readBit()) 0 else layers
    for (i in start..layers) repeat(3) { bits.readUnsignedExpGolombCodedInt() }
    repeat(6) { bits.readUnsignedExpGolombCodedInt() }
    if (bits.readBit() && bits.readBit()) {
      for (sizeId in 0..3) {
        for (matrixId in 0..5 step if (sizeId == 3) 3 else 1) {
          if (!bits.readBit()) bits.readUnsignedExpGolombCodedInt() else {
            if (sizeId > 1) bits.readSignedExpGolombCodedInt()
            repeat(minOf(64, 1 shl (4 + 2 * sizeId))) { bits.readSignedExpGolombCodedInt() }
          }
        }
      }
    }
    bits.skipBits(2)
    if (bits.readBit()) {
      bits.skipBits(8)
      repeat(2) { bits.readUnsignedExpGolombCodedInt() }
      bits.skipBit()
    }
    val sets = bits.readUnsignedExpGolombCodedInt()
    if (sets !in 0..64) return false
    for (i in 0 until sets) {
      // Predicted reference sets need a fuller bounded syntax reader; leave them unknown for now.
      if (i > 0 && bits.readBit()) return false
      val negative = bits.readUnsignedExpGolombCodedInt()
      val positive = bits.readUnsignedExpGolombCodedInt()
      if (negative !in 0..16 || positive !in 0..16 || negative + positive > 16) return false
      repeat(negative + positive) { bits.readUnsignedExpGolombCodedInt(); bits.skipBit() }
    }
    if (bits.readBit()) {
      val count = bits.readUnsignedExpGolombCodedInt()
      if (count !in 0..32) return false
      repeat(count) { bits.skipBits(pocBits + 5) }
    }
    return true
  }

  private fun pixels(luma: Int, chroma: Int, subsampling: Int): MediaFact<PixelFormat> =
    if (luma != chroma || luma !in 0..8 || subsampling !in 0..3) MediaFact.Unknown
    else MediaFact.Known(PixelFormat(luma + 8,
      if (subsampling == 1) ChromaSubsampling.Yuv420 else ChromaSubsampling.Other))

  private fun color(standard: Int, transfer: Int, range: Int) = ColorMetadata(
    positiveCode(standard), positiveCode(transfer), positiveCode(range))

  /** MediaExtractor supplies AVC/HEVC CSD in Annex B form, with three- or four-byte prefixes. */
  private fun annexBNalUnits(data: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i + 2 < data.size) {
      val prefix = when {
        data[i] != 0.toByte() || data[i + 1] != 0.toByte() -> 0
        data[i + 2] == 1.toByte() -> 3
        i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
        else -> 0
      }
      if (prefix > 0) { starts.add(i to prefix); i += prefix } else i++
    }
    return starts.mapIndexed { index, (start, prefix) ->
      data.copyOfRange(start + prefix, starts.getOrNull(index + 1)?.first ?: data.size)
    }
  }

  /** Channel count alone cannot prove a surround layout. PCE/extension configurations stay unknown. */
  fun aacLayout(configuration: ByteArray?, channelCount: Int?): MediaFact<AudioChannelLayout> {
    if (configuration == null || configuration.size !in 2..MAX_CONFIGURATION_BYTES) return MediaFact.Unknown
    return try {
      val bits = ParsableBitArray(configuration)
      val objectType = bits.readBits(5)
      if (objectType !in 1..4) return MediaFact.Unknown
      val frequencyIndex = bits.readBits(4)
      if (frequencyIndex == 15) {
        if (bits.readBits(24) <= 0) return MediaFact.Unknown
      } else if (frequencyIndex > 12) return MediaFact.Unknown
      val channels = bits.readBits(4)
      val expectedCount = when (channels) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5; 6 -> 6; 7 -> 8; else -> null }
        ?: return MediaFact.Unknown
      if (channelCount != null && channelCount != expectedCount) return MediaFact.Unknown
      MediaFact.Known(when (channels) {
        1 -> AudioChannelLayout.Mono
        2 -> AudioChannelLayout.Stereo
        6 -> AudioChannelLayout.FivePointOne
        else -> AudioChannelLayout.Other
      })
    } catch (_: RuntimeException) { MediaFact.Unknown }
  }
}

internal fun positive(value: Double?): MediaFact<Double> =
  if (value != null && value.isFinite() && value > 0) MediaFact.Known(value) else MediaFact.Unknown

internal fun positiveCode(value: Int?): MediaFact<Int> =
  if (value != null && value > 0) MediaFact.Known(value) else MediaFact.Unknown

/** Missing evidence may be filled; contradictory evidence stays unknown. */
internal fun <T> reconcile(a: MediaFact<T>, b: MediaFact<T>): MediaFact<T> = when {
  a == MediaFact.Unknown -> b
  b == MediaFact.Unknown -> a
  a == b -> a
  else -> MediaFact.Unknown
}
