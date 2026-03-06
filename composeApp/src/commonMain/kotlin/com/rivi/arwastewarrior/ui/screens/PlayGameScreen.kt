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
import androidx.compose.runtime.mutableStateMapOf
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
import com.rivi.arwastewarrior.detection.DetectionResult
import com.rivi.arwastewarrior.detection.GarbageCategory
import com.rivi.arwastewarrior.detection.GarbageDetectionService
import com.rivi.arwastewarrior.detection.LiveScanResult
import com.rivi.arwastewarrior.detection.defaultDominantKind
import com.rivi.arwastewarrior.detection.normalizeDemonMix
import com.rivi.arwastewarrior.rememberCameraPermissionHandler
import com.rivi.arwastewarrior.speech.rememberHindiSpeechPlayer
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.components.LiveCameraDetectionView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    var currentSceneKey by remember { mutableStateOf<String?>(null) }
    var currentBinSceneHash by remember { mutableStateOf<String?>(null) }
    var latestLiveScan by remember { mutableStateOf<LiveScanResult?>(null) }
    var pickProgress by remember { mutableStateOf(0f) }
    var secondsLeft by remember { mutableStateOf(2) }
    var isBinDetected by remember { mutableStateOf(false) }
    val binFrameCount = remember { intArrayOf(0) }
    val sceneDemonRemaining = remember { mutableStateMapOf<String, Int>() }
    val sceneDetectionCache = remember { mutableStateMapOf<String, DetectionResult>() }
    val sceneKeyAlias = remember { mutableStateMapOf<String, String>() }
    val knownBinScenes = remember { mutableStateMapOf<String, String>() }
    var pendingThrowDemons by remember { mutableStateOf(0) }
    var pendingThrowCo2Kg by remember { mutableStateOf(0.0) }
    var pendingThrowSceneKey by remember { mutableStateOf<String?>(null) }
    var victoryDemons by remember { mutableStateOf(0) }
    var victoryCo2SavedKg by remember { mutableStateOf(0.0) }
    var pickupAiPending by remember { mutableStateOf(false) }
    var throwAiPending by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzingSceneKey by remember { mutableStateOf("") }
    var isBinAnalyzing by remember { mutableStateOf(false) }
    var lastBinAnalyzeAt by remember { mutableStateOf(0L) }
    var lastAnalyzeAt by remember { mutableStateOf(0L) }
    var lastSignature by remember { mutableStateOf("") }
    var scanStatus by remember {
        mutableStateOf(t(selectedLanguage, "Point camera at garbage to scan.", "कचरे पर कैमरा रखें।"))
    }
    var showPermDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechPlayer = rememberHindiSpeechPlayer()
    var throwProgress by remember { mutableStateOf(0f) }
    val permHandler = rememberCameraPermissionHandler { granted ->
        if (!granted) showPermDialog = true
    }

    // Single LaunchedEffect drives all timed transitions
    LaunchedEffect(gamePhase) {
        when (gamePhase) {
            GamePhase.DETECTED -> {
                secondsLeft = 2
                while (secondsLeft > 0 && gamePhase == GamePhase.DETECTED) {
                    delay(1000L)
                    secondsLeft -= 1
                }
                if (gamePhase == GamePhase.DETECTED) {
                    val det = currentDetection
                    if (det == null || !det.isGarbage || det.remainingDemons <= 0) {
                        gamePhase = GamePhase.SCANNING
                        currentDetection = null
                        currentSceneKey = null
                        return@LaunchedEffect
                    }
                    gamePhase = GamePhase.PICKING
                    speechPlayer.speak(
                        t(
                            selectedLanguage,
                            "Pick the garbage and keep camera on the scene for AI verification.",
                            "कचरा उठाएं और AI वेरिफिकेशन के लिए कैमरा सीन पर रखें।"
                        ),
                        selectedLanguage
                    )
                }
            }
            GamePhase.PICKING -> {
                pickupAiPending = false
                pickProgress = 0f
                val totalSteps = 25 // ~2.5s camera evidence window
                for (step in 0 until totalSteps) {
                    delay(100L)
                    // Keep progress below 100% until backend AI confirms.
                    pickProgress = ((step + 1).toFloat() / totalSteps) * 0.75f
                }
                val det = currentDetection
                val sceneKey = currentSceneKey
                if (det != null && sceneKey != null) {
                    scope.launch {
                        pickupAiPending = true
                        pickProgress = 0.85f
                        val pickupDecision = withTimeoutOrNull(2500L) {
                            detectionService.checkPickup(
                                sceneHash = sceneKey,
                                liveScanResult = latestLiveScan,
                                language = selectedLanguage,
                                motionPeak = 0f,
                                motionHits = 0,
                                durationMs = 7000
                            )
                        }
                        val availableDemons = (sceneDemonRemaining[sceneKey] ?: det.remainingDemons)
                            .takeIf { it > 0 }
                            ?: det.demonCount.coerceAtLeast(1)
                        val backendPickup = pickupDecision
                        val confirmedByAi = backendPickup?.pickupConfirmed == true
                        if (!confirmedByAi) {
                            pickupAiPending = false
                            pickProgress = 0f
                            scanStatus = when {
                                backendPickup == null -> t(
                                    selectedLanguage,
                                    "AI pickup verification unavailable. Scan again.",
                                    "AI पिकअप वेरिफिकेशन उपलब्ध नहीं है। फिर से स्कैन करें।"
                                )
                                else -> backendPickup.reason.ifBlank {
                                    t(selectedLanguage, "Pickup not confirmed. Scan again.", "पिकअप कन्फर्म नहीं हुआ। फिर स्कैन करें।")
                                }
                            }
                            // Keep the same scene active for retry; do not clear current detection.
                            gamePhase = GamePhase.DETECTED
                            return@launch
                        }
                        pickupAiPending = false
                        pickProgress = 1f

                        pendingThrowDemons = backendPickup?.pickupStrength
                            ?.takeIf { it > 0 }
                            ?: estimatePickupDemons(
                                availableDemons = availableDemons,
                                peakMotion = 0.5f
                            )
                        pendingThrowDemons = pendingThrowDemons.coerceIn(1, availableDemons.coerceAtLeast(1))
                        pendingThrowSceneKey = sceneKey
                        pendingThrowCo2Kg = det.co2SavedKg *
                            pendingThrowDemons.toDouble() /
                            det.demonCount.coerceAtLeast(1).toDouble()
                        isBinDetected = false
                        currentBinSceneHash = null
                        binFrameCount[0] = 0
                        throwAiPending = false
                        throwProgress = 0f
                        gamePhase = GamePhase.FINDING_BIN
                        speechPlayer.speak(
                            t(
                                selectedLanguage,
                                "Garbage picked. Find a dustbin and point camera at it.",
                                "कचरा उठा लिया। डस्टबिन खोजें और कैमरा उस पर रखें।"
                            ),
                            selectedLanguage
                        )
                    }
                } else {
                    pickupAiPending = false
                    scanStatus = t(
                        selectedLanguage,
                        "Pickup not confirmed. Scan the garbage again.",
                        "पिकअप कन्फर्म नहीं हुआ। दोबारा स्कैन करें।"
                    )
                    gamePhase = GamePhase.SCANNING
                    currentDetection = null
                    currentSceneKey = null
                    pickProgress = 0f
                }
            }
            GamePhase.FINDING_BIN -> {
                // Wait until the bin is actually detected; never force success.
                var elapsedTicks = 0
                while (gamePhase == GamePhase.FINDING_BIN && !isBinDetected) {
                    delay(200L)
                    elapsedTicks++
                    if (elapsedTicks % 75 == 0) {
                        scanStatus = t(
                            selectedLanguage,
                            "Searching for bin... keep the camera steady on a dustbin.",
                            "डस्टबिन खोज रहे हैं... कैमरा डस्टबिन पर स्थिर रखें।"
                        )
                    }
                }
                if (gamePhase != GamePhase.FINDING_BIN || !isBinDetected) {
                    return@LaunchedEffect
                }
                speechPlayer.speak(
                    t(selectedLanguage, "Bin spotted! Throw the garbage in!", "डस्टबिन मिला! अब कचरा फेंकें!"),
                    selectedLanguage
                )
                throwAiPending = false
                gamePhase = GamePhase.THROWING
            }
            GamePhase.THROWING -> {
                throwAiPending = false
                throwProgress = 0f
                // Camera evidence window before backend throw verification.
                var throwStep = 0
                while (throwStep < 30 && gamePhase == GamePhase.THROWING) {
                    delay(100L)
                    throwStep++
                    // Keep progress below 100% until backend AI confirms.
                    throwProgress = (throwStep / 30f) * 0.75f
                }
                if (gamePhase != GamePhase.THROWING) {
                    return@LaunchedEffect
                }

                val sceneKey = pendingThrowSceneKey
                val det = currentDetection
                if (sceneKey == null || det == null) {
                    gamePhase = GamePhase.SCANNING
                    return@LaunchedEffect
                }
                throwAiPending = true
                throwProgress = 0.85f
                val throwDecision = withTimeoutOrNull(3000L) {
                    detectionService.checkThrow(
                        sceneHash = sceneKey,
                        binSceneHash = currentBinSceneHash,
                        liveScanResult = latestLiveScan,
                        language = selectedLanguage,
                        motionPeak = 0f,
                        motionHits = 0,
                        durationMs = 10_000,
                        binDetected = isBinDetected,
                        requestedDestroyCount = pendingThrowDemons.coerceAtLeast(1)
                    )
                }
                val throwConfirmedByAi = throwDecision?.throwConfirmed == true
                if (!throwConfirmedByAi) {
                    throwAiPending = false
                    throwProgress = 0f
                    scanStatus = throwDecision?.reason?.ifBlank {
                        t(selectedLanguage, "Throw not confirmed. Try again.", "थ्रो कन्फर्म नहीं हुआ। फिर कोशिश करें।")
                    } ?: t(selectedLanguage, "Throw not confirmed. Try again.", "थ्रो कन्फर्म नहीं हुआ। फिर कोशिश करें।")
                    gamePhase = GamePhase.FINDING_BIN
                    return@LaunchedEffect
                }
                throwAiPending = false
                throwProgress = 1f

                val currentRemaining = (sceneDemonRemaining[sceneKey] ?: det.remainingDemons)
                    .takeIf { it > 0 }
                    ?: det.demonCount.coerceAtLeast(1)
                val defeated = throwDecision?.destroyedDemons?.takeIf { it > 0 }
                    ?: throwDecision?.destroyCount?.coerceIn(1, currentRemaining)
                    ?: pendingThrowDemons.coerceIn(1, currentRemaining)
                val updatedRemaining = throwDecision?.remainingDemons
                    ?.takeIf { it >= 0 }
                    ?: (currentRemaining - defeated).coerceAtLeast(0)
                syncSceneState(
                    sceneKeyAlias = sceneKeyAlias,
                    sceneDemonRemaining = sceneDemonRemaining,
                    sceneDetectionCache = sceneDetectionCache,
                    canonicalSceneKey = sceneKey,
                    remainingDemons = updatedRemaining
                )

                victoryDemons = defeated
                victoryCo2SavedKg = pendingThrowCo2Kg.coerceAtLeast(0.0)
                onSessionUpdate(
                    session.addVictory(
                        demonCount = defeated,
                        co2Kg = victoryCo2SavedKg
                    )
                )

                if (updatedRemaining > 0) {
                    // Continue cleanup in the same scene from pickup flow; do not send user
                    // back to throw again unless they pick the next garbage item.
                    val cachedBase = sceneDetectionCache[sceneKey] ?: det
                    currentDetection = applyRemainingDemons(
                        detection = cachedBase.copy(sceneHash = sceneKey),
                        remainingDemons = updatedRemaining
                    )
                    currentSceneKey = sceneKey
                    pendingThrowDemons = 0
                    pendingThrowCo2Kg = 0.0
                    pendingThrowSceneKey = null
                    isBinDetected = false
                    currentBinSceneHash = null
                    binFrameCount[0] = 0
                    throwAiPending = false
                    throwProgress = 0f
                    pickupAiPending = false
                    scanStatus = t(
                        selectedLanguage,
                        "$updatedRemaining demons remain. Pick next garbage from this scene.",
                        "अभी $updatedRemaining डेमन बाकी हैं। इसी सीन से अगला कचरा उठाएं।"
                    )
                    speechPlayer.speak(
                        t(
                            selectedLanguage,
                            "$updatedRemaining demons remain. Pick next garbage.",
                            "अभी $updatedRemaining डेमन बाकी हैं। अगला कचरा उठाएं।"
                        ),
                        selectedLanguage
                    )
                    gamePhase = GamePhase.DETECTED
                } else {
                    scanStatus = t(
                        selectedLanguage,
                        "Scene cleared. Look for other garbage.",
                        "सीन साफ हो गया। अब दूसरा कचरा खोजें।"
                    )
                    speechPlayer.speak(
                        t(selectedLanguage, "Victory! Demons defeated! Planet saved!", "जीत! डेमन हार गए! पृथ्वी बची!"),
                        selectedLanguage
                    )
                    gamePhase = GamePhase.VICTORY
                }
            }
            GamePhase.VICTORY -> {
                delay(3500L)
                // Full reset for next round — clear stale Bedrock state too
                isAnalyzing = false
                analyzingSceneKey = ""
                isBinAnalyzing = false
                lastSignature = ""
                isBinDetected = false
                binFrameCount[0] = 0
                currentDetection = null
                currentSceneKey = null
                currentBinSceneHash = null
                pickProgress = 0f
                pendingThrowDemons = 0
                pendingThrowCo2Kg = 0.0
                pendingThrowSceneKey = null
                pickupAiPending = false
                throwAiPending = false
                throwProgress = 0f
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
                        latestLiveScan = liveScan
                        // Garbage scene scanning: trigger AI analysis, but spawn demons only on trusted AI confirmation.
                        if (gamePhase == GamePhase.SCANNING &&
                            (liveScan.isLikelyGarbage || looksLikeGarbageByLabels(liveScan.rawLabels))
                        ) {
                            val rawSig = buildGarbageSceneKey(liveScan)
                            val sig = sceneKeyAlias[rawSig] ?: rawSig
                            val now = Clock.System.now().toEpochMilliseconds()
                            val elapsedSinceAnalyze = now - lastAnalyzeAt
                            val canAnalyze = (sig != lastSignature && elapsedSinceAnalyze >= 700L) ||
                                (sig == lastSignature && elapsedSinceAnalyze >= 3000L)
                            if (canAnalyze) {
                                lastSignature = sig
                                lastAnalyzeAt = now

                                val cachedRemaining = sceneDemonRemaining[sig]
                                if (cachedRemaining != null && cachedRemaining <= 0) {
                                    scanStatus = t(
                                        selectedLanguage,
                                        "This scene is already cleaned. Scan another garbage spot.",
                                        "यह सीन पहले ही साफ हो चुका है। किसी और कचरे को स्कैन करें।"
                                    )
                                } else {
                                    currentSceneKey = sig
                                    val cachedDetection = sceneDetectionCache[sig] ?: sceneDetectionCache[rawSig]
                                    if (cachedDetection != null && cachedRemaining != null) {
                                        currentDetection = applyRemainingDemons(cachedDetection, cachedRemaining)
                                        gamePhase = GamePhase.DETECTED
                                        speechPlayer.speak(cachedDetection.speechTextHindi, selectedLanguage)
                                    } else {
                                        scanStatus = t(
                                            selectedLanguage,
                                            "Analyzing scene with AI...",
                                            "AI से सीन का विश्लेषण किया जा रहा है..."
                                        )
                                        if (!isAnalyzing || analyzingSceneKey != sig) {
                                            isAnalyzing = true
                                            analyzingSceneKey = sig
                                            val capturedSig = sig
                                            val capturedRawSig = rawSig
                                            scope.launch {
                                                try {
                                                    val enriched = withTimeoutOrNull(6000L) {
                                                        detectionService.scanGarbage(liveScan, selectedLanguage)
                                                    }
                                                    if (enriched == null) {
                                                        if (gamePhase == GamePhase.SCANNING && lastSignature == capturedSig) {
                                                            scanStatus = t(
                                                                selectedLanguage,
                                                                "AI is taking longer. Hold steady and keep scanning.",
                                                                "AI को समय लग रहा है। कैमरा स्थिर रखें और स्कैन जारी रखें।"
                                                            )
                                                        }
                                                        return@launch
                                                    }

                                                    val aiSource = enriched.source.trim().uppercase()
                                                    // Backward-compatible: older Lambda responses may omit `source`
                                                    // and parse as UNKNOWN even when backend inference succeeded.
                                                    val trustedByAi = aiSource == "BEDROCK" ||
                                                        aiSource == "CACHE" ||
                                                        aiSource == "UNKNOWN"
                                                    val confirmedGarbage = trustedByAi &&
                                                        enriched.isGarbage &&
                                                        enriched.category != GarbageCategory.UNKNOWN &&
                                                        (enriched.remainingDemons > 0 || enriched.demonCount > 0)
                                                    if (!confirmedGarbage) {
                                                        if (gamePhase == GamePhase.SCANNING && lastSignature == capturedSig) {
                                                            currentDetection = null
                                                            currentSceneKey = null
                                                            scanStatus = t(
                                                                selectedLanguage,
                                                                if (!trustedByAi) {
                                                                    "AI verification unavailable. Check network/Lambda auth."
                                                                } else {
                                                                    "No garbage detected. Point to actual waste."
                                                                },
                                                                if (!trustedByAi) {
                                                                    "AI वेरिफिकेशन उपलब्ध नहीं है। नेटवर्क/Lambda auth जांचें।"
                                                                } else {
                                                                    "कचरा नहीं मिला। वास्तविक कचरे पर कैमरा रखें।"
                                                                }
                                                            )
                                                        }
                                                    } else {
                                                        val backendSceneKey = enriched.sceneHash.ifBlank { capturedSig }
                                                        sceneKeyAlias[capturedRawSig] = backendSceneKey
                                                        if (backendSceneKey != capturedSig) {
                                                            sceneDemonRemaining[capturedSig]?.let { known ->
                                                                val existing = sceneDemonRemaining[backendSceneKey]
                                                                sceneDemonRemaining[backendSceneKey] = if (existing == null) {
                                                                    known
                                                                } else {
                                                                    minOf(existing, known)
                                                                }
                                                            }
                                                        }
                                                        currentSceneKey = backendSceneKey
                                                        sceneDetectionCache[backendSceneKey] = enriched
                                                        sceneDetectionCache[capturedRawSig] = enriched.copy(
                                                            sceneHash = backendSceneKey
                                                        )
                                                        val backendSuggested = enriched.remainingDemons
                                                            .takeIf { it >= 0 }
                                                        val knownRemaining = sceneDemonRemaining[backendSceneKey]
                                                            ?: sceneDemonRemaining[capturedSig]
                                                        val liveRemaining = when {
                                                            knownRemaining != null && backendSuggested != null -> {
                                                                minOf(knownRemaining, backendSuggested)
                                                            }
                                                            knownRemaining != null -> knownRemaining
                                                            backendSuggested != null -> backendSuggested
                                                            else -> enriched.demonCount.coerceAtLeast(1)
                                                        }
                                                        syncSceneState(
                                                            sceneKeyAlias = sceneKeyAlias,
                                                            sceneDemonRemaining = sceneDemonRemaining,
                                                            sceneDetectionCache = sceneDetectionCache,
                                                            canonicalSceneKey = backendSceneKey,
                                                            remainingDemons = liveRemaining
                                                        )
                                                        if (gamePhase == GamePhase.SCANNING &&
                                                            lastSignature == capturedSig
                                                        ) {
                                                            val finalDetection = applyRemainingDemons(enriched, liveRemaining)
                                                            currentDetection = finalDetection
                                                            currentSceneKey = backendSceneKey
                                                            gamePhase = GamePhase.DETECTED
                                                            speechPlayer.speak(finalDetection.speechTextHindi, selectedLanguage)
                                                        }
                                                    }
                                                } catch (_: Exception) {
                                                    if (gamePhase == GamePhase.SCANNING && lastSignature == capturedSig) {
                                                        scanStatus = t(
                                                            selectedLanguage,
                                                            "AI check failed. Try scanning again.",
                                                            "AI जांच विफल रही। फिर से स्कैन करें।"
                                                        )
                                                    }
                                                } finally {
                                                    if (analyzingSceneKey == capturedSig) {
                                                        isAnalyzing = false
                                                        analyzingSceneKey = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Bin detection during FINDING_BIN phase; cached bins confirm faster.
                        if (gamePhase == GamePhase.FINDING_BIN) {
                            val binKey = buildBinSceneKey(liveScan)
                            val hasBin = liveScan.detectedBins.isNotEmpty()
                            val now = Clock.System.now().toEpochMilliseconds()
                            if (hasBin && binKey != null) {
                                val bestBin = liveScan.detectedBins.maxByOrNull { it.confidence }
                                val requiredFrames = if (
                                    knownBinScenes.containsKey(binKey) || (bestBin?.confidence ?: 0) >= 55
                                ) {
                                    1
                                } else {
                                    2
                                }
                                binFrameCount[0]++
                                if (binFrameCount[0] >= requiredFrames) {
                                    if (!isBinDetected) {
                                        isBinDetected = true
                                        currentBinSceneHash = binKey
                                        bestBin?.let { knownBinScenes[binKey] = it.type.name }
                                    }
                                }
                            } else {
                                binFrameCount[0] = 0
                            }

                            // Always run backend bin scan; do not depend only on local detector.
                            if (!isBinAnalyzing && now - lastBinAnalyzeAt >= 320L) {
                                isBinAnalyzing = true
                                lastBinAnalyzeAt = now
                                val activeSceneHash = currentSceneKey
                                val fallbackBinKey = binKey
                                scope.launch {
                                    val binResult = withTimeoutOrNull(1500L) {
                                        detectionService.scanBin(
                                            liveScanResult = liveScan,
                                            sceneHash = activeSceneHash,
                                            language = selectedLanguage
                                        )
                                    }
                                    if (binResult?.binDetected == true) {
                                        isBinDetected = true
                                        currentBinSceneHash = binResult.binSceneHash.ifBlank { fallbackBinKey.orEmpty() }
                                        if (currentBinSceneHash.isNullOrBlank()) {
                                            currentBinSceneHash = fallbackBinKey
                                        }
                                        fallbackBinKey?.let { key ->
                                            knownBinScenes[key] = binResult.binType.name
                                        }
                                    }
                                    isBinAnalyzing = false
                                }
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
                            color = if (secondsLeft <= 2) Color(0xEEFF2222) else Color(0xCC000000),
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
            val activeDetection = currentDetection
            when (gamePhase) {
                GamePhase.SCANNING -> ScanningPanel(scanStatus, selectedLanguage, permHandler.hasCameraPermission()) {
                    permHandler.requestCameraPermission()
                }
                GamePhase.DETECTED -> if (activeDetection != null) {
                    DetectedPanel(
                        detection = activeDetection,
                        selectedLanguage = selectedLanguage
                    )
                } else {
                    ScanningPanel(scanStatus, selectedLanguage, permHandler.hasCameraPermission()) {
                        permHandler.requestCameraPermission()
                    }
                }
                GamePhase.PICKING -> PickingPanel(
                    progress = pickProgress,
                    awaitingAi = pickupAiPending,
                    selectedLanguage = selectedLanguage
                )
                GamePhase.FINDING_BIN -> FindingBinPanel(
                    isBinDetected = isBinDetected,
                    selectedLanguage = selectedLanguage
                )
                GamePhase.THROWING -> ThrowingPanel(
                    progress = throwProgress,
                    awaitingAi = throwAiPending,
                    selectedLanguage = selectedLanguage
                )
                GamePhase.VICTORY -> VictoryPanel(
                    session = session,
                    selectedLanguage = selectedLanguage,
                    defeatedDemons = victoryDemons,
                    co2SavedKg = victoryCo2SavedKg
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
    selectedLanguage: AppLanguage
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
        Text(
            t(
                selectedLanguage,
                "Pickup starts automatically. Keep camera steady.",
                "पिकअप अपने आप शुरू होगा। कैमरा स्थिर रखें।"
            ),
            color = Color(0xFFFFB155),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun PickingPanel(
    progress: Float,
    awaitingAi: Boolean,
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
                    color = Color(0xFFFFB155),
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
                    if (awaitingAi) {
                        t(selectedLanguage, "Verifying pickup with AI...", "AI से पिकअप वेरिफाई कर रहे हैं...")
                    } else {
                        t(selectedLanguage, "Show picked item briefly to camera", "उठाया हुआ कचरा कैमरे को दिखाएं")
                    },
                    color = Color(0xFFFFB155),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (awaitingAi) {
                        t(selectedLanguage, "Hold steady. Checking latest frames.", "स्थिर रखें। नवीनतम फ्रेम जांच रहे हैं।")
                    } else {
                        t(selectedLanguage, "AI verifies pickup from camera frames", "AI कैमरा फ्रेम से पिकअप वेरिफाई करता है")
                    },
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Camera evidence progress (not sensor based)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (awaitingAi) {
                        t(selectedLanguage, "AI verification", "AI सत्यापन")
                    } else {
                        t(selectedLanguage, "Camera evidence", "कैमरा प्रमाण")
                    },
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (progress > 0.7f) Color(0xFFFFD700) else Color(0xFF0088CC),
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
    progress: Float,
    awaitingAi: Boolean,
    selectedLanguage: AppLanguage
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (awaitingAi) {
                t(selectedLanguage, "Verifying throw with AI...", "AI से थ्रो वेरिफाई कर रहे हैं...")
            } else {
                t(selectedLanguage, "THROW the garbage into the bin!", "कचरा डस्टबिन में फेंकें!")
            },
            color = Color(0xFFFF4E00),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (awaitingAi) {
                    t(selectedLanguage, "Hold steady. Checking throw frames.", "स्थिर रखें। थ्रो फ्रेम जांच रहे हैं।")
                } else {
                    t(selectedLanguage, "Throw into bin and keep camera steady", "कचरा बिन में डालें और कैमरा स्थिर रखें")
                },
                color = AppPalette.textMuted,
                style = MaterialTheme.typography.labelSmall
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (progress > 0.7f) Color(0xFFFF4E00) else Color(0xFF0088CC),
                trackColor = Color(0xFF1A4455)
            )
        }
    }
}

@Composable
private fun VictoryPanel(
    session: GameSession,
    selectedLanguage: AppLanguage,
    defeatedDemons: Int,
    co2SavedKg: Double
) {
    val gained = defeatedDemons.coerceAtLeast(1) * 15 + 10
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
                "${defeatedDemons.coerceAtLeast(1)}",
                t(selectedLanguage, "demons", "डेमन"),
                Color(0xFFFF6B35)
            )
            VictoryStat(
                formatKg(co2SavedKg),
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

private fun applyRemainingDemons(
    detection: DetectionResult,
    remainingDemons: Int
): DetectionResult {
    val safeRemaining = remainingDemons.coerceAtLeast(0)
    val renderCount = safeRemaining
    return detection.copy(
        isGarbage = safeRemaining > 0,
        demonCount = renderCount,
        demonsDefeatedGain = renderCount,
        remainingDemons = safeRemaining,
        recommendedDestroyCount = if (renderCount > 0) minOf(3, renderCount) else 0,
        demonMix = if (renderCount > 0) {
            normalizeDemonMix(
                proposedMix = detection.demonMix,
                totalCount = renderCount,
                dominantKind = defaultDominantKind(detection.category)
            )
        } else {
            emptyList()
        }
    )
}

private fun estimatePickupDemons(
    availableDemons: Int,
    peakMotion: Float
): Int {
    val target = when {
        peakMotion >= 0.85f -> 3
        peakMotion >= 0.45f -> 2
        else -> 1
    }
    return target.coerceIn(1, availableDemons.coerceAtLeast(1))
}

private fun buildGarbageSceneKey(scan: LiveScanResult): String {
    val category = scan.garbageCategory?.name ?: "NONE"
    val labels = scan.rawLabels
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .take(5)
        .joinToString("|")
    return "$category::${scan.garbageSize.name}::$labels"
}

private fun buildBinSceneKey(scan: LiveScanResult): String? {
    val labelHints = scan.rawLabels
        .map { it.trim().lowercase() }
        .filter { label ->
            label.contains("bin") ||
                label.contains("recycle") ||
                label.contains("compost") ||
                label.contains("bucket") ||
                label.contains("basket") ||
                label.contains("container") ||
                label.contains("trash") ||
                label.contains("waste") ||
                label.contains("dust")
        }
        .distinct()
        .sorted()
        .take(4)
        .joinToString("|")
    val bins = if (scan.detectedBins.isEmpty()) {
        "NO_LOCAL_BIN"
    } else {
        scan.detectedBins
            .map { it.type.name }
            .sorted()
            .joinToString("|")
    }
    if (scan.detectedBins.isEmpty() && labelHints.isBlank()) return null
    return "$bins::${scan.isBinClosed}::${scan.isBinOverflowing}::$labelHints"
}

private fun looksLikeGarbageByLabels(rawLabels: List<String>): Boolean {
    if (rawLabels.isEmpty()) return false
    val combined = rawLabels.joinToString(" ").lowercase()
    return GARBAGE_SCAN_HINTS.any { hint -> combined.contains(hint) }
}

private fun syncSceneState(
    sceneKeyAlias: Map<String, String>,
    sceneDemonRemaining: MutableMap<String, Int>,
    sceneDetectionCache: MutableMap<String, DetectionResult>,
    canonicalSceneKey: String,
    remainingDemons: Int
) {
    val safeRemaining = remainingDemons.coerceAtLeast(0)
    val targetKeys = buildSet {
        add(canonicalSceneKey)
        sceneKeyAlias.forEach { (rawKey, mappedKey) ->
            if (mappedKey == canonicalSceneKey) add(rawKey)
        }
    }
    targetKeys.forEach { key ->
        sceneDemonRemaining[key] = safeRemaining
        sceneDetectionCache[key]?.let { cached ->
            sceneDetectionCache[key] = applyRemainingDemons(cached, safeRemaining)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private val GARBAGE_SCAN_HINTS = setOf(
    "garbage",
    "trash",
    "waste",
    "litter",
    "bottle",
    "plastic",
    "wrapper",
    "can",
    "paper",
    "cardboard",
    "glass",
    "food",
    "organic",
    "peel"
)

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
