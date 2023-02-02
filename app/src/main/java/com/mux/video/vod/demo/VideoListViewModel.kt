package com.mux.video.vod.demo

import android.app.Application
import androidx.lifecycle.*
import com.mux.video.vod.demo.model.DeviceVideo
import kotlinx.coroutines.launch

/**
 * Queries the device's content provider for saved videos to upload
 */
class VideoListViewModel(private val app: Application): AndroidViewModel(app) {

  val videoList: LiveData<List<DeviceVideo>> by this::innerVideoList
  private val innerVideoList = MutableLiveData<List<DeviceVideo>>()

  /**
   * Refresh the video list by querying it again
   */
  fun refresh() {
    viewModelScope.launch {
      val videos = fetchVideos()
      innerVideoList.postValue(videos)
    }
  }

  private suspend fun fetchVideos(): List<DeviceVideo> {
    return listOf()
  }


}