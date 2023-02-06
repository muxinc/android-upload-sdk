package com.mux.video.vod.demo.mediastore

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding
import com.mux.video.vod.demo.mediastore.model.UploadingVideo

class MediaStoreVideosActivity : AppCompatActivity() {

  companion object {
    // For now, you have to paste this from the direct-upload response
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east1-vop1-uploads/oBUuzwZMxN468TXycLFZMB?Expires=1675719736&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=ayh1frqcEXWy1xYA9C9k%2BkKPoBbZKqXZQH04obUs1292s6uVGeVqE1o9b1lfPtB%2FOZLpW2mZKmGw792LAB1yFcy94Dw%2FQ8cvdzM5XQlCxQfKx12%2FFBComFLU%2BKRtEWotDIGCmVNMJMvQE0jxBunO3oow%2FYxvYaFxVjO1hK0IwhOFJSgCNDuSmFWPVTrYx0jvvFo%2BssyqD%2BMXY2yGx7EHPPSbWbSgx4dbPtutknI6VY29ZrwrUJwks8RMx8kBn4jdK4UfCnkqulT%2B6weUejYfq8z5oThCve6uevEOm%2BaEc1X15SG6uJPBtk9Q%2BOD%2B53gAtQ2gTKjx3ZBXJBi%2BcCOzxQ%3D%3D&upload_id=ADPycdvz7mJfiISTkBDIm-5f8_dndDZ9M0d6gIpVcOH-XW1p8X7H4Iw7Q52pXbxEy7tFWMKECH0SUmaqdqq8IbO700e1wB68HytC"
  }

  private lateinit var binding: ActivityVideoListBinding
  private lateinit var listAdapter: MediaStoreVideosAdapter
  private val viewModel by viewModels<MediaStoreVideosViewModel>()
  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { grantedPermissions ->
      if (!grantedPermissions.containsKey(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        || grantedPermissions.containsKey(android.Manifest.permission.READ_MEDIA_VIDEO)
      ) {
        maybeRequestPermissionsApi33()
      }
    }
  private val openDocument =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { docUri ->
      Log.d(javaClass.simpleName, "Got doc with URI $docUri")
      viewModel.beginUpload(docUri!!)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoListBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.videoListList.includeRecyclerView.layoutManager = LinearLayoutManager(this)
    viewModel.uploads.observe(this) { handleListUpdate(it) }

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      openDocument.launch(arrayOf("video/*"))
    }

    maybeRequestPermissions()
  }

  private fun handleListUpdate(list: List<MuxUpload>) {
    // TODO: Use AsyncListDiffer to make this look nice
    listAdapter = MediaStoreVideosAdapter(list)
    binding.videoListList.includeRecyclerView.adapter = listAdapter
  }

  private fun maybeRequestPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      maybeRequestPermissionsApi33()
    } else {
      maybeRequestPermissionsOld()
    }
  }

  private fun maybeRequestPermissionsOld() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadStorage) {
      requestPermissions.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }
  }

  @TargetApi(Build.VERSION_CODES.TIRAMISU)
  private fun maybeRequestPermissionsApi33() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED
    val hasReadVideo = ActivityCompat.checkSelfPermission(
      this,
      android.Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadVideo || !hasReadStorage) {
      requestPermissions.launch(
        arrayOf(
          android.Manifest.permission.READ_EXTERNAL_STORAGE,
          android.Manifest.permission.READ_MEDIA_VIDEO
        )
      )
    }
  }

}