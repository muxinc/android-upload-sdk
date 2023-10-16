package com.mux.video.upload.internal

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.mux.video.upload.BuildConfig
import com.mux.video.upload.MuxUploadSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


internal class UploadMetrics private constructor() {

  private val logger get() = MuxUploadSdk.logger


  private fun formatMilliseconds(ms:Long):String {
    return String.format("%02d:%02d:%02d",
      TimeUnit.MILLISECONDS.toHours(ms),
      TimeUnit.MILLISECONDS.toMinutes(ms) -
              TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(ms)), // The change is in this line
      TimeUnit.MILLISECONDS.toSeconds(ms) -
              TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(ms)));
  }

  private suspend fun getEventInfo(startTimeMillis: Long,
                                   endTimeMillis: Long,
                                   inputFileDurationMs: Long,
                                   uploadInfo: UploadInfo): JSONObject {
    val iso8601Sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    var videoDuration = inputFileDurationMs
    if (inputFileDurationMs <= 0) {
      videoDuration = withContext(Dispatchers.IO) {
        try {
          MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(uploadInfo.inputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
          }
        } catch (e: Exception) {
          MuxUploadSdk.logger.e("UploadMetrics", "Failed to get video duration", e)
          null
        }
      }!!.toLong()
    }
    return JSONObject().apply {
      put("upload_start_time", iso8601Sdf.format(startTimeMillis)) // ISO8601
      put("upload_start_time", iso8601Sdf.format(endTimeMillis)) // ISO8601
      put("input_size", uploadInfo.inputFile.length())
      put("input_duration", formatMilliseconds(videoDuration)) // HH:mm:ss
      put("upload_url", uploadInfo.remoteUri.toString())
      put("sdk_version", BuildConfig.LIB_VERSION)
      put("platform_name", "Android")
      put("platform_version", Build.VERSION.RELEASE)
      put("device_model", Build.MODEL)
      put("app_name", appName)
      put("app_version", appVersion)
      put("region_code", Locale.getDefault().country)
    }
  }

  private suspend fun sendPost(body:JSONObject) {
    val httpClient = MuxUploadSdk.httpClient()
      .newBuilder()
      // The SDK's http client is configured for uploading. We want the tighter default timeouts
      .callTimeout(0, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      // We need to do a non-compliant redirect: 301 or 302 but preserving method and body
      .addInterceptor(Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code in 301..302 || response.code in 307..308) {
          val redirectUri = response.headers("Location").firstOrNull()?.let { Uri.parse(it) }
          // If 'Location' was present and was a real URL, redirect to it
          if (redirectUri == null) {
            logger.w("UploadMetrics", "redirect with invalid or blank url. ignoring")
            response
          } else {
            response.close() // Required before starting a new request in the chain
            val redirectedReq = response.request.newBuilder()
            if (redirectUri.isRelative) {
              val requestUrl = response.request.url
              var relativePathDelimiter = "/"
              if (redirectUri.toString().startsWith("/")) {
                relativePathDelimiter = "";
              }
              redirectedReq.url(requestUrl.scheme + "//" + requestUrl.host + ":"
                      + requestUrl.port + relativePathDelimiter + redirectUri.toString())
            } else {
              redirectedReq.url(redirectUri.toString())
            }
            chain.proceed(redirectedReq.build())
          }
        } else {
          response
        }
      })
      .build()
    val request = Request.Builder()
      .url("https://mux-sdks-telemetry.vercel.app/api/upload-sdk-native")
      .method("POST", body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    // The HTTP Client will log if this fails or succeeds
    withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
  }

  @JvmSynthetic
  internal suspend fun reportStandardizationSuccess(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    inputReasons: JSONArray,
    maximumResolution:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) {
    var body = JSONObject()
    body.put("type", "upload_input_standardization_succeeded")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, endTimeMillis, inputFileDurationMs, uploadInfo)
    data.put("maximum_resolution", maximumResolution)
    data.put("non_standard_input_reasons", inputReasons)
    data.put("input_standardization_enabled", true)
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportStandardizationFailed(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    errorDescription: String,
    inputReasons: JSONArray,
    maximumResolution:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) {
    var body = JSONObject()
    body.put("type", "upload_input_standardization_failed")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, endTimeMillis, inputFileDurationMs, uploadInfo)
    data.put("error_description", errorDescription)
    data.put("maximum_resolution", maximumResolution)
    data.put("non_standard_input_reasons", inputReasons)
    data.put("upload_canceled", false)
    data.put("input_standardization_enabled", true)
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportUploadSucceeded(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    sessionId: String,
    uploadInfo: UploadInfo
  ) {
    var body = JSONObject()
    body.put("type", "upload_succeeded")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, endTimeMillis, inputFileDurationMs, uploadInfo)
    data.put("input_standardization_requested", uploadInfo.standardizationRequested
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    data.remove("upload_start_time")
    data.remove("upload_start_time")
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportUploadFailed(
    startTimeMillis: Long,
    endTimeMillis: Long,
    inputFileDurationMs: Long,
    errorDescription:String,
    sessionId: String,
    uploadInfo: UploadInfo
  ) {
    var body = JSONObject()
    body.put("type", "uploadfailed")
    body.put("session_id", sessionId)
    body.put("version", "1")
    val data = getEventInfo(startTimeMillis, endTimeMillis, inputFileDurationMs, uploadInfo)
    data.put("input_standardization_requested", uploadInfo.standardizationRequested
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    data.put("error_description", errorDescription)
    data.remove("upload_start_time")
    data.remove("upload_start_time")
    body.put("data", data)
    sendPost(body)
  }

  @JvmSynthetic
  internal suspend fun reportUpload(
    startTimeMillis: Long,
    endTimeMillis: Long,
    uploadInfo: UploadInfo
  ) {
    val videoDuration = withContext(Dispatchers.IO) {
      try {
        MediaMetadataRetriever().use { retriever ->
          retriever.setDataSource(uploadInfo.inputFile.absolutePath)
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()
        }
      } catch (e: Exception) {
        MuxUploadSdk.logger.e("UploadMetrics", "Failed to get video duration", e)
        null
      }
    }

    val eventJson = UploadEvent(
      startTimeMillis = startTimeMillis,
      endTimeMillis = endTimeMillis,
      fileSize = uploadInfo.inputFile.length(),
      videoDuration = videoDuration ?: 0,
      uploadURL = uploadInfo.remoteUri.toString(),
      sdkVersion = BuildConfig.LIB_VERSION,
      osName = "Android",
      osVersion = Build.VERSION.RELEASE,
      deviceModel = Build.MODEL,
      appName = appName,
      appVersion = appVersion,
      regionCode = Locale.getDefault().country
    ).toJson()
    sendPost(JSONObject(eventJson))
  }

  companion object {
    // Set by initialize()
    private lateinit var appName: String
    private lateinit var appVersion: String

    @JvmSynthetic
    internal fun create() = UploadMetrics()

    @JvmSynthetic
    internal fun initialize(appContext: Context) {
      val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.packageManager.getPackageInfo(
          appContext.packageName,
          PackageManager.PackageInfoFlags.of(0)
        )
      } else {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
      }
      appName = packageInfo.packageName
      appVersion = packageInfo.versionName
    }
  }
}

private data class UploadEvent(
  // Video-Specific
  val startTimeMillis: Long,
  val endTimeMillis: Long,
  val fileSize: Long,
  val videoDuration: Int,
  val uploadURL: String,
  // Device-Derived
  val sdkVersion: String,
  val osName: String,
  val osVersion: String,
  val deviceModel: String,
  val appName: String,
  val appVersion: String,
  val regionCode: String
) {
  fun toJson(): String {
    return JSONObject().apply {
      put("type", "upload")
      put("start_time", startTimeMillis / 1000.0)
      put("end_time", endTimeMillis / 1000.0)
      put("file_size", fileSize)
      put("video_duration", videoDuration)
      put("upload_url", uploadURL)
      put("sdk_version", sdkVersion)
      put("os_name", osName)
      put("os_version", osVersion)
      put("device_model", deviceModel)
      put("app_name", appName)
      put("app_version", appVersion)
      put("region_code", regionCode)
    }.toString()
  }
}
