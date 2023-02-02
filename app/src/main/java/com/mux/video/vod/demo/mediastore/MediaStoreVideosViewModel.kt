package com.mux.video.vod.demo.mediastore

import android.app.Application
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Video.VideoColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.vod.demo.mediastore.model.MediaStoreVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
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
          DateTime.now().withMillis(dateMillis * 1000).withZoneRetainFields(DateTimeZone.getDefault())

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
