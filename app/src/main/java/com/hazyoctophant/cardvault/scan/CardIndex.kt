package com.hazyoctophant.cardvault.scan

import com.hazyoctophant.cardvault.model.Game
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** One known card in a visual index. [id] matches the app's card ids ("mtg:…", "pkm:…", "op:…"). */
data class IndexEntry(
    val id: String,
    val name: String,
    val setCode: String,
    val number: String,
    val signature: VisualSignature,
)

data class IndexHit(val entry: IndexEntry, val game: Game, val similarity: Float)

/**
 * Visual signatures for every card image of one game, kept as flat arrays so a photo can be compared
 * against tens of thousands of cards in a fraction of a second.
 *
 * File format (gzip applied outside): "CVIX", version, game ordinal, count, then per card:
 * id, name, set code, number (UTF), art, full, color bytes, art hash.
 */
class CardIndex private constructor(
    val game: Game,
    val version: String,
    private val ids: Array<String>,
    private val names: Array<String>,
    private val sets: Array<String>,
    private val numbers: Array<String>,
    private val arts: ByteArray,
    private val fulls: ByteArray,
    private val colors: ByteArray,
    private val hashes: LongArray,
) {
    val size: Int get() = ids.size

    fun entry(i: Int) = IndexEntry(
        ids[i], names[i], sets[i], numbers[i],
        VisualSignature(
            arts.copyOfRange(i * VisualSignature.ART_SIZE, (i + 1) * VisualSignature.ART_SIZE),
            fulls.copyOfRange(i * VisualSignature.FULL_SIZE, (i + 1) * VisualSignature.FULL_SIZE),
            colors.copyOfRange(i * VisualSignature.COLOR_SIZE, (i + 1) * VisualSignature.COLOR_SIZE),
            hashes[i],
        ),
    )

    /**
     * The [limit] most similar cards. A cheap first pass (artwork + whole-card correlation, best crop only)
     * keeps a shortlist, which is then scored with every crop and all signature parts.
     */
    fun search(photo: PhotoSignature, limit: Int = 30, shortlist: Int = 400): List<IndexHit> {
        val base = photo.variants.first()
        val quick = FloatArray(size)
        for (i in 0 until size) {
            quick[i] = 0.6f * VisualSignature.ncc(base.art, arts, 0, i * VisualSignature.ART_SIZE, VisualSignature.ART_SIZE) +
                0.4f * VisualSignature.ncc(base.full, fulls, 0, i * VisualSignature.FULL_SIZE, VisualSignature.FULL_SIZE)
        }
        val candidates = (0 until size).sortedByDescending { quick[it] }.take(shortlist)
        return candidates.map { i ->
            val e = entry(i)
            IndexHit(e, game, photo.similarity(e.signature))
        }.sortedByDescending { it.similarity }.take(limit)
    }

    /** Cards whose printed number matches (for combining with what OCR read). */
    fun withNumber(predicate: (IndexEntry) -> Boolean): List<IndexEntry> =
        (0 until size).asSequence().map { entry(it) }.filter(predicate).toList()

    companion object {
        private const val MAGIC = 0x43564958 // "CVIX"
        const val FORMAT_VERSION = 1

        fun write(out: OutputStream, game: Game, version: String, entries: List<IndexEntry>) {
            val d = DataOutputStream(out.buffered())
            d.writeInt(MAGIC)
            d.writeInt(FORMAT_VERSION)
            d.writeInt(game.ordinal)
            d.writeUTF(version)
            d.writeInt(entries.size)
            for (e in entries) {
                d.writeUTF(e.id); d.writeUTF(e.name); d.writeUTF(e.setCode); d.writeUTF(e.number)
                d.write(e.signature.art); d.write(e.signature.full); d.write(e.signature.color)
                d.writeLong(e.signature.hash)
            }
            d.flush()
        }

        fun read(input: InputStream): CardIndex {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "Not a card index file" }
            require(d.readInt() == FORMAT_VERSION) { "Unsupported card index version" }
            val game = Game.entries[d.readInt()]
            val version = d.readUTF()
            val n = d.readInt()
            val ids = Array(n) { "" }; val names = Array(n) { "" }; val sets = Array(n) { "" }; val numbers = Array(n) { "" }
            val arts = ByteArray(n * VisualSignature.ART_SIZE)
            val fulls = ByteArray(n * VisualSignature.FULL_SIZE)
            val colors = ByteArray(n * VisualSignature.COLOR_SIZE)
            val hashes = LongArray(n)
            for (i in 0 until n) {
                ids[i] = d.readUTF(); names[i] = d.readUTF(); sets[i] = d.readUTF(); numbers[i] = d.readUTF()
                d.readFully(arts, i * VisualSignature.ART_SIZE, VisualSignature.ART_SIZE)
                d.readFully(fulls, i * VisualSignature.FULL_SIZE, VisualSignature.FULL_SIZE)
                d.readFully(colors, i * VisualSignature.COLOR_SIZE, VisualSignature.COLOR_SIZE)
                hashes[i] = d.readLong()
            }
            return CardIndex(game, version, ids, names, sets, numbers, arts, fulls, colors, hashes)
        }
    }
}
