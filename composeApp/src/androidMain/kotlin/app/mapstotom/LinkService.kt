package app.mapstotom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.mapstotom.link.CarLinkState
import app.mapstotom.link.CarLinkState.Phase
import app.mapstotom.link.LinkAddr
import app.mapstotom.link.LinkHost
import app.mapstotom.link.LinkWiring
import app.mapstotom.link.LocalService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * The in-car link: a Bluetooth "Dial-up Networking" server the TomTom dials like a phone modem
 * (go950-navcore-9541/docs/android_bridge.md). One TomTom at a time.
 */
class LinkService : Service() {
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        if (!running) {
            running = true
            CarLinkState.reset()
            worker = Thread(::serveLoop, "car-link").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        CarLinkState.set(Phase.OFF)
        CarLinkState.log("Link stopped")
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked by the activity before starting the service
    private fun serveLoop() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            CarLinkState.log("Bluetooth is off: turn it on and press Start again")
            stopSelf(); return
        }
        val prefs = getSharedPreferences("mapstotom", Context.MODE_PRIVATE)
        val live = runCatching { LinkWiring.liveService({ prefs.getString(it, null) }, File(filesDir, "carlink").apply { mkdirs() }) }
            .onFailure { CarLinkState.log("Live services failed to start: ${it.message}") }.getOrNull()
        val host = AndroidLinkHost(live)
        while (running) {
            try {
                val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("Dial-up Networking", DUN_UUID)
                server = ss
                CarLinkState.set(Phase.WAITING)
                CarLinkState.log("Waiting for the TomTom (phone \"${runCatching { adapter.name }.getOrNull() ?: "?"}\")")
                val sock = ss.accept()
                runCatching { ss.close() }
                CarLinkState.set(Phase.MODEM)
                CarLinkState.log("TomTom connected: ${runCatching { sock.remoteDevice.name ?: sock.remoteDevice.address }.getOrNull()}")
                try {
                    sock.use { LinkWiring.runSession(it.inputStream, it.outputStream, host) }
                } catch (e: Exception) {
                    CarLinkState.log("Session ended: ${e.message ?: e.javaClass.simpleName}")
                }
                CarLinkState.log("TomTom hung up")
            } catch (e: Exception) {
                if (!running) break
                CarLinkState.log("Bluetooth: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(2000)
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car link", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Car link on")
            .setContentText("The TomTom can use this phone for Live services")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)
    }

    /** Platform side of the PPP link: the in-app services for 192.168.77.1:8080, sockets and DNS for the rest. */
    private class AndroidLinkHost(private val live: LocalService?) : LinkHost {
        override fun local(port: Int): LocalService? {
            if (port != LinkAddr.LIVE_PORT || live == null) return null
            return LocalService { input, output ->
                CarLinkState.set(Phase.ONLINE)
                CarLinkState.countRequest()
                live.serve(input, output)
            }
        }

        override fun connect(ip: ByteArray, port: Int): Socket? = runCatching {
            Socket().apply { connect(InetSocketAddress(InetAddress.getByAddress(ip), port), 10_000) }
        }.onFailure { log("connect ${InetAddress.getByAddress(ip).hostAddress}:$port failed: ${it.message}") }.getOrNull()

        override fun resolve(name: String): List<ByteArray> =
            runCatching { InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().map { it.address } }.getOrDefault(emptyList())

        override fun log(line: String) = CarLinkState.log(line)
    }

    companion object {
        /** Bluetooth Dial-up Networking service class. */
        val DUN_UUID: UUID = UUID.fromString("00001103-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "carlink"
        private const val NOTE_ID = 7
        private const val ACTION_STOP = "app.mapstotom.STOP_LINK"

        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LinkService::class.java)) }
    }
}
