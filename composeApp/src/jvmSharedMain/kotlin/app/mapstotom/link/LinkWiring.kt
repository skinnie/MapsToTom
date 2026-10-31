package app.mapstotom.link

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Glue between the platform (Bluetooth socket, settings, storage) and the portable link code.
 * TODO(merge): replace the bodies with the AtModem/PppLink and LiveServer entry points.
 */
object LinkWiring {
    /** Serve one Bluetooth connection from the TomTom until it hangs up. */
    fun runSession(input: InputStream, output: OutputStream, host: LinkHost) {
        error("link not built yet")
    }

    /** The in-app Live services for 192.168.77.1:8080. [setting] reads app settings; [dir] keeps login state. */
    fun liveService(setting: (String) -> String?, dir: File): LocalService? = null
}
