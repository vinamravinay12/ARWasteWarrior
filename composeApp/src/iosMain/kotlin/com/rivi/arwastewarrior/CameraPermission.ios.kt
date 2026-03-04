package com.rivi.arwastewarrior

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class IosCameraPermissionHandler(
    private val onPermissionResult: (Boolean) -> Unit
) : CameraPermissionHandler {
    override fun hasCameraPermission(): Boolean = true

    override fun requestCameraPermission() {
        onPermissionResult(true)
    }
}

@Composable
actual fun rememberCameraPermissionHandler(
    onPermissionResult: (Boolean) -> Unit
): CameraPermissionHandler {
    return remember(onPermissionResult) {
        IosCameraPermissionHandler(onPermissionResult)
    }
}
