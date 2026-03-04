package com.rivi.arwastewarrior.detection

class SimulatedArAiDetectionService(
    private val aiTipService: GarbageAiTipService = RuleBasedGarbageAiTipService()
) : GarbageDetectionService {
    override suspend fun detectGarbage(
        liveScanResult: LiveScanResult?,
        language: AppLanguage
    ): DetectionResult {
        val scan = liveScanResult ?: return nonGarbageResult(
            confidence = 0,
            detectedBins = emptyList(),
            aiTipService = aiTipService,
            language = language
        )

        val preferredCategory = scan.garbageCategory ?: GarbageCategory.UNKNOWN
        val recommendedBin = if (preferredCategory != GarbageCategory.UNKNOWN) {
            recommendedBinType(preferredCategory)
        } else {
            BinType.GENERAL
        }
        val recommendedBinNearby = preferredCategory != GarbageCategory.UNKNOWN &&
            scan.detectedBins.any { observation ->
                observation.type == recommendedBin || observation.type == BinType.GENERAL
            }

        val aiEncounter = aiTipService.analyzeEncounter(
            category = preferredCategory,
            confidence = scan.confidence.coerceIn(0, 99),
            recommendedBinNearby = recommendedBinNearby,
            language = language,
            garbageSize = scan.garbageSize,
            binClosed = scan.isBinClosed,
            binOverflowing = scan.isBinOverflowing,
            likelyGarbage = scan.isLikelyGarbage,
            rawLabels = scan.rawLabels,
            detectedBins = scan.detectedBins
        )

        val resolvedCategory = aiEncounter.resolvedCategory ?: preferredCategory
        if (!aiEncounter.isGarbage || resolvedCategory == GarbageCategory.UNKNOWN) {
            return DetectionResult(
                category = GarbageCategory.UNKNOWN,
                isGarbage = false,
                confidence = scan.confidence.coerceIn(0, 99),
                aiTip = aiEncounter.tip,
                binIssue = aiEncounter.binIssue,
                actionPrompt = aiEncounter.actionPrompt,
                co2SavedKg = 0.0,
                demonsDefeatedGain = 0,
                detectedBins = scan.detectedBins.map { it.type.label },
                isRecommendedBinNearby = false,
                demonCount = 0,
                demonType = aiEncounter.demonType,
                demonMix = emptyList(),
                gameModeOptions = listOf(GameModeOption.REAL),
                recommendedMode = GameModeOption.REAL,
                diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
                speechTextHindi = aiEncounter.speechTextHindi
            )
        }

        val correctedRecommendedBin = recommendedBinType(resolvedCategory)
        val correctedBinNearby = scan.detectedBins.any { observation ->
            observation.type == correctedRecommendedBin || observation.type == BinType.GENERAL
        }

        return DetectionResult(
            category = resolvedCategory,
            isGarbage = true,
            confidence = scan.confidence.coerceIn(55, 99),
            aiTip = aiEncounter.tip,
            binIssue = aiEncounter.binIssue,
            actionPrompt = aiEncounter.actionPrompt,
            co2SavedKg = co2SavedFor(resolvedCategory),
            demonsDefeatedGain = aiEncounter.demonCount.coerceAtLeast(1),
            detectedBins = scan.detectedBins.map { it.type.label },
            isRecommendedBinNearby = correctedBinNearby,
            demonCount = aiEncounter.demonCount.coerceAtLeast(1),
            demonType = aiEncounter.demonType,
            demonMix = normalizeDemonMix(
                proposedMix = aiEncounter.demonMix,
                totalCount = aiEncounter.demonCount.coerceAtLeast(1),
                dominantKind = defaultDominantKind(resolvedCategory)
            ),
            gameModeOptions = listOf(GameModeOption.REAL),
            recommendedMode = GameModeOption.REAL,
            diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
            speechTextHindi = aiEncounter.speechTextHindi
        )
    }

    private suspend fun nonGarbageResult(
        confidence: Int,
        detectedBins: List<String>,
        aiTipService: GarbageAiTipService,
        language: AppLanguage
    ): DetectionResult {
        val aiEncounter = aiTipService.analyzeEncounter(
            category = GarbageCategory.UNKNOWN,
            confidence = confidence,
            recommendedBinNearby = false,
            language = language,
            likelyGarbage = false
        )
        return DetectionResult(
            category = GarbageCategory.UNKNOWN,
            isGarbage = false,
            confidence = confidence,
            aiTip = aiEncounter.tip,
            binIssue = aiEncounter.binIssue,
            actionPrompt = aiEncounter.actionPrompt,
            co2SavedKg = 0.0,
            demonsDefeatedGain = 0,
            detectedBins = detectedBins,
            isRecommendedBinNearby = false,
            demonCount = 0,
            demonType = aiEncounter.demonType,
            demonMix = emptyList(),
            gameModeOptions = listOf(GameModeOption.REAL),
            recommendedMode = GameModeOption.REAL,
            diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
            speechTextHindi = aiEncounter.speechTextHindi
        )
    }

    private fun co2SavedFor(category: GarbageCategory): Double {
        return when (category) {
            GarbageCategory.UNKNOWN -> 0.0
            GarbageCategory.PLASTIC -> 0.32
            GarbageCategory.PAPER -> 0.18
            GarbageCategory.METAL -> 0.52
            GarbageCategory.GLASS -> 0.27
            GarbageCategory.ORGANIC -> 0.14
            GarbageCategory.E_WASTE -> 0.61
        }
    }
}
