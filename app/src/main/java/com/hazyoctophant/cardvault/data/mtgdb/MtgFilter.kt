package com.hazyoctophant.cardvault.data.mtgdb

/** How the selected colors constrain a card's color identity. */
enum class ColorMode(val label: String) {
    WITHIN("Within these colors"),
    INCLUDES("Includes these colors"),
    EXACTLY("Exactly these colors"),
}

enum class MtgSort(val label: String, val scryfallOrder: String, val scryfallDir: String) {
    NAME("Name", "name", "asc"),
    POPULAR("Most played (EDHREC)", "edhrec", "asc"),
    PRICE("Price", "usd", "desc"),
    MANA_VALUE("Mana value", "cmc", "asc"),
    NEWEST("Newest", "released", "desc"),
}

/**
 * Search filters for the Magic database. The same filter runs offline (as SQL over the downloaded
 * database) or online (as a Scryfall query), so results match either way.
 */
data class MtgFilter(
    val text: String = "",
    /** Any of W, U, B, R, G, and C for colorless. */
    val colors: Set<String> = emptySet(),
    val colorMode: ColorMode = ColorMode.WITHIN,
    /** Card types, e.g. "Creature", "Instant"; a card matches if it has any of them. */
    val types: Set<String> = emptySet(),
    /** "Common", "Uncommon", "Rare", "Mythic". */
    val rarities: Set<String> = emptySet(),
    val minManaValue: Int = 0,
    /** 7 means "7 or more". */
    val maxManaValue: Int = MAX_MV,
    /** Scryfall format key, e.g. "standard", "commander". */
    val format: String? = null,
    val maxPriceUsd: Double? = null,
    val sort: MtgSort = MtgSort.NAME,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && colors.isEmpty() && types.isEmpty() && rarities.isEmpty() &&
            minManaValue == 0 && maxManaValue >= MAX_MV && format == null && maxPriceUsd == null

    /** SQL over the `mtg_db` table, with positional arguments. */
    fun toSql(limit: Int = 300): Pair<String, List<Any>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        text.trim().takeIf { it.isNotEmpty() }?.let { t ->
            // Every word must appear in the name, type line or rules text.
            t.split(Regex("\\s+")).forEach { w ->
                where += "(name LIKE ? OR typeLine LIKE ? OR text LIKE ?)"
                repeat(3) { args += "%$w%" }
            }
        }

        val chosen = colors.filter { it in WUBRG }.toSet()
        val colorless = "C" in colors
        when {
            chosen.isEmpty() && colorless -> where += "colors = ''"
            chosen.isEmpty() -> Unit
            colorMode == ColorMode.WITHIN -> WUBRG.filter { it !in chosen }.forEach { where += "colors NOT LIKE '%$it%'" }
            colorMode == ColorMode.INCLUDES -> chosen.forEach { where += "colors LIKE '%$it%'" }
            colorMode == ColorMode.EXACTLY -> WUBRG.forEach { c ->
                where += if (c in chosen) "colors LIKE '%$c%'" else "colors NOT LIKE '%$c%'"
            }
        }

        if (types.isNotEmpty()) {
            where += types.joinToString(" OR ", "(", ")") { "typeLine LIKE ?" }
            types.forEach { args += "%$it%" }
        }
        if (rarities.isNotEmpty()) {
            where += rarities.joinToString(",", "rarity IN (", ")") { "?" }
            args.addAll(rarities)
        }
        if (minManaValue > 0) { where += "cost >= ?"; args += minManaValue.toDouble() }
        if (maxManaValue < MAX_MV) { where += "cost <= ?"; args += maxManaValue.toDouble() }
        format?.let { where += "(',' || legalities || ',') LIKE ?"; args += "%,$it,%" }
        maxPriceUsd?.let { where += "usd IS NOT NULL AND usd <= ?"; args += it }

        val order = when (sort) {
            MtgSort.NAME -> "name COLLATE NOCASE ASC"
            MtgSort.POPULAR -> "edhrecRank IS NULL, edhrecRank ASC"
            MtgSort.PRICE -> "usd IS NULL, usd DESC"
            MtgSort.MANA_VALUE -> "cost ASC, name COLLATE NOCASE ASC"
            MtgSort.NEWEST -> "releasedAt DESC, name COLLATE NOCASE ASC"
        }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return "SELECT * FROM mtg_db$whereSql ORDER BY $order LIMIT $limit" to args
    }

    /** The same filter in Scryfall search syntax. */
    fun toScryfallQuery(): String {
        val parts = mutableListOf<String>()
        text.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.forEach { w ->
            val q = w.replace("\"", "")
            parts += "(\"$q\" or o:\"$q\" or t:\"$q\")"
        }
        val chosen = WUBRG.filter { it in colors }.joinToString("").lowercase()
        when {
            chosen.isEmpty() && "C" in colors -> parts += "id=c"
            chosen.isEmpty() -> Unit
            colorMode == ColorMode.WITHIN -> parts += "id<=$chosen"
            colorMode == ColorMode.INCLUDES -> parts += "id>=$chosen"
            colorMode == ColorMode.EXACTLY -> parts += "id=$chosen"
        }
        if (types.isNotEmpty()) parts += types.joinToString(" or ", "(", ")") { "t:${it.lowercase()}" }
        if (rarities.isNotEmpty()) parts += rarities.joinToString(" or ", "(", ")") { "r:${it.lowercase()}" }
        if (minManaValue > 0) parts += "mv>=$minManaValue"
        if (maxManaValue < MAX_MV) parts += "mv<=$maxManaValue"
        format?.let { parts += "f:$it" }
        maxPriceUsd?.let { parts += "usd<=${"%.2f".format(java.util.Locale.US, it)}" }
        if (parts.isEmpty()) parts += "game:paper"
        return parts.joinToString(" ")
    }

    companion object {
        const val MAX_MV = 7
        val WUBRG = listOf("W", "U", "B", "R", "G")
        val TYPES = listOf("Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Land", "Battle", "Legendary")
        val RARITIES = listOf("Common", "Uncommon", "Rare", "Mythic")
        val FORMATS = listOf("standard", "pioneer", "modern", "legacy", "vintage", "pauper", "commander", "brawl")
    }
}
