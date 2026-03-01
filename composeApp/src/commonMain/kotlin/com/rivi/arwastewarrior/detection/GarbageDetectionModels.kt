package com.rivi.arwastewarrior.detection

enum class GarbageCategory(val label: String, val recommendedBin: String) {
    PLASTIC("Plastic", "Plastic Bin"),
    PAPER("Paper", "Paper Bin"),
    METAL("Metal", "Metal Bin"),
    GLASS("Glass", "Glass Bin"),
    ORGANIC("Organic", "Organic Bin"),
    E_WASTE("E-Waste", "E-Waste Bin")
}

data class DetectionResult(
    val category: GarbageCategory,
    val confidence: Int,
    val aiTip: String,
    val co2SavedKg: Double,
    val demonsDefeatedGain: Int
)
