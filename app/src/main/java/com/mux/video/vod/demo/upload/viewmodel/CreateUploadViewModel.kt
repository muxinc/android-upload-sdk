package com.mux.video.vod.demo.upload.viewmodel

import android.app.Application
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.upload.UploadListActivity
import com.mux.video.vod.demo.upload.model.MediaStoreVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class CreateUploadViewModel(private val app: Application) : AndroidViewModel(app) {

  val videoState: LiveData<ScreenState> by this::videoStateLiveData
  private val videoStateLiveData =
    MutableLiveData(ScreenState(prepareState = PrepareState.NONE, thumbnail = null))

  private var prepareJob: Job? = null

  fun prepareForUpload(contentUri: Uri) {
    videoStateLiveData.value = ScreenState(PrepareState.PREPARING, null)

    prepareJob?.cancel()
    prepareJob = viewModelScope.launch {
      val videoFile = copyIntoTempFile(contentUri)
      val thumbnailBitmap = withContext(Dispatchers.IO) {
        try {
          MediaMetadataRetriever().use {
            it.setDataSource(videoFile.absolutePath)
            // TODO: Version-specific
            //it.getFrameAtIndex(0)
            it.getFrameAtTime(0)
          }
        } catch (e: Exception) {
          Log.d("CreateUploadViewModel", "Error getting thumb bitmap")
          null
        }
      } // val thumbnailBitmap = ...
      // TODO: Fake a backend service for creating uploads

      videoStateLiveData.postValue(ScreenState(PrepareState.READY, videoFile, thumbnailBitmap))
    } // prepareJob = viewModelScope.launch { ...
  }

  fun beginUpload() {
    if(((videoState.value?.prepareState) ?: PrepareState.NONE) == PrepareState.READY) {
      MuxUpload.Builder(
        UploadListActivity.PUT_URL,
        videoState.value!!.chosenFile!!
      ).build().start()
    }
  }

  /**
   * In order to upload a file from the device's media store, the file must be copied into the app's
   * temp directory. (Technically we could stream it from the source, but this prevents the other
   * app from modifying the file if we pause the upload for a long time or whatever)
   * TODO<em> Is this something that should go in the SDK? This is a common workflow
   */
  @Throws
  private suspend fun copyIntoTempFile(contentUri: Uri): File {
    // Create a unique name for our temp file. There are a ton of ways to do this, but this one is
    //  pretty easy to implement and protects from unsafe characters
    val basename = android.util.Base64.encode(
      contentUri.pathSegments.joinToString(separator = "-").encodeToByteArray(),
      0
    ).decodeToString()

    val cacheDir = File(app.cacheDir, "mux-upload")
    cacheDir.mkdirs()
    val destFile = File(cacheDir, basename)

    withContext(Dispatchers.IO) {
      val output = FileOutputStream(destFile).channel
      val fileDescriptor = app.contentResolver.openFileDescriptor(contentUri, "r")
      val input = FileInputStream(fileDescriptor!!.fileDescriptor).channel

      try {
        val fileSize = input.size()
        var read = 0L
        do {
          read += input.transferTo(read, 10 * 1024, output)
        } while (read < fileSize)
      } finally {
        input.close()
        fileDescriptor.close()
        output.close()
      }
    }

    return destFile
  }

  // Might need something like this
  private suspend fun fetchVideos(): List<MediaStoreVideo> {
    fun ownerPackageName(cursor: Cursor): String {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cursor.getString(MediaStore.Video.VideoColumns.OWNER_PACKAGE_NAME) ?: "??"
      } else {
        "??"
      }
    }

    fun columns(): Array<String> {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
          MediaStore.Video.VideoColumns.DISPLAY_NAME,
          MediaStore.Video.VideoColumns.DATA,
          MediaStore.Video.VideoColumns.OWNER_PACKAGE_NAME,
          MediaStore.Video.VideoColumns.DATE_ADDED,
          MediaStore.Video.VideoColumns.DATE_TAKEN
        )
      } else {
        arrayOf(
          MediaStore.Video.VideoColumns.DISPLAY_NAME,
          MediaStore.Video.VideoColumns.DATA,
          MediaStore.Video.VideoColumns.DATE_ADDED,
          MediaStore.Video.VideoColumns.DATE_TAKEN
        )
      }
    }

    withContext(Dispatchers.IO) {
      app.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        columns(),
        null,
        null,
        null,
      )!!
    }.use { cursor ->
      if (cursor.count <= 0) {
        Log.w(javaClass.simpleName, "No videos found")
        return listOf()
      }

      val videos = mutableListOf<MediaStoreVideo>()
      cursor.moveToFirst()
      do {
        val title = cursor.getString(MediaStore.Video.VideoColumns.DISPLAY_NAME) ?: "[no name]"
        val file = cursor.getString(MediaStore.Video.VideoColumns.DATA) ?: continue
        val fromApp = ownerPackageName(cursor)
        val dateMillis = cursor.getLong(MediaStore.Video.VideoColumns.DATE_ADDED)
        val dateTime =
          DateTime.now().withMillis(dateMillis * 1000)
            .withZoneRetainFields(DateTimeZone.getDefault())

        val vid = MediaStoreVideo(
          title = title,
          file = File(file),
          fromApp = fromApp,
          date = dateTime.toString()
        )
        videos += vid
      } while (cursor.moveToNext())

      return videos
    }
  }

  private fun Cursor.getLong(columnName: String): Long {
    val colIdx = getColumnIndexOrThrow(columnName)
    return getLong(colIdx)
  }

  private fun Cursor.getString(columnName: String): String? {
    val colIdx = getColumnIndexOrThrow(columnName)
    return getString(colIdx)
  }

  enum class PrepareState { NONE, PREPARING, ERROR, READY }

  data class ScreenState(
    val prepareState: PrepareState,
    val chosenFile: File? = null,
    val thumbnail: Bitmap? = null,
  )
}
