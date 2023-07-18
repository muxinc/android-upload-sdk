package com.mux.video.upload.internal

import android.content.Context
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.os.Build
import androidx.annotation.RequiresApi
import com.mux.video.upload.MuxUploadSdk
import io.github.crow_misia.libyuv.FilterMode
import io.github.crow_misia.libyuv.Nv12Buffer
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import kotlin.experimental.and


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class TranscoderContext private constructor(
    private var uploadInfo: UploadInfo,
    private val appContext: Context
) {
    private val logger get() = MuxUploadSdk.logger

    val MAX_ALLOWED_BITRATE = 8000000
    val MAX_ALLOWED_FRAMERATE = 120;
    val MAX_ALLOWED_WIDTH = 1920
    val MAX_ALLOWED_HEIGTH = 1080
    val OPTIMAL_FRAMERATE = 30
    val I_FRAME_INTERVAL = 5 // in seconds

    private val extractor: MediaExtractor = MediaExtractor()
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -10
    private var audioTrackIndex = -10
    private var outputVideoTrackIndex = -1
    private var outputAudioTrackIndex = -1
    // Used to configure decoders
    private var inputAudioFormat: MediaFormat? = null
    private var inputVideoFormat: MediaFormat? = null

    // Used to configure encoders
    private var outputAudioFormat: MediaFormat? = null
    private var outputVideoFormat: MediaFormat? = null

    // This is what decoder actually provide as an output, bit different then what we used to configure it
    private var videoDecoderOutputFormat: MediaFormat? = null
    private var decodedFrameWidth: Int = -1;
    private var decodedFrameHeight: Int = -1;
    private var targetedWidth = -1
    private var targetedHeight = -1
    private var targetedFramerate = -1
    private var targetedBitrate = -1
    private var scaledSizeYuv: Nv12Buffer? = null
    val audioFrames = ArrayList<AVFrame>()

    // Input parameters
    private var inputWidth = -1
    private var inputHeighth = -1
    private var inputBitrate = -1
    private var inputFramerate = -1

    // Wait indefinetly for negative value, exit imidetly on 0, or timeout after a given us+
    private var dequeueTimeout:Long = 0;
    private var eofReached: Boolean = false;
    private var transcodeAudio = false;
    private var muxerConfigured = false;
    private var numberOfDecodedFrames = 0;
    private var numberOfEncodedFrames = 0;
    private var numberOfInputFrames = -1;

    private var videoDecoder:MediaCodec? = null
    private var audioDecoder:MediaCodec? = null
    private var videoEncoder:MediaCodec? = null
    private var audioEncoder:MediaCodec? = null
    var fileTranscoded = false
    private var configured = false

    companion object {
      const val LOG_TAG = "TranscoderContext"

      @JvmSynthetic
      internal fun create(uploadInfo: UploadInfo, appContext: Context): TranscoderContext {
        return TranscoderContext(uploadInfo, appContext)
      }
    }

    private fun getHWCapableEncoders(mimeType: String): ArrayList<MediaCodecInfo> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS);
        var result:ArrayList<MediaCodecInfo> = ArrayList<MediaCodecInfo>();
        for(codecInfo in list.codecInfos) {
            logger.v("CodecInfo", codecInfo.name)
            if(codecInfo.name.contains(mimeType) && codecInfo.isEncoder && codecInfo.isHardwareAcceleratedCompat) {
                result.add(codecInfo);
            }
        }
        return result;
    }

    private fun configure() {
      val cacheDir = File(appContext.cacheDir, "mux-upload")
      cacheDir.mkdirs()
      val destFile = File(cacheDir, UUID.randomUUID().toString() + ".mp4")
      destFile.createNewFile()

      muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      uploadInfo = uploadInfo.update(standardizedFile = destFile)

      try {
        extractor.setDataSource(uploadInfo.inputFile.absolutePath)
        configureDecoders()
        configured = true
      } catch (e:Exception) {
        logger.e(LOG_TAG, "Failed to initialize.", e)
      }
    }

    private fun checkIfTranscodingIsNeeded(): Boolean {
        var shouldStandardize = false
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            var inputDuration:Long = -1;
            if (mime?.lowercase()?.contains("video") == true) {
                inputWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                inputHeighth = format.getInteger(MediaFormat.KEY_HEIGHT)
                // Check if resolution is greater then 720p
                if ((inputWidth > MAX_ALLOWED_WIDTH && inputHeighth > MAX_ALLOWED_HEIGTH)
                    || (inputHeighth > MAX_ALLOWED_WIDTH && inputWidth > MAX_ALLOWED_HEIGTH)) {
                    logger.v(LOG_TAG, "Should standardize because the size is incorrect")
                    shouldStandardize = true
                    if(inputWidth > inputHeighth) {
                        targetedWidth = MAX_ALLOWED_WIDTH
                        targetedHeight = targetedWidth * (inputHeighth / inputWidth)
                    } else {
                        targetedHeight = MAX_ALLOWED_WIDTH
                        targetedWidth = targetedHeight * (inputWidth / inputHeighth)
                    }
                } else {
                    targetedWidth = inputWidth
                    targetedHeight = inputHeighth
                }
                scaledSizeYuv = Nv12Buffer.allocate(targetedWidth, targetedHeight)

                // Check if compersion is h264
                if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    logger.v(LOG_TAG, "Should standardize because the input is not h.264")
                    shouldStandardize = true
                }
                inputBitrate = format.getIntegerCompat(MediaFormat.KEY_BIT_RATE, -1)
                inputDuration = format.getLongCompat(MediaFormat.KEY_DURATION, -1)
                if (inputBitrate == -1 && inputDuration != -1L) {
                    inputBitrate = ((uploadInfo.inputFile.length() * 8) / (inputDuration / 1000000)).toInt()
                }
                if (inputBitrate > MAX_ALLOWED_BITRATE) {
                    logger.v(LOG_TAG, "Should standardize because the input bitrate is too high")
                    shouldStandardize = true
                    targetedBitrate = MAX_ALLOWED_BITRATE
                }
                inputFramerate = format.getIntegerCompat(MediaFormat.KEY_FRAME_RATE, -1)
                if (inputFramerate > MAX_ALLOWED_FRAMERATE) {
                  logger.v(LOG_TAG, "Should standardize because the input frame rate is too high")
                    shouldStandardize = true
                    targetedFramerate = OPTIMAL_FRAMERATE
                } else {
                    targetedFramerate = inputFramerate
                }
                videoTrackIndex = i;
                inputVideoFormat = format;
                extractor.selectTrack(i)
            }
            if (mime?.lowercase()?.contains("audio") == true) {
                // TODO check if audio need to be standardized
                audioTrackIndex = i;
                inputAudioFormat = format;
                extractor.selectTrack(i)
            }
        }

        return shouldStandardize
    }

    private fun configureDecoders() {
        // Init decoders and encoders
        numberOfInputFrames = inputVideoFormat!!.getIntegerCompat("frame-count", -1)
        videoDecoder =
            MediaCodec.createDecoderByType(inputVideoFormat!!.getString(MediaFormat.KEY_MIME)!!)
        videoDecoder!!.configure(inputVideoFormat, null, null, 0)
        videoDecoder!!.start()
        if (transcodeAudio) {
            audioDecoder =
                MediaCodec.createDecoderByType(inputAudioFormat!!.getString(MediaFormat.KEY_MIME)!!)
            audioDecoder!!.configure(inputAudioFormat, null, null, 0)
            audioDecoder!!.start()
        }
    }

    private fun configureEncoders() {
        // We will need this when we apply the image resize
        decodedFrameWidth = videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_WIDTH)
        decodedFrameHeight = videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_HEIGHT)

        outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetedWidth, targetedHeight)
        // This is NV12 actually
        outputVideoFormat!!.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            COLOR_FormatYUV420SemiPlanar)

        outputVideoFormat!!.setInteger(MediaFormat.KEY_ROTATION, inputVideoFormat!!.getInteger(MediaFormat.KEY_ROTATION))
        outputVideoFormat!!.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            targetedFramerate
        )
        outputVideoFormat!!.setInteger("slice-height", targetedHeight + targetedHeight/2);
        outputVideoFormat!!.setInteger("stride", targetedWidth);
        outputVideoFormat!!.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        outputVideoFormat!!.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        outputVideoFormat!!.setInteger(MediaFormat.KEY_BIT_RATE, targetedBitrate)
        // configure output audio format, if input format is already AAC, then just do copy
        transcodeAudio = !inputAudioFormat!!.getString(MediaFormat.KEY_MIME)?.contains(MediaFormat.MIMETYPE_AUDIO_AAC)!!
        if (transcodeAudio) {
            outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            outputAudioFormat!!.setString(MediaFormat.KEY_AAC_PROFILE, "2")
            outputAudioFormat!!.setString(MediaFormat.KEY_PROFILE, "2")
        } else {
            outputAudioFormat = inputAudioFormat
        }

        val encoders = getHWCapableEncoders("avc")
        for (encoder in encoders) {
            try {
                val codecCap = encoder.getCapabilitiesForType("video/avc")
                for  (profile in codecCap.profileLevels ) {
                    if (profile.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ) {
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_PROFILE, profile.profile);
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_LEVEL, profile.level)
                        break
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    outputVideoFormat!!.setInteger(MediaFormat.KEY_QUALITY, codecCap.encoderCapabilities.qualityRange.upper)
                }
                videoEncoder = MediaCodec.createByCodecName(encoder.name)
                // Check if B-frame encoding is supported
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (codecCap.isFeatureSupported("FEATURE_B_FRAME")) {
                        // Enable B-frames by setting the appropriate parameter
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2)
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_OUTPUT_REORDER_DEPTH, 2)
                    }
                }
                videoEncoder!!.configure(outputVideoFormat,null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                break;
            } catch (err:java.lang.Exception) {
              logger.w(LOG_TAG, "Couldn't evaluate encoder ${encoder.name}. Skipping it", err)
            }
        }
        videoEncoder!!.start()
        if (transcodeAudio) {
            audioEncoder =
                MediaCodec.createEncoderByType(outputAudioFormat!!.getString(MediaFormat.KEY_MIME)!!)
            audioEncoder!!.configure(outputAudioFormat, null, null, 0)
            audioEncoder!!.start()
        }
    }

    private fun releaseCodecs() {
        logger.v(LOG_TAG, "releaseCodecs(): called")

        videoDecoder?.stop()
        videoDecoder?.release()
        videoEncoder?.stop()
        videoEncoder?.release()

        audioDecoder?.apply {
          stop()
          release()
        }
        audioEncoder?.apply {
          stop()
          release()
        }
    }

    private fun configureMuxer() {
        outputVideoTrackIndex = muxer!!.addTrack(videoEncoder!!.outputFormat)
        muxer!!.setOrientationHint(inputVideoFormat!!.getInteger(MediaFormat.KEY_ROTATION))
        if (inputAudioFormat != null) {
            outputAudioTrackIndex = muxer!!.addTrack(outputAudioFormat!!)
        }
        muxer!!.start()
    }

    @JvmSynthetic
    internal fun process(): UploadInfo {
        logger.v(LOG_TAG, "process() starting")
        if (!checkIfTranscodingIsNeeded()) {
          logger.i(LOG_TAG, "Standardization was not required. Skipping")
          return uploadInfo
        }

        logger.i(LOG_TAG, "Standardizing input")
        configure()
        if (!configured) {
            logger.e(
              LOG_TAG,
              "Skipped: Components could not be configured. Check the logs for errors"
            )
            return uploadInfo;
        }

        val started = System.currentTimeMillis()
        try {
            extractor.selectTrack(videoTrackIndex)
            while (!eofReached) {
                if (extractor.sampleTrackIndex == audioTrackIndex) {
                    muxAudioFrame()
                } else {
                    muxVideoFrame()
                }
            }
            // Get queued frames from encoder/decoder when we reach EOF
            videoDecoder!!.flush()
            videoEncoder!!.flush()
            muxVideoFrame()
            releaseCodecs()
        } catch (err:Exception) {
            logger.e(LOG_TAG, "Failed to standardize input file ${uploadInfo.inputFile}", err)
        }
        val duration = System.currentTimeMillis() - started
        try {
            muxer!!.stop()
            muxer!!.release()
            fileTranscoded = true

            logger.i(LOG_TAG, "Transcoding duration time: $duration")
            logger.i(LOG_TAG, "Original file size: ${uploadInfo.inputFile.length()}")
            logger.i(LOG_TAG, "Transcoded file size: ${uploadInfo.standardizedFile?.length()}")
        } catch (ex:Exception) {
          // todo em - we might be able to slide by with a success as long as stop() completes
          logger.e(LOG_TAG, "Couldn't stop the MediaMuxer!", ex)
        }

        return uploadInfo
    }

    private fun muxVideoFrame() {
        val frames = getVideoFrames()
        for (frame in frames) {
//            if (frame.isBFrame()) {
//                Log.i("Muxer", "We got B frame");
//            }
            if (frame.isKeyFrame()) {
                logger.i(
                    "Muxer", "Muxed video sample, size: " + frame.info.size
                            + ", pts: " + frame.info.presentationTimeUs
                )
            }
            muxer!!.writeSampleData(outputVideoTrackIndex, frame.buff, frame.info)
        }
    }

    private fun muxAudioFrame() {
        val audioFrame = getNextAudioFrame()
        // This is an audio frame, for now just copy, in the future, transcode maybe
        if (outputAudioTrackIndex == -1) {
            // Muxer not initialized yet, store these and mux later
//            Log.i(
//                "Muxer", "Not ready, save audio frame for later muxing, pts: "
//                        + audioFrame!!.info.presentationTimeUs
//            )
            audioFrames.add(audioFrame!!)
        } else {
            // if we have some accumulated audio samples write them first
            for (audioFrame in audioFrames) {
//                Log.i(
//                    "Muxer", "Muxing accumulated audio frame, pts: "
//                            + audioFrame.info.presentationTimeUs
//                )
                muxAudioFrame(audioFrame)
            }
            audioFrames.clear()
            muxAudioFrame(audioFrame!!)
        }
    }

    private fun muxAudioFrame(frame:AVFrame) {
        muxer!!.writeSampleData(
            outputAudioTrackIndex,
            frame.buff,
            frame.info
        )
//        Log.i(
//            "Muxer", "Muxed audio sample, size: " + frame.info.size
//                    + ", pts: " + frame.info.presentationTimeUs
//        )
    }

    private fun getVideoFrames() : ArrayList<AVFrame> {
        // TODO if EOF is reached maybe call flush on decoder and encoder some frames may still be in there
        if (!eofReached) {
            // This will advance the extractor
            feedVideoDecoder()
        }
        val decodedFrames = getDecodedVideoFrame()
        for (decoded in decodedFrames ) {
            feedVideoEncoder(decoded);
            decoded.release()
        }
        return getEncodedVideoFrames()
    }

    private fun getNextAudioFrame(): AVFrame? {
        val extractorBuffer:ByteBuffer = ByteBuffer.allocate(1024)
        val extractedFrame = AVFrame(-1, extractorBuffer, BufferInfo(), isRaw = false)
        val sampleSize = extractor.readSampleData(extractorBuffer, 0);
        if (sampleSize == -1) {
            eofReached = true;
            // TODO fuls encoders / decoders
            return null;
        } else {
            extractedFrame.info.size = sampleSize
            extractedFrame.info.presentationTimeUs = extractor.sampleTime
            extractor.advance()
        }
        return extractedFrame;
    }

    private fun feedVideoDecoder() {
        val inIndex: Int = videoDecoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = videoDecoder!!.getInputBuffer(inIndex)!!;
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                // We have reached the end of video
                eofReached = true;
//                return null
            } else {
                videoDecoder!!.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
                numberOfInputFrames++
            }
        }
    }

    private fun getDecodedVideoFrame():ArrayList<AVFrame> {
        var info = BufferInfo()
        var outputBuffer:ByteBuffer? = null
        val result = ArrayList<AVFrame>()
        var outIndex = videoDecoder!!.dequeueOutputBuffer(info, dequeueTimeout);
        while(outIndex > 0) {
            outputBuffer = videoDecoder!!.getOutputBuffer(outIndex);
            numberOfDecodedFrames++;
            result.add(AVFrame(
                outIndex, outputBuffer!!, info, decodedFrameWidth, decodedFrameHeight,
                videoDecoder!!, true
            ))
            numberOfDecodedFrames++
            outIndex = videoDecoder!!.dequeueOutputBuffer(info, dequeueTimeout);
        }
        when (outIndex) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // This give us real image height, to avoid corruptions in video
                videoDecoderOutputFormat = videoDecoder!!.outputFormat;
                configureEncoders()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // Timedout also not good
            }
        }
        return result
    }

    private fun findAnnexBPosition(buff:ByteBuffer, startSearchAt:Int, buffSize:Int): Int {
        // We are assuming integer is 4 bytes on every device, we also assume anexB is 4 bytes long
        // instead of 3 which is also possible sometimes
        for(i in startSearchAt..buffSize - 4) {
            if (buff.getInt(i) == 1) {
                return i;
            }
        }
        return -1
    }

    private fun convertAnnexBtoAvcc(buff:ByteBuffer, buffSize:Int) {
        val positions = ArrayList<Int>()
        var annexBPos = findAnnexBPosition(buff, 0, buffSize)
        while (annexBPos != -1) {
            positions.add(annexBPos)
            annexBPos = findAnnexBPosition(buff, annexBPos + 4, buffSize)
        }
        for (i in 0..positions.size -1) {
            var naluLength = 0
            if (i == positions.size -1) {
                // This is the last position
                naluLength =  buffSize - positions.get(i) - 4
            } else {
                naluLength = positions.get(i + 1) - positions.get(i) -4;
            }
            buff.position(positions.get(i))
            buff.putInt(naluLength)
        }
    }

    private fun feedVideoEncoder(rawInput:AVFrame) {
        val inIndex: Int = videoEncoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            // Scale input to match output
            rawInput.yuvBuffer!!.scale(scaledSizeYuv!!, FilterMode.BILINEAR)
            val buffer: ByteBuffer = videoEncoder!!.getInputBuffer(inIndex)!!;
            buffer.clear()
            scaledSizeYuv!!.write(buffer)
            videoEncoder!!.queueInputBuffer(inIndex, 0,
                buffer.capacity(), rawInput.info.presentationTimeUs, 0)
        }
    }

    fun getEncodedVideoFrames():ArrayList<AVFrame> {
        val result = ArrayList<AVFrame>()
        if (videoEncoder == null) {
            return result;
        }
        var info = BufferInfo()
        var outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        var outputBuffer:ByteBuffer?
        while (outIndex >= 0) {
            if (!muxerConfigured) {
                configureMuxer()
                muxerConfigured = true;
            }
            outputBuffer = videoEncoder!!.getOutputBuffer(outIndex)
            val buff = ByteBuffer.allocate(info.size)
            outputBuffer!!.get(buff.array(), 0, info.size)
            result.add(AVFrame(outIndex, buff, info, 0, 0,
                videoEncoder, true, false))
            numberOfEncodedFrames++
            videoEncoder!!.releaseOutputBuffer(outIndex, false)
            info = BufferInfo()
            outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        }
        return result;
    }

    private fun encodeVideoFrame(rawInput:AVFrame): AVFrame? {
        val result:AVFrame? = null;
        val inIndex: Int = videoEncoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            // Scale input to match output
            rawInput.yuvBuffer!!.scale(scaledSizeYuv!!, FilterMode.BILINEAR)
            val buffer: ByteBuffer = videoEncoder!!.getInputBuffer(inIndex)!!;
            buffer.clear()
            scaledSizeYuv!!.write(buffer)
            videoEncoder!!.queueInputBuffer(inIndex, 0,
                buffer.capacity(), rawInput.info.presentationTimeUs, 0)
        }

        var info = BufferInfo()
        var outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        var outputBuffer:ByteBuffer?
        val encodedBuffers:ArrayList<AVFrame> = ArrayList<AVFrame>()
        var totalBufferSize:Int = 0
        while (outIndex >= 0) {
            outputBuffer = videoEncoder!!.getOutputBuffer(outIndex)
            totalBufferSize += info.size
            encodedBuffers.add(AVFrame(outIndex, outputBuffer!!, info, 0, 0,
                videoEncoder, true, false))
            numberOfEncodedFrames++
            info = BufferInfo()
            outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        }
        if (encodedBuffers.size > 0) {
            val outputBuffer = ByteBuffer.allocate(totalBufferSize)
            var offset = 0
            val info = BufferInfo()
            for (frame in encodedBuffers) {
                // TODO maybe convert annexB to avcc, pay attention, sps and pps are in single buffer
//                frame.buff.position(4)
                frame.buff.get(outputBuffer.array(), offset, frame.info.size)
                offset += frame.info.size
                info.flags = info.flags or frame.info.flags
                info.presentationTimeUs = frame.info.presentationTimeUs
                frame.release()
            }

            info.offset = 0
            info.size = totalBufferSize
            return AVFrame(outIndex, outputBuffer, info, targetedWidth,
                targetedHeight, videoEncoder!!, false, false)
        }
        return result;
    }

    class AVFrame constructor(val index:Int, val buff:ByteBuffer, val info:BufferInfo,
                              val width:Int = 0, val heigth:Int = 0,
                              val codec:MediaCodec? = null, val shouldRelease:Boolean = true, val isRaw:Boolean = true){

        // TODO support other color formats, NV12 is default decoder format for AVC
        var yuvBuffer:Nv12Buffer? = null;

        init {
            if (isRaw) {
                yuvBuffer = Nv12Buffer.wrap(buff, width, heigth)
            }
        }

        fun release() {
            if (shouldRelease) {
                codec?.releaseOutputBuffer(index, false);
            }
        }

        fun getNalType(): Int {
            return (buff.get(4) and 0x1F).toInt()
        }

        fun isBFrame(): Boolean {
            val nalType = getNalType()
            return (nalType == 2 || nalType == 3 || nalType == 4)
        }

        fun isKeyFrame(): Boolean {
            val nalType = getNalType()
            // Sometimes key frame is packed with pps and sps
            return (nalType == 5 || nalType == 7 || nalType == 8)
        }

        fun clone():AVFrame {
            val buffCopy = ByteBuffer.allocate(info.size)
            buffCopy.get(buff.array(), 0, info.size)
            val infoCopy = BufferInfo()
            infoCopy.size = info.size
            infoCopy.offset = info.offset
            infoCopy.presentationTimeUs = info.presentationTimeUs
            infoCopy.flags = info.flags
            return AVFrame(index, buffCopy, infoCopy, width, heigth, codec, shouldRelease, isRaw)
        }
    }
}
