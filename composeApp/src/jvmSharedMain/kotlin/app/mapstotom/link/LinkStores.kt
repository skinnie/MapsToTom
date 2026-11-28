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
 * File `fuelprices-<id>-<type>.txt`: line 1 = when (epoch seconds), then one price per station (0 = none), so a TomTom
 * gets an answer at once: downloading a big country on a tablet takes minutes and the TomTom gives up long before.
 * Prices older than [maxAgeS] are refreshed in the background; the next request gets the new ones.
 */
class StoredFuelSets(
    private val dir: File,
    private val net: Net,
    private val log: (String) -> Unit = {},
    private val maxAgeS: Long = 3600,
    private val waitS: Long = 90,
    private val background: java.util.concurrent.Executor = defaultBackground,
) : FuelPriceSets {
    private val cache = HashMap<String, Pair<Instant, Map<String, FuelStation>>>()
    private val refreshing = HashMap<String, java.util.concurrent.CompletableFuture<List<Int>?>>()

    private class SetFile(val id: Long, val sources: List<FuelSource>, val order: List<String>)

    private fun read(setId: String): SetFile? {
        val f = File(dir, "fuelset-${setId.filter(Char::isDigit)}.txt").takeIf { it.isFile } ?: return null
        val lines = f.readLines()
        val id = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val sources = lines.getOrNull(1).orEmpty().split(',').mapNotNull { FuelSource.of(it.trim()) }
        return SetFile(id, sources, lines.drop(2).filter { it.isNotBlank() })
    }

    override fun find(setId: String): FuelPriceSet? {
        val set = read(setId) ?: return null
        val prices = object : AbstractMap<Int, List<Int>>() {
            override val entries get() = emptySet<Map.Entry<Int, List<Int>>>()
            override fun get(key: Int): List<Int>? {
                val type = FuelType.of(key) ?: return null
                val saved = loadPrices(set, type)
                if (saved != null) {
                    if (saved.first.isBefore(Instant.now().minusSeconds(maxAgeS))) refreshLater(set, type)
                    return saved.second.takeIf { p -> p.any { it > 0 } }
                }
                // nothing saved yet: wait a while, the download goes on and is saved for the next request
                val p = runCatching { refreshLater(set, type).get(waitS, java.util.concurrent.TimeUnit.SECONDS) }
                    .onFailure { log("fuel: ${type.label} still downloading, the TomTom can ask again in a few minutes") }.getOrNull()
                return p?.takeIf { q -> q.any { it > 0 } }
            }
        }
        return FuelPriceSet(set.id, "fuelset-${set.id}.txt", prices)
    }

    /** Refresh every saved price list that is old (call when the car link starts). */
    fun warm() {
        for (f in dir.listFiles { f -> f.name.startsWith("fuelset-") }.orEmpty()) {
            val set = read(f.name.removePrefix("fuelset-").removeSuffix(".txt")) ?: continue
            for (type in FuelType.entries) {
                val saved = loadPrices(set, type) ?: continue
                if (saved.first.isBefore(Instant.now().minusSeconds(maxAgeS))) refreshLater(set, type)
            }
        }
    }

    private fun pricesFile(set: SetFile, type: FuelType) = File(dir, "fuelprices-${set.id}-${type.code}.txt")

    private fun loadPrices(set: SetFile, type: FuelType): Pair<Instant, List<Int>>? {
        val lines = runCatching { pricesFile(set, type).readLines() }.getOrNull() ?: return null
        val time = lines.firstOrNull()?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val p = lines.drop(1).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        return if (p.size == set.order.size) time to p else null
    }

    private fun refreshLater(set: SetFile, type: FuelType): java.util.concurrent.CompletableFuture<List<Int>?> {
        val key = "${set.id}-${type.code}"
        synchronized(refreshing) {
            refreshing[key]?.let { return it }
            val f = java.util.concurrent.CompletableFuture<List<Int>?>()
            refreshing[key] = f
            background.execute {
                val r = runCatching { refresh(set, type) }.getOrNull()
                synchronized(refreshing) { refreshing.remove(key) }
                f.complete(r)
            }
            return f
        }
    }

    /** Download, merge with the saved list (a country that failed keeps its last prices) and save. */
    private fun refresh(set: SetFile, type: FuelType): List<Int>? {
        val byId = HashMap<String, FuelStation>()
        var failed = false
        for (s in set.sources) stations(s, type)?.let { byId += it } ?: run { failed = true }
        val old = loadPrices(set, type)?.second
        val p = set.order.mapIndexed { i, id -> byId[id]?.prices?.get(type.code)?.takeIf { it > 0 } ?: old?.getOrNull(i) ?: 0 }
        if (byId.isEmpty() && old == null) return null
        if (byId.isNotEmpty()) save(dir, set.id, type, p, if (failed && old != null) loadPrices(set, type)!!.first else Instant.now())
        log("fuel: ${type.label} prices ready (${p.count { it > 0 }} stations)")
        return p
    }

    /** Today's stations of one source, kept for an hour (Portugal is per fuel type and slow); null = download failed. */
    @Synchronized private fun stations(s: FuelSource, type: FuelType): Map<String, FuelStation>? {
        val key = s.iso3 + if (s.byType) "/${type.code}" else ""
        cache[key]?.takeIf { it.first.isAfter(Instant.now().minusSeconds(3600)) }?.let { return it.second }
        log("fuel: downloading ${s.country}")
        val list = runCatching { fetchFuel(s, net, type) }.onFailure { log("fuel: ${s.country} failed: ${it.message}") }.getOrElse { return null }
        return list.associateBy { it.id }.also { cache[key] = Instant.now() to it }
    }

    companion object {
        private val defaultBackground = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "fuel-refresh").apply { isDaemon = true }
        }

        private fun save(dir: File, setId: Long, type: FuelType, prices: List<Int>, time: Instant) {
            val f = File(dir, "fuelprices-$setId-${type.code}.txt")
            val tmp = File(dir, f.name + ".new")
            tmp.writeText("${time.epochSecond}\n" + prices.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }

        /**
         * Called after a cable install ([ordered] = the stations in the order written to the .ov2). Also saves the
         * installed prices of every fuel type, so the first request from the TomTom is answered at once.
         */
        fun record(dir: File, setId: Int, sources: List<FuelSource>, ordered: List<FuelStation>, time: Instant = Instant.now()) {
            dir.mkdirs()
            File(dir, "fuelset-$setId.txt").writeText(
                "$setId\n" + sources.joinToString(",") { it.iso3 } + "\n" + ordered.joinToString("\n") { it.id } + "\n")
            dir.listFiles { f -> f.name.startsWith("fuelprices-$setId-") }.orEmpty().forEach { it.delete() }
            for (type in FuelType.entries) {
                val p = ordered.map { it.prices[type.code] ?: 0 }
                if (p.any { it > 0 }) save(dir, setId.toLong(), type, p, time)
            }
        }
    }
}
