package com.mux.video.vod.demo.backend

import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST


/**
 * Takes the place of the backend web service you must build in order to create Direct Uploads on
 * Mux's servers. This class only serves the purposes of the example, and would be too insecure for
 * you to replicate in your own app.
 *
 * In real life, you should *not* do this interaction client-side! The keys used are too sensitive.
 */
object ImaginaryBackend {

  private val muxVideoBackend: ImaginaryWebapp by lazy {
    val gson = Gson()
    val muxHttpClient = OkHttpClient.Builder().build() // basic config is just fine

    Retrofit.Builder()
      .baseUrl("https://api.mux.com/video/")
      .addConverterFactory(ScalarsConverterFactory.create())
      .addConverterFactory(GsonConverterFactory.create(gson))
      .client(muxHttpClient)
      .build().create(ImaginaryWebapp::class.java)
  }

  @Throws
  suspend fun createUploadUrl(): String {
    val post = VideoUploadPost(
      assetSettings = listOf(NewAssetSettings())
    )
    val response = muxVideoBackend.postUploads(basicCredential(), post)
    return response.data.url
  }

  // note: You shouldn't do basic auth with hard-coded keys in a real app
  private fun basicCredential(): String = Credentials.basic(ACCESS_TOKEN_ID, ACCESS_TOKEN_SECRET)

  private const val ACCESS_TOKEN_ID = "990ad4f6-709e-4296-98dc-078498b979c7"
  private const val ACCESS_TOKEN_SECRET = "je4hm5xjH3TabBYbVmCO3xLNCe/nT0Etqr0Z6E5yt361OOz/6B6UjA03ZNY2zgB1vaVVLAi3H7A"
}

private interface ImaginaryWebapp {
  /**
   * Creates a new Upload on the Mux backend. This does not upload the video itself. It just creates
   * a new asset and provides an authenticated URL to a resume-able upload.
   *
   * When the upload completes, the Mux backend will process the video and create an Asset
   */
  @POST("v1/uploads")
  @Headers("Content-Type: application/json")
  suspend fun postUploads(
    @Header("Authorization") basicAuth: String,
    @Body postBody: VideoUploadPost
  ): MuxVideoUploadResponse
}

/**
 * Represents the POST body for creating a new video asset.
 *
 * The default values for this object are chosen for the purposes of this example and may not be
 * optimal for your use case.
 *
 * Further documentation can be found here:
 *  https://docs.mux.com/api-reference/video#operation/create-direct-upload
 */
private data class VideoUploadPost(
  /**
   * List of new assets to create.
   */
  val assetSettings: List<NewAssetSettings> = listOf(NewAssetSettings()),
  /**
   * Origin for the CORS header in a browser playback situation. "*" is a reasonable default
   * TODO: Is it a good default?
   */
  val corsOrigin: String = "*"
)

/**
 * Settings for the newly-created asset.
 */
private data class NewAssetSettings(
  /**
   * Possible values:
   *  "public": Public assets can be streamed without authentication
   *  "signed": Signed assets require authentication by token to be streamed.
   *      (see https://docs.mux.com/api-reference/video)
   */
  val playbackPolicy: List<String> = listOf("public"),
  /**
   * Arbitrary data that can be passed along with the asset.
   * NOTE: The backend limits the length of this string to 255 characters.
   */
  val passthrough: String = "Extra video data! This can be anything you want!",
  /**
   * Toggles support for simple mp4 playback for this asset. HLS is preferable for streaming
   * experience, but mp4 is useful for scenarios such as offline playback
   *
   * Possible Values:
   *  "none": mp4 support disabled
   *  "standard": mp4 support enabled
   */
  val mp4Support: String = "standard",
  /**
   * Toggles audio loudness normalization for this asset as part of the transcode process
   */
  val normalizeAudio: Boolean = true,
  /**
   * Marks the asset as a test asset when the value is set to true. A Test asset can help evaluate
   * the Mux Video APIs without incurring any cost. There is no limit on number of test assets
   * created. Test asset are watermarked with the Mux logo, limited to 10 seconds, deleted after 24 hrs.
   */
  val test: Boolean = false,
)

/**
 * Represents an active (or finished) video upload, connected with a recently-created asset
 * For more information, see:
 *  https://docs.mux.com/api-reference/video#operation/create-direct-upload
 */
private data class MuxVideoUpload(
  /**
   * Resume-able PUT URL for the video file being uploaded
   */
  val url: String,
  /**
   * ID of the Asset
   */
  val assetId: String?,
  /**
   * Timeout (in seconds) before this upload link times out
   */
  val timeout: Long?,
  /**
   * Status of this video upload. Possible values are:
   *  "waiting",
   *  "asset_created",
   *  "errored",
   *  "cancelled",
   *  "timed_out
   */
  val status: String,
  /**
   * The settings for this asset, most of which can be updated by calling the POST/uploads API
   * again
   */
  val newAssetSettings: List<NewAssetSettings>,
  /**
   * The ID of the created asset on Mux Video. Will be non-null if the status is "asset_created"
   */
  val id: String,
  /**
   * Arbitrary object representing an error, if one occurred while trying to create the asset
   */
  val error: MuxVideoUploadError,
  /**
   * The origin for a videos CORS headers. Only relevant if sent from a browser
   */
  val corsOrigin: String,
)

private data class MuxVideoUploadError(
  val type: String,
  val message: String
)

/**
 * Wrapper for the upload response data
 */
private data class MuxVideoUploadResponse(
  val data: MuxVideoUpload
)
