package com.rivi.arwastewarrior.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rivi.arwastewarrior.detection.LiveScanResult

@Composable
expect fun LiveCameraDetectionView(
    modifier: Modifier = Modifier,
    onScanResult: (LiveScanResult) -> Unit,
    onError: (String) -> Unit
)
