package com.rivi.arwastewarrior.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import arwastewarrior.composeapp.generated.resources.Res
import arwastewarrior.composeapp.generated.resources.demon_bacteria
import arwastewarrior.composeapp.generated.resources.demon_fungus
import arwastewarrior.composeapp.generated.resources.demon_virus
import com.rivi.arwastewarrior.GameSession
import com.rivi.arwastewarrior.detection.AppLanguage
import com.rivi.arwastewarrior.detection.DemonKind
import com.rivi.arwastewarrior.detection.DemonType
import com.rivi.arwastewarrior.detection.DetectionResult
import com.rivi.arwastewarrior.detection.GameModeOption
import com.rivi.arwastewarrior.detection.GarbageCategory
import com.rivi.arwastewarrior.detection.GarbageDetectionService
import com.rivi.arwastewarrior.detection.GarbageSize
import com.rivi.arwastewarrior.detection.LiveScanResult
import com.rivi.arwastewarrior.detection.buildDemonMix
import com.rivi.arwastewarrior.detection.defaultDominantKind
import com.rivi.arwastewarrior.detection.normalizeDemonMix
import com.rivi.arwastewarrior.rememberCameraPermissionHandler
import com.rivi.arwastewarrior.rememberPickupGestureDetector
import com.rivi.arwastewarrior.speech.rememberHindiSpeechPlayer
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.components.LiveCameraDetectionView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Clock

// ── Game state machine ────────────────────────────────────────────────────────

private enum class GamePhase {
    SCANNING, DETECTED, PICKING, FINDING_BIN, THROWING, VICTORY
}

// Fractional (x,y) positions for demon icons over the camera view (upper area)
private val DEMON_SPAWN_FRACTIONS = listOf(
    0.18f to 0.16f, 0.72f to 0.12f, 0.45f to 0.30f,
    0.15f to 0.50f, 0.80f to 0.38f, 0.55f to 0.18f,
    0.33f to 0.42f, 0.88f to 0.22f
)

// ── Main screen ───────────────────────────────────────────────────────────────

@Composable
fun PlayGameScreen(
    detectionService: GarbageDetectionService,
    selectedLanguage: AppLanguage,
    session: GameSession,
    onSessionUpdate: (GameSession) -> Unit,
    onBack: () -> Unit
) {
    var gamePhase by remember { mutableStateOf(GamePhase.SCANNING) }
    var currentDetection by remember { mutableStateOf<DetectionResult?>(null) }
    var pickProgress by remember { mutableStateOf(0f) }
    var secondsLeft by remember { mutableStateOf(30) }
    var isBinDetected by remember { mutableStateOf(false) }
    val binFrameCount = remember { intArrayOf(0) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var lastAnalyzeAt by remember { mutableStateOf(0L) }
    var lastSignature by remember { mutableStateOf("") }
    var scanStatus by remember {
        mutableStateOf(t(selectedLanguage, "Point camera at garbage to scan.", "कचरे पर कैमरा रखें।"))
    }
    var showPermDialog by remember { mutableStateOf(false) }
    var isThrowConfirmedByUser by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechPlayer = rememberHindiSpeechPlayer()
    // Pickup: easier to trigger (lift phone while bending), throw: needs deliberate flick
    val pickupDetector = rememberPickupGestureDetector(motionThreshold = 2.0f, requiredHits = 1)
    val throwDetector = rememberPickupGestureDetector(motionThreshold = 4.0f, requiredHits = 3)
    val permHandler = rememberCameraPermissionHandler { granted ->
        if (!granted) showPermDialog = true
    }

    // Single LaunchedEffect drives all timed transitions
    LaunchedEffect(gamePhase) {
        when (gamePhase) {
            GamePhase.DETECTED -> {
                secondsLeft = 30
                while (secondsLeft > 0) {
                    delay(1000L)
                    secondsLeft -= 1
                }
                if (gamePhase == GamePhase.DETECTED) {
                    scanStatus = t(selectedLanguage, "Garbage escaped! Scan again.", "कचरा भाग गया! दोबारा स्कैन करें।")
                    gamePhase = GamePhase.SCANNING
                    currentDetection = null
                }
            }
            GamePhase.PICKING -> {
                pickupDetector.reset()
                pickupDetector.start()
                pickProgress = 0f
                try {
                    // 7-second fallback; exits early on confirmed lift gesture
                    val totalSteps = 70
                    for (step in 0 until totalSteps) {
                        delay(100L)
                        pickProgress = (step + 1).toFloat() / totalSteps
                        if (pickupDetector.isPickupDetected) {
                            pickProgress = 1f
                            break
                        }
                    }
                } finally {
                    pickupDetector.stop()
                }
                isBinDetected = false
                binFrameCount[0] = 0
                gamePhase = GamePhase.FINDING_BIN
                speechPlayer.speak(
                    t(selectedLanguage, "Garbage picked! Find a dustbin and point camera at it.", "कचरा उठा लिया! डस्टबिन खोजें और कैमरा उस पर रखें।"),
                    selectedLanguage
                )
            }
            GamePhase.FINDING_BIN -> {
                // Wait up to 30 seconds for ML Kit to detect a bin; 200ms poll
                var elapsed = 0
                while (elapsed < 150 && !isBinDetected) {
                    delay(200L)
                    elapsed++
                }
                speechPlayer.speak(
                    t(selectedLanguage, "Bin spotted! Throw the garbage in!", "डस्टबिन मिला! अब कचरा फेंकें!"),
                    selectedLanguage
                )
                isThrowConfirmedByUser = false
                throwDetector.reset()
                gamePhase = GamePhase.THROWING
            }
            GamePhase.THROWING -> {
                throwDetector.start()
                try {
                    // 10-second fallback; exits early on gesture OR button press
                    var throwStep = 0
                    while (throwStep < 100 &&
                        !throwDetector.isPickupDetected &&
                        !isThrowConfirmedByUser) {
                        delay(100L)
                        throwStep++
                    }
                } finally {
                    throwDetector.stop()
                }
                val det = currentDetection
                if (det != null) {
                    onSessionUpdate(
                        session.addVictory(
                            demonCount = det.demonsDefeatedGain.coerceAtLeast(1),
                            co2Kg = det.co2SavedKg
                        )
                    )
                }
                speechPlayer.speak(
                    t(selectedLanguage, "Victory! Demons defeated! Planet saved!", "जीत! डेमन हार गए! पृथ्वी बची!"),
                    selectedLanguage
                )
                gamePhase = GamePhase.VICTORY
            }
            GamePhase.VICTORY -> {
                delay(3500L)
                // Full reset for next round — clear stale Bedrock state too
                isAnalyzing = false
                lastSignature = ""
                isThrowConfirmedByUser = false
                isBinDetected = false
                binFrameCount[0] = 0
                currentDetection = null
                pickProgress = 0f
                gamePhase = GamePhase.SCANNING
                scanStatus = t(selectedLanguage, "Great work! Keep scanning!", "बढ़िया! स्कैन जारी रखें!")
            }
            else -> Unit
        }
    }

    DisposableEffect(Unit) {
        if (!permHandler.hasCameraPermission()) permHandler.requestCameraPermission()
        onDispose { speechPlayer.stop() }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Camera area (takes all remaining height) ──────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (permHandler.hasCameraPermission()) {
                LiveCameraDetectionView(
                    modifier = Modifier.fillMaxSize(),
                    onScanResult = { liveScan ->
                        // Only scan when in SCANNING phase and ML Kit is confident
                        if (gamePhase == GamePhase.SCANNING &&
                            liveScan.isLikelyGarbage &&
                            liveScan.garbageCategory != null
                        ) {
                            val sig = buildSig(liveScan)
                            val now = Clock.System.now().epochSeconds
                            if (sig != lastSignature && now - lastAnalyzeAt >= 2L) {
                                lastSignature = sig
                                lastAnalyzeAt = now

                                // ── Stage 1: Instant response from ML Kit (~0ms) ──────────
                                // Build a preliminary result right now so demons appear
                                // immediately without waiting for the network.
                                val preliminary = buildPreliminaryDetection(liveScan)
                                currentDetection = preliminary
                                gamePhase = GamePhase.DETECTED
                                speechPlayer.speak(preliminary.speechTextHindi, selectedLanguage)

                                // ── Stage 2: Bedrock enrichment in background (~5-10s) ────
                                // Capture signature NOW so we can verify this result still
                                // applies when the coroutine finishes (prevents old Bedrock
                                // responses from overwriting a NEW scan's preliminary result).
                                if (!isAnalyzing) {
                                    isAnalyzing = true
                                    val capturedSig = sig
                                    scope.launch {
                                        val enriched = detectionService.detectGarbage(liveScan, selectedLanguage)
                                        // Only apply if we're still on the SAME scan in DETECTED
                                        if (gamePhase == GamePhase.DETECTED &&
                                            lastSignature == capturedSig &&
                                            enriched.isGarbage) {
                                            currentDetection = enriched
                                        }
                                        isAnalyzing = false
                                    }
                                }
                            }
                        }
                        // Bin detection during FINDING_BIN phase
                        if (gamePhase == GamePhase.FINDING_BIN) {
                            if (liveScan.detectedBins.isNotEmpty()) {
                                binFrameCount[0]++
                                if (binFrameCount[0] >= 2) isBinDetected = true
                            } else {
                                binFrameCount[0] = 0
                            }
                        }
                    },
                    onError = { scanStatus = it }
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF061020)))
            }

            // Radar scanning overlays
            if (gamePhase == GamePhase.SCANNING) ScanningOverlay(Color(0xFF00FF99))
            if (gamePhase == GamePhase.FINDING_BIN) ScanningOverlay(Color(0xFF00BFFF))

            // AR demon overlay (visible through FINDING_BIN; dimmed when throwing)
            val det = currentDetection
            if (det != null && det.isGarbage &&
                (gamePhase == GamePhase.DETECTED || gamePhase == GamePhase.PICKING ||
                    gamePhase == GamePhase.FINDING_BIN || gamePhase == GamePhase.THROWING)
            ) {
                DemonOverlay(detection = det, dimmed = gamePhase == GamePhase.THROWING)
            }

            // Victory flash on camera
            if (gamePhase == GamePhase.VICTORY) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x4400FF88)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "🎉",
                        fontSize = 80.sp
                    )
                }
            }

            // Top HUD (always visible)
            GameHud(
                session = session,
                selectedLanguage = selectedLanguage,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            )

            // Timer badge during DETECTED
            if (gamePhase == GamePhase.DETECTED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 16.dp)
                        .size(52.dp)
                        .background(
                            color = if (secondsLeft <= 10) Color(0xEEFF2222) else Color(0xCC000000),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$secondsLeft",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Bottom game panel ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF5061020))
        ) {
            when (gamePhase) {
                GamePhase.SCANNING -> ScanningPanel(scanStatus, selectedLanguage, permHandler.hasCameraPermission()) {
                    permHandler.requestCameraPermission()
                }
                GamePhase.DETECTED -> DetectedPanel(
                    detection = currentDetection!!,
                    selectedLanguage = selectedLanguage,
                    onPickUp = {
                        gamePhase = GamePhase.PICKING
                        speechPlayer.speak(
                            t(selectedLanguage, "Picking up the garbage...", "कचरा उठा रहे हैं..."),
                            selectedLanguage
                        )
                    }
                )
                GamePhase.PICKING -> PickingPanel(
                    progress = pickProgress,
                    motionLevel = pickupDetector.motionLevel,
                    isPickupConfirmed = pickupDetector.isPickupDetected,
                    selectedLanguage = selectedLanguage
                )
                GamePhase.FINDING_BIN -> FindingBinPanel(
                    isBinDetected = isBinDetected,
                    selectedLanguage = selectedLanguage
                )
                GamePhase.THROWING -> ThrowingPanel(
                    motionLevel = throwDetector.motionLevel,
                    isThrowConfirmed = throwDetector.isPickupDetected || isThrowConfirmedByUser,
                    selectedLanguage = selectedLanguage,
                    onConfirmThrow = { isThrowConfirmedByUser = true }
                )
                GamePhase.VICTORY -> VictoryPanel(
                    detection = currentDetection!!,
                    session = session,
                    selectedLanguage = selectedLanguage
                )
            }
        }
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text(t(selectedLanguage, "Camera Required", "कैमरा आवश्यक")) },
            text = { Text(t(selectedLanguage, "Grant camera access to play.", "गेम खेलने के लिए कैमरा एक्सेस दें।")) },
            confirmButton = {
                TextButton(onClick = { showPermDialog = false; permHandler.requestCameraPermission() }) {
                    Text(t(selectedLanguage, "Grant", "अनुमति दें"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermDialog = false }) {
                    Text(t(selectedLanguage, "Cancel", "रद्द करें"))
                }
            }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun GameHud(
    session: GameSession,
    selectedLanguage: AppLanguage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xCC000000))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text("← ${t(selectedLanguage, "Back", "वापस")}", color = Color(0xFFFFB155))
        }
        HudStat(value = "${session.score}", label = t(selectedLanguage, "pts", "अंक"), color = Color.White)
        HudStat(value = "${session.demonsDefeated}", label = t(selectedLanguage, "demons", "डेमन"), color = Color(0xFFFF6B35))
        HudStat(value = "${session.itemsDisposed}", label = t(selectedLanguage, "cleaned", "साफ"), color = AppPalette.accentGreen)
    }
}

@Composable
private fun HudStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, color = AppPalette.textMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ScanningOverlay(color: Color) {
    val inf = rememberInfiniteTransition(label = "scan")
    val sweep by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val pulse by inf.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val scanColor = color
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy) * 0.62f
        val sw = 2.5.dp.toPx()
        // Pulsing outer circle
        drawCircle(scanColor.copy(alpha = pulse * 0.4f), r, Offset(cx, cy), style = Stroke(sw))
        // Inner circle
        drawCircle(scanColor.copy(alpha = pulse * 0.25f), r * 0.55f, Offset(cx, cy), style = Stroke(sw * 0.7f))
        // Rotating sweep line
        rotate(sweep, Offset(cx, cy)) {
            drawLine(
                color = scanColor.copy(alpha = 0.85f),
                start = Offset(cx, cy),
                end = Offset(cx, cy - r),
                strokeWidth = sw * 1.2f,
                cap = StrokeCap.Round
            )
        }
        // Corner brackets
        val bx1 = cx - r * 0.68f; val by1 = cy - r * 0.68f
        val bx2 = cx + r * 0.68f; val by2 = cy + r * 0.68f
        val bl = 34f; val bw = sw * 2.2f; val bc = scanColor.copy(alpha = 0.75f)
        drawLine(bc, Offset(bx1, by1 + bl), Offset(bx1, by1), bw)
        drawLine(bc, Offset(bx1, by1), Offset(bx1 + bl, by1), bw)
        drawLine(bc, Offset(bx2 - bl, by1), Offset(bx2, by1), bw)
        drawLine(bc, Offset(bx2, by1), Offset(bx2, by1 + bl), bw)
        drawLine(bc, Offset(bx1, by2 - bl), Offset(bx1, by2), bw)
        drawLine(bc, Offset(bx1, by2), Offset(bx1 + bl, by2), bw)
        drawLine(bc, Offset(bx2 - bl, by2), Offset(bx2, by2), bw)
        drawLine(bc, Offset(bx2, by2), Offset(bx2, by2 - bl), bw)
        // Crosshair centre
        drawLine(scanColor.copy(alpha = 0.5f), Offset(cx - 60f, cy), Offset(cx + 60f, cy), sw)
        drawLine(scanColor.copy(alpha = 0.5f), Offset(cx, cy - 60f), Offset(cx, cy + 60f), sw)
        drawCircle(scanColor, 5f, Offset(cx, cy))
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val alpha by rememberInfiniteTransition(label = "txt").animateFloat(
            0.5f, 1f,
            infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "ta"
        )
        Text(
            if (color == Color(0xFF00BFFF)) "AI Bin Scanner..." else "AI Scanning...",
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun DemonOverlay(detection: DetectionResult, dimmed: Boolean) {
    val demonList = remember(detection.demonMix, detection.demonCount) {
        val mix = normalizeDemonMix(
            proposedMix = detection.demonMix,
            totalCount = detection.demonCount.coerceAtLeast(1),
            dominantKind = defaultDominantKind(detection.category)
        )
        mix.flatMap { spawn -> List(spawn.count) { spawn.kind } }.take(8)
    }

    val inf = rememberInfiniteTransition(label = "demon")
    val pulse by inf.animateFloat(
        initialValue = 0.88f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dp"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val iconDp = 56.dp
        demonList.forEachIndexed { idx, kind ->
            val (fx, fy) = DEMON_SPAWN_FRACTIONS[idx % DEMON_SPAWN_FRACTIONS.size]
            Image(
                painter = painterResource(demonRes(kind)),
                contentDescription = kind.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(iconDp)
                    .offset(x = w * fx - iconDp / 2, y = h * fy - iconDp / 2)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                        alpha = if (dimmed) 0.55f else 1f
                    }
            )
        }
    }
}

@Composable
private fun ScanningPanel(
    status: String,
    selectedLanguage: AppLanguage,
    hasCamera: Boolean,
    onRequestCamera: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasCamera) {
            Text(
                t(selectedLanguage, "Camera permission required.", "कैमरा अनुमति आवश्यक है।"),
                color = Color(0xFFFFB155),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRequestCamera,
                colors = ButtonDefaults.buttonColors(containerColor = AppPalette.accentGreen)
            ) { Text(t(selectedLanguage, "Enable Camera", "कैमरा चालू करें"), color = Color.Black) }
        } else {
            Text(
                t(selectedLanguage, "AI Waste Warrior", "AI वेस्ट वॉरियर"),
                color = AppPalette.accentGreen,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(status, color = AppPalette.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetectedPanel(
    detection: DetectionResult,
    selectedLanguage: AppLanguage,
    onPickUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Badge(text = detection.category.label, color = Color(0xFFFF4E00))
            Badge(text = "x${detection.demonCount} ${t(selectedLanguage, "Demons", "डेमन")}", color = Color(0xFFAA22FF))
            Badge(text = "${(detection.confidence)}%", color = Color(0xFF0088CC))
        }
        Text(
            detection.aiTip,
            color = AppPalette.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
        if (detection.diseaseWarningHindi.isNotBlank()) {
            Text(
                if (selectedLanguage == AppLanguage.HINDI) detection.diseaseWarningHindi else detection.diseaseWarningHindi,
                color = Color(0xFFFFD5B5),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2
            )
        }
        Button(
            onClick = onPickUp,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                t(selectedLanguage, "PICK UP GARBAGE", "कचरा उठाएं"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PickingPanel(
    progress: Float,
    motionLevel: Float,
    isPickupConfirmed: Boolean,
    selectedLanguage: AppLanguage
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 6.dp,
                    color = if (isPickupConfirmed) AppPalette.accentGreen else Color(0xFFFFB155),
                    trackColor = Color(0xFF1A4455)
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isPickupConfirmed)
                        t(selectedLanguage, "✓ PICKUP CONFIRMED!", "✓ उठा लिया!")
                    else
                        t(selectedLanguage, "Lift phone to pick up!", "फोन ऊपर उठाएं!"),
                    color = if (isPickupConfirmed) AppPalette.accentGreen else Color(0xFFFFB155),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isPickupConfirmed)
                        t(selectedLanguage, "Gesture detected! Finding bin...", "हाव-भाव पकड़ा! डस्टबिन ढूंढ रहे हैं...")
                    else
                        t(selectedLanguage, "Move your phone upward as you pick up the garbage", "कचरा उठाते हुए फोन ऊपर उठाएं"),
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Live motion meter
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                t(selectedLanguage, "Motion sensor", "गति सेंसर"),
                color = AppPalette.textMuted,
                style = MaterialTheme.typography.labelSmall
            )
            LinearProgressIndicator(
                progress = { motionLevel },
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    isPickupConfirmed -> AppPalette.accentGreen
                    motionLevel > 0.7f -> Color(0xFFFFD700)
                    else -> Color(0xFF0088CC)
                },
                trackColor = Color(0xFF1A4455)
            )
        }
    }
}

@Composable
private fun FindingBinPanel(
    isBinDetected: Boolean,
    selectedLanguage: AppLanguage
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (isBinDetected)
                t(selectedLanguage, "✓ Bin detected! Hold steady...", "✓ डस्टबिन मिला! स्थिर रखें...")
            else
                t(selectedLanguage, "Find a garbage bin", "कचरा डस्टबिन खोजें"),
            color = if (isBinDetected) AppPalette.accentGreen else Color(0xFF00BFFF),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (isBinDetected)
                t(selectedLanguage, "Bin confirmed. Preparing throw...", "डस्टबिन पुष्टि हुई। थ्रो की तैयारी...")
            else
                t(selectedLanguage, "Point camera at any dustbin or trash can", "कैमरा किसी भी डस्टबिन पर रखें"),
            color = AppPalette.textMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ThrowingPanel(
    motionLevel: Float,
    isThrowConfirmed: Boolean,
    selectedLanguage: AppLanguage,
    onConfirmThrow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (isThrowConfirmed)
                t(selectedLanguage, "✓ THROWN! Defeating demons...", "✓ फेंक दिया! डेमन हार रहे हैं...")
            else
                t(selectedLanguage, "THROW the garbage into the bin!", "कचरा डस्टबिन में फेंकें!"),
            color = if (isThrowConfirmed) AppPalette.accentGreen else Color(0xFFFF4E00),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (!isThrowConfirmed) {
            // Primary action — always reliable for demo
            Button(
                onClick = onConfirmThrow,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    t(selectedLanguage, "Confirm Throw!", "थ्रो कन्फर्म करें!"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            // Secondary — physical gesture indicator
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    t(selectedLanguage, "Or flick phone forward (throw force)", "या फोन आगे झटकें (थ्रो बल)"),
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { motionLevel },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (motionLevel > 0.7f) Color(0xFFFF4E00) else Color(0xFF0088CC),
                    trackColor = Color(0xFF1A4455)
                )
            }
        } else {
            Text(
                t(selectedLanguage, "Gesture / tap detected!", "हाव-भाव / टैप पकड़ा!"),
                color = AppPalette.textMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VictoryPanel(
    detection: DetectionResult,
    session: GameSession,
    selectedLanguage: AppLanguage
) {
    val gained = detection.demonsDefeatedGain.coerceAtLeast(1) * 15 + 10
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            t(selectedLanguage, "DEMONS DEFEATED!", "डेमन हार गए!"),
            color = AppPalette.accentGreen,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VictoryStat("+$gained", t(selectedLanguage, "points", "अंक"), Color.White)
            VictoryStat(
                "${detection.demonsDefeatedGain.coerceAtLeast(1)}",
                t(selectedLanguage, "demons", "डेमन"),
                Color(0xFFFF6B35)
            )
            VictoryStat(
                formatKg(detection.co2SavedKg),
                t(selectedLanguage, "CO₂ saved", "CO₂ बचाया"),
                AppPalette.accentGreen
            )
        }
        Text(
            t(selectedLanguage, "Total score: ${session.score}", "कुल अंक: ${session.score}"),
            color = AppPalette.textMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            t(selectedLanguage, "Returning to scan in 3s...", "3 सेकंड में स्कैन पर वापस..."),
            color = AppPalette.textMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun VictoryStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = AppPalette.textMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

// ── Preliminary detection (instant, ML Kit only — no network) ────────────────
//
// Demon count is intentionally varied by rawLabels.size + confidence so the
// same garbage type spawns different counts depending on scan quality/context.

private fun buildPreliminaryDetection(liveScan: LiveScanResult): DetectionResult {
    val category = liveScan.garbageCategory ?: GarbageCategory.PLASTIC

    val base = when (category) {
        GarbageCategory.E_WASTE -> 4
        GarbageCategory.PLASTIC, GarbageCategory.METAL -> 3
        GarbageCategory.ORGANIC, GarbageCategory.PAPER, GarbageCategory.GLASS -> 2
        GarbageCategory.UNKNOWN -> 1
    }
    val sizeBonus = when (liveScan.garbageSize) {
        GarbageSize.SMALL -> 0; GarbageSize.MEDIUM -> 1; GarbageSize.LARGE -> 2
    }
    // contextBonus = 0..2  — varies with how many ML Kit labels fired and confidence level
    val contextBonus = ((liveScan.rawLabels.size / 2) + (liveScan.confidence / 50)).coerceIn(0, 2)
    val demonCount = (base + sizeBonus + contextBonus).coerceIn(1, 8)

    val demonType = when (category) {
        GarbageCategory.E_WASTE -> DemonType.E_WASTE
        GarbageCategory.ORGANIC -> DemonType.ORGANIC
        else -> DemonType.PLASTIC
    }
    val dominantKind = defaultDominantKind(category)

    return DetectionResult(
        category = category,
        isGarbage = true,
        confidence = liveScan.confidence.coerceIn(55, 99),
        aiTip = "${category.label} waste detected. AI is generating tips...",
        binIssue = if (liveScan.detectedBins.isNotEmpty()) "Bin detected nearby." else "Locate the correct bin.",
        actionPrompt = "Pick up the garbage and throw it in the correct bin.",
        co2SavedKg = co2ForCategory(category),
        demonsDefeatedGain = demonCount,
        demonCount = demonCount,
        demonType = demonType,
        demonMix = buildDemonMix(demonCount, dominantKind),
        gameModeOptions = listOf(GameModeOption.REAL),
        recommendedMode = GameModeOption.REAL,
        diseaseWarningHindi = "कचरे का गलत निपटान बीमारियों का जोखिम बढ़ाता है।",
        speechTextHindi = "कचरा मिला! जल्दी उठाइए।"
    )
}

private fun co2ForCategory(category: GarbageCategory): Double = when (category) {
    GarbageCategory.E_WASTE -> 0.61
    GarbageCategory.METAL -> 0.52
    GarbageCategory.PLASTIC -> 0.32
    GarbageCategory.GLASS -> 0.27
    GarbageCategory.PAPER -> 0.18
    GarbageCategory.ORGANIC -> 0.14
    GarbageCategory.UNKNOWN -> 0.10
}

private fun buildSig(scan: LiveScanResult): String {
    val cat = scan.garbageCategory?.name ?: "NONE"
    val bins = scan.detectedBins.joinToString("|") { "${it.type.name}:${it.confidence}" }
    val labels = scan.rawLabels.take(3).joinToString("|")
    return "$cat::${scan.isLikelyGarbage}::${scan.garbageSize.name}::$bins::$labels"
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun t(lang: AppLanguage, en: String, hi: String) = if (lang == AppLanguage.HINDI) hi else en

private fun demonRes(kind: DemonKind): DrawableResource = when (kind) {
    DemonKind.BACTERIA -> Res.drawable.demon_bacteria
    DemonKind.VIRUS -> Res.drawable.demon_virus
    DemonKind.FUNGUS -> Res.drawable.demon_fungus
}

// KMP-safe decimal formatter (no String.format in commonMain)
private fun formatKg(kg: Double): String {
    val grams = (kg * 1000).toInt()
    return if (grams < 1000) "${grams}g" else "${grams / 1000}.${(grams % 1000) / 100}kg"
}

