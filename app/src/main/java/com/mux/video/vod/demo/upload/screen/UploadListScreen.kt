package com.mux.video.vod.demo.upload.screen

import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel
import com.mux.video.vod.demo.upload.viewmodel.UploadListViewModel

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
    topBar = {
      AppBar(closeThisScreen)
    },
  ) { contentPadding ->
    BodyContent(
      Modifier
        .padding(contentPadding)
        .padding(16.dp)
    )
  }
}


@Composable
private fun BodyContent(modifier: Modifier = Modifier) {
}

@Composable
private fun AppBar(closeThisScreen: () -> Unit) {
  val viewModel: CreateUploadViewModel = viewModel()
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
    actions = {
      TextButton(
        onClick = {
          viewModel.beginUpload()
          closeThisScreen()
        },
      ) {
        Text(
          text = stringResource(id = R.string.action_create_upload),
          style = TextStyle(color = MaterialTheme.colors.onPrimary),
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
