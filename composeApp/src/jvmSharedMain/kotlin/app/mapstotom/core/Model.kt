package app.mapstotom.core

/** A point to send to the TomTom. */
data class Point(
    var name: String,
    var lat: Double,
    var lon: Double,
    var kind: Kind = Kind.STOP,
) {
    /** STOP: a destination. VIA: a Google drag point. SHAPE: added along the road. */
    enum class Kind { STOP, VIA, SHAPE }

    val hasCoords get() = !lat.isNaN() && !lon.isNaN()
    fun label() = "%.6f, %.6f".format(java.util.Locale.ROOT, lat, lon)
}

data class ParseResult(
    val points: MutableList<Point> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
)

internal fun pretty(lat: Double, lon: Double) = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)
