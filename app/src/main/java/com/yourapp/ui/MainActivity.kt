package com.yourapp.yamahaarranger.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Timber.w("Audio permission denied")
        // One system dialog at a time: asking for storage while the audio
        // dialog is still showing makes Android drop one of the two requests.
        requestStorageAccess()
    }

    private val requestAllFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The settings screen returns no useful result code; re-check and let
        // the ViewModel retry loading the SoundFont / rescan the folders.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Timber.i("YamahaArranger storage access granted")
        } else {
            Timber.w("YamahaArranger storage access not granted")
        }
        viewModel.onStorageAccessMayHaveChanged()
    }

    // Android 8-10: classic runtime permission (MANAGE_EXTERNAL_STORAGE does not exist there).
    // It was declared in the manifest but never requested, so on those versions the
    // SoundFont/Style folders could never be created or read.
    private val requestLegacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onStorageAccessMayHaveChanged() }

    private val pickStyleFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onStyleFilePicked) }

    private val pickSoundFont = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::onSoundFontFilePicked) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestStorageAccess()
        }

        setContent {
            YamahaArrangerTheme {
                val showInspector = remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    SxMainScreen(
                        viewModel = viewModel,
                        onOpenInspector = { showInspector.value = true },
                        onRequestStorageAccess = { requestStorageAccess() }
                    )
                    ChordModeSelector(
                        modifier = androidx.compose.ui.Modifier
                            .align(androidx.compose.ui.Alignment.TopCenter)
                            .padding(top = 7.dp)
                    )
                }
                if (showInspector.value) {
                    Sf2StyleInspectorDialog(
                        onDismiss = { showInspector.value = false },
                        viewModel = viewModel
                    )
                }
            }
        }

        lifecycleScope.launch {
            delay(1500L)
            Timber.i("MIDI auto-connect…")
            viewModel.connectFirstAvailableMidiDevice()
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system permission screen (or another app after the
        // user copied files): re-check access and rescan folders if it changed.
        viewModel.onStorageAccessMayHaveChanged()
    }

    /** Asks for the storage access that matches the Android version. Safe to call repeatedly. */
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return
            Timber.i("Opening Android All files access for YamahaArranger folders")
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:" + packageName)
            }
            try {
                requestAllFilesAccess.launch(appIntent)
            } catch (_: ActivityNotFoundException) {
                // Some ROMs do not resolve the per-app screen; use the generic list.
                try {
                    requestAllFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    Timber.e(e, "No settings screen for All files access")
                }
            }
        } else {
            val missing = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestLegacyStoragePermission.launch(missing.toTypedArray())
        }
    }
}
