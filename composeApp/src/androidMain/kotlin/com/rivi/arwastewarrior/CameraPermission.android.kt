package com.rivi.arwastewarrior

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private class AndroidCameraPermissionHandler(
    private val hasPermissionCheck: () -> Boolean,
    private val requestPermissionAction: () -> Unit
) : CameraPermissionHandler {
    override fun hasCameraPermission(): Boolean = hasPermissionCheck()

    override fun requestCameraPermission() {
        requestPermissionAction()
    }
}

@Composable
actual fun rememberCameraPermissionHandler(
    onPermissionResult: (Boolean) -> Unit
): CameraPermissionHandler {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onPermissionResult
    )

    return remember(context, permissionLauncher) {
        AndroidCameraPermissionHandler(
            hasPermissionCheck = {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            },
            requestPermissionAction = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        )
    }
}
