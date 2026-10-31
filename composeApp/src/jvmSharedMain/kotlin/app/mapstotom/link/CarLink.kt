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

    fun set(p: Phase) { _phase.value = p }
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
    /** Region code for traffic, like PT. */
    const val REGION = "carLink.region"
}
