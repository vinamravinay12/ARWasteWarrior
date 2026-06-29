package com.rivi.arwastewarrior.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import arwastewarrior.composeapp.generated.resources.Res
import arwastewarrior.composeapp.generated.resources.bin_biohazard
import arwastewarrior.composeapp.generated.resources.bin_dry
import arwastewarrior.composeapp.generated.resources.bin_ewaste
import arwastewarrior.composeapp.generated.resources.bin_reject
import arwastewarrior.composeapp.generated.resources.bin_wet
import arwastewarrior.composeapp.generated.resources.waste_biohazard
import arwastewarrior.composeapp.generated.resources.waste_dry
import arwastewarrior.composeapp.generated.resources.waste_ewaste
import arwastewarrior.composeapp.generated.resources.waste_glass
import arwastewarrior.composeapp.generated.resources.waste_metal
import arwastewarrior.composeapp.generated.resources.waste_paper
import arwastewarrior.composeapp.generated.resources.waste_plastic
import arwastewarrior.composeapp.generated.resources.waste_wet
import com.rivi.arwastewarrior.detection.AppLanguage
import com.rivi.arwastewarrior.detection.DetectionResult
import com.rivi.arwastewarrior.detection.GarbageCategory
import com.rivi.arwastewarrior.detection.GarbageDetectionService
import com.rivi.arwastewarrior.detection.LiveScanResult
import com.rivi.arwastewarrior.rememberCameraPermissionHandler
import com.rivi.arwastewarrior.speech.rememberHindiSpeechPlayer
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.components.LiveCameraDetectionView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Clock

@Composable
fun EducationalScanScreen(
    detectionService: GarbageDetectionService,
    selectedLanguage: AppLanguage,
    onBack: () -> Unit
) {
    var latestLiveScan by remember { mutableStateOf<LiveScanResult?>(null) }
    var currentDetection by remember { mutableStateOf<DetectionResult?>(null) }
    var statusText by remember {
        mutableStateOf(t(selectedLanguage, "Point camera at waste and ask questions.", "कचरे पर कैमरा रखें और सवाल पूछें।"))
    }
    var showPermDialog by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var lastSignature by remember { mutableStateOf("") }
    var lastAnalyzeAt by remember { mutableStateOf(0L) }
    var lastAnalyzedFrameHash by remember { mutableStateOf(0L) }
    var lastPositiveAt by remember { mutableStateOf(0L) }
    var negativeFrameStreak by remember { mutableStateOf(0) }
    var questionText by remember { mutableStateOf("") }
    var answerText by remember { mutableStateOf("") }
    var showLearningGuide by remember { mutableStateOf(false) }
    var showVirtualGame by remember { mutableStateOf(false) }
    var showVirtualGameCta by remember { mutableStateOf(false) }
    var activeGarbageKey by remember { mutableStateOf("") }
    var lastSpokenKey by remember { mutableStateOf("") }
    val sceneCache = remember { mutableStateMapOf<String, DetectionResult>() }
    val scope = rememberCoroutineScope()
    val speechPlayer = rememberHindiSpeechPlayer()
    val permHandler = rememberCameraPermissionHandler { granted ->
        if (!granted) showPermDialog = true
    }

    DisposableEffect(Unit) {
        if (!permHandler.hasCameraPermission()) permHandler.requestCameraPermission()
        onDispose { speechPlayer.stop() }
    }

    fun speakAndUnlockGame(text: String, key: String) {
        showVirtualGameCta = false
        if (text.isBlank()) {
            showVirtualGameCta = true
            return
        }
        speechPlayer.speak(text, selectedLanguage)
        val waitMs = estimateSpeechDurationMs(text)
        scope.launch {
            delay(waitMs)
            if (activeGarbageKey == key && currentDetection?.isGarbage == true) {
                showVirtualGameCta = true
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                        val signature = scanSignature(liveScan)
                        val now = Clock.System.now().toEpochMilliseconds()
                        if (signature.isBlank()) {
                            return@LiveCameraDetectionView
                        }

                        if (!shouldAnalyzeGarbageScene(liveScan)) {
                            negativeFrameStreak += 1
                            val keepLatched = currentDetection?.isGarbage == true &&
                                (
                                    now - lastPositiveAt <= DETECTION_LOCK_MS ||
                                        negativeFrameStreak < NEGATIVE_STREAK_TO_CLEAR
                                    )
                            if (!keepLatched) {
                                currentDetection = null
                                activeGarbageKey = ""
                                showVirtualGame = false
                                showVirtualGameCta = false
                                showLearningGuide = false
                                lastSpokenKey = ""
                                answerText = ""
                                lastAnalyzedFrameHash = 0L
                                statusText = t(
                                    selectedLanguage,
                                    "No clear garbage in focus. Point at discarded waste.",
                                    "फोकस में स्पष्ट कचरा नहीं है। त्यागे हुए कचरे पर कैमरा रखें।"
                                )
                            }
                            return@LiveCameraDetectionView
                        }
                        negativeFrameStreak = 0

                        // Image-level stability: if the frame looks the same as what we already
                        // analyzed, skip the Lambda call and keep the current result.
                        val fHash = liveScan.frameHash
                        if (fHash != 0L && lastAnalyzedFrameHash != 0L
                            && fHash.hammingDistance(lastAnalyzedFrameHash) <= SAME_SCENE_HASH_THRESHOLD
                            && currentDetection != null
                        ) {
                            return@LiveCameraDetectionView
                        }

                        if (signature == lastSignature && now - lastAnalyzeAt < 1200L) {
                            return@LiveCameraDetectionView
                        }
                        lastSignature = signature
                        lastAnalyzeAt = now

                        val cached = sceneCache[signature]
                        if (cached != null) {
                            currentDetection = cached
                            if (cached.isGarbage) {
                                lastPositiveAt = now
                                activeGarbageKey = signature
                                if (lastSpokenKey != signature) {
                                    lastSpokenKey = signature
                                    showVirtualGame = false
                                    showLearningGuide = false
                                    val speakText = if (selectedLanguage == AppLanguage.HINDI) {
                                        cached.speechTextHindi.ifBlank { cached.aiTip }
                                    } else {
                                        cached.speechTextEnglish.ifBlank { cached.aiTip }
                                    }
                                    speakAndUnlockGame(speakText, signature)
                                }
                            } else {
                                activeGarbageKey = ""
                                showVirtualGame = false
                                showVirtualGameCta = false
                                showLearningGuide = false
                                lastSpokenKey = ""
                                answerText = ""
                            }
                            statusText = if (cached.isGarbage) {
                                t(
                                    selectedLanguage,
                                    "Detected: ${cached.category.label}. Ask what to do with it.",
                                    "पहचाना गया: ${cached.category.label}। पूछें इसे कैसे संभालें।"
                                )
                            } else {
                                t(selectedLanguage, "No garbage in focus. Point to real waste.", "फोकस में कचरा नहीं है। वास्तविक कचरे पर कैमरा रखें।")
                            }
                            return@LiveCameraDetectionView
                        }

                        if (isAnalyzing) return@LiveCameraDetectionView
                        isAnalyzing = true
                        statusText = t(selectedLanguage, "Analyzing with AI...", "AI से विश्लेषण हो रहा है...")
                        scope.launch {
                            try {
                                val result = withTimeoutOrNull(6000L) {
                                    detectionService.scanGarbage(
                                        liveScanResult = liveScan,
                                        language = selectedLanguage
                                    )
                                }
                                if (result == null) {
                                    statusText = t(
                                        selectedLanguage,
                                        "AI is taking longer. Hold camera steady.",
                                        "AI को समय लग रहा है। कैमरा स्थिर रखें।"
                                    )
                                } else {
                                    sceneCache[signature] = result
                                    currentDetection = result
                                    if (fHash != 0L) lastAnalyzedFrameHash = fHash
                                    if (result.isGarbage) {
                                        lastPositiveAt = Clock.System.now().toEpochMilliseconds()
                                        negativeFrameStreak = 0
                                        activeGarbageKey = signature
                                    } else {
                                        negativeFrameStreak += 1
                                        activeGarbageKey = ""
                                        showVirtualGame = false
                                        showVirtualGameCta = false
                                        showLearningGuide = false
                                        lastSpokenKey = ""
                                        answerText = ""
                                    }
                                    statusText = if (result.isGarbage) {
                                        t(
                                            selectedLanguage,
                                            "Detected: ${result.category.label}. Ask your question below.",
                                            "पहचाना गया: ${result.category.label}। नीचे अपना सवाल पूछें।"
                                        )
                                    } else {
                                        t(
                                            selectedLanguage,
                                            "No garbage detected. Point to actual waste.",
                                            "कचरा नहीं मिला। वास्तविक कचरे पर कैमरा रखें।"
                                        )
                                    }
                                    if (result.isGarbage) {
                                        val speakText = if (selectedLanguage == AppLanguage.HINDI) {
                                            result.speechTextHindi.ifBlank { result.aiTip }
                                        } else {
                                            result.speechTextEnglish.ifBlank { result.aiTip }
                                        }
                                        if (lastSpokenKey != signature) {
                                            lastSpokenKey = signature
                                            showVirtualGame = false
                                            showLearningGuide = false
                                            speakAndUnlockGame(speakText, signature)
                                        } else if (!showVirtualGameCta) {
                                            showVirtualGameCta = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                statusText = t(
                                    selectedLanguage,
                                    "AI check failed. Retry by holding the camera steady.",
                                    "AI जांच विफल रही। कैमरा स्थिर रखकर फिर कोशिश करें।"
                                )
                            } finally {
                                isAnalyzing = false
                            }
                        }
                    },
                    onError = { statusText = it }
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF061020)))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0xB3000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← ${t(selectedLanguage, "Back", "वापस")}", color = Color(0xFFFFB155))
                }
                Text(
                    t(selectedLanguage, "AI Waste Coach", "AI वेस्ट कोच"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!showVirtualGame) {
                Text(
                    text = statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (showVirtualGame) {
                BinThrowingGameOverlay(
                    category = currentDetection?.category,
                    selectedLanguage = selectedLanguage,
                    onClose = { showVirtualGame = false }
                )
            }
        }

        if (!showVirtualGame) Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(390.dp)
                .background(Color(0xF5061020))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val detectedGarbage = currentDetection?.takeIf { it.isGarbage }

            if (detectedGarbage == null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        t(
                            selectedLanguage,
                            "Waiting for garbage detection. Point camera to real discarded waste.",
                            "कचरा डिटेक्शन का इंतज़ार है। कैमरा वास्तविक त्यागे हुए कचरे पर रखें।"
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                DetectionEducationCard(
                    detection = detectedGarbage,
                    selectedLanguage = selectedLanguage,
                    liveScan = latestLiveScan
                )

                if (showVirtualGameCta) {
                    Button(
                        onClick = { showVirtualGame = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C49A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            t(
                                selectedLanguage,
                                "Play Bin Throwing Game",
                                "बिन थ्रोइंग गेम खेलें"
                            )
                        )
                    }
                }

                if (showVirtualGameCta) {
                    TextButton(onClick = { showLearningGuide = !showLearningGuide }) {
                        Text(
                            if (showLearningGuide) {
                                t(selectedLanguage, "Hide Learning Guide", "लर्निंग गाइड छिपाएं")
                            } else {
                                t(selectedLanguage, "Show Learning Guide", "लर्निंग गाइड दिखाएं")
                            },
                            color = Color(0xFFFFB155)
                        )
                    }
                }

                if (showLearningGuide) {
                    WasteTypeVisualGuideSection(selectedLanguage = selectedLanguage)
                    BinTypeGuideSection(selectedLanguage = selectedLanguage)
                    RecyclableGuideSection(selectedLanguage = selectedLanguage)
                }
            }

            if (detectedGarbage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            t(
                                selectedLanguage,
                                "Ask about this scene (e.g., \"Where should I dump this?\")",
                                "इस सीन के बारे में पूछें (जैसे, \"इसे कहाँ डालूँ?\")"
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        OutlinedTextField(
                            value = questionText,
                            onValueChange = { questionText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    t(selectedLanguage, "Ask disposal question...", "निपटान से जुड़ा सवाल पूछें..."),
                                    color = AppPalette.textMuted
                                )
                            }
                        )
                        Button(
                            onClick = {
                                val answer = buildEducationAnswer(
                                    question = questionText,
                                    detection = currentDetection,
                                    selectedLanguage = selectedLanguage
                                )
                                answerText = answer
                                if (answer.isNotBlank()) {
                                    speechPlayer.speak(answer, selectedLanguage)
                                }
                            },
                            enabled = questionText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(t(selectedLanguage, "Ask AI", "AI से पूछें"))
                        }
                        if (answerText.isNotBlank()) {
                            Text(
                                answerText,
                                color = Color(0xFFE0F7FF),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text(t(selectedLanguage, "Camera Required", "कैमरा आवश्यक")) },
            text = { Text(t(selectedLanguage, "Grant camera access to scan waste.", "कचरा स्कैन करने के लिए कैमरा अनुमति दें।")) },
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

@Composable
private fun DetectionEducationCard(
    detection: DetectionResult?,
    selectedLanguage: AppLanguage,
    liveScan: LiveScanResult?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (detection == null) {
                Text(
                    t(
                        selectedLanguage,
                        "Point camera at garbage to get AI guidance.",
                        "AI मार्गदर्शन के लिए कैमरा कचरे पर रखें।"
                    ),
                    color = Color.White
                )
                return@Column
            }

            if (!detection.isGarbage) {
                Text(
                    t(selectedLanguage, "No garbage identified in this scene.", "इस सीन में कचरा नहीं मिला।"),
                    color = Color(0xFFFFC18A),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    t(
                        selectedLanguage,
                        "Try focusing on discarded items like plastic bottles, wrappers, paper, or food waste.",
                        "प्लास्टिक बोतल, रैपर, कागज या खाने के कचरे जैसी चीज़ों पर फोकस करें।"
                    ),
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            Text(
                t(selectedLanguage, "Detected Type", "पहचाना गया प्रकार") + ": ${detection.category.label}",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                t(selectedLanguage, "AI Advice", "AI सलाह") + ": ${detection.aiTip}",
                color = Color(0xFFE0F7FF),
                style = MaterialTheme.typography.bodySmall
            )
            if (detection.binIssue.isNotBlank()) {
                Text(
                    t(selectedLanguage, "Bin Status", "बिन स्थिति") + ": ${detection.binIssue}",
                    color = Color(0xFFFFD9B0),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                disposalGuideFor(detection.category, selectedLanguage),
                color = Color(0xFFB7F4E8),
                style = MaterialTheme.typography.bodySmall
            )
            val warning = if (selectedLanguage == AppLanguage.HINDI) {
                detection.diseaseWarningHindi
            } else {
                detection.diseaseWarningEnglish.ifBlank { detection.diseaseWarningHindi }
            }
            if (warning.isNotBlank()) {
                Text(
                    warning,
                    color = Color(0xFFFFC8C8),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val nearbyBins = liveScan?.detectedBins.orEmpty()
            if (nearbyBins.isNotEmpty()) {
                val binsText = nearbyBins.joinToString { "${it.type.label} (${it.confidence}%)" }
                Text(
                    t(selectedLanguage, "Nearby bins", "पास के बिन") + ": $binsText",
                    color = AppPalette.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun WasteTypeVisualGuideSection(selectedLanguage: AppLanguage) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                t(selectedLanguage, "Garbage Types (Visual)", "कचरे के प्रकार (चित्र सहित)"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LEARNING_WASTE_ITEMS.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x22000000)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(item.icon),
                                contentDescription = item.displayName(selectedLanguage),
                                modifier = Modifier.size(56.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                item.displayName(selectedLanguage),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                if (item.isRecyclable) {
                                    t(selectedLanguage, "Recyclable", "रीसायक्लेबल")
                                } else {
                                    t(selectedLanguage, "Not Recyclable", "रीसायक्लेबल नहीं")
                                },
                                color = if (item.isRecyclable) Color(0xFF8AF7C2) else Color(0xFFFFC7C7),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BinTypeGuideSection(selectedLanguage: AppLanguage) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                t(selectedLanguage, "Bin Identification Guide", "बिन पहचान गाइड"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LEARNING_BIN_TYPES.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x22000000)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(item.icon),
                                contentDescription = item.displayName(selectedLanguage),
                                modifier = Modifier.size(56.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                item.displayName(selectedLanguage),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                item.shortHint(selectedLanguage),
                                color = AppPalette.textMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecyclableGuideSection(selectedLanguage: AppLanguage) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                t(selectedLanguage, "Recyclable vs Non-Recyclable", "रीसायक्लेबल बनाम नॉन-रीसायक्लेबल"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            LEARNING_WASTE_ITEMS.forEach { item ->
                val recycleText = if (item.isRecyclable) {
                    t(selectedLanguage, "Recyclable", "रीसायक्लेबल")
                } else {
                    t(selectedLanguage, "Not Recyclable", "रीसायक्लेबल नहीं")
                }
                Text(
                    "• ${item.displayName(selectedLanguage)}: $recycleText • ${item.recommendedBin.displayName(selectedLanguage)}",
                    color = if (item.isRecyclable) Color(0xFFB7F4E8) else Color(0xFFFFD1A3),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun VirtualFloatingSortingGame(
    selectedLanguage: AppLanguage,
    seedKey: String,
    onSpeak: (String) -> Unit
) {
    val tokens = remember(seedKey) {
        mutableStateListOf<VirtualWasteToken>().apply {
            addAll(buildVirtualWasteTokens(seedKey))
        }
    }
    var selectedTokenId by remember(seedKey) { mutableStateOf(tokens.firstOrNull()?.id) }
    var score by remember(seedKey) { mutableStateOf(0) }
    var feedback by remember(seedKey) { mutableStateOf("") }
    val transition = rememberInfiniteTransition(label = "float-waste")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x22000000)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                t(selectedLanguage, "Virtual Sorting Game", "वर्चुअल सॉर्टिंग गेम"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                t(
                    selectedLanguage,
                    "Tap a floating waste item, then choose the correct bin.",
                    "फ्लोटिंग कचरे पर टैप करें, फिर सही बिन चुनें।"
                ),
                color = AppPalette.textMuted,
                style = MaterialTheme.typography.bodySmall
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            ) {
                tokens.forEachIndexed { index, token ->
                    val baseX = 12 + (index % 3) * 106
                    val baseY = 18 + (index / 3) * 72
                    val drift = (sin((phase * 2 * PI) + (index * 0.9)) * 10.0).toFloat()
                    val isSelected = selectedTokenId == token.id
                    Image(
                        painter = painterResource(token.item.icon),
                        contentDescription = token.item.displayName(selectedLanguage),
                        modifier = Modifier
                            .size(if (isSelected) 56.dp else 48.dp)
                            .padding(2.dp)
                            .align(Alignment.TopStart)
                            .clickable { selectedTokenId = token.id }
                            .padding(start = baseX.dp, top = (baseY + drift).dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LEARNING_BIN_TYPES.forEach { bin ->
                    Button(
                        onClick = {
                            val selected = tokens.firstOrNull { it.id == selectedTokenId }
                            if (selected == null) {
                                feedback = t(
                                    selectedLanguage,
                                    "Select a floating item first.",
                                    "पहले एक फ्लोटिंग आइटम चुनें।"
                                )
                                onSpeak(feedback)
                                return@Button
                            }
                            val correct = selected.item.recommendedBin == bin.type
                            if (correct) {
                                tokens.remove(selected)
                                score += 1
                                selectedTokenId = tokens.firstOrNull()?.id
                                feedback = t(
                                    selectedLanguage,
                                    "Correct throw into ${bin.displayName(selectedLanguage)}!",
                                    "${bin.displayName(selectedLanguage)} में सही थ्रो!"
                                )
                            } else {
                                feedback = t(
                                    selectedLanguage,
                                    "Wrong bin. Try ${selected.item.recommendedBin.displayName(selectedLanguage)}.",
                                    "गलत बिन। ${selected.item.recommendedBin.displayName(selectedLanguage)} ट्राई करें।"
                                )
                            }
                            onSpeak(feedback)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D5A6D))
                    ) {
                        Text(bin.displayName(selectedLanguage))
                    }
                }
            }

            Text(
                t(selectedLanguage, "Score", "स्कोर") + ": $score / ${buildVirtualWasteTokens(seedKey).size}",
                color = Color(0xFF8AF7C2),
                style = MaterialTheme.typography.labelMedium
            )
            if (feedback.isNotBlank()) {
                Text(
                    feedback,
                    color = Color(0xFFE0F7FF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (tokens.isEmpty()) {
                Text(
                    t(
                        selectedLanguage,
                        "Great! You sorted all virtual waste correctly.",
                        "बहुत बढ़िया! आपने सभी वर्चुअल कचरे को सही सॉर्ट किया।"
                    ),
                    color = Color(0xFFFFD38C),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BinThrowingGameOverlay(
    category: GarbageCategory?,
    selectedLanguage: AppLanguage,
    onClose: () -> Unit
) {
    val totalRounds = 5
    var currentRound by remember { mutableStateOf(1) }
    var score by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    var feedbackIsCorrect by remember { mutableStateOf(true) }
    var showingFeedback by remember { mutableStateOf(false) }
    var gameOver by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val bounceTrans = rememberInfiniteTransition(label = "bounce")
    val bobOffset by bounceTrans.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    val wasteIcon = wasteIconForCategory(category)
    val correctBin = correctBinForCategory(category)
    val wasteName = category?.label ?: t(selectedLanguage, "Unknown Waste", "अज्ञात कचरा")

    fun resetGame() {
        currentRound = 1
        score = 0
        feedback = ""
        showingFeedback = false
        gameOver = false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xEA061020))
    ) {
        if (gameOver) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    t(selectedLanguage, "Game Over!", "खेल खत्म!"),
                    color = Color(0xFFFFD38C),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    t(selectedLanguage, "Final Score: $score / ${totalRounds * 5}", "फाइनल स्कोर: $score / ${totalRounds * 5}"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = { resetGame() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C49A))
                ) {
                    Text(t(selectedLanguage, "Play Again", "फिर खेलें"))
                }
                TextButton(onClick = onClose) {
                    Text(t(selectedLanguage, "Close", "बंद करें"), color = Color(0xFFFFB155))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t(selectedLanguage, "Round $currentRound/$totalRounds", "राउंड $currentRound/$totalRounds"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        t(selectedLanguage, "Score: $score", "स्कोर: $score"),
                        color = Color(0xFF8AF7C2),
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClose) {
                        Text("✕", color = Color(0xFFFFB155))
                    }
                }

                // Center – floating waste item
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            t(selectedLanguage, "Throw this into the correct bin!", "इसे सही बिन में फेंको!"),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Image(
                            painter = painterResource(wasteIcon),
                            contentDescription = wasteName,
                            modifier = Modifier
                                .size(120.dp)
                                .offset(y = bobOffset.dp),
                            contentScale = ContentScale.Fit
                        )
                        Text(wasteName, color = Color(0xFFFFD38C), fontWeight = FontWeight.Bold)
                    }
                    if (showingFeedback && feedback.isNotBlank()) {
                        Text(
                            feedback,
                            color = if (feedbackIsCorrect) Color(0xFF8AF7C2) else Color(0xFFFF6B6B),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        )
                    }
                }

                // Bottom bin row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LEARNING_BIN_TYPES.forEach { bin ->
                        Column(
                            modifier = Modifier
                                .clickable(enabled = !showingFeedback) {
                                    val isCorrect = bin.type == correctBin
                                    if (isCorrect) score += 5 else score -= 2
                                    feedback = if (isCorrect) {
                                        t(selectedLanguage, "Correct! +5 points", "सही! +5 अंक")
                                    } else {
                                        t(selectedLanguage, "Wrong bin! -2 points", "गलत बिन! -2 अंक")
                                    }
                                    feedbackIsCorrect = isCorrect
                                    showingFeedback = true
                                    scope.launch {
                                        delay(1000L)
                                        showingFeedback = false
                                        feedback = ""
                                        if (isCorrect) {
                                            if (currentRound >= totalRounds) {
                                                gameOver = true
                                            } else {
                                                currentRound++
                                            }
                                        }
                                    }
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Image(
                                painter = painterResource(bin.icon),
                                contentDescription = bin.displayName(selectedLanguage),
                                modifier = Modifier.size(64.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                bin.displayName(selectedLanguage),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun wasteIconForCategory(category: GarbageCategory?): DrawableResource {
    return when (category) {
        GarbageCategory.PLASTIC -> Res.drawable.waste_plastic
        GarbageCategory.PAPER -> Res.drawable.waste_paper
        GarbageCategory.METAL -> Res.drawable.waste_metal
        GarbageCategory.GLASS -> Res.drawable.waste_glass
        GarbageCategory.ORGANIC -> Res.drawable.waste_wet
        GarbageCategory.E_WASTE -> Res.drawable.waste_ewaste
        GarbageCategory.UNKNOWN, null -> Res.drawable.waste_dry
    }
}

private fun correctBinForCategory(category: GarbageCategory?): LearningBinType {
    return when (category) {
        GarbageCategory.PLASTIC, GarbageCategory.PAPER,
        GarbageCategory.METAL, GarbageCategory.GLASS -> LearningBinType.DRY
        GarbageCategory.ORGANIC -> LearningBinType.WET
        GarbageCategory.E_WASTE -> LearningBinType.E_WASTE
        GarbageCategory.UNKNOWN, null -> LearningBinType.REJECT
    }
}

private data class VirtualWasteToken(
    val id: Int,
    val item: LearningWasteItem
)

private fun buildVirtualWasteTokens(seedKey: String): List<VirtualWasteToken> {
    val pool = LEARNING_WASTE_ITEMS
    if (pool.isEmpty()) return emptyList()
    val shift = kotlin.math.abs(seedKey.hashCode()) % pool.size
    val picked = (0 until 6).map { idx -> pool[(shift + idx) % pool.size] }
    return picked.mapIndexed { index, item ->
        VirtualWasteToken(id = index + 1, item = item)
    }
}

@Composable
private fun VirtualSortingPracticeSection(
    selectedLanguage: AppLanguage,
    practiceIndex: Int,
    practiceFeedback: String,
    onAnswer: (LearningBinType) -> Unit,
    onNext: () -> Unit
) {
    val item = LEARNING_WASTE_ITEMS[practiceIndex % LEARNING_WASTE_ITEMS.size]
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                t(selectedLanguage, "Virtual Sorting Practice", "वर्चुअल सॉर्टिंग प्रैक्टिस"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(item.icon),
                    contentDescription = item.displayName(selectedLanguage),
                    modifier = Modifier.size(58.dp),
                    contentScale = ContentScale.Fit
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.displayName(selectedLanguage),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        item.description(selectedLanguage),
                        color = AppPalette.textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LEARNING_BIN_TYPES.forEach { bin ->
                    Button(
                        onClick = { onAnswer(bin.type) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D5A6D))
                    ) {
                        Text(bin.displayName(selectedLanguage))
                    }
                }
            }
            if (practiceFeedback.isNotBlank()) {
                Text(
                    practiceFeedback,
                    color = Color(0xFFE0F7FF),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t(selectedLanguage, "Next Item", "अगला आइटम"))
                }
            }
        }
    }
}

private fun scanSignature(scan: LiveScanResult): String {
    val cat = scan.garbageCategory?.name ?: "NONE"
    val labels = scan.rawLabels.map { it.lowercase().trim() }.distinct().sorted().take(8).joinToString("|")
    val bins = scan.detectedBins.map { it.type.name }.sorted().joinToString("|")
    return "$cat::${scan.garbageSize.name}::$bins::$labels"
}

private fun disposalGuideFor(category: GarbageCategory, language: AppLanguage): String {
    return if (language == AppLanguage.HINDI) {
        when (category) {
            GarbageCategory.PLASTIC, GarbageCategory.PAPER, GarbageCategory.METAL, GarbageCategory.GLASS ->
                "ड्राई वेस्ट: साफ और सूखा कचरा ड्राई वेस्ट बिन में डालें।"
            GarbageCategory.ORGANIC ->
                "वेट वेस्ट: खाने का बचा हुआ, छिलके और गीला कचरा वेट/कम्पोस्ट बिन में डालें।"
            GarbageCategory.E_WASTE ->
                "ई-वेस्ट: बैटरी, चार्जर, तार, इलेक्ट्रॉनिक्स को ई-वेस्ट कलेक्शन पॉइंट पर दें।"
            GarbageCategory.UNKNOWN ->
                "पहचान न हो तो अलग रखें और स्थानीय नगर निगम की गाइडलाइन देखें।"
        }
    } else {
        when (category) {
            GarbageCategory.PLASTIC, GarbageCategory.PAPER, GarbageCategory.METAL, GarbageCategory.GLASS ->
                "Dry waste: Put clean and dry recyclables in the dry/recyclable bin."
            GarbageCategory.ORGANIC ->
                "Wet waste: Food scraps and organic waste should go to wet/compost bin."
            GarbageCategory.E_WASTE ->
                "E-waste: Batteries, chargers, wires and electronics should go to authorized e-waste collection."
            GarbageCategory.UNKNOWN ->
                "If unsure, keep it separate and follow your local municipal waste guide."
        }
    }
}

private fun buildEducationAnswer(
    question: String,
    detection: DetectionResult?,
    selectedLanguage: AppLanguage
): String {
    val q = question.lowercase()
    val isHindi = selectedLanguage == AppLanguage.HINDI

    val sanitaryAsked = q.contains("sanitary") || q.contains("pad") || q.contains("diaper") ||
        q.contains("biohazard") || q.contains("biomedical") || q.contains("मासिक") || q.contains("पैड")
    if (sanitaryAsked) {
        return if (isHindi) {
            "सैनिटरी पैड/डायपर को अलग से पेपर में लपेटकर बायोमेडिकल/सैनिटरी वेस्ट सिस्टम में दें। अगर अलग सुविधा न हो तो स्थानीय गाइडलाइन के अनुसार चिन्हित करके अलग बंद पैकेट में दें।"
        } else {
            "Sanitary pads/diapers should be wrapped securely and disposed via sanitary/biomedical waste stream where available. If not available, seal and label separately per local rules."
        }
    }

    if (detection == null || !detection.isGarbage) {
        if (q.contains("recycle") || q.contains("recyclable") || q.contains("रीसायकल") || q.contains("recycling")) {
            return if (isHindi) {
                "सामान्य नियम: प्लास्टिक, कागज, धातु, कांच (साफ/सूखा) रीसायक्लेबल हैं। गीला कचरा कम्पोस्टेबल है। ई-वेस्ट और सैनिटरी वेस्ट को अलग विशेष चैनल में दें।"
            } else {
                "General rule: clean dry plastic/paper/metal/glass are recyclable. Wet waste is compostable. E-waste and sanitary waste must go through separate special streams."
            }
        }
        return if (isHindi) {
            "अभी स्पष्ट कचरा नहीं दिख रहा। कृपया कैमरा सीधे कचरे पर रखें और फिर पूछें।"
        } else {
            "I cannot see clear garbage yet. Point the camera directly at the waste and ask again."
        }
    }

    if (q.contains("which bin") || q.contains("where") || q.contains("dump") || q.contains("throw") ||
        q.contains("कौन") || q.contains("कहाँ") || q.contains("डाल")
    ) {
        return disposalGuideFor(detection.category, selectedLanguage)
    }

    if (q.contains("why") || q.contains("health") || q.contains("danger") || q.contains("risk") ||
        q.contains("क्यों") || q.contains("बीमारी") || q.contains("खतरा")
    ) {
        return if (isHindi) {
            detection.diseaseWarningHindi.ifBlank { "गलत निपटान से संक्रमण, बदबू और पर्यावरण प्रदूषण बढ़ सकता है।" }
        } else {
            "Improper disposal can increase infection risk, odor, pests, and local pollution."
        }
    }

    if (q.contains("identify bin") || q.contains("how to identify") || q.contains("कैसे पहचान")) {
        return if (isHindi) {
            "बिन पहचान टिप्स: हरा/कम्पोस्ट = वेट वेस्ट, नीला = ड्राई वेस्ट, ई-वेस्ट लेबल = इलेक्ट्रॉनिक्स, सैनिटरी/बायोहाज़र्ड लेबल = विशेष कचरा।"
        } else {
            "Bin ID tips: green/compost for wet waste, blue for dry waste, e-waste label for electronics, sanitary/biomedical label for special hygiene waste."
        }
    }

    if (q.contains("recycle") || q.contains("recyclable") || q.contains("रीसायकल") || q.contains("recycling")) {
        return recyclabilityNoteForCategory(detection.category, selectedLanguage)
    }

    return if (isHindi) {
        "यह ${detection.category.label} कचरा लगता है। ${detection.aiTip}"
    } else {
        "This looks like ${detection.category.label} waste. ${detection.aiTip}"
    }
}

private fun shouldAnalyzeGarbageScene(scan: LiveScanResult): Boolean {
    val confidence = scan.confidence
    val hasCategory = scan.garbageCategory != null && scan.garbageCategory != GarbageCategory.UNKNOWN
    val normalizedLabels = scan.rawLabels.map { normalizeLabel(it) }
    val hasDiscardCue = normalizedLabels.any { containsAnyToken(it, EDU_DISCARD_CUE_TOKENS) }
    val hasDiscardContext = normalizedLabels.any { containsAnyToken(it, EDU_DISCARD_CONTEXT_TOKENS) }
    val hasDisposableCue = normalizedLabels.any { containsAnyToken(it, EDU_DISPOSABLE_ITEM_TOKENS) }
    val hasBinContext = scan.detectedBins.isNotEmpty()

    if (hasCategory && (scan.isLikelyGarbage || confidence >= 35)) return true
    if (hasDiscardCue) return true
    if (hasDisposableCue && (hasDiscardContext || hasBinContext || confidence >= 48)) return true
    return false
}

private fun normalizeLabel(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun containsAnyToken(text: String, tokens: Set<String>): Boolean {
    if (text.isBlank()) return false
    return text.split(" ").any { it in tokens }
}

private val EDU_DISCARD_CUE_TOKENS = setOf(
    "trash",
    "waste",
    "garbage",
    "litter",
    "discarded",
    "rubbish",
    "dump"
)

private val EDU_DISCARD_CONTEXT_TOKENS = setOf(
    "ground",
    "floor",
    "street",
    "road",
    "footpath",
    "sidewalk",
    "drain",
    "gutter",
    "dirt",
    "dust",
    "messy"
)

private val EDU_DISPOSABLE_ITEM_TOKENS = setOf(
    "bottle",
    "wrapper",
    "packaging",
    "polybag",
    "bag",
    "cup",
    "lid",
    "cap",
    "straw",
    "sachet",
    "pouch",
    "tube",
    "tissue",
    "napkin",
    "receipt",
    "carton",
    "foil",
    "can",
    "eggshell",
    "peel",
    "leftover",
    "plastic",
    "paper",
    "metal",
    "glass",
    "organic",
    "ewaste",
    "battery",
    "charger",
    "cable",
    "wire",
    "electronics"
)

private fun recyclabilityNoteForCategory(category: GarbageCategory, language: AppLanguage): String {
    return if (language == AppLanguage.HINDI) {
        when (category) {
            GarbageCategory.PLASTIC, GarbageCategory.PAPER, GarbageCategory.METAL, GarbageCategory.GLASS ->
                "यह सामान्यतः रीसायक्लेबल है अगर साफ और सूखा हो। इसे ड्राई/रीसायक्लिंग बिन में डालें।"
            GarbageCategory.ORGANIC ->
                "यह रीसायक्लेबल नहीं, लेकिन कम्पोस्टेबल है। इसे वेट/कम्पोस्ट बिन में डालें।"
            GarbageCategory.E_WASTE ->
                "ई-वेस्ट को सामान्य रीसायक्लिंग में न डालें। अधिकृत ई-वेस्ट कलेक्शन में दें।"
            GarbageCategory.UNKNOWN ->
                "पहले पहचान सुनिश्चित करें। संदिग्ध वस्तु को अलग रखें और स्थानीय गाइडलाइन देखें।"
        }
    } else {
        when (category) {
            GarbageCategory.PLASTIC, GarbageCategory.PAPER, GarbageCategory.METAL, GarbageCategory.GLASS ->
                "Usually recyclable if clean and dry. Put it in the dry/recycling bin."
            GarbageCategory.ORGANIC ->
                "Not recyclable, but compostable. Put it in the wet/compost bin."
            GarbageCategory.E_WASTE ->
                "Do not put e-waste in normal recycling. Use authorized e-waste collection."
            GarbageCategory.UNKNOWN ->
                "Confirm identity first. Keep doubtful items separate and follow local rules."
        }
    }
}

private enum class LearningBinType {
    DRY, WET, E_WASTE, BIOHAZARD, REJECT
}

private data class LearningWasteItem(
    val type: String,
    val typeHindi: String,
    val descriptionEn: String,
    val descriptionHi: String,
    val isRecyclable: Boolean,
    val recommendedBin: LearningBinType,
    val icon: DrawableResource
) {
    fun displayName(language: AppLanguage): String = if (language == AppLanguage.HINDI) typeHindi else type
    fun description(language: AppLanguage): String = if (language == AppLanguage.HINDI) descriptionHi else descriptionEn
}

private data class LearningBinItem(
    val type: LearningBinType,
    val labelEn: String,
    val labelHi: String,
    val hintEn: String,
    val hintHi: String,
    val icon: DrawableResource
) {
    fun displayName(language: AppLanguage): String = if (language == AppLanguage.HINDI) labelHi else labelEn
    fun shortHint(language: AppLanguage): String = if (language == AppLanguage.HINDI) hintHi else hintEn
}

private fun LearningBinType.displayName(language: AppLanguage): String {
    return if (language == AppLanguage.HINDI) {
        when (this) {
            LearningBinType.DRY -> "ड्राई बिन"
            LearningBinType.WET -> "वेट/कम्पोस्ट बिन"
            LearningBinType.E_WASTE -> "ई-वेस्ट बिन"
            LearningBinType.BIOHAZARD -> "बायोहाज़र्ड/सैनिटरी बिन"
            LearningBinType.REJECT -> "रिजेक्ट/लैंडफिल बिन"
        }
    } else {
        when (this) {
            LearningBinType.DRY -> "Dry Bin"
            LearningBinType.WET -> "Wet/Compost Bin"
            LearningBinType.E_WASTE -> "E-Waste Bin"
            LearningBinType.BIOHAZARD -> "Biohazard/Sanitary Bin"
            LearningBinType.REJECT -> "Reject/Landfill Bin"
        }
    }
}

private val LEARNING_BIN_TYPES = listOf(
    LearningBinItem(
        type = LearningBinType.DRY,
        labelEn = "Dry Waste",
        labelHi = "ड्राई वेस्ट",
        hintEn = "Paper, clean plastic, metal, glass",
        hintHi = "कागज, साफ प्लास्टिक, धातु, कांच",
        icon = Res.drawable.bin_dry
    ),
    LearningBinItem(
        type = LearningBinType.WET,
        labelEn = "Wet Waste",
        labelHi = "वेट वेस्ट",
        hintEn = "Food scraps and compostables",
        hintHi = "खाने का कचरा और कम्पोस्टेबल",
        icon = Res.drawable.bin_wet
    ),
    LearningBinItem(
        type = LearningBinType.E_WASTE,
        labelEn = "E-Waste",
        labelHi = "ई-वेस्ट",
        hintEn = "Battery, charger, cable, gadgets",
        hintHi = "बैटरी, चार्जर, तार, गैजेट",
        icon = Res.drawable.bin_ewaste
    ),
    LearningBinItem(
        type = LearningBinType.BIOHAZARD,
        labelEn = "Biohazard",
        labelHi = "बायोहाज़र्ड",
        hintEn = "Sanitary pads, diapers, medical waste",
        hintHi = "सैनिटरी पैड, डायपर, मेडिकल वेस्ट",
        icon = Res.drawable.bin_biohazard
    ),
    LearningBinItem(
        type = LearningBinType.REJECT,
        labelEn = "Reject",
        labelHi = "रिजेक्ट",
        hintEn = "Soiled mixed waste, non-recyclables",
        hintHi = "गंदा मिश्रित कचरा, नॉन-रीसायक्लेबल",
        icon = Res.drawable.bin_reject
    )
)

private val LEARNING_WASTE_ITEMS = listOf(
    LearningWasteItem(
        type = "Plastic Bottle",
        typeHindi = "प्लास्टिक बोतल",
        descriptionEn = "Clean bottles are recyclable after rinsing.",
        descriptionHi = "साफ बोतल धोकर रीसायकल की जा सकती है।",
        isRecyclable = true,
        recommendedBin = LearningBinType.DRY,
        icon = Res.drawable.waste_plastic
    ),
    LearningWasteItem(
        type = "Paper/Cardboard",
        typeHindi = "कागज/कार्डबोर्ड",
        descriptionEn = "Dry paper and cardboard can be recycled.",
        descriptionHi = "सूखा कागज और कार्डबोर्ड रीसायकल हो सकता है।",
        isRecyclable = true,
        recommendedBin = LearningBinType.DRY,
        icon = Res.drawable.waste_paper
    ),
    LearningWasteItem(
        type = "Food Waste",
        typeHindi = "खाद्य कचरा",
        descriptionEn = "Organic leftovers should be composted.",
        descriptionHi = "जैविक बचा हुआ कचरा कम्पोस्ट में जाए।",
        isRecyclable = false,
        recommendedBin = LearningBinType.WET,
        icon = Res.drawable.waste_wet
    ),
    LearningWasteItem(
        type = "Mixed Dry Waste",
        typeHindi = "मिक्स ड्राई वेस्ट",
        descriptionEn = "Segregate clean recyclables from reject waste.",
        descriptionHi = "साफ रीसायक्लेबल को रिजेक्ट वेस्ट से अलग करें।",
        isRecyclable = false,
        recommendedBin = LearningBinType.REJECT,
        icon = Res.drawable.waste_dry
    ),
    LearningWasteItem(
        type = "E-Waste Item",
        typeHindi = "ई-वेस्ट आइटम",
        descriptionEn = "Electronics should go to authorized e-waste stream.",
        descriptionHi = "इलेक्ट्रॉनिक्स अधिकृत ई-वेस्ट चैनल में दें।",
        isRecyclable = false,
        recommendedBin = LearningBinType.E_WASTE,
        icon = Res.drawable.waste_ewaste
    ),
    LearningWasteItem(
        type = "Sanitary/Biohazard",
        typeHindi = "सैनिटरी/बायोहाज़र्ड",
        descriptionEn = "Wrap securely and dispose as sanitary/biohazard waste.",
        descriptionHi = "अच्छे से पैक करके सैनिटरी/बायोहाज़र्ड में दें।",
        isRecyclable = false,
        recommendedBin = LearningBinType.BIOHAZARD,
        icon = Res.drawable.waste_biohazard
    )
)

private fun t(lang: AppLanguage, en: String, hi: String): String = if (lang == AppLanguage.HINDI) hi else en

private fun estimateSpeechDurationMs(text: String): Long {
    val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    return (words * 360L).coerceIn(1500L, 9000L)
}

private const val DETECTION_LOCK_MS = 6000L
private const val NEGATIVE_STREAK_TO_CLEAR = 6
private const val SAME_SCENE_HASH_THRESHOLD = 10

// Number of bits that differ between two 64-bit perceptual hashes.
// ≤ 10/64 means the scene is visually the same (normal hand tremor range is 1–4).
private fun Long.hammingDistance(other: Long): Int = (this xor other).countOneBits()
