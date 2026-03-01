package com.rivi.arwastewarrior.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.detection.DetectionResult
import com.rivi.arwastewarrior.detection.GarbageDetectionService
import com.rivi.arwastewarrior.ui.AppPalette
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    username: String,
    detectionService: GarbageDetectionService,
    onLogout: () -> Unit
) {
    var level by remember { mutableStateOf(1) }
    var points by remember { mutableStateOf(3450) }
    var streak by remember { mutableStateOf(7) }
    var demonsDefeated by remember { mutableStateOf(156) }
    var co2Saved by remember { mutableStateOf(23.4) }
    var lastDetection by remember { mutableStateOf<DetectionResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF06B88E), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(username.take(1).uppercase(), color = Color.White)
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(username, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Level $level", color = AppPalette.accentGreen, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onLogout) {
                Text("Logout", color = Color(0xFFFF6F6F))
            }
        }

        Text("AR Waste Warriors", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text("Clean the world, defeat the demons!", color = AppPalette.textMuted)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B699)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Level $level", color = Color(0xFFD4FFF2))
                Text(points.toString(), color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text("Total Points", color = Color(0xFFD4FFF2))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Streak", "$streak", "days", Color(0xFF174955), Modifier.weight(1f))
            StatCard("Defeated", "$demonsDefeated", "demons", Color(0xFF174955), Modifier.weight(1f))
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF239AEF)
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Environmental Impact", color = Color(0xFFE6F6FF))
                Text("${"%.2f".format(co2Saved)} kg", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text("CO₂ Saved", color = Color(0xFFE6F6FF))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF123F4E)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AR + AI Garbage Detection", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Scan scene, detect item, classify bin, and generate AI tip.",
                    color = AppPalette.textMuted
                )

                Button(
                    onClick = {
                        scope.launch {
                            scanning = true
                            val result = detectionService.detectGarbage()
                            lastDetection = result
                            points += result.confidence
                            demonsDefeated += result.demonsDefeatedGain
                            co2Saved += result.co2SavedKg
                            if (points >= level * 1000) {
                                level += 1
                            }
                            scanning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (scanning) "Scanning..." else "Scan For Garbage")
                }

                lastDetection?.let { result ->
                    DetectionResultCard(result)
                }
            }
        }

        Button(
            onClick = {},
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5F00)),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Start Battle")
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color(0xFFFFB155))
            Text(value, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(label, color = AppPalette.textMuted)
        }
    }
}

@Composable
private fun DetectionResultCard(result: DetectionResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3140)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Detected: ${result.category.label}", color = Color.White)
            Text("Confidence: ${result.confidence}%", color = AppPalette.accentGreen)
            Text("Recommended Bin: ${result.category.recommendedBin}", color = Color(0xFFB6DAE1))
            Text("AI Tip: ${result.aiTip}", color = Color(0xFFD0E8EC))
        }
    }
}
