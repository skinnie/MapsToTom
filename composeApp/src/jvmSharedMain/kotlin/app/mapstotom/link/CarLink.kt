package app.mapstotom.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Starts and stops the in-car link (Android: a foreground service with a Bluetooth DUN server). */
interface CarLinkControl {
    fun start()
    fun stop()
    /** Ask Android to make the phone visible to Bluetooth searches for a while (for pairing). */
    fun makeVisible()
}

/** A file going to the TomTom through the car link. */
data class CarTransfer(val path: String, val sent: Int, val total: Int, val startedAt: Long) {
    val what get() = when {
        path.startsWith("ephem/") -> "QuickGPSFix"
        path.endsWith(".itn", ignoreCase = true) -> "route"
        else -> "POIs"
    }
    val fraction get() = if (total == 0) 0f else sent.toFloat() / total
    val finished get() = sent >= total

    /** Seconds left at the speed so far, or null until there's enough to measure. */
    fun secondsLeft(now: Long): Long? {
        val spent = now - startedAt
        if (sent < 2048 || spent < 2000 || finished) return null
        return (spent * (total - sent) / sent + 999) / 1000
    }

    fun text(now: Long): String {
        if (finished) return "Saving $what on the TomTom…"
        val left = secondsLeft(now)?.let { s -> if (s < 60) "under a minute left" else "about ${(s + 30) / 60} min left" }
        return listOfNotNull("Sending $what", "${sent / 1024} of ${(total + 1023) / 1024} KB", left).joinToString("  ·  ")
    }
}

/** What the helper on the TomTom's card says about the device each time it asks for files. */
data class CarDeviceReport(val model: String?, val gpsType: Int?, val gpsVersion: String?) {
    companion object {
        private val SAFE = Regex("[A-Za-z0-9._() -]{1,40}")
        fun fromQuery(query: String): CarDeviceReport? {
            val q = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "").replace('+', ' ').trim() }
            val type = q["gpstype"]?.toIntOrNull() ?: return null
            return CarDeviceReport(q["model"]?.takeIf(SAFE::matches), type, q["gps"]?.takeIf(SAFE::matches))
        }
    }
}

/** Shared between the link service and the screen (same process). */
object CarLinkState {
    enum class Phase(val label: String) { OFF("Off"), WAITING("Waiting for the TomTom"), MODEM("TomTom connected (modem)"), ONLINE("TomTom online") }

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase = _phase.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val _requests = MutableStateFlow(0)
    /** Requests answered by the in-app Live services since the link started. */
    val requests = _requests.asStateFlow()

    private val _device = MutableStateFlow<CarDeviceReport?>(null)
    /** The TomTom connected now, as its helper reported it. */
    val device = _device.asStateFlow()
    fun report(d: CarDeviceReport?) { if (d != null) _device.value = d }

    private val _transfer = MutableStateFlow<CarTransfer?>(null)
    /** The file being sent now. */
    val transfer = _transfer.asStateFlow()
    private val _queued = MutableStateFlow(0)
    /** Files waiting to go to the TomTom, including the one being sent. */
    val queued = _queued.asStateFlow()
    fun setQueued(n: Int) { _queued.value = n }
    fun transferStart(path: String, total: Int) { _transfer.value = CarTransfer(path, 0, total, System.currentTimeMillis()) }
    fun transferSent(sent: Int) { _transfer.value = _transfer.value?.copy(sent = sent) }
    fun transferEnd() { _transfer.value = null }

    fun set(p: Phase) { _phase.value = p; if (p == Phase.OFF || p == Phase.WAITING) { _device.value = null; _transfer.value = null } }
    fun countRequest() { _requests.value += 1 }
    fun reset() { _requests.value = 0 }

    fun log(line: String) {
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _log.value = (_log.value + "$stamp $line").takeLast(200)
    }
}

/** Settings keys used by the link. */
object CarLinkSettings {
    /** The user's own TomTom Traffic API key (developer.tomtom.com, free tier); traffic is off without it. */
    const val TRAFFIC_KEY = "carLink.tomtomKey"
}
