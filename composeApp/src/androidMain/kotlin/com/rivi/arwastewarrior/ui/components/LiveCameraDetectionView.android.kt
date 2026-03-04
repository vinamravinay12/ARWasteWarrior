package com.rivi.arwastewarrior.ui.components

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.rivi.arwastewarrior.detection.BinObservation
import com.rivi.arwastewarrior.detection.BinType
import com.rivi.arwastewarrior.detection.GarbageCategory
import com.rivi.arwastewarrior.detection.GarbageSize
import com.rivi.arwastewarrior.detection.LiveScanResult
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@Composable
actual fun LiveCameraDetectionView(
    modifier: Modifier,
    onScanResult: (LiveScanResult) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(onScanResult, onError) {
        MlKitLiveAnalyzer(
            onScanResult = onScanResult,
            onError = onError
        )
    }

    DisposableEffect(lifecycleOwner, previewView, analyzer, cameraExecutor) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                    onError("Unable to initialize camera detection.")
                }
            },
            mainExecutor
        )

        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
                // no-op
            }
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}

private class MlKitLiveAnalyzer(
    private val onScanResult: (LiveScanResult) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, Closeable {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private var processingFrame = false
    private var lastEmitAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        if (processingFrame) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        processingFrame = true
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scope.launch {
            try {
                val objects = objectDetector.process(image).awaitResult()
                val labels = imageLabeler.process(image).awaitResult()
                val frameArea = (mediaImage.width * mediaImage.height).toFloat()
                val liveResult = inferLiveScanResult(objects, labels, frameArea) ?: return@launch
                val now = System.currentTimeMillis()
                if (now - lastEmitAt >= 500L) {
                    lastEmitAt = now
                    uiHandler.post { onScanResult(liveResult) }
                }
            } catch (_: Exception) {
                uiHandler.post { onError("Live ML detection failed for current frame.") }
            } finally {
                processingFrame = false
                imageProxy.close()
            }
        }
    }

    override fun close() {
        scope.cancel()
        objectDetector.close()
        imageLabeler.close()
    }
}

private fun inferLiveScanResult(
    objects: List<DetectedObject>,
    labels: List<ImageLabel>,
    frameArea: Float
): LiveScanResult? {
    val candidates = mutableListOf<LabelSignal>()
    objects.forEach { detectedObject ->
        detectedObject.labels.forEach { label ->
            candidates += LabelSignal(
                text = label.text.lowercase(),
                confidence = label.confidence
            )
        }
    }
    labels.take(15).forEach { label ->
        candidates += LabelSignal(
            text = label.text.lowercase(),
            confidence = label.confidence
        )
    }
    if (candidates.isEmpty()) return null

    val garbageScores = mutableMapOf<GarbageCategory, Float>().apply {
        GarbageCategory.entries.forEach { put(it, 0f) }
    }
    val binScores = mutableMapOf<BinType, Float>().apply {
        BinType.entries.forEach { put(it, 0f) }
    }
    var genericBinHintScore = 0f
    var nonGarbageScore = 0f
    var hasExplicitGarbageCue = false
    var closedScore = 0f
    var openScore = 0f
    var overflowScore = 0f

    candidates.forEach { signal ->
        val normalizedText = normalizeLabelText(signal.text)
        GARBAGE_KEYWORDS.forEach { (category, keywords) ->
            val score = keywordScore(signal.text, keywords)
            if (score > 0f) {
                garbageScores[category] = (garbageScores[category] ?: 0f) + score * signal.confidence
            }
        }
        NON_GARBAGE_KEYWORDS.forEach { keywords ->
            val score = keywordScore(signal.text, keywords)
            if (score > 0f) {
                nonGarbageScore += score * signal.confidence
            }
        }
        BIN_KEYWORDS.forEach { (binType, keywords) ->
            val score = keywordScore(signal.text, keywords)
            if (score > 0f) {
                binScores[binType] = (binScores[binType] ?: 0f) + score * signal.confidence
            }
        }
        if (containsAnyToken(normalizedText, GARBAGE_CUE_TOKENS)) {
            hasExplicitGarbageCue = true
        }
        if (containsAnyToken(normalizedText, BIN_CLOSED_TOKENS)) {
            closedScore += signal.confidence
        }
        if (containsAnyToken(normalizedText, BIN_OPEN_TOKENS)) {
            openScore += signal.confidence
        }
        if (containsAnyToken(normalizedText, BIN_OVERFLOW_TOKENS)) {
            overflowScore += signal.confidence
        }
        val genericBinScore = keywordScore(signal.text, GENERAL_BIN_PHRASE_KEYWORDS)
        if (genericBinScore > 0f) {
            genericBinHintScore += genericBinScore * signal.confidence
        }
        // Color + container hints for common household bins.
        if (
            genericBinScore > 0f &&
            containsAnyToken(normalizedText, ORGANIC_COLOR_HINT_TOKENS)
        ) {
            binScores[BinType.ORGANIC] = (binScores[BinType.ORGANIC] ?: 0f) + 0.45f * signal.confidence
        }
    }

    val garbagePick = garbageScores
        .entries
        .maxByOrNull { it.value }
        ?.takeIf { it.value > 0f }
    val garbageCategoryCandidate = garbagePick?.key
    val garbageScore = garbagePick?.value ?: 0f
    val likelyGarbageByScore = garbageScore >= MIN_GARBAGE_SCORE &&
        garbageScore >= (nonGarbageScore * 1.15f)
    val isLikelyGarbage = hasExplicitGarbageCue || likelyGarbageByScore
    val garbageCategory = if (isLikelyGarbage) garbageCategoryCandidate else null
    val garbageConfidence = if (isLikelyGarbage) toConfidence(garbageScore) else 0

    val bins = binScores.entries
        .filter { it.value >= MIN_BIN_SCORE }
        .sortedByDescending { it.value }
        .take(3)
        .map { BinObservation(it.key, toConfidence(it.value)) }

    val finalBins = if (bins.isEmpty() && genericBinHintScore >= MIN_GENERIC_BIN_HINT_SCORE) {
        listOf(BinObservation(BinType.GENERAL, toConfidence(genericBinHintScore)))
    } else {
        bins
    }

    if (garbageCategory == null && finalBins.isEmpty() && !isLikelyGarbage) return null

    val largestObjectArea = objects.maxOfOrNull { objectDetection ->
        objectDetection.boundingBox.width() * objectDetection.boundingBox.height()
    }?.toFloat() ?: 0f
    val normalizedArea = if (frameArea > 0f) largestObjectArea / frameArea else 0f
    val garbageSize = when {
        normalizedArea < 0.02f -> GarbageSize.SMALL
        normalizedArea < 0.09f -> GarbageSize.MEDIUM
        else -> GarbageSize.LARGE
    }
    val binClosed = if (finalBins.isNotEmpty() && (closedScore + openScore) > 0.25f) {
        closedScore > (openScore * 1.1f)
    } else {
        null
    }
    val binOverflowing = if (finalBins.isNotEmpty() && overflowScore > 0.22f) {
        true
    } else {
        null
    }

    return LiveScanResult(
        garbageCategory = garbageCategory,
        isLikelyGarbage = isLikelyGarbage,
        confidence = garbageConfidence,
        garbageSize = garbageSize,
        isBinClosed = binClosed,
        isBinOverflowing = binOverflowing,
        detectedBins = finalBins,
        rawLabels = candidates.map { it.text }.distinct().take(6)
    )
}

private fun keywordScore(text: String, keywords: List<String>): Float {
    val normalizedText = normalizeLabelText(text)
    if (normalizedText.isBlank()) return 0f
    val textTokens = normalizedText.split(" ").filter { it.isNotBlank() }
    var score = 0f
    keywords.forEach { keyword ->
        val normalizedKeyword = normalizeLabelText(keyword)
        if (normalizedKeyword.isBlank()) return@forEach

        if (normalizedText.contains(normalizedKeyword)) {
            score += 1f
            return@forEach
        }

        val keywordTokens = normalizedKeyword.split(" ").filter { it.isNotBlank() }
        if (keywordTokens.isEmpty()) return@forEach

        var matched = 0
        keywordTokens.forEach { keywordToken ->
            val tokenMatched = textTokens.any { textToken ->
                textToken == keywordToken ||
                    textToken.startsWith(keywordToken) ||
                    keywordToken.startsWith(textToken)
            }
            if (tokenMatched) matched += 1
        }

        if (matched == keywordTokens.size) {
            score += 0.85f
        } else if (matched > 0) {
            score += 0.35f * (matched.toFloat() / keywordTokens.size.toFloat())
        }
    }
    return score
}

private fun normalizeLabelText(value: String): String {
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

private fun toConfidence(score: Float): Int {
    val normalized = ((score / (score + 1.5f)) * 100f).roundToInt()
    return normalized.coerceIn(0, 99)
}

private data class LabelSignal(
    val text: String,
    val confidence: Float
)

private val GARBAGE_KEYWORDS = mapOf(
    GarbageCategory.PLASTIC to listOf(
        "plastic", "bottle", "wrapper", "packaging", "polybag", "pet",
        "container", "bag", "cup", "lid", "cap", "straw", "sachet",
        "disposable", "polythene", "pouch", "tube"
    ),
    GarbageCategory.PAPER to listOf(
        "paper", "cardboard", "newspaper", "book", "carton",
        "tissue", "napkin", "box", "receipt", "envelope", "magazine"
    ),
    GarbageCategory.METAL to listOf(
        "metal", "can", "aluminum", "steel", "tin", "iron",
        "foil", "scrap", "wire", "nail"
    ),
    GarbageCategory.GLASS to listOf(
        "glass", "jar", "bottle glass", "broken glass", "mirror"
    ),
    GarbageCategory.ORGANIC to listOf(
        "food", "fruit", "vegetable", "organic", "leaf", "compost",
        "banana", "orange", "apple", "peel", "rotten", "peel",
        "kitchen waste", "eggshell", "leftover"
    ),
    GarbageCategory.E_WASTE to listOf(
        "electronic", "electronics", "battery", "charger", "cable", "adapter",
        "phone", "circuit", "wire", "bulb", "device", "gadget", "remote"
    )
)

private val BIN_KEYWORDS = mapOf(
    BinType.PLASTIC to listOf("plastic bin", "recycling bin", "recycle", "plastic container"),
    BinType.PAPER to listOf("paper bin", "paper recycling", "paper container"),
    BinType.METAL to listOf("metal bin", "can bin", "aluminum can"),
    BinType.GLASS to listOf("glass bin", "glass container"),
    BinType.ORGANIC to listOf("compost bin", "organic bin", "green bin", "food waste", "wet waste"),
    BinType.E_WASTE to listOf("e-waste", "electronics bin", "battery bin", "electronic waste"),
    BinType.GENERAL to listOf(
        "trash can",
        "garbage can",
        "waste bin",
        "dustbin",
        "litter bin",
        "trash bin",
        "waste container",
        "garbage container",
        "waste basket",
        "trash basket"
    )
)

private val GENERAL_BIN_PHRASE_KEYWORDS = listOf(
    "trash can",
    "garbage can",
    "waste bin",
    "dustbin",
    "litter bin",
    "trash bin",
    "waste basket",
    "rubbish bin",
    "bin",
    "dustbin",
    "container",
    "bucket",
    "barrel",
    "canister",
    "receptacle"
)

private val ORGANIC_COLOR_HINT_TOKENS = setOf("green")
private val GARBAGE_CUE_TOKENS = setOf(
    "trash",
    "waste",
    "litter",
    "garbage",
    "dirty",
    "discarded",
    "rubbish"
)
private val BIN_CLOSED_TOKENS = setOf("closed", "lid", "cover", "cap", "sealed")
private val BIN_OPEN_TOKENS = setOf("open", "opened", "uncovered")
private val BIN_OVERFLOW_TOKENS = setOf("overflow", "overflowing", "full", "stuffed", "pile", "spilling")
private val NON_GARBAGE_KEYWORDS = listOf(
    listOf("person", "human", "face", "hand", "shoe", "clothing"),
    listOf("monitor", "screen", "laptop", "keyboard", "mouse", "desk", "table", "chair"),
    listOf("car", "bike", "road", "building", "wall", "floor"),
    listOf("dog", "cat", "pet", "bird"),
    listOf("tree", "plant", "flower", "grass")
)
private const val MIN_GARBAGE_SCORE = 0.45f
private const val MIN_BIN_SCORE = 0.35f
private const val MIN_GENERIC_BIN_HINT_SCORE = 0.45f

private suspend fun <T> Task<T>.awaitResult(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        addOnFailureListener { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.cancel()
            }
        }
    }
}
