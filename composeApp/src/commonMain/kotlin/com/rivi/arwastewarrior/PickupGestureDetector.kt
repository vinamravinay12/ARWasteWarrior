package com.rivi.arwastewarrior

import androidx.compose.runtime.Composable

/**
 * Detects a physical pickup gesture (lift phone + acceleration spike).
 * Platform-specific: Android uses TYPE_LINEAR_ACCELERATION; iOS is a no-op stub.
 */
interface PickupGestureDetector {
    /** True once a pickup gesture has been confirmed. Compose-observable. */
    val isPickupDetected: Boolean

    /** Live motion intensity 0f..1f for the UI meter. Compose-observable. */
    val motionLevel: Float

    /** Start listening for the pickup gesture. */
    fun start()

    /** Stop listening and release sensor resources. */
    fun stop()

    /** Clear previous pickup state so the detector can be reused for a new round. */
    fun reset()
}

@Composable
expect fun rememberPickupGestureDetector(
    motionThreshold: Float = 2.5f,
    requiredHits: Int = 2
): PickupGestureDetector
