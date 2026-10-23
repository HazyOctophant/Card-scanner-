package com.hazyoctophant.cardvault.data.remote

import com.hazyoctophant.cardvault.data.mtgdb.MtgDbCard
import com.hazyoctophant.cardvault.model.Finish
import com.hazyoctophant.cardvault.model.PriceSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScryfallParseTest {
    // Trimmed-down objects in the shape of Scryfall's bulk "oracle-cards" file.
    private val bulk = """
        [
          {"object":"card","id":"e3285e6b-3e79-4d7c-bf96-d920f973b122","name":"Lightning Bolt","layout":"normal",
           "games":["paper","mtgo"],"mana_cost":"{R}","cmc":1.0,"type_line":"Instant",
           "oracle_text":"Lightning Bolt deals 3 damage to any target.","colors":["R"],"color_identity":["R"],
           "legalities":{"standard":"not_legal","modern":"legal","legacy":"legal","commander":"legal"},
           "set":"m25","set_name":"Masters 25","collector_number":"141","rarity":"uncommon","released_at":"2018-03-16",
           "edhrec_rank":3,"image_uris":{"normal":"https://img/n.jpg","large":"https://img/l.jpg"},
           "prices":{"usd":"1.50","usd_foil":"4.00","usd_etched":null,"eur":"1.20","eur_foil":null,"tix":"0.02"},
           "purchase_uris":{"tcgplayer":"https://tcg/bolt"}},
          {"object":"card","id":"t1","name":"Goblin Token","layout":"token","games":["paper"],"type_line":"Token Creature — Goblin",
           "color_identity":["R"],"set":"tm25","set_name":"Tokens","collector_number":"1","rarity":"common","prices":{}},
          {"object":"card","id":"f1","name":"Delver of Secrets // Insectile Aberration","layout":"transform","games":["paper"],
           "cmc":1.0,"type_line":"Creature — Human Wizard // Creature — Human Insect","color_identity":["U"],
           "card_faces":[
             {"name":"Delver of Secrets","mana_cost":"{U}","type_line":"Creature — Human Wizard","oracle_text":"At the beginning of your upkeep, look at the top card of your library.","power":"1","toughness":"1","image_uris":{"normal":"https://img/d.jpg"}},
             {"name":"Insectile Aberration","mana_cost":"","type_line":"Creature — Human Insect","oracle_text":"Flying","power":"3","toughness":"2"}
           ],
           "legalities":{"modern":"legal"},"set":"isd","set_name":"Innistrad","collector_number":"51","rarity":"common",
           "prices":{"usd":"0.25"}}
        ]
    """.trimIndent()

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun streamsAndParsesBulkCards() {
        val http = Http()
        val scryfall = ScryfallCatalog(http)
        val parsed = http.json.decodeToSequence(bulk.byteInputStream(), JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .mapNotNull { it.obj() }
            .filter { it.str("layout") != "token" }
            .map { o -> MtgDbCard.from(scryfall.parseCard(o), o.str("mana_cost") ?: "", o.num("edhrec_rank")?.toInt(), 1L) }
            .toList()

        assertEquals(2, parsed.size)
        val bolt = parsed[0]
        assertEquals("mtg:e3285e6b-3e79-4d7c-bf96-d920f973b122", bolt.id)
        assertEquals("M25", bolt.setCode)
        assertEquals("R", bolt.colors)
        assertEquals(1.0, bolt.cost!!, 0.0)
        assertEquals(1.5, bolt.usd!!, 1e-9)
        assertEquals(4.0, bolt.usdFoil!!, 1e-9)
        assertEquals(1.2, bolt.eur!!, 1e-9)
        assertEquals(3, bolt.edhrecRank)
        assertTrue(bolt.legalities.split(',').containsAll(listOf("modern", "legacy", "commander")))
        assertTrue("standard" !in bolt.legalities.split(','))

        val delver = parsed[1]
        assertEquals("https://img/d.jpg", delver.imageUrl)
        assertEquals("1/1", delver.power)
        assertTrue(delver.text.contains("Insectile Aberration: Flying"))

        // Round trip back to a catalog card keeps the prices per source and finish.
        val prices = bolt.toCatalogCard().prices
        assertEquals(1.5, prices.single { it.source == PriceSource.TCGPLAYER && it.finish == Finish.NORMAL }.market!!, 1e-9)
        assertEquals(1.2, prices.single { it.source == PriceSource.CARDMARKET && it.finish == Finish.NORMAL }.market!!, 1e-9)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun readsBothArrayAndOneCardPerLineBulkFormats() {
        val http = Http()
        val array = """[{"id":"a","name":"A"},{"id":"b","name":"B"}]"""
        val lines = "{\"id\":\"a\",\"name\":\"A\"}\n{\"id\":\"b\",\"name\":\"B\"}\n"
        for (input in listOf(array, lines)) {
            val ids = http.json.decodeToSequence(input.byteInputStream(), JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)
                .mapNotNull { it.obj()?.str("id") }.toList()
            assertEquals(listOf("a", "b"), ids)
        }
    }
}
