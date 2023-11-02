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
import org.json.JSONArray
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.experimental.and


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class TranscoderContext private constructor(
    private var uploadInfo: UploadInfo,
    private val appContext: Context,
    private val sessionId: String
) {
    private val logger get() = MuxUploadSdk.logger

    val MAX_ALLOWED_BITRATE = 8000000
    val MAX_ALLOWED_FRAMERATE = 120;
    val MAX_ALLOWED_WIDTH = 1920
    val MAX_ALLOWED_HEIGTH = 1080
    val OPTIMAL_FRAMERATE = 30
    val I_FRAME_INTERVAL = 5 // in seconds
    val OUTPUT_SAMPLERATE = 48000
    val OUTPUT_NUMBER_OF_CHANNELS = 2
    val OUTPUT_AUDIO_BITRATE = 96000

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
    private var audioDecoderOutputFormat: MediaFormat? = null
    private var decodedFrameWidth: Int = -1;
    private var decodedFrameHeight: Int = -1;
    private var targetedWidth = -1
    private var targetedHeight = -1
    private var targetedFramerate = -1
    private var targetedBitrate = -1
    private var scaledSizeYuv: Nv12Buffer? = null
    private var resampleCreated = false
    private var resample: Resample = Resample()
    private val audioFrames = ArrayList<AVFrame>()

    // Input parameters
    private var inputWidth = -1
    private var inputHeighth = -1
    private var inputBitrate = -1
    private var inputFramerate = -1
    private var inputChannelCount = -1
    private var inputSamplerate = -1

    // Wait indefinetly for negative value, exit imidetly on 0, or timeout after a given us+
    private var dequeueTimeout:Long = 0;
    private var eofReached: Boolean = false;
    private var transcodeAudio = false;
    private var muxerConfigured = false;
    private var numberOfDecodedFrames = 0;
    private var numberOfEncodedFrames = 0;
    private var numberOfDecodedSamples = 0;
    private var numberOfEncodedSamples = 0;
    private var numberOfInputFrames = -1;
    private var numberOfLostAudioFrames = 0;

    private var videoDecoder:MediaCodec? = null
    private var audioDecoder:MediaCodec? = null
    private var videoEncoder:MediaCodec? = null
    private var audioEncoder:MediaCodec? = null
    private var videoOutputStream:OutputStream? = null;
    private var audioOutputStream:OutputStream? = null;
    private var rawAudioOutputStream:OutputStream? = null;
    private var resampledAudioOutputStream:OutputStream? = null;
    var fileTranscoded = false
    private var configured = false
    private var nonStandardInputReasons:JSONArray = JSONArray()
    private var inputFileDurationMs:Long = 0 // Ms
    private var errorDescription = ""

    companion object {
      const val LOG_TAG = "TranscoderContext"

      @JvmSynthetic
      internal fun create(uploadInfo: UploadInfo, appContext: Context, sessionId: String): TranscoderContext {
        return TranscoderContext(uploadInfo, appContext, sessionId)
      }
    }

    private fun getEncoders(mimeType: String, hwCapableOnly:Boolean): ArrayList<MediaCodecInfo> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS);
        var result:ArrayList<MediaCodecInfo> = ArrayList<MediaCodecInfo>();
        for(codecInfo in list.codecInfos) {
            logger.v("CodecInfo", codecInfo.name)
            if(codecInfo.name.contains(mimeType) && codecInfo.isEncoder) {
                if (!hwCapableOnly) {
                    result.add(codecInfo);
                } else if (codecInfo.isHardwareAcceleratedCompat) {
                    result.add(codecInfo);
                }
            }
        }
        return result
    }

    private fun configure() {
      val cacheDir = File(appContext.cacheDir, "mux-upload")
//        val cacheDir = File(appContext.externalCacheDir, "mux-upload")
      cacheDir.mkdirs()

//        val videoOutput = File(cacheDir,  "video.h264")
//        videoOutputStream = videoOutput.outputStream()
//
//        val audioOutput = File(cacheDir,  "audio.aac")
//        audioOutputStream = audioOutput.outputStream()
//
//        val rawAudioOutput = File(cacheDir,  "audio_original.raw")
//        rawAudioOutputStream = rawAudioOutput.outputStream()
//
//        val resampledAudioOutput = File(cacheDir,  "audio_resampled.raw")
//        resampledAudioOutputStream = resampledAudioOutput.outputStream()

        val destFile = File(cacheDir, UUID.randomUUID().toString() + ".mp4")
//        val destFile = File(cacheDir,  "output.mp4")
        destFile.createNewFile()

        muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        uploadInfo = uploadInfo.createUpdated(standardizedFile = destFile)

        try {
            configureDecoders()
            configureAudioEncoder()
            configured = true
        } catch (e:Exception) {
            logger.e(LOG_TAG, "Failed to initialize.", e)
        }
    }

    private fun checkIfTranscodingIsNeeded(): Boolean {
        var shouldStandardize = false
        try {
            extractor.setDataSource(uploadInfo.inputFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                var inputDuration: Long = -1;
                if (mime?.lowercase()?.contains("video") == true) {
                    inputWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    inputHeighth = format.getInteger(MediaFormat.KEY_HEIGHT)
                    // Check if resolution is greater then 720p
                    if ((inputWidth > MAX_ALLOWED_WIDTH && inputHeighth > MAX_ALLOWED_HEIGTH)
                        || (inputHeighth > MAX_ALLOWED_WIDTH && inputWidth > MAX_ALLOWED_HEIGTH)
                    ) {
                        logger.v(LOG_TAG, "Should standardize because the size is incorrect")
                        shouldStandardize = true
                        if (inputWidth > inputHeighth) {
                            targetedWidth = MAX_ALLOWED_WIDTH
                            targetedHeight = targetedWidth * (inputHeighth / inputWidth)
                        } else {
                            targetedHeight = MAX_ALLOWED_WIDTH
                            targetedWidth = targetedHeight * (inputWidth / inputHeighth)
                        }
                        nonStandardInputReasons.put("video_resolution")
                    } else {
                        targetedWidth = inputWidth
                        targetedHeight = inputHeighth
                    }
                    scaledSizeYuv = Nv12Buffer.allocate(targetedWidth, targetedHeight)

                    // Check if compersion is h264
                    if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        logger.v(LOG_TAG, "Should standardize because the input is not h.264")
                        shouldStandardize = true
                        nonStandardInputReasons.put("video_codec")
                    }
                    inputBitrate = format.getIntegerCompat(MediaFormat.KEY_BIT_RATE, -1)
                    inputDuration = format.getLongCompat(MediaFormat.KEY_DURATION, -1)
                    if (inputBitrate == -1 && inputDuration != -1L) {
                        inputBitrate =
                            ((uploadInfo.inputFile.length() * 8) / (inputDuration / 1000000)).toInt()
                    }
                    if (inputBitrate > MAX_ALLOWED_BITRATE) {
                        logger.v(
                            LOG_TAG,
                            "Should standardize because the input bitrate is too high"
                        )
                        shouldStandardize = true
                        targetedBitrate = MAX_ALLOWED_BITRATE
                        nonStandardInputReasons.put("video_bitrate")
                    }
                    inputFramerate = format.getIntegerCompat(MediaFormat.KEY_FRAME_RATE, -1)
                    targetedFramerate = OPTIMAL_FRAMERATE
                    if (inputFramerate > MAX_ALLOWED_FRAMERATE) {
                        logger.v(
                            LOG_TAG,
                            "Should standardize because the input frame rate is too high"
                        )
                        shouldStandardize = true
                        targetedFramerate = OPTIMAL_FRAMERATE
                        nonStandardInputReasons.put("video_framerate")
                    } else {
                        targetedFramerate = inputFramerate
                    }
                    videoTrackIndex = i;
                    inputVideoFormat = format;
                    extractor.selectTrack(i)
                }
                if (mime?.lowercase()?.contains("audio") == true) {
                    //  check if audio need to be standardized
                    audioTrackIndex = i;
                    inputAudioFormat = format;
                    extractor.selectTrack(i)
                    inputChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    inputSamplerate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (inputChannelCount > 2) {
                        // We do not support this
                        transcodeAudio = false
                    } else {
                        if (!mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC)) {
                            transcodeAudio = true;
                        }
//                        if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 48000) {
//                            transcodeAudio = true;
//                        }
                    }
                }
            }
        } catch (ex:Exception) {
          logger.e(
            LOG_TAG,
            "Couldn't completely inspect input. Will standardize? $shouldStandardize",
            ex
          )
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

    private fun configureAudioEncoder() {
        if (transcodeAudio) {
            outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                inputSamplerate, inputChannelCount)
            outputAudioFormat!!.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            outputAudioFormat!!.setInteger(MediaFormat.KEY_PROFILE, 2)
            outputAudioFormat!!.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
            outputAudioFormat!!.setInteger("max-bitrate", OUTPUT_AUDIO_BITRATE)
            outputAudioFormat!!.setInteger("aac-format-adif", 0)
            val audioEncoders = getEncoders("aac", false)
            for (encoder in audioEncoders) {
                try {
                    // TODO see the codec capabileties
                    val codecCap = encoder.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder = MediaCodec.createByCodecName(encoder.name)
                    audioEncoder!!.configure(outputAudioFormat,null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioEncoder!!.start()
                    break
                } catch (err:java.lang.Exception) {
                    logger.w(LOG_TAG, "Couldn't evaluate audio encoder ${encoder.name}. Skipping it", err)
                }
            }
        } else {
            outputAudioFormat = inputAudioFormat
        }
    }

    private fun configureVideoEncoder() {
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
        outputVideoFormat!!.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        outputVideoFormat!!.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        outputVideoFormat!!.setInteger(MediaFormat.KEY_BIT_RATE, targetedBitrate)


        val encoders = getEncoders("avc", true)
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
                logger.w(LOG_TAG, "Couldn't evaluate video encoder ${encoder.name}. Skipping it", err)
            }
        }
        videoEncoder!!.start()
    }

    private fun releaseCodecs() {
        logger.v(LOG_TAG, "releaseCodecs(): called")
        videoDecoder?.safeDispose()
        audioDecoder?.safeDispose()
        videoEncoder?.safeDispose()
        audioEncoder?.safeDispose()
        extractor.safeDispose()
        muxer?.safeDispose()
    }

    private fun configureMuxer() {
        outputVideoTrackIndex = muxer!!.addTrack(videoEncoder!!.outputFormat)
        muxer!!.setOrientationHint(inputVideoFormat!!.getInteger(MediaFormat.KEY_ROTATION))
        if (transcodeAudio) {
            outputAudioTrackIndex = muxer!!.addTrack(audioEncoder!!.outputFormat)
        } else {
            // Audio copy if present
            if (inputAudioFormat != null ){
                outputAudioTrackIndex = muxer!!.addTrack(inputAudioFormat!!)
            }
        }

        muxer!!.start()
    }

    @JvmSynthetic
    internal suspend fun process(): UploadInfo {
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
        val metrics = UploadMetrics.create()
        var maxStandardInputRes = ""
        try {
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
            muxer!!.stop()
            fileTranscoded = true
        } catch (err:Exception) {
            errorDescription += err.localizedMessage
            logger.e(LOG_TAG, "Failed to standardize input file ${uploadInfo.inputFile}", err)
        } finally {
            releaseCodecs()
        }
        val ended = System.currentTimeMillis();
        val duration = ended - started
        logger.i(LOG_TAG, "Transcoding duration time: $duration")
        logger.i(LOG_TAG, "Original file size: ${uploadInfo.inputFile.length()}")
        logger.i(LOG_TAG, "Transcoded file size: ${uploadInfo.standardizedFile?.length()}")
        maxStandardInputRes = (MAX_ALLOWED_WIDTH / MAX_ALLOWED_HEIGTH).toString()
        if (fileTranscoded && uploadInfo.optOut) {
            metrics.reportStandardizationSuccess(started, ended, inputFileDurationMs,
                nonStandardInputReasons, maxStandardInputRes, sessionId, uploadInfo)
        } else if(uploadInfo.optOut) {
            metrics.reportStandardizationFailed(started, ended, inputFileDurationMs,
                errorDescription, nonStandardInputReasons, maxStandardInputRes, sessionId, uploadInfo)
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
            inputFileDurationMs = frame.info.presentationTimeUs * 1000;
            muxer!!.writeSampleData(outputVideoTrackIndex, frame.buff, frame.info)
        }
    }

    private fun muxAudioFrame() {
        if (transcodeAudio) {
            if (!eofReached) {
                // This will advance the extractor
                feedAudioDecoder()
            }
            val decodedFrames = getDecodedAudioFrame()
            for (decoded in decodedFrames ) {
                feedAudioEncoder(decoded)
                decoded.release()
            }
            // iterate encoded audio frames and mux them
            val encodedAudioFrames = getEncodedAudioFrames()
            for(frame:AVFrame in encodedAudioFrames) {
                if (outputAudioTrackIndex == -1) {
                    // Muxer not initialized yet, store these and mux later
                    audioFrames.add(frame)
                } else {
                    // if we have some accumulated audio samples write them first
                    for (queuedFrame in audioFrames) {
                        muxAudioFrame(queuedFrame)
                    }
                    audioFrames.clear()
                    muxAudioFrame(frame)
                }
            }
        } else {
            copyAudioFrame();
        }
    }

    private fun muxAudioFrame(frame:AVFrame) {
        inputFileDurationMs = frame.info.presentationTimeUs * 1000;
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
                configureVideoEncoder()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // Timedout also not good
            }
        }
        return result
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

    /////////////////////////////////////////////////////////////////////////////////////////////
    ///////////// Audio ////////////////////////////////////////////////////////////////////////

    private fun copyAudioFrame() {
        val audioFrame = getNextAudioFrame()
        if (outputAudioTrackIndex == -1) {
            // Muxer not initialized yet, store these and mux later
            audioFrames.add(audioFrame!!)
        } else {
            // if we have some accumulated audio samples write them first
            for (audioFrame in audioFrames) {
                muxAudioFrame(audioFrame)
            }
            audioFrames.clear()
            muxAudioFrame(audioFrame!!)
        }
    }

    private fun getNextAudioFrame(): AVFrame? {
        val extractorBuffer:ByteBuffer = ByteBuffer.allocate(1024)
        val extractedFrame = AVFrame(-1, extractorBuffer, BufferInfo(), isRaw = false)
        val sampleSize = extractor.readSampleData(extractorBuffer, 0);
        if (sampleSize == -1) {
            eofReached = true;
            return null;
        } else {
            extractedFrame.info.size = sampleSize
            extractedFrame.info.presentationTimeUs = extractor.sampleTime
            extractor.advance()
        }
        return extractedFrame;
    }

    private fun feedAudioDecoder() {
        val inIndex: Int = audioDecoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = audioDecoder!!.getInputBuffer(inIndex)!!;
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                // We have reached the end of video
                eofReached = true;
            } else {
                audioDecoder!!.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
    }

    private fun getDecodedAudioFrame():ArrayList<AVFrame> {
        var info = BufferInfo()
        var outputBuffer:ByteBuffer? = null
        val result = ArrayList<AVFrame>()
        var outIndex = audioDecoder!!.dequeueOutputBuffer(info, dequeueTimeout);
        while(outIndex > 0) {
            outputBuffer = audioDecoder!!.getOutputBuffer(outIndex);
            result.add(AVFrame(
                outIndex, outputBuffer!!, info, 0, 0,
                audioDecoder!!, true
            ))
            numberOfDecodedSamples++
            outIndex = audioDecoder!!.dequeueOutputBuffer(info, dequeueTimeout);
        }
        when (outIndex) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // Not sure what to do here
                audioDecoderOutputFormat = audioDecoder!!.outputFormat;
//                configureEncoders()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // Timedout also not good
            }
        }
        return result
    }

    private fun feedAudioEncoder(rawInput:AVFrame) {
        rawInput.buff.rewind()
        // Resampling not working as expected, we need a better solution
//        var tmp:ByteBuffer = ByteBuffer.allocate(rawInput.info.size)
//        tmp.put(rawInput.buff)
//        rawInput.buff.rewind()
//        tmp.rewind()
//        rawAudioOutputStream!!.write(tmp.array(), 0, tmp.remaining())
//        tmp.rewind()
//        val resampled = resample.resample(tmp.array(), rawInput.info.size, true, inputSamplerate, OUTPUT_SAMPLERATE)
////            val output_len = resample.resampleEx(rawInput.buff, buffer, rawInput.buff.remaining())
//        if (resampled == null) {
//            logger.i(LOG_TAG, "It is a problem :-D")
//        }
//        resampledAudioOutputStream!!.write(resampled, 0, resampled!!.size)
//        var bytesQueued = 0;

//        while (bytesQueued < resampled!!.size) {
            val inIndex: Int = audioEncoder!!.dequeueInputBuffer(dequeueTimeout)
            if (inIndex >= 0) {
                // resample input to match output
                val buffer: ByteBuffer = audioEncoder!!.getInputBuffer(inIndex)!!
                buffer.rewind()
//                val remaining = resampled!!.size - bytesQueued
//                var toWrite =  buffer.capacity()
//                if (remaining < buffer.capacity()) {
//                    toWrite = remaining
//                }
                buffer.put(rawInput.buff)
                buffer.rewind()
                audioEncoder!!.queueInputBuffer(
                    inIndex, 0,
                    buffer.remaining(), rawInput.info.presentationTimeUs, 0
                )
//                bytesQueued+=toWrite
            } else {
                logger.e(LOG_TAG, "We lost audio frame :-D")
                numberOfLostAudioFrames++;
            }
//        }
    }

    private fun getEncodedAudioFrames():ArrayList<AVFrame> {
        val result = ArrayList<AVFrame>()
        if (audioEncoder == null) {
            return result;
        }
        var info = BufferInfo()
        var outIndex = audioEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        var outputBuffer:ByteBuffer?
        while (outIndex >= 0) {
            if (!muxerConfigured) {
                // TODO maybe note that audio is ready to be configured in a muxer
            }
            outputBuffer = audioEncoder!!.getOutputBuffer(outIndex)
            val buff = ByteBuffer.allocate(info.size)
            outputBuffer!!.get(buff.array(), 0, info.size)
            result.add(AVFrame(outIndex, buff, info, 0, 0,
                audioEncoder, true, false))
            numberOfEncodedSamples++
            audioEncoder!!.releaseOutputBuffer(outIndex, false)
            info = BufferInfo()
            outIndex = audioEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        }
        return result;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////// Helpers //////////////////////////////////////////////////////////

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

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////


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
