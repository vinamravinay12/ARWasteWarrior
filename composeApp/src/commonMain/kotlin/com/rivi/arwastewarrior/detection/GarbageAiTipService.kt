package com.rivi.arwastewarrior.detection

interface GarbageAiTipService {
    suspend fun analyzeEncounter(
        category: GarbageCategory,
        confidence: Int,
        recommendedBinNearby: Boolean,
        language: AppLanguage = AppLanguage.ENGLISH,
        garbageSize: GarbageSize = GarbageSize.MEDIUM,
        binClosed: Boolean? = null,
        binOverflowing: Boolean? = null,
        likelyGarbage: Boolean = true,
        rawLabels: List<String> = emptyList(),
        detectedBins: List<BinObservation> = emptyList()
    ): GarbageAiEncounter
}

data class GarbageAiEncounter(
    val isGarbage: Boolean,
    val resolvedCategory: GarbageCategory?,
    val tip: String,
    val binIssue: String,
    val actionPrompt: String,
    val demonType: DemonType,
    val demonCount: Int,
    val demonMix: List<DemonSpawn>,
    val gameModeOptions: List<GameModeOption>,
    val recommendedMode: GameModeOption,
    val diseaseWarningHindi: String,
    val speechTextHindi: String
)

class RuleBasedGarbageAiTipService : GarbageAiTipService {
    override suspend fun analyzeEncounter(
        category: GarbageCategory,
        confidence: Int,
        recommendedBinNearby: Boolean,
        language: AppLanguage,
        garbageSize: GarbageSize,
        binClosed: Boolean?,
        binOverflowing: Boolean?,
        likelyGarbage: Boolean,
        rawLabels: List<String>,
        detectedBins: List<BinObservation>
    ): GarbageAiEncounter {
        val resolvedCategory = if (category == GarbageCategory.UNKNOWN) null else category
        val isGarbage = likelyGarbage && resolvedCategory != null
        val confidenceNote = if (language == AppLanguage.HINDI) {
            if (confidence < 80) {
                "बेहतर परिणाम के लिए पास आकर दोबारा स्कैन करें।"
            } else {
                "डिटेक्शन स्थिर है।"
            }
        } else {
            if (confidence < 80) {
                "Move closer and rescan for stable detection."
            } else {
                "Detection is stable."
            }
        }

        val demonType = when (resolvedCategory) {
            GarbageCategory.E_WASTE -> DemonType.E_WASTE
            GarbageCategory.ORGANIC -> DemonType.ORGANIC
            else -> DemonType.PLASTIC
        }

        val baseCount = when (resolvedCategory) {
            GarbageCategory.E_WASTE -> 4
            GarbageCategory.PLASTIC -> 3
            GarbageCategory.ORGANIC -> 2
            GarbageCategory.METAL -> 3
            GarbageCategory.GLASS -> 2
            GarbageCategory.PAPER -> 2
            else -> 0
        }
        val sizeBonus = when (garbageSize) {
            GarbageSize.SMALL -> 0
            GarbageSize.MEDIUM -> 1
            GarbageSize.LARGE -> 2
        }
        val binPenalty = if (!recommendedBinNearby) 1 else 0
        val closedPenalty = if (binClosed == true) 1 else 0
        val overflowPenalty = if (binOverflowing == true) 1 else 0
        val demonCount = if (!isGarbage) {
            0
        } else {
            (baseCount + sizeBonus + binPenalty + closedPenalty + overflowPenalty).coerceIn(1, 8)
        }

        val binIssue = if (language == AppLanguage.HINDI) {
            when {
                !isGarbage -> "कचरा कार्रवाई की जरूरत नहीं।"
                binOverflowing == true -> "पास का बिन भरा हुआ लग रहा है। फैलाव से बचने के लिए दूसरा बिन लें।"
                binClosed == true -> "पास का बिन बंद है। निपटान से पहले सुरक्षित तरीके से खोलें।"
                !recommendedBinNearby -> "सही बिन अभी पास में नहीं मिला।"
                else -> "सही बिन पास में मिला।"
            }
        } else {
            when {
                !isGarbage -> "No waste action needed."
                binOverflowing == true -> "Nearby bin appears overflowing. Use another bin to avoid spill and contamination."
                binClosed == true -> "Nearby bin appears closed. Open safely before disposal."
                !recommendedBinNearby -> "Correct bin not detected nearby yet."
                else -> "Correct bin detected nearby."
            }
        }

        val tip = if (language == AppLanguage.HINDI) {
            when (resolvedCategory) {
                GarbageCategory.PLASTIC -> "प्लास्टिक कचरे को गीले कचरे से अलग रखें। $confidenceNote"
                GarbageCategory.PAPER -> "कागज़ को सूखा रखें और अलग संग्रहित करें। $confidenceNote"
                GarbageCategory.METAL -> "धातु कचरे को अलग करें ताकि रीसाइक्लिंग बेहतर हो। $confidenceNote"
                GarbageCategory.GLASS -> "कांच को सावधानी से फेंकें, टूटने से बचाएं। $confidenceNote"
                GarbageCategory.ORGANIC -> "जैविक कचरा केवल गीले/कम्पोस्ट बिन में डालें। $confidenceNote"
                GarbageCategory.E_WASTE -> "ई-वेस्ट केवल अधिकृत ई-वेस्ट बिन में डालें। $confidenceNote"
                else -> "यह वस्तु सामान्य लग रही है, कचरा नहीं। $confidenceNote"
            }
        } else {
            when (resolvedCategory) {
                GarbageCategory.PLASTIC -> "Identify plastic waste and avoid mixing with wet waste. $confidenceNote"
                GarbageCategory.PAPER -> "Keep paper dry and segregated from wet waste. $confidenceNote"
                GarbageCategory.METAL -> "Separate metal waste to improve recycling recovery. $confidenceNote"
                GarbageCategory.GLASS -> "Handle glass carefully and dispose without breaking. $confidenceNote"
                GarbageCategory.ORGANIC -> "Send organic waste to wet/compost bin only. $confidenceNote"
                GarbageCategory.E_WASTE -> "Dispose e-waste only in authorized e-waste collection bins. $confidenceNote"
                else -> "Object looks normal, not garbage. $confidenceNote"
            }
        }

        val actionPrompt = if (language == AppLanguage.HINDI) {
            when {
                !isGarbage -> "मिशन शुरू नहीं हुआ। वास्तविक कचरे पर कैमरा रखें।"
                recommendedBinNearby -> "मिशन: कचरा उठाएं, पिक-अप कैप्चर करें, फिर बिन में डालकर डिस्पोज़ल कैप्चर करें।"
                else -> "मिशन: सही बिन खोजें, फिर पिक-अप और डिस्पोज़ल कैप्चर करें।"
            }
        } else {
            when {
                !isGarbage -> "No mission. Point at real garbage to start."
                recommendedBinNearby -> "Mission: pick garbage, capture pick-up, then throw into bin and capture disposal."
                else -> "Mission: locate correct bin first, then capture pick-up and disposal."
            }
        }

        val diseaseWarning = if (language == AppLanguage.HINDI) {
            when (resolvedCategory) {
                GarbageCategory.PLASTIC -> "गलत प्लास्टिक निपटान से जहरीले रसायन निकलते हैं और सांस तथा हार्मोन संबंधी समस्याएं बढ़ती हैं।"
                GarbageCategory.PAPER -> "गीला कागज़ बैक्टीरिया और फफूंदी बढ़ाकर एलर्जी और संक्रमण का खतरा बढ़ाता है।"
                GarbageCategory.METAL -> "जंग लगे धातु कचरे से चोट और संक्रमण का जोखिम बढ़ता है।"
                GarbageCategory.GLASS -> "टूटा कांच कट और संक्रमण का कारण बन सकता है, इसलिए सुरक्षित निपटान जरूरी है।"
                GarbageCategory.ORGANIC -> "सड़ा जैविक कचरा मच्छर-मक्खी बढ़ाकर डेंगू और पेट के संक्रमण का खतरा बढ़ाता है।"
                GarbageCategory.E_WASTE -> "ई-वेस्ट में विषैले तत्व होते हैं जो नसों, किडनी और बच्चों के विकास पर असर डालते हैं।"
                else -> "यह सामान्य वस्तु लग रही है, कचरा नहीं।"
            }
        } else {
            when (resolvedCategory) {
                GarbageCategory.PLASTIC -> "Improper plastic disposal increases toxic exposure and respiratory risk."
                GarbageCategory.PAPER -> "Wet paper can grow bacteria and fungus, increasing infection risk."
                GarbageCategory.METAL -> "Rusty metal waste can cause cuts and infection."
                GarbageCategory.GLASS -> "Broken glass can cause injury and infections if not disposed safely."
                GarbageCategory.ORGANIC -> "Rotting organic waste attracts vectors and raises infection risk."
                GarbageCategory.E_WASTE -> "E-waste toxins can affect nerves, kidneys, and child development."
                else -> "This appears to be a normal object, not garbage."
            }
        }

        val speechText = if (language == AppLanguage.HINDI) {
            when {
                !isGarbage -> "यह कचरा नहीं है। कृपया वास्तविक कचरे पर कैमरा रखें।"
                recommendedBinNearby -> "कचरा उठाइए, कैमरे में दिखाइए, और सही डस्टबिन में डालकर फिर से कैप्चर कीजिए।"
                else -> "सही डस्टबिन ढूंढिए, फिर कचरा उठाकर उसमें डालने की क्रिया कैप्चर कीजिए।"
            }
        } else {
            when {
                !isGarbage -> "This is not garbage. Point the camera at actual waste."
                recommendedBinNearby -> "Pick the garbage, show pick-up on camera, then throw it in the right bin and capture disposal."
                else -> "Find the correct bin first, then capture pick-up and disposal."
            }
        }

        val dominantKind = defaultDominantKind(resolvedCategory)
        val mix = if (isGarbage) {
            buildDemonMix(totalCount = demonCount, dominantKind = dominantKind)
        } else {
            emptyList()
        }

        return GarbageAiEncounter(
            isGarbage = isGarbage,
            resolvedCategory = resolvedCategory,
            tip = tip,
            binIssue = binIssue,
            actionPrompt = actionPrompt,
            demonType = demonType,
            demonCount = demonCount,
            demonMix = mix,
            gameModeOptions = listOf(GameModeOption.REAL),
            recommendedMode = GameModeOption.REAL,
            diseaseWarningHindi = diseaseWarning,
            speechTextHindi = speechText
        )
    }
}

expect fun platformGarbageAiTipService(): GarbageAiTipService
