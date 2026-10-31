package app.mapstotom.link

import app.mapstotom.core.FuelSource
import app.mapstotom.core.FuelStation
import app.mapstotom.core.FuelType
import app.mapstotom.core.Net
import app.mapstotom.core.fetchFuel
import app.mapstotom.link.live.FuelPriceSet
import app.mapstotom.link.live.FuelPriceSets
import app.mapstotom.link.live.LiveStore
import java.io.File
import java.time.Instant
import java.util.Base64

/** Last Hercules login per TomTom, one file each, so an app restart can still decrypt its calls. */
class FileLiveStore(private val dir: File) : LiveStore {
    private fun safe(serial: String) = serial.filter { it.isLetterOrDigit() }.take(40).ifEmpty { "unknown" }

    override fun lastLogins(): Map<String, ByteArray> =
        dir.listFiles { f -> f.name.startsWith("login-") }.orEmpty().associate { f ->
            val lines = f.readText().lines()
            lines[0] to Base64.getDecoder().decode(lines.getOrElse(1) { "" })
        }

    override fun saveLogin(serial: String, body: ByteArray) {
        dir.mkdirs()
        File(dir, "login-${safe(serial)}.txt").writeText(serial + "\n" + Base64.getEncoder().encodeToString(body))
    }
}

/**
 * Fuel sets installed by cable, so the phone can send today's prices for the same stations in the same order.
 * File `fuelset-<id>.txt`: line 1 = set id, line 2 = source countries (ISO3, comma separated), then one station id per line.
 */
class StoredFuelSets(private val dir: File, private val net: Net, private val log: (String) -> Unit = {}) : FuelPriceSets {
    private val cache = HashMap<String, Pair<Instant, Map<String, FuelStation>>>()

    override fun find(setId: String): FuelPriceSet? {
        val f = File(dir, "fuelset-${setId.filter(Char::isDigit)}.txt").takeIf { it.isFile } ?: return null
        val lines = f.readLines()
        val id = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val sources = lines.getOrNull(1).orEmpty().split(',').mapNotNull { FuelSource.of(it.trim()) }
        val order = lines.drop(2).filter { it.isNotBlank() }
        val prices = object : AbstractMap<Int, List<Int>>() {
            override val entries get() = emptySet<Map.Entry<Int, List<Int>>>()
            override fun get(key: Int): List<Int>? {
                val type = FuelType.of(key) ?: return null
                val byId = HashMap<String, FuelStation>()
                for (s in sources) byId += stations(s, type)
                return order.map { byId[it]?.prices?.get(key) ?: 0 }.takeIf { p -> p.any { it > 0 } }
            }
        }
        return FuelPriceSet(id, f.name, prices)
    }

    /** Today's stations of one source, kept for an hour (Portugal is per fuel type and slow). */
    @Synchronized private fun stations(s: FuelSource, type: FuelType): Map<String, FuelStation> {
        val key = s.iso3 + if (s.byType) "/${type.code}" else ""
        cache[key]?.takeIf { it.first.isAfter(Instant.now().minusSeconds(3600)) }?.let { return it.second }
        log("fuel: downloading ${s.country}")
        val list = runCatching { fetchFuel(s, net, type) }.onFailure { log("fuel: ${s.country} failed: ${it.message}") }.getOrElse { return emptyMap() }
        return list.associateBy { it.id }.also { cache[key] = Instant.now() to it }
    }

    companion object {
        /** Called after a cable install ([ordered] = the stations in the order written to the .ov2). */
        fun record(dir: File, setId: Int, sources: List<FuelSource>, ordered: List<FuelStation>) {
            dir.mkdirs()
            File(dir, "fuelset-$setId.txt").writeText(
                "$setId\n" + sources.joinToString(",") { it.iso3 } + "\n" + ordered.joinToString("\n") { it.id } + "\n")
        }
    }
}
