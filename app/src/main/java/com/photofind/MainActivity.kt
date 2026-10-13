package com.photofind

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.FileProvider
import com.photofind.ui.MainViewModel
import com.photofind.ui.PhotoFindScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onTrashResult(confirmed = it.resultCode == RESULT_OK)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.trashRequest }.distinctUntilChanged().collect { uris ->
                    if (uris != null) {
                        // Android shows its own "Move to trash?" dialog; trashed items stay restorable for 30 days.
                        val request = MediaStore.createTrashRequest(contentResolver, uris, true)
                        viewModel.onTrashLaunched()
                        trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                PhotoFindScreen(
                    viewModel = viewModel,
                    onGrantStorage = ::openAllFilesSettings,
                    onOpenPhoto = ::openPhoto,
                    onSharePhoto = ::sharePhoto,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }

    private fun openAllFilesSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openPhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareable(uri), "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, shareable(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    private fun shareable(uri: Uri): Uri = if (uri.scheme == "file") {
        FileProvider.getUriForFile(this, "$packageName.files", File(requireNotNull(uri.path)))
    } else {
        uri
    }
}
