package com.rivi.arwastewarrior

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

private class AndroidPickupGestureDetector(
    context: Context,
    private val motionThreshold: Float,
    private val requiredHits: Int
) : PickupGestureDetector {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val _isPickupDetected = mutableStateOf(false)
    private val _motionLevel = mutableStateOf(0f)
    private var consecutiveHits = 0

    override val isPickupDetected: Boolean get() = _isPickupDetected.value
    override val motionLevel: Float get() = _motionLevel.value

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (_isPickupDetected.value) return  // already confirmed

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)

            _motionLevel.value = (magnitude / motionThreshold).coerceIn(0f, 1f)

            if (magnitude >= motionThreshold) {
                consecutiveHits++
                if (consecutiveHits >= requiredHits) {
                    _isPickupDetected.value = true
                    _motionLevel.value = 1f
                }
            } else {
                consecutiveHits = 0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun start() {
        val sensor = linearAccelSensor ?: return
        sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
            Handler(Looper.getMainLooper())
        )
    }

    override fun stop() {
        sensorManager.unregisterListener(listener)
        _motionLevel.value = 0f
    }

    override fun reset() {
        _isPickupDetected.value = false
        _motionLevel.value = 0f
        consecutiveHits = 0
    }
}

@Composable
actual fun rememberPickupGestureDetector(
    motionThreshold: Float,
    requiredHits: Int
): PickupGestureDetector {
    val context = LocalContext.current
    val detector = remember(motionThreshold, requiredHits) {
        AndroidPickupGestureDetector(context, motionThreshold, requiredHits)
    }
    DisposableEffect(detector) {
        onDispose { detector.stop() }
    }
    return detector
}
