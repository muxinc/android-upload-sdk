package com.mux.video.vod.demo.upload

import android.annotation.TargetApi
import android.content.Intent
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
import com.mux.video.vod.demo.upload.viewmodel.PlainViewExampleViewModel

class PlainViewActivity : AppCompatActivity() {

  companion object {
    // For now, you have to paste this from the direct-upload response
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east4-vop1-uploads/uUsEP6fMQ4wTO84T4XV63A?Expires=1681428358&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=d4URl1B1ZZgPKB4ecMuRdBQun3%2BTcVtEptcBcequLSjjxlcYebFrI9B8E1T%2FBAcbKgE%2B6gBHKXfRLWQ2Sw%2BB9vc3MhzKNi7Ex7q5%2Bj%2BajmUGoOHmrpvmkiMQxj8MX4jT29jUTTrOMR7nE85bAe1UfpgMJO%2F5zqMW%2FaaC0FIrzKXvwfkzVj5kiJ8MGqdFgt%2Fe5gMOhF18VRKAKwiIZV6XciONcRQzFpY0jDdQzpd%2F%2BcYJizcWpUXDAzj%2BQjZpwX%2BscC0CQcZaRdWZVr2AvpTouAZTOk0nCIMnnQRVfuc126ETgnDhVweRKwAbZ49gq0i3JvtEM2eA3vaaHpgQ2I5dFA%3D%3D&upload_id=ADPycdsxumXKD0vChqQuUNC-zHyETEFRdMa8ebw7Pxj3PGwuiJzd5IEwbq8mkNCmZvFjPnXd1lkhllmR2fcXUVXt6ifIgackrlOo"
  }

  private lateinit var binding: ActivityVideoListBinding
  private lateinit var listAdapter: UploadListAdapter
  private val viewModel by viewModels<PlainViewExampleViewModel>()
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
      //openDocument.launch(arrayOf("video/*"))
      startActivity(Intent(this, CreateUploadActivity::class.java))
    }

    //maybeRequestPermissions()
  }

  private fun handleListUpdate(list: List<MuxUpload>) {
    listAdapter = UploadListAdapter(list)
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