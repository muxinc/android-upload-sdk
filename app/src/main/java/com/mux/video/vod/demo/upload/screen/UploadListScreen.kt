package com.mux.video.vod.demo.upload.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel
import com.mux.video.vod.demo.upload.viewmodel.UploadListViewModel
import java.io.File

@Composable
fun UploadListScreen() {

}

@Composable
private fun ScreenContent(
  closeThisScreen: () -> Unit = {},
  screenState: CreateUploadViewModel.State
) {
  return Scaffold(
    topBar = {
      AppBar(closeThisScreen, screenState.chosenFile)
    },
  ) { contentPadding ->
    BodyContent(
      screenState,
      Modifier
        .padding(contentPadding)
        .padding(16.dp)
    )
  }
}


@Composable
private fun BodyContent(state: CreateUploadViewModel.State, modifier: Modifier = Modifier) {
}

@Composable
private fun AppBar(closeThisScreen: () -> Unit, videoFile: File?) {
  val viewModel: CreateUploadViewModel = viewModel()
  val enableAction = videoFile != null
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_activity_create_upload)) },
    navigationIcon = {
      IconButton(
        onClick = {
          closeThisScreen()
        },
        enabled = videoFile != null
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
        enabled = enableAction
      ) {
        Text(
          text = stringResource(id = R.string.action_create_upload),
          style = TextStyle(color = MaterialTheme.colors.onPrimary),
          modifier = Modifier.alpha(
            if (enableAction) {
              1.0F
            } else {
              0.6F
            }
          )
        )
      }
    },
  )
}

@Composable
private fun screenViewModel(): UploadListViewModel = viewModel()
