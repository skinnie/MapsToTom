package app.mapstotom.link.live

/** One traffic incident (port of server/traffic.py). Coordinates on the wire are 1e-5 degree. */
class Incident(
    val id: Long,                 // low 24 bits are used by the device
    val lat: Double,
    val lon: Double,
    val category: Int = 3,        // 1..10, the device's icon
    val direction: Boolean = true,
    val speed: Long? = null,      // 1/100 km/h; null = none
    val text: String = "",        // <= 59 characters
    val removed: Boolean = false,
    // set by parseTomTom, used by Portland's selection
    val group: Long = id,
    val tier: Int = 2,
    val delay: Double = 0.0,
    val length10: Long = 0,
) {
    val id24 get() = id and 0xFFFFFF

    /** Hercules incident struct. Text (tag 23) is left out: the device drops incidents that have it. */
    fun toStruct(): HStruct {
        if (removed) return HStruct(10 to id24, 22 to true)
        val items = mutableListOf<Pair<Int, Any?>>(
            10 to id24, 12 to direction,
            13 to ">%dx%d".format(pyRound(lon * SCALE), pyRound(lat * SCALE)),
            14 to category.coerceIn(1, 10).toLong(),
        )
        if (speed != null) items += 16 to speed
        return HStruct(items)
    }

    companion object { const val SCALE = 100000.0 }
}

/** Incidents for an area (x1, y1, x2, y2 in 1e-5 degree, any corner order), or for "everything" when null. */
fun interface IncidentSource {
    fun incidents(bbox: LongArray?): List<Incident>
}

fun trafficPayload(slot: Int, incidents: List<Incident>, counter: Long? = null): HStruct {
    val data = mutableListOf<Pair<Int, Any?>>()
    if (counter != null) data += 1 to counter
    data += 2 to incidents.map { it.toStruct() }
    return HStruct(5 to slot.toLong(), 0x10 to HStruct(data))
}
