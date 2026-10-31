package app.mapstotom.link

import app.mapstotom.core.RealNet
import app.mapstotom.link.live.LiveServer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Glue between the platform (Bluetooth socket, settings, storage) and the portable link code. */
object LinkWiring {
    /** Serve one Bluetooth connection from the TomTom until it hangs up. */
    fun runSession(input: InputStream, output: OutputStream, host: LinkHost) = runModem(input, output, host)

    /** The in-app Live services for 192.168.77.1:8080. [setting] reads app settings; [dir] keeps logins and fuel sets. */
    fun liveService(setting: (String) -> String?, dir: File): LocalService {
        val net = RealNet()
        return LiveServer(net, { setting(CarLinkSettings.TRAFFIC_KEY)?.takeIf { it.isNotBlank() } },
            StoredFuelSets(dir, net, CarLinkState::log), FileLiveStore(dir), CarLinkState::log)
    }
}
