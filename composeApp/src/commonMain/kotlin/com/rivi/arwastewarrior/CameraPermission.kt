package com.rivi.arwastewarrior

import androidx.compose.runtime.Composable

interface CameraPermissionHandler {
    fun hasCameraPermission(): Boolean
    fun requestCameraPermission()
}

@Composable
expect fun rememberCameraPermissionHandler(
    onPermissionResult: (Boolean) -> Unit
): CameraPermissionHandler
