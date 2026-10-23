package com.hazyoctophant.cardvault.strategy

import com.hazyoctophant.cardvault.data.db.CardEntity
import com.hazyoctophant.cardvault.model.Game

/** Functional roles a card can play, recognised from its rules text. */
enum class Role(val label: String) {
    REMOVAL("Removal"),
    BOARD_WIPE("Board wipe"),
    DRAW("Card draw"),
    RAMP("Ramp"),
    COUNTERSPELL("Counterspell"),
    SEARCH("Search / tutor"),
    BLOCKER("Blocker"),
    RUSH("Rush"),
    TRIGGER("Trigger"),
    COUNTER_EVENT("Counter event"),
    DON_RAMP("DON!! acceleration"),
    SWITCHING("Switching"),
    ENERGY_ACCEL("Energy acceleration"),
    HEALING("Healing"),
}

object CardRoles {
    private val mtg = mapOf(
        Role.REMOVAL to Regex(
            """destroy target|exile target (?:creature|artifact|enchantment|planeswalker|nonland|permanent)|deals? (?:\d+|x) damage to (?:any target|target creature|target planeswalker)|target creature gets -\d+/-\d+|fights? (?:target|up to one)|return target (?:creature|nonland permanent) to its owner's hand""",
            RegexOption.IGNORE_CASE,
        ),
        Role.BOARD_WIPE to Regex("""destroy all|exile all (?:creatures|nonland)|damage to each creature|all creatures get -""", RegexOption.IGNORE_CASE),
        Role.DRAW to Regex("""draws? (?:a|two|three|four|x|that many) cards?|investigate|look at the top \w+ cards .* put .* into your hand""", RegexOption.IGNORE_CASE),
        Role.RAMP to Regex("""add \{|add one mana|add (?:two|three) mana|search your library for (?:a|up to two|two) basic land|put (?:a|up to one) land card .* onto the battlefield|treasure token""", RegexOption.IGNORE_CASE),
        Role.COUNTERSPELL to Regex("""counter target (?:\w+ )?spell""", RegexOption.IGNORE_CASE),
        Role.SEARCH to Regex("""search your library for a (?:card|creature|instant|sorcery|artifact|enchantment)""", RegexOption.IGNORE_CASE),
    )

    private val pokemon = mapOf(
        Role.DRAW to Regex("""draw (?:a|\d+|that many|cards until)""", RegexOption.IGNORE_CASE),
        Role.SEARCH to Regex("""search your deck for""", RegexOption.IGNORE_CASE),
        Role.SWITCHING to Regex("""switch (?:your active|in 1 of your opponent's benched|1 of your opponent's benched)""", RegexOption.IGNORE_CASE),
        Role.ENERGY_ACCEL to Regex("""attach (?:up to )?(?:a|\d+) .*energy (?:card )?from your (?:deck|discard pile|hand) to""", RegexOption.IGNORE_CASE),
        Role.HEALING to Regex("""heal \d+ damage""", RegexOption.IGNORE_CASE),
        Role.REMOVAL to Regex("""knocked out|put \d+ damage counters on (?:1 of )?your opponent's""", RegexOption.IGNORE_CASE),
    )

    private val onePiece = mapOf(
        Role.REMOVAL to Regex("""k\.o\. (?:up to )?\d+ of your opponent's|return (?:up to )?\d+ of your opponent's characters? .*to the owner's hand|place (?:up to )?\d+ of your opponent's characters? at the bottom|give (?:up to )?\d+ of your opponent's .* -\d+ power""", RegexOption.IGNORE_CASE),
        Role.DRAW to Regex("""draw \d+ cards?""", RegexOption.IGNORE_CASE),
        Role.SEARCH to Regex("""look at (?:the top )?\d+ cards? (?:from|of) (?:the top of )?your deck""", RegexOption.IGNORE_CASE),
        Role.BLOCKER to Regex("""\[blocker]""", RegexOption.IGNORE_CASE),
        Role.RUSH to Regex("""\[rush]""", RegexOption.IGNORE_CASE),
        Role.TRIGGER to Regex("""\[trigger]""", RegexOption.IGNORE_CASE),
        Role.COUNTER_EVENT to Regex("""\[counter]""", RegexOption.IGNORE_CASE),
        Role.DON_RAMP to Regex("""add (?:up to )?\d+ don!! cards? from your don!! deck""", RegexOption.IGNORE_CASE),
    )

    fun of(card: CardEntity): Set<Role> {
        val table = when (card.game) {
            Game.MTG -> mtg
            Game.POKEMON -> pokemon
            Game.ONE_PIECE -> onePiece
        }
        val roles = table.filter { (_, regex) -> regex.containsMatchIn(card.text) }.keys.toMutableSet()
        if (card.game == Game.MTG && card.typeLine.contains("Land")) roles.remove(Role.RAMP)
        if (card.game == Game.ONE_PIECE && !card.typeLine.equals("EVENT", true)) roles.remove(Role.COUNTER_EVENT)
        return roles
    }

    // ---- Simple type predicates ----
    fun isLand(c: CardEntity) = c.game == Game.MTG && c.typeLine.contains("Land")
    fun isCreature(c: CardEntity) = c.game == Game.MTG && c.typeLine.contains("Creature")
    fun isPokemon(c: CardEntity) = c.game == Game.POKEMON && c.typeLine.startsWith("Pok")
    fun isTrainer(c: CardEntity) = c.game == Game.POKEMON && c.typeLine == "Trainer"
    fun isEnergy(c: CardEntity) = c.game == Game.POKEMON && c.typeLine == "Energy"
    fun hasSubtype(c: CardEntity, s: String) = c.subtypeList.any { it.equals(s, true) }
    fun isRuleBox(c: CardEntity) = c.subtypeList.any { it in setOf("ex", "EX", "V", "VMAX", "VSTAR", "GX", "V-UNION", "Radiant") }
}
