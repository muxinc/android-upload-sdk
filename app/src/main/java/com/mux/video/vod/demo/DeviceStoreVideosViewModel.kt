package com.mux.video.vod.demo

import android.app.Application
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Video.VideoColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.vod.demo.model.DeviceStoreVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Queries the device's content provider for saved videos to upload
 */
class DeviceStoreVideosViewModel(private val app: Application) : AndroidViewModel(app) {

  val videoList: LiveData<List<DeviceStoreVideo>> by this::innerVideoList
  private val innerVideoList = MutableLiveData<List<DeviceStoreVideo>>()

  /**
   * Refresh the video list by querying it again
   */
  fun refresh() {
    viewModelScope.launch {
      val videos = fetchVideos()
      innerVideoList.postValue(videos)
    }
  }

  private suspend fun fetchVideos(): List<DeviceStoreVideo> {
    fun ownerPackageName(cursor: Cursor): String {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cursor.getString(VideoColumns.OWNER_PACKAGE_NAME) ?: "??"
      } else {
        "??"
      }
    }

    val cursor = withContext(Dispatchers.IO) {
      app.contentResolver.query(
        MediaStore.Video.Media.INTERNAL_CONTENT_URI,
        /* projection = */ null,
        /* selection = */ null,
        /* selectionArgs = */ null,
        /* sortOrder = */ MediaStore.Video.VideoColumns.DISPLAY_NAME + " DESC"
      )!!
    }

    val videos = mutableListOf<DeviceStoreVideo>()
    cursor.moveToFirst()
    do {
      val title = cursor.getString(VideoColumns.DISPLAY_NAME) ?: "[no name]"
      val file = cursor.getString(VideoColumns.DATA) ?: continue
      val fromApp = ownerPackageName(cursor)

      val vid = DeviceStoreVideo(
        title = title,
        file = File(file),
        fromApp = fromApp,
      )
      videos += vid
    } while (cursor.moveToNext())

    return videos
  }

  private fun Cursor.getString(columnName: String): String? {
    val colIdx = getColumnIndexOrThrow(columnName)
    return getString(colIdx)
  }

  /**
   * In order to upload a file from the device's media store, the file must be copied into the app's
   * temp directory. (Technically we could stream it from the source, but this prevents the other
   * app from modifying the file if we pause the upload for a long time (or whatever)
   *
   * TODO: Wait we don't really have to do this! Whoohoo!
   * TODO<em>: Should the SDK do this? Most ugc apps won't need it, but any app that wants to
   *  interact with the device Media Store (different from local flat files) needs to do this step
   */
//  @Throws
//  private suspend fun copyIntoTempFile(video: DeviceStoreVideo): File {
//    val basename = video.contentUri.pathSegments.joinToString(separator = "-")
//    val cacheDir = File(app.cacheDir, "mux-upload")
//    cacheDir.mkdirs()
//    val destFile = File(cacheDir, basename)
//
//    withContext(Dispatchers.IO) {
//      val input = FileInputStream(video.fileDescriptor.fileDescriptor).channel
//      val output = FileOutputStream(destFile).channel
//      val fileSize = input.size()
//      try {
//        var read = 0L
//        do {
//          read += input.transferTo(read, 10 * 1024, output)
//        } while (read < fileSize)
//      } finally {
//        input.close()
//        output.close()
//      }
//    }
//
//    return destFile
//  }
}
