package com.mux.video.vod.demo.upload.screen

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.CreateUploadActivity
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.viewmodel.UploadListViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@Composable
fun UploadListScreen() {
  val activity = LocalContext.current as Activity
  val closeScreen = { activity.finish() }
  ScreenContent(closeScreen)
}

@Composable
private fun ScreenContent(
  closeThisScreen: () -> Unit = {},
) {
  return Scaffold(
    topBar = { AppBar(closeThisScreen) },
    floatingActionButton = { CreateUploadFab() }
  ) { contentPadding ->
    BodyContent(
      Modifier
        .padding(contentPadding)
        //.padding(16.dp)
    )
  }
}

@Composable
private fun CreateUploadFab() {
  val activity = LocalContext.current as Activity

  FloatingActionButton(onClick = {
    activity.startActivity(
      Intent(activity, CreateUploadActivity::class.java)
    )
  }) {
    Icon( Icons.Filled.Upload, contentDescription = "Start new upload")
  }
}

@Composable
private fun BodyContent(modifier: Modifier = Modifier) {
  Box(modifier = modifier.padding(16.dp)) {
    //val currentItems = screenViewModel().uploadsFlow.collectAsState(initial = listOf())
    UploadList()
  }
}

@Composable
private fun UploadList() {
  val items = screenViewModel().uploads.observeAsState()
  val viewModel = screenViewModel()
  SideEffect { viewModel.refreshList() }

  val listState = rememberLazyListState()
  ReportDrawnWhen { listState.layoutInfo.totalItemsCount > 0 }
  items.value?.let { list ->
    LazyColumn(state = listState) {
      items(list) { ListItem(upload = it) }
    }
  }
}

@Composable
private fun ListItem(upload: MuxUpload) {
  Row(modifier = Modifier.padding(PaddingValues(vertical = 12.dp))) {
    Box(
      modifier = Modifier
        .clip(CircleShape)
        .background(Color.Gray)
        .size(36.dp)
    )
    Spacer(modifier = Modifier.size(16.dp))
    Column {
      Box( // Status Text
        modifier = Modifier.height(36.dp)
      ) {
        val uploadState = upload.currentState
        val uploadTimeElapsed = uploadState.updatedTime - uploadState.startTime
        val dataRateEst = uploadState.bytesUploaded / uploadTimeElapsed.toDouble()

        val stateTxt = if (upload.isSuccessful) {
          "Done!"
        } else if (upload.error != null) {
          "Failed"
        } else if (upload.isRunning) {
          "Uploading"
        } else {
          "Paused"
        }
        val stateLine = buildAnnotatedString {
          withStyle(
            style = SpanStyle(
              fontWeight = FontWeight.Medium
            )
          ) {
            append("$stateTxt ")
          }
          val df = DecimalFormat("#.00")
          val formattedRate = df.format(dataRateEst)
          val formattedTime = df.format(uploadTimeElapsed / 1000f)
          append("$formattedRate Kb/s in ${formattedTime}s")
        }
        Text(
          text = stateLine,
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.Center)
        ) // status text
        Spacer(modifier = Modifier.padding(16.dp))
        ListItemThumbnail(upload)
      }
    }
  }
}

@Composable
private fun ListItemThumbnail(upload: MuxUpload) {
  Box(
    modifier = Modifier
      .wrapContentSize(Alignment.Center)
      .fillMaxWidth()
      .height(256.dp)
      .border(
        width = 1.dp,
        color = Color.LightGray,
        shape = RoundedCornerShape(12.dp),
      )
  ) {
    // TODO: Probs put the video thumb here. Need a suspend fun retrieveThumbnail
    // TODO: use retrieveThumbnail here, maybe also try `remember`
    if (upload.isSuccessful) {
      // Video Thumb: Fulls-size
    } else if (upload.error != null) {
      // V
    } else if (upload.isRunning) {

    }
  }
}

@Composable
private fun AppBar(closeThisScreen: () -> Unit) {
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_main_activity)) },
    navigationIcon = {
      IconButton(
        onClick = {
          closeThisScreen()
        },
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(id = android.R.string.cancel),
        )
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun ListScreenPreview() {
  MuxUploadSDKForAndroidTheme {
    ScreenContent {}
  }
}

@Composable
private fun screenViewModel(): UploadListViewModel = viewModel()
