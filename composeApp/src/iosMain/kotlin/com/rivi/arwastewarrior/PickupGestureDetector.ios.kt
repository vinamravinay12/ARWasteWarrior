package com.rivi.arwastewarrior

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class NoOpPickupGestureDetector : PickupGestureDetector {
    override val isPickupDetected: Boolean = false
    override val motionLevel: Float = 0f
    override fun start() = Unit
    override fun stop() = Unit
    override fun reset() = Unit
}

@Composable
actual fun rememberPickupGestureDetector(
    motionThreshold: Float,
    requiredHits: Int
): PickupGestureDetector {
    return remember { NoOpPickupGestureDetector() }
}
