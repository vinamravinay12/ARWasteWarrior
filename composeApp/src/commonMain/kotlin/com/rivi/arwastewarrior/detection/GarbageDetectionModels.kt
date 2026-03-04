package com.rivi.arwastewarrior.detection

enum class GarbageCategory(val label: String, val recommendedBin: String) {
    UNKNOWN("Not Garbage", "N/A"),
    PLASTIC("Plastic", "Plastic Bin"),
    PAPER("Paper", "Paper Bin"),
    METAL("Metal", "Metal Bin"),
    GLASS("Glass", "Glass Bin"),
    ORGANIC("Organic", "Organic Bin"),
    E_WASTE("E-Waste", "E-Waste Bin")
}

enum class GarbageSize {
    SMALL,
    MEDIUM,
    LARGE
}

enum class GameModeOption {
    VIRTUAL,
    REAL
}

enum class DemonType(val label: String) {
    PLASTIC("Plastic Demon"),
    ORGANIC("Organic Demon"),
    E_WASTE("E-Waste Demon")
}

enum class DemonKind(val label: String) {
    BACTERIA("Bacteria"),
    VIRUS("Virus"),
    FUNGUS("Fungus")
}

data class DemonSpawn(
    val kind: DemonKind,
    val count: Int
)

enum class BinType(val label: String) {
    PLASTIC("Plastic Bin"),
    PAPER("Paper Bin"),
    METAL("Metal Bin"),
    GLASS("Glass Bin"),
    ORGANIC("Organic Bin"),
    E_WASTE("E-Waste Bin"),
    GENERAL("General Waste Bin")
}

data class BinObservation(
    val type: BinType,
    val confidence: Int
)

data class LiveScanResult(
    val garbageCategory: GarbageCategory?,
    val isLikelyGarbage: Boolean,
    val confidence: Int,
    val garbageSize: GarbageSize = GarbageSize.MEDIUM,
    val isBinClosed: Boolean? = null,
    val isBinOverflowing: Boolean? = null,
    val detectedBins: List<BinObservation>,
    val rawLabels: List<String> = emptyList()
)

data class DetectionResult(
    val category: GarbageCategory,
    val isGarbage: Boolean = true,
    val confidence: Int,
    val aiTip: String,
    val binIssue: String = "",
    val actionPrompt: String = "",
    val co2SavedKg: Double,
    val demonsDefeatedGain: Int,
    val detectedBins: List<String> = emptyList(),
    val isRecommendedBinNearby: Boolean = false,
    val demonCount: Int = 1,
    val demonType: DemonType = DemonType.PLASTIC,
    val demonMix: List<DemonSpawn> = emptyList(),
    val gameModeOptions: List<GameModeOption> = listOf(GameModeOption.REAL),
    val recommendedMode: GameModeOption = GameModeOption.REAL,
    val diseaseWarningHindi: String = "",
    val speechTextHindi: String = ""
)

private val orderedDemonKinds = listOf(
    DemonKind.BACTERIA,
    DemonKind.VIRUS,
    DemonKind.FUNGUS
)

fun defaultDominantKind(category: GarbageCategory?): DemonKind {
    return when (category) {
        GarbageCategory.ORGANIC -> DemonKind.FUNGUS
        GarbageCategory.E_WASTE -> DemonKind.VIRUS
        else -> DemonKind.BACTERIA
    }
}

fun buildDemonMix(
    totalCount: Int,
    dominantKind: DemonKind
): List<DemonSpawn> {
    if (totalCount <= 0) return emptyList()

    val counts = mutableMapOf<DemonKind, Int>()
    orderedDemonKinds.forEach { kind -> counts[kind] = 0 }
    counts[dominantKind] = (counts[dominantKind] ?: 0) + 1

    var remaining = totalCount - 1
    var pointer = orderedDemonKinds.indexOf(dominantKind)
    while (remaining > 0) {
        pointer = (pointer + 1) % orderedDemonKinds.size
        val nextKind = orderedDemonKinds[pointer]
        counts[nextKind] = (counts[nextKind] ?: 0) + 1
        remaining--
    }

    return orderedDemonKinds
        .map { kind -> DemonSpawn(kind = kind, count = counts[kind] ?: 0) }
        .filter { it.count > 0 }
}

fun normalizeDemonMix(
    proposedMix: List<DemonSpawn>,
    totalCount: Int,
    dominantKind: DemonKind
): List<DemonSpawn> {
    if (totalCount <= 0) return emptyList()

    val aggregated = proposedMix
        .groupBy { it.kind }
        .mapValues { (_, spawns) -> spawns.sumOf { it.count.coerceAtLeast(0) } }
        .toMutableMap()
    orderedDemonKinds.forEach { kind -> aggregated.putIfAbsent(kind, 0) }

    var currentTotal = aggregated.values.sum()
    if (currentTotal == 0) {
        return buildDemonMix(totalCount = totalCount, dominantKind = dominantKind)
    }

    if (currentTotal > totalCount) {
        while (currentTotal > totalCount) {
            val reducible = orderedDemonKinds
                .sortedByDescending { kind ->
                    if (kind == dominantKind) Int.MIN_VALUE else (aggregated[kind] ?: 0)
                }
                .firstOrNull { (aggregated[it] ?: 0) > 0 }
                ?: dominantKind
            aggregated[reducible] = (aggregated[reducible] ?: 0) - 1
            currentTotal--
        }
    } else if (currentTotal < totalCount) {
        var pointer = orderedDemonKinds.indexOf(dominantKind)
        while (currentTotal < totalCount) {
            val kind = orderedDemonKinds[pointer]
            aggregated[kind] = (aggregated[kind] ?: 0) + 1
            pointer = (pointer + 1) % orderedDemonKinds.size
            currentTotal++
        }
    }

    return orderedDemonKinds
        .map { kind -> DemonSpawn(kind = kind, count = (aggregated[kind] ?: 0).coerceAtLeast(0)) }
        .filter { it.count > 0 }
}

fun recommendedBinType(category: GarbageCategory): BinType {
    return when (category) {
        GarbageCategory.UNKNOWN -> BinType.GENERAL
        GarbageCategory.PLASTIC -> BinType.PLASTIC
        GarbageCategory.PAPER -> BinType.PAPER
        GarbageCategory.METAL -> BinType.METAL
        GarbageCategory.GLASS -> BinType.GLASS
        GarbageCategory.ORGANIC -> BinType.ORGANIC
        GarbageCategory.E_WASTE -> BinType.E_WASTE
    }
}
