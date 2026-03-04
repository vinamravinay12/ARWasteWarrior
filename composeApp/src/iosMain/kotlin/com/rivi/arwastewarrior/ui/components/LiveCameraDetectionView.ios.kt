package com.rivi.arwastewarrior.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rivi.arwastewarrior.detection.LiveScanResult

@Composable
actual fun LiveCameraDetectionView(
    modifier: Modifier,
    onScanResult: (LiveScanResult) -> Unit,
    onError: (String) -> Unit
) {
    Box(
        modifier = modifier.background(Color(0xFF0E2C3A)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Live camera ML detection will be added on iOS.",
            color = Color(0xFFE3F6FB),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
