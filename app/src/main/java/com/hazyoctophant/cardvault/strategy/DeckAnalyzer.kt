package com.hazyoctophant.cardvault.strategy

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.DeckZone
import com.hazyoctophant.cardvault.model.Game
import kotlin.math.roundToInt

data class Stat(val label: String, val value: String)
data class Bar(val label: String, val count: Int)

data class DeckAnalysis(
    val archetype: String,
    val archetypeSummary: String,
    val stats: List<Stat>,
    val curve: List<Bar>,
    val breakdown: List<Bar>,
    val colors: List<Bar>,
    val tips: List<Issue>,
    /** Roles the deck is short on; used to rank suggestions. */
    val needs: Set<Role>,
    /** Mana values / costs the curve is thin at. */
    val curveGaps: Set<Int>,
)

/**
 * Heuristic deck analysis built on widely used deck-building guidelines for each game
 * (land/energy ratios, curve shape, role counts, opening-hand odds). It's a coach, not a solver.
 */
object DeckAnalyzer {

    fun analyze(format: DeckFormat, entries: List<DeckEntry>): DeckAnalysis = when (format.game) {
        Game.MTG -> analyzeMagic(format, entries)
        Game.POKEMON -> analyzePokemon(entries)
        Game.ONE_PIECE -> analyzeOnePiece(entries)
    }

    // ---------------------------------------------------------------- Magic

    private fun analyzeMagic(format: DeckFormat, entries: List<DeckEntry>): DeckAnalysis {
        val main = entries.filter { it.zone == DeckZone.MAIN || it.zone == DeckZone.COMMANDER }
        val total = main.sumOf { it.quantity }
        val commander = format.id == "commander"
        fun count(p: (CardEntity) -> Boolean) = main.filter { p(it.card) }.sumOf { it.quantity }
        fun roleCount(r: Role) = main.filter { r in CardRoles.of(it.card) }.sumOf { it.quantity }

        val lands = count(CardRoles::isLand)
        val creatures = count(CardRoles::isCreature)
        val instantsSorceries = count { it.typeLine.contains("Instant") || it.typeLine.contains("Sorcery") }
        val nonLand = main.filterNot { CardRoles.isLand(it.card) }
        val nonLandCount = nonLand.sumOf { it.quantity }
        val avgMv = if (nonLandCount == 0) 0.0 else nonLand.sumOf { (it.card.cost ?: 0.0) * it.quantity } / nonLandCount
        val removal = roleCount(Role.REMOVAL)
        val wipes = roleCount(Role.BOARD_WIPE)
        val draw = roleCount(Role.DRAW)
        val ramp = roleCount(Role.RAMP)
        val counters = roleCount(Role.COUNTERSPELL)

        val (archetype, summary, landRange) = when {
            commander -> Triple(
                "Commander",
                "100-card singleton. A common template: 36–38 lands, ~10 ramp, ~10 card draw, 8–10 targeted removal, 2–3 board wipes, and the rest synergy with your commander.",
                36..38,
            )
            total in 1..45 -> Triple("Limited", "40-card deck: 16–17 lands, 15–17 creatures, a smooth curve and as much removal as you can.", 16..17)
            ramp >= 8 -> Triple("Ramp", "Accelerate mana early and win with expensive threats. Wants 25–27 lands plus 8+ ramp pieces.", 25..27)
            creatures <= 12 && (removal + counters + draw + wipes) >= 14 ->
                Triple("Control", "Answer everything, draw cards, and win late. Wants 25–27 lands, lots of removal/counters, and card draw.", 25..27)
            avgMv <= 2.4 && creatures >= 18 ->
                Triple("Aggro", "Cheap creatures and burn to end the game fast. Wants 20–22 lands, average mana value ≤ 2.3, many 1–2 drops.", 20..22)
            else -> Triple("Midrange", "Efficient threats backed by interaction. Wants 23–25 lands and a curve peaking at 2–3.", 23..25)
        }

        val tips = mutableListOf<Issue>()
        val needs = mutableSetOf<Role>()
        if (total > 0) {
            if (lands < landRange.first) tips += Issue(Severity.WARNING, "Only $lands lands — $archetype decks usually run ${landRange.first}–${landRange.last}.")
            else if (lands > landRange.last) tips += Issue(Severity.WARNING, "$lands lands is a lot for $archetype (typical ${landRange.first}–${landRange.last}); risk of flooding.")
            else tips += Issue(Severity.GOOD, "Land count ($lands) fits a $archetype deck.")

            val scale = if (commander) 100.0 / 60 else total / 60.0
            val removalTarget = if (commander) 8 else (6 * scale).roundToInt()
            if (removal + wipes < removalTarget) {
                tips += Issue(Severity.WARNING, "Only ${removal + wipes} removal spells; aim for $removalTarget+ to handle opposing threats.")
                needs += Role.REMOVAL
            }
            val drawTarget = when { commander -> 10; archetype == "Control" -> 6; else -> 0 }
            if (draw < drawTarget) {
                tips += Issue(Severity.WARNING, "Card draw is light ($draw); $archetype decks want about $drawTarget.")
                needs += Role.DRAW
            }
            if (commander && ramp < 10) {
                tips += Issue(Severity.WARNING, "Ramp: $ramp pieces. Commander decks typically run ~10 (mana rocks, land search).")
                needs += Role.RAMP
            }
            if (commander && wipes < 2) {
                tips += Issue(Severity.INFO, "Consider 2–3 board wipes to recover when opponents get ahead.")
                needs += Role.BOARD_WIPE
            }
            if (archetype == "Aggro" && count { !CardRoles.isLand(it) && (it.cost ?: 0.0) <= 2 } < 14) {
                tips += Issue(Severity.WARNING, "Aggro wants 14+ plays costing 2 or less.")
            }
            if (!commander && avgMv > 3.5) tips += Issue(Severity.WARNING, "Average mana value ${"%.2f".format(avgMv)} is high; trim expensive spells or add ramp.")

            // Colour sources vs. colour demand.
            val colorDemand = nonLand.flatMap { e -> e.card.colorList.map { it to e.quantity } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val sources = main.filter { CardRoles.isLand(it.card) }
                .flatMap { e -> e.card.colorList.ifEmpty { landColors(e.card) }.map { it to e.quantity } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            colorDemand.filter { it.value >= nonLandCount * 0.2 }.forEach { (color, _) ->
                val have = sources[color] ?: 0
                val want = if (commander) 15 else (lands * 0.4).roundToInt().coerceAtLeast(8)
                if (lands > 0 && have < want) tips += Issue(Severity.WARNING, "Only $have ${colorName(color)} sources for a main color; aim for ~$want.")
            }

            if (!commander && lands > 0) {
                val p = probabilityBetween(total, lands, 7, 2, 4)
                tips += Issue(Severity.INFO, "Chance of 2–4 lands in your opening 7: ${(p * 100).roundToInt()}%.")
            }
        }

        val curve = (0..7).map { mv ->
            val n = nonLand.filter { e ->
                val c = (e.card.cost ?: 0.0).toInt()
                if (mv == 7) c >= 7 else c == mv
            }.sumOf { it.quantity }
            Bar(if (mv == 7) "7+" else "$mv", n)
        }
        val gaps = if (archetype == "Aggro") setOf(1, 2).filter { curve[it].count < 6 }.toSet()
        else setOf(2, 3).filter { curve[it].count < 5 }.toSet()

        return DeckAnalysis(
            archetype = archetype,
            archetypeSummary = summary,
            stats = listOf(
                Stat("Cards", "$total"),
                Stat("Lands", "$lands"),
                Stat("Creatures", "$creatures"),
                Stat("Instants & sorceries", "$instantsSorceries"),
                Stat("Avg. mana value", "%.2f".format(avgMv)),
                Stat("Removal", "${removal + wipes}"),
                Stat("Card draw", "$draw"),
                Stat("Ramp", "$ramp"),
            ),
            curve = curve,
            breakdown = typeBreakdown(main) { c ->
                listOf("Land", "Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Battle")
                    .firstOrNull { c.typeLine.contains(it) } ?: "Other"
            },
            colors = colorBars(nonLand),
            tips = tips,
            needs = needs,
            curveGaps = gaps,
        )
    }

    private fun landColors(card: CardEntity): List<String> = buildList {
        val t = card.text + " " + card.name
        if (Regex("""\{W}|Plains""").containsMatchIn(t)) add("W")
        if (Regex("""\{U}|Island""").containsMatchIn(t)) add("U")
        if (Regex("""\{B}|Swamp""").containsMatchIn(t)) add("B")
        if (Regex("""\{R}|Mountain""").containsMatchIn(t)) add("R")
        if (Regex("""\{G}|Forest""").containsMatchIn(t)) add("G")
    }

    private fun colorName(c: String) = when (c) {
        "W" -> "white"; "U" -> "blue"; "B" -> "black"; "R" -> "red"; "G" -> "green"; else -> c
    }

    // ---------------------------------------------------------------- Pokémon

    private fun analyzePokemon(entries: List<DeckEntry>): DeckAnalysis {
        val main = entries.filter { it.zone == DeckZone.MAIN }
        val total = main.sumOf { it.quantity }
        fun count(p: (CardEntity) -> Boolean) = main.filter { p(it.card) }.sumOf { it.quantity }

        val pokemon = count(CardRoles::isPokemon)
        val trainers = count(CardRoles::isTrainer)
        val energy = count(CardRoles::isEnergy)
        val basics = count { CardRoles.isPokemon(it) && CardRoles.hasSubtype(it, "Basic") }
        val stage1 = count { CardRoles.hasSubtype(it, "Stage 1") }
        val stage2 = count { CardRoles.hasSubtype(it, "Stage 2") }
        val ruleBox = count { CardRoles.isPokemon(it) && CardRoles.isRuleBox(it) }
        val supporters = count { CardRoles.hasSubtype(it, "Supporter") }
        val drawSupporters = count { CardRoles.hasSubtype(it, "Supporter") && Role.DRAW in CardRoles.of(it) }
        val search = main.filter { CardRoles.isTrainer(it.card) && Role.SEARCH in CardRoles.of(it.card) }.sumOf { it.quantity }
        val switching = main.filter { Role.SWITCHING in CardRoles.of(it.card) }.sumOf { it.quantity }
        val rareCandy = count { it.name.equals("Rare Candy", true) }

        val (archetype, summary) = when {
            stage2 >= 3 -> "Stage 2 engine" to
                "Builds toward a powerful Stage 2. Typical lines are 4-3-3 or 4-2-3 with 3–4 Rare Candy, plus heavy search and draw."
            ruleBox == 0 && pokemon > 0 -> "Single-prize" to
                "Many non-rule-box attackers that each give up only one Prize. Trade efficiently and make your opponent take 6 KOs."
            ruleBox >= 6 && stage1 + stage2 <= 4 -> "Basic ex/V box" to
                "A toolbox of Basic rule-box attackers. Fast setup; use switching and energy acceleration to pick the right attacker."
            else -> "Evolution midrange" to
                "Basic + Stage 1 attackers backed by consistent draw. Aim for a 4-3 or 3-3 main line with a secondary attacker."
        }

        val tips = mutableListOf<Issue>()
        val needs = mutableSetOf<Role>()
        if (total > 0) {
            if (pokemon !in 10..22) tips += Issue(Severity.WARNING, "$pokemon Pokémon — most competitive decks run about 12–20.")
            if (trainers < 28) tips += Issue(Severity.WARNING, "$trainers Trainers; consistent decks usually have 30–40.") else tips += Issue(Severity.GOOD, "Trainer count ($trainers) looks healthy.")
            val energyRange = if (count { CardRoles.isEnergy(it) && !CardRoles.hasSubtype(it, "Basic") } > 4) 6..12 else 8..14
            if (energy !in energyRange) tips += Issue(Severity.WARNING, "$energy Energy; this kind of deck typically runs ${energyRange.first}–${energyRange.last}.")
            if (basics < 8) tips += Issue(Severity.WARNING, "Only $basics Basic Pokémon — raise to 8+ to reduce mulligans.")
            if (drawSupporters < 8) {
                tips += Issue(Severity.WARNING, "$drawSupporters draw Supporters (of $supporters total); aim for 8–12 so you can play one every turn.")
                needs += Role.DRAW
            }
            if (search < 4) {
                tips += Issue(Severity.WARNING, "$search search cards (e.g. Nest Ball, Ultra Ball); 4–8 makes setup much more consistent.")
                needs += Role.SEARCH
            }
            if (switching < 2) {
                tips += Issue(Severity.INFO, "Add 2–4 switching cards so a stuck Active Pokémon doesn't cost you the game.")
                needs += Role.SWITCHING
            }
            if (stage2 > 0 && rareCandy == 0 && stage1 < stage2) tips += Issue(Severity.WARNING, "Stage 2 cards without Rare Candy need at least as many Stage 1s ($stage1 vs $stage2).")

            // Broken evolution lines.
            val names = main.map { it.card.name }.toSet()
            main.map { it.card }.distinctBy { it.name }.forEach { c ->
                val from = c.evolvesFrom ?: return@forEach
                if (from !in names && !(CardRoles.hasSubtype(c, "Stage 2") && rareCandy > 0)) {
                    tips += Issue(Severity.ERROR, "${c.name} evolves from $from, which isn't in the deck.")
                }
            }

            // Energy types vs. Pokémon types.
            val energyNames = main.filter { CardRoles.isEnergy(it.card) }.joinToString(" ") { it.card.name.lowercase() }
            main.filter { CardRoles.isPokemon(it.card) }.flatMap { it.card.colorList }.toSet()
                .filter { it != "Colorless" && it != "Dragon" }
                .filterNot { energyNames.contains(it.lowercase()) }
                .forEach { type ->
                    tips += Issue(Severity.WARNING, "You have $type Pokémon but no $type Energy.")
                    needs += Role.ENERGY_ACCEL
                }

            val p = 1 - probabilityBetween(total.coerceAtLeast(7), basics, 7, 0, 0)
            tips += Issue(Severity.INFO, "Chance of at least one Basic Pokémon in your opening 7: ${(p * 100).roundToInt()}%.")
        }

        return DeckAnalysis(
            archetype = archetype,
            archetypeSummary = summary,
            stats = listOf(
                Stat("Cards", "$total"),
                Stat("Pokémon", "$pokemon"),
                Stat("Trainers", "$trainers"),
                Stat("Energy", "$energy"),
                Stat("Basic / Stage 1 / Stage 2", "$basics / $stage1 / $stage2"),
                Stat("Rule-box Pokémon", "$ruleBox"),
                Stat("Draw Supporters", "$drawSupporters"),
                Stat("Search cards", "$search"),
            ),
            curve = listOf(
                Bar("Basic", basics), Bar("Stage 1", stage1), Bar("Stage 2", stage2),
                Bar("Supporter", supporters), Bar("Item", count { CardRoles.hasSubtype(it, "Item") }),
                Bar("Tool", count { CardRoles.hasSubtype(it, "Pokémon Tool") }),
                Bar("Stadium", count { CardRoles.hasSubtype(it, "Stadium") }),
            ),
            breakdown = listOf(Bar("Pokémon", pokemon), Bar("Trainer", trainers), Bar("Energy", energy)),
            colors = colorBars(main.filter { CardRoles.isPokemon(it.card) }),
            tips = tips,
            needs = needs,
            curveGaps = emptySet(),
        )
    }

    // ---------------------------------------------------------------- One Piece

    private val typeRef = Regex("""\{([^}]+)}""")

    /** Card types a leader's text refers to, e.g. "{Straw Hat Crew}". */
    fun leaderTypes(leader: CardEntity?): Set<String> =
        leader?.let { l -> typeRef.findAll(l.text).map { it.groupValues[1].trim() }.toSet() + l.subtypeList }.orEmpty()

    private fun analyzeOnePiece(entries: List<DeckEntry>): DeckAnalysis {
        val leader = entries.firstOrNull { it.zone == DeckZone.LEADER }?.card
        val main = entries.filter { it.zone == DeckZone.MAIN }
        val total = main.sumOf { it.quantity }
        fun count(p: (CardEntity) -> Boolean) = main.filter { p(it.card) }.sumOf { it.quantity }
        fun roleCount(r: Role) = main.filter { r in CardRoles.of(it.card) }.sumOf { it.quantity }

        val characters = count { it.typeLine == "CHARACTER" }
        val events = count { it.typeLine == "EVENT" }
        val stages = count { it.typeLine == "STAGE" }
        val counter1k = count { it.counter == 1000 }
        val counter2k = count { it.counter == 2000 }
        val counterEvents = roleCount(Role.COUNTER_EVENT)
        val blockers = roleCount(Role.BLOCKER)
        val rush = roleCount(Role.RUSH)
        val triggers = roleCount(Role.TRIGGER)
        val removal = roleCount(Role.REMOVAL)
        val draw = roleCount(Role.DRAW) + roleCount(Role.SEARCH)
        val donRamp = roleCount(Role.DON_RAMP)
        val avgCost = if (total == 0) 0.0 else main.sumOf { (it.card.cost ?: 0.0) * it.quantity } / total
        val types = leaderTypes(leader)
        val onType = if (types.isEmpty()) 0 else count { c -> c.subtypeList.any { it in types } }

        val (archetype, summary) = when {
            avgCost <= 3.3 && rush >= 4 -> "Rush / Aggro" to
                "Pressure life early with cheap characters and Rush. Spend DON!! on attacks; keep counters for key turns."
            donRamp >= 4 || avgCost >= 4.3 -> "Ramp / Control" to
                "Gain DON!! advantage, survive with blockers and removal, then take over with high-cost bombs."
            else -> "Midrange" to
                "Develop efficient 3–5 cost characters, trade well with removal, and keep a healthy counter suite."
        }

        val tips = mutableListOf<Issue>()
        val needs = mutableSetOf<Role>()
        if (leader == null) tips += Issue(Severity.ERROR, "Pick a Leader — it sets your colors, life and strategy.")
        if (total > 0) {
            if (characters < 30) tips += Issue(Severity.WARNING, "$characters Characters; most decks run 35–42.")
            if (events > 14) tips += Issue(Severity.WARNING, "$events Events is heavy; 6–12 is typical.")
            val counterCards = counter1k + counter2k
            if (counterCards < 15) tips += Issue(Severity.WARNING, "Only $counterCards cards with a counter value. Aim for 15–20 to survive attacks.")
            else tips += Issue(Severity.GOOD, "$counterCards counter cards ($counter2k with 2000) — solid defence.")
            if (counter2k < 6 && archetype != "Rush / Aggro") tips += Issue(Severity.INFO, "More 2000-counter characters make defending much easier.")
            if (blockers < 4 && archetype != "Rush / Aggro") { tips += Issue(Severity.INFO, "Consider 4+ [Blocker] characters."); needs += Role.BLOCKER }
            if (removal < 6) { tips += Issue(Severity.WARNING, "$removal removal effects (K.O./bounce/bottom-deck); 6–10 is typical."); needs += Role.REMOVAL }
            if (draw < 8) { tips += Issue(Severity.INFO, "$draw draw/search effects; card advantage keeps your hand full of counters."); needs += Role.SEARCH }
            if (counterEvents < 2) needs += Role.COUNTER_EVENT
            if (types.isNotEmpty() && characters > 0) {
                val pct = onType * 100 / total
                if (pct < 60) tips += Issue(Severity.WARNING, "Only $pct% of cards share a type with your Leader (${types.joinToString()}); many leader effects need them.")
                else tips += Issue(Severity.GOOD, "$pct% of cards match your Leader's types.")
            }
            val cheap = count { (it.cost ?: 0.0) <= 2 }
            if (cheap < 10) tips += Issue(Severity.WARNING, "$cheap cards cost 0–2; 10+ keeps early turns smooth.")
            val heavy = count { (it.cost ?: 0.0) >= 7 }
            if (heavy > 8) tips += Issue(Severity.WARNING, "$heavy cards cost 7+; 4–8 finishers is usually plenty.")
        }

        val curve = (0..10).map { cost ->
            Bar(if (cost == 10) "10+" else "$cost", main.filter { e ->
                val c = (e.card.cost ?: 0.0).toInt()
                if (cost == 10) c >= 10 else c == cost
            }.sumOf { it.quantity })
        }
        return DeckAnalysis(
            archetype = archetype,
            archetypeSummary = summary,
            stats = listOf(
                Stat("Cards", "$total / 50"),
                Stat("Leader", leader?.name ?: "—"),
                Stat("Characters / Events / Stages", "$characters / $events / $stages"),
                Stat("Avg. cost", "%.2f".format(avgCost)),
                Stat("Counter +1000 / +2000", "$counter1k / $counter2k"),
                Stat("Blockers", "$blockers"),
                Stat("Triggers", "$triggers"),
                Stat("Removal", "$removal"),
            ),
            curve = curve,
            breakdown = listOf(Bar("Character", characters), Bar("Event", events), Bar("Stage", stages)),
            colors = colorBars(main),
            tips = tips,
            needs = needs,
            curveGaps = (1..5).filter { c -> curve[c].count < 4 }.toSet(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun typeBreakdown(entries: List<DeckEntry>, typeOf: (CardEntity) -> String): List<Bar> =
        entries.groupBy { typeOf(it.card) }.map { (t, list) -> Bar(t, list.sumOf { it.quantity }) }.sortedByDescending { it.count }

    private fun colorBars(entries: List<DeckEntry>): List<Bar> =
        entries.flatMap { e -> e.card.colorList.ifEmpty { listOf("Colorless") }.map { it to e.quantity } }
            .groupBy({ it.first }, { it.second })
            .map { Bar(it.key, it.value.sum()) }
            .sortedByDescending { it.count }

    /** Hypergeometric P(lo ≤ X ≤ hi) for drawing [draws] cards from [deck] with [hits] successes. */
    fun probabilityBetween(deck: Int, hits: Int, draws: Int, lo: Int, hi: Int): Double {
        if (deck <= 0 || draws > deck) return 0.0
        val denominator = choose(deck, draws)
        return (lo..hi).sumOf { k -> choose(hits, k) * choose(deck - hits, draws - k) } / denominator
    }

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n || n < 0) return 0.0
        var r = 1.0
        for (i in 1..minOf(k, n - k)) r = r * (n - minOf(k, n - k) + i) / i
        return r
    }
}
