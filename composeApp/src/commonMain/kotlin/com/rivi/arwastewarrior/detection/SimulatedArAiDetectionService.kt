package com.rivi.arwastewarrior.detection

import kotlin.random.Random

class SimulatedArAiDetectionService : GarbageDetectionService {
    override suspend fun detectGarbage(): DetectionResult {
        val category = GarbageCategory.entries.random()
        val confidence = Random.nextInt(78, 99)
        val co2Saved = when (category) {
            GarbageCategory.PLASTIC -> 0.32
            GarbageCategory.PAPER -> 0.18
            GarbageCategory.METAL -> 0.52
            GarbageCategory.GLASS -> 0.27
            GarbageCategory.ORGANIC -> 0.14
            GarbageCategory.E_WASTE -> 0.61
        }

        val aiTip = when (category) {
            GarbageCategory.PLASTIC -> "Rinse before disposal to improve recycling quality."
            GarbageCategory.PAPER -> "Keep paper dry and flatten if possible."
            GarbageCategory.METAL -> "Separate cans from mixed waste for higher recovery."
            GarbageCategory.GLASS -> "Avoid breaking glass; place directly in glass bin."
            GarbageCategory.ORGANIC -> "Compostable waste should be isolated from dry waste."
            GarbageCategory.E_WASTE -> "Route to certified e-waste bin to avoid toxin leaks."
        }

        return DetectionResult(
            category = category,
            confidence = confidence,
            aiTip = aiTip,
            co2SavedKg = co2Saved,
            demonsDefeatedGain = Random.nextInt(1, 4)
        )
    }
}
