package app.mapstotom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    /** Channel of the open listener, and the one to move to when a TomTom with another remembered channel shows up. */
    @Volatile private var listeningOn: Int? = null
    @Volatile private var moveTo: Int? = null

    /**
     * Each TomTom redials the channel it found when it paired, so with two TomToms the listener must follow whichever one
     * is around: when a paired TomTom connects (it does that before it dials), listen on its channel.
     */
    private val aclReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            if (!isTomTom(device) || CarLinkState.phase.value != Phase.WAITING) return
            val want = getSharedPreferences("mapstotom", Context.MODE_PRIVATE).getInt(channelKey(device.address), -1)
            if (want <= 0 || want == listeningOn) return
            CarLinkState.log("${runCatching { device.name }.getOrNull() ?: "A TomTom"} is here: listening on its channel $want")
            moveTo = want
            runCatching { server?.close() }   // accept() fails and the loop reopens on [moveTo]
        }
    }

    /** The TomTom this service is pairing with. The PIN is only ever given to it. */
    @Volatile private var pairingWith: String? = null

    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceIn(intent) ?: return
            // Ours: the pairing this service started. A TomTom that starts pairing itself (the GO 950 does, and retries
            // every few seconds) gets the same fixed PIN, otherwise Android opens a PIN box for every retry.
            if (device.address != pairingWith) {
                if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST || !isTomTom(device)) return
                pairingWith = device.address
                CarLinkState.log("The TomTom asked to pair")
            }
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST ->
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1) == BluetoothDevice.PAIRING_VARIANT_PIN) {
                        device.setPin(TOMTOM_PIN.toByteArray())
                        if (isOrderedBroadcast) abortBroadcast()
                    }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> { pairingWith = null; CarLinkState.log("Paired with the TomTom") }
                    BluetoothDevice.BOND_NONE -> { pairingWith = null; CarLinkState.log("The TomTom refused pairing") }
                }
            }
        }

        private fun deviceIn(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        if (!running) {
            running = true
            CarLinkState.reset()
            val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 // before the system's PIN box
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pairingReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(pairingReceiver, filter)
            val acl = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(aclReceiver, acl, Context.RECEIVER_EXPORTED)
            else registerReceiver(aclReceiver, acl)
            worker = Thread(::serveLoop, "car-link").apply { isDaemon = true; start() }
            Thread(::knockLoop, "car-link-knock").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(pairingReceiver) }
        runCatching { unregisterReceiver(aclReceiver) }
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
        // One listener for the whole run: a new one gets another channel, and the TomTom redials the one it remembers.
        var listener: android.bluetooth.BluetoothServerSocket? = null
        while (running) {
            try {
                val ss = listener ?: listenOnRememberedChannel(adapter, prefs, moveTo).also { listener = it; server = it; moveTo = null; listeningOn = channelOf(it) }
                CarLinkState.set(Phase.WAITING)
                CarLinkState.log("Waiting for the TomTom (phone \"${runCatching { adapter.name }.getOrNull() ?: "?"}\")")
                val sock = ss.accept()
                CarLinkState.set(Phase.MODEM)
                CarLinkState.log("TomTom connected: ${runCatching { sock.remoteDevice.name ?: sock.remoteDevice.address }.getOrNull()}")
                if (isTomTom(sock.remoteDevice)) channelOf(ss)?.let {
                    prefs.edit().putInt(DUN_CHANNEL_KEY, it).putInt(channelKey(sock.remoteDevice.address), it).apply()
                }
                pairIfNeeded(sock.remoteDevice)
                try {
                    sock.use { LinkWiring.runSession(it.inputStream, it.outputStream, host) }
                } catch (e: Exception) {
                    CarLinkState.log("Session ended: ${e.message ?: e.javaClass.simpleName}")
                }
                CarLinkState.log("TomTom hung up")
            } catch (e: Exception) {
                if (!running) break
                val moving = moveTo != null
                if (!moving) CarLinkState.log("Bluetooth: ${e.message ?: e.javaClass.simpleName}")
                runCatching { listener?.close() }
                listener = null
                if (!moving) Thread.sleep(2000)
            }
        }
        runCatching { listener?.close() }
    }

    /**
     * After a restart the TomTom doesn't call its phone by itself. A computer's Bluetooth reconnects to a paired device,
     * which wakes it up, so while nothing is connected this does the same: a short connection attempt to the paired TomTom.
     * Often at first, then rarely, so a TomTom that is switched off costs next to nothing.
     */
    @SuppressLint("MissingPermission")
    private fun knockLoop() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        var waited = 0
        while (running) {
            val pause = if (waited < KNOCK_FAST_FOR) KNOCK_FAST else KNOCK_SLOW
            runCatching { Thread.sleep(pause * 1000L) }
            if (!running) return
            if (CarLinkState.phase.value != Phase.WAITING) { waited = 0; continue }
            waited += pause
            // Never let a knock take the app down: it is only a convenience.
            runCatching {
                if (!adapter.isEnabled) return@runCatching
                for (d in adapter.bondedDevices.filter(::isTomTom)) {
                    // Nothing listens for this on the TomTom: opening the link is the knock, and the attempt then just fails.
                    runCatching { d.createInsecureRfcommSocketToServiceRecord(KNOCK_UUID).use { it.connect() } }
                }
            }
        }
    }

    /**
     * The TomTom's phone check holds this connection open and waits up to a minute for a stored pairing key, and it only
     * accepts pairing started by the phone. So the phone pairs now, while the TomTom is waiting.
     */
    @SuppressLint("MissingPermission")
    private fun pairIfNeeded(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_NONE) return
        // A TomTom that only just connected often has no name yet, so its maker's address block counts too.
        if (!isTomTom(device)) return
        pairingWith = device.address
        CarLinkState.log(if (device.createBond()) "Pairing with the TomTom" else "Couldn't start pairing with the TomTom")
    }

    /** A TomTom that only just connected often has no name yet, so its maker's address block counts too. */
    @SuppressLint("MissingPermission")
    private fun isTomTom(device: BluetoothDevice) =
        device.name?.contains("TomTom", ignoreCase = true) == true || device.address.startsWith(TOMTOM_ADDRESS_BLOCK, ignoreCase = true)

    /**
     * Listens on the channel the TomTom last used, because it redials that channel without looking it up. Android picks
     * channels itself, counting upward and wrapping round, so this reopens the socket until it lands there. Only one is
     * open at a time: closing a second socket for the same service breaks the one that stays.
     */
    @SuppressLint("MissingPermission")
    private fun listenOnRememberedChannel(adapter: android.bluetooth.BluetoothAdapter, prefs: android.content.SharedPreferences, channel: Int? = null): BluetoothServerSocket {
        val want = channel ?: prefs.getInt(DUN_CHANNEL_KEY, -1)
        repeat(if (want > 0) MAX_CHANNEL_TRIES else 1) {
            val s = adapter.listenUsingInsecureRfcommWithServiceRecord("Dial-up Networking", DUN_UUID)
            val got = channelOf(s)
            if (want <= 0 || got == null || got == want) return s // null: this Android won't say, so keep what it gave
            runCatching { s.close() }
            Thread.sleep(60)
        }
        CarLinkState.log("Bluetooth channel $want is busy: search for this phone again on the TomTom")
        return adapter.listenUsingInsecureRfcommWithServiceRecord("Dial-up Networking", DUN_UUID)
    }

    /** The channel Android gave [s]. It's a hidden field: null where the system doesn't allow reading it. */
    private fun channelOf(s: BluetoothServerSocket): Int? = runCatching {
        val inner = BluetoothServerSocket::class.java.getDeclaredField("mSocket").apply { isAccessible = true }.get(s)
        android.bluetooth.BluetoothSocket::class.java.getDeclaredField("mPort").apply { isAccessible = true }.getInt(inner)
    }.getOrNull()?.takeIf { it > 0 }

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
        private const val TOMTOM_PIN = "0000"
        private const val DUN_CHANNEL_KEY = "carLink.dunChannel"
        /** The channel one TomTom (by Bluetooth address) dialled last; [DUN_CHANNEL_KEY] is the last one of any. */
        private fun channelKey(address: String) = "$DUN_CHANNEL_KEY.${address.uppercase()}"
        private const val MAX_CHANNEL_TRIES = 35
        private val KNOCK_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val KNOCK_FAST = 15
        private const val KNOCK_SLOW = 60
        private const val KNOCK_FAST_FOR = 300
        private const val TOMTOM_ADDRESS_BLOCK = "00:13:6C:"

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
