package com.mux.video.vod.demo.mediastore

import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Video.VideoColumns
import android.util.Log
import android.widget.Toast
import androidx.core.util.Consumer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.mediastore.model.MediaStoreVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

/**
 * Queries the device's content provider for saved videos to upload
 */
class MediaStoreVideosViewModel(private val app: Application) : AndroidViewModel(app) {

  val videoList: LiveData<List<MediaStoreVideo>> by this::innerVideoList
  private val innerVideoList = MutableLiveData<List<MediaStoreVideo>>()

  /**
   * Refresh the video list by querying it again
   */
  fun refresh() {
    viewModelScope.launch {
      val videos = fetchVideos()
      innerVideoList.postValue(videos)
    }
  }

  fun beginUpload(contentUri: Uri) {
    viewModelScope.launch {
      Log.d(javaClass.simpleName, "Beginning upload of uri $contentUri")
      val copiedFile = copyIntoTempFile(contentUri)
      Log.d(javaClass.simpleName, "Copied file to $copiedFile")

      val upl = MuxUpload.Builder(MediaStoreVideosActivity.PUT_URL, copiedFile).build()
      upl.addProgressConsumer(Consumer {
        Log.v(javaClass.simpleName, "Upload progress: ${it.bytesUploaded} / ${it.totalBytes}")
      })
      upl.addSuccessConsumer(Consumer {
        Log.w(javaClass.simpleName, "YAY! Uploaded the file: $contentUri")
      })
      upl.start()
    }
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
  @Throws
  private suspend fun copyIntoTempFile(contentUri: Uri): File {
    val basename = contentUri.pathSegments.joinToString(separator = "-")
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

  private suspend fun fetchVideos(): List<MediaStoreVideo> {
    fun ownerPackageName(cursor: Cursor): String {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cursor.getString(VideoColumns.OWNER_PACKAGE_NAME) ?: "??"
      } else {
        "??"
      }
    }

    fun columns(): Array<String> {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
          VideoColumns.DISPLAY_NAME,
          VideoColumns.DATA,
          VideoColumns.OWNER_PACKAGE_NAME,
          VideoColumns.DATE_ADDED,
          VideoColumns.DATE_TAKEN
        )
      } else {
        arrayOf(
          VideoColumns.DISPLAY_NAME,
          VideoColumns.DATA,
          VideoColumns.DATE_ADDED,
          VideoColumns.DATE_TAKEN
        )
      }
    }

    withContext(Dispatchers.IO) {
      app.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        /* projection = */
        columns(),
        /* selection = */
        null,
        /* selectionArgs = */
        null,
        /* sortOrder = */
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
        val title = cursor.getString(VideoColumns.DISPLAY_NAME) ?: "[no name]"
        val file = cursor.getString(VideoColumns.DATA) ?: continue
        val fromApp = ownerPackageName(cursor)
        val dateMillis = cursor.getLong(VideoColumns.DATE_ADDED)
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
}
