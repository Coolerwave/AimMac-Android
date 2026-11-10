package com.coolerwave.aimmac

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone side of the Bluetooth link. Finds Macs running AimMac, remembers
 * the one you pair with and streams input to it. Everything here runs on the
 * main thread; Bluetooth callbacks hop over to it.
 */
@SuppressLint("MissingPermission")
class MacLink(private val ctx: Context) {
    enum class State { NoPermission, BluetoothOff, Searching, Connecting, WaitingApproval, Ready, Denied }

    data class FoundMac(val address: String, var name: String, var rssi: Int)

    @Volatile
    var state = State.Searching
        private set
    val found = mutableListOf<FoundMac>()
    var macName = ""
        private set
    var macPaused = false
        private set
    @Volatile
    var paused = false
        private set

    /** Called on the main thread whenever anything above changes. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    val hasPairedMac: Boolean get() = pairedAddress != null

    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("aimmac", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var input: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var scanning = false
    private var started = false
    private var helloSent = false

    // The write queue is touched from the sensor thread, the main thread and
    // Bluetooth callbacks, so every access to it goes through this lock.
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var latestAim: ByteArray? = null
    private var inFlight = false
    private var inFlightSince = 0L

    private var pairedAddress: String?
        get() = prefs.getString("pairedAddress", null)
        set(v) = prefs.edit().putString("pairedAddress", v).apply()
    private var pairedName: String
        get() = prefs.getString("pairedName", "") ?: ""
        set(v) = prefs.edit().putString("pairedName", v).apply()

    /** Stable id for this phone, so the Mac remembers it after you allow it once. */
    private val deviceId: UUID = run {
        val s = prefs.getString("deviceId", null)
        val u = s?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        u ?: UUID.randomUUID().also { prefs.edit().putString("deviceId", it.toString()).apply() }
    }

    private val deviceName: String
        get() = Settings.Global.getString(ctx.contentResolver, "device_name") ?: Build.MODEL

    // MARK: public

    fun hasPermissions(): Boolean = permissions().all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Safe to call again, for example after the permission dialog. */
    fun start() {
        if (!started) {
            started = true
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                        scanning = false
                        dropConnection()
                    }
                    main.post { evaluate() }
                }
            }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        evaluate()
    }

    fun pair(mac: FoundMac) {
        pairedAddress = mac.address
        pairedName = if (mac.name == "Mac") "" else mac.name
        found.clear()
        stopScan()
        val dev = adapter?.getRemoteDevice(mac.address) ?: return
        connect(dev)
    }

    fun unpair() {
        dropConnection()
        stopScan()
        pairedAddress = null
        pairedName = ""
        macName = ""
        setState(State.Searching)
        scan()
    }

    fun setPaused(on: Boolean) {
        if (paused == on) return
        paused = on
        val w = PacketWriter(Kind.PAUSE)
        w.u8(if (on) 1 else 0)
        sendEvent(w.bytes())
        changed()
    }

    fun sendAim(t: Double, az: Double, el: Double) {
        if (state != State.Ready || paused) return
        val w = PacketWriter(Kind.AIM)
        w.f64(t)
        w.f32(az.toFloat())
        w.f32(el.toFloat())
        synchronized(lock) { latestAim = w.bytes() }
        flush()
    }

    fun press() = timed(Kind.PRESS)
    fun release() = timed(Kind.RELEASE)
    fun rightClick() = timed(Kind.RIGHT)

    fun center() = sendEvent(PacketWriter(Kind.CENTER).bytes())

    fun scroll(dx: Double, dy: Double) {
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return
        val w = PacketWriter(Kind.SCROLL)
        w.f32(dx.toFloat())
        w.f32(dy.toFloat())
        sendEvent(w.bytes())
    }

    fun move(dx: Double, dy: Double) {
        val w = PacketWriter(Kind.MOVE)
        w.f32(dx.toFloat())
        w.f32(dy.toFloat())
        sendEvent(w.bytes())
    }

    // MARK: state

    private fun changed() {
        for (l in listeners) l()
    }

    private fun setState(s: State) {
        state = s
        changed()
    }

    private fun evaluate() {
        val ad = adapter
        when {
            !hasPermissions() -> setState(State.NoPermission)
            ad == null || !ad.isEnabled -> setState(State.BluetoothOff)
            gatt != null -> changed()
            else -> {
                setState(if (pairedAddress != null) State.Connecting else State.Searching)
                scan()
            }
        }
    }

    // MARK: scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onFound(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    private fun scan() {
        val ad = adapter ?: return
        if (scanning || !ad.isEnabled || !hasPermissions()) return
        val scanner = ad.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(TM.service)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        scanning = true
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
    }

    private fun nameMatches(advName: String): Boolean {
        val p = pairedName
        return p.isNotEmpty() && (p.startsWith(advName) || advName.startsWith(p))
    }

    private fun onFound(r: ScanResult) {
        val dev = r.device
        val advName = r.scanRecord?.deviceName
        if (pairedAddress != null) {
            if (gatt != null) return
            // Macs rotate their Bluetooth address, so the address can change. The
            // advertised name is the fallback; it is missing from some scan results,
            // and the Mac asks before it trusts anything anyway.
            val same = dev.address == pairedAddress || advName == null || nameMatches(advName)
            if (same) connect(dev)
            return
        }
        val name = advName ?: dev.name ?: "Mac"
        val i = found.indexOfFirst { it.address == dev.address }
        if (i >= 0) {
            found[i].rssi = r.rssi
            if (advName != null) found[i].name = name
        } else {
            found.add(FoundMac(dev.address, name, r.rssi))
        }
        changed()
    }

    // MARK: connection

    private fun connect(dev: BluetoothDevice) {
        if (gatt != null) return
        stopScan()
        if (pairedAddress != null) pairedAddress = dev.address
        mtu = 23
        setState(State.Connecting)
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun dropConnection() {
        main.removeCallbacks(infoTick)
        main.removeCallbacks(helloFallback)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        input = null
        statusChar = null
        helloSent = false
        synchronized(lock) {
            inFlight = false
            events.clear()
            latestAim = null
        }
    }

    private fun handleDisconnect(g: BluetoothGatt) {
        g.close()
        if (gatt === g) gatt = null
        input = null
        statusChar = null
        synchronized(lock) { inFlight = false }
        helloSent = false
        main.removeCallbacks(infoTick)
        main.removeCallbacks(helloFallback)
        if (adapter?.isEnabled != true) {
            setState(State.BluetoothOff)
            return
        }
        setState(if (pairedAddress != null) State.Connecting else State.Searching)
        main.postDelayed({ if (gatt == null) scan() }, 400)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    g.close()
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleDisconnect(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g === gatt) servicesReady(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                // Subscribed (or not): read the current status, then introduce ourselves.
                val sc = statusChar
                if (sc != null) g.readCharacteristic(sc) else sayHello()
            }
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value ?: return
            main.post { if (g === gatt) gotStatus(c, v) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            main.post { if (g === gatt) gotStatus(c, value) }
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            main.post { if (g === gatt) gotStatus(c, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g === gatt) gotStatus(c, value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // straight on the Bluetooth thread: the next packet goes out without waiting for the main thread
            synchronized(lock) { inFlight = false }
            flush()
        }
    }

    private fun servicesReady(g: BluetoothGatt) {
        val s = g.getService(TM.service)
        if (s == null) {
            g.disconnect()
            return
        }
        input = s.getCharacteristic(TM.input)
        statusChar = s.getCharacteristic(TM.status)
        synchronized(lock) {
            events.clear()
            latestAim = null
        }
        helloSent = false
        setState(State.WaitingApproval)

        val sc = statusChar
        val ccc = sc?.getDescriptor(CCC)
        if (sc != null && ccc != null) {
            g.setCharacteristicNotification(sc, true)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(ccc)
            }
            // if the steps above stall for any reason, still say hello
            main.postDelayed(helloFallback, 3000)
        } else {
            sayHello()
        }
    }

    private fun gotStatus(c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid != TM.status || v.size < 2) return
        macPaused = v[1].toInt() != 0
        if (v.size > 2) {
            macName = String(v, 2, v.size - 2, Charsets.UTF_8)
            if (macName.isNotEmpty() && pairedAddress != null) pairedName = macName
        }
        when (v[0].toInt()) {
            1 -> state = State.Ready
            2 -> state = State.Denied
            else -> state = State.WaitingApproval
        }
        changed()
        sayHello()
    }

    private val helloFallback = Runnable { sayHello() }

    private fun sayHello() {
        if (helloSent || gatt == null || input == null) return
        helloSent = true
        main.removeCallbacks(helloFallback)
        val w = PacketWriter(Kind.HELLO)
        w.uuid(deviceId)
        w.u8(Kind.DEVICE_PHONE)
        w.string(deviceName)
        synchronized(lock) { events.addFirst(w.bytes()) }
        sendInfo()
        flush()
        main.removeCallbacks(infoTick)
        main.postDelayed(infoTick, 30_000)
    }

    private val infoTick = object : Runnable {
        override fun run() {
            sendInfo()
            main.postDelayed(this, 30_000)
        }
    }

    private fun sendInfo() {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val w = PacketWriter(Kind.INFO)
        w.u8(if (pct in 0..100) pct else 255)
        w.string(Build.MODEL)
        sendEvent(w.bytes())
    }

    // MARK: sending

    private fun timed(kind: Int) {
        val w = PacketWriter(kind)
        // same clock as the sensor timestamps
        w.f64(SystemClock.elapsedRealtimeNanos() / 1e9)
        sendEvent(w.bytes())
    }

    private fun sendEvent(d: ByteArray) {
        val k = d[0].toInt()
        if (state != State.Ready && k != Kind.HELLO && k != Kind.INFO) return
        synchronized(lock) {
            events.addLast(d)
            while (events.size > 64) events.removeFirst()
        }
        flush()
    }

    private val retry = Runnable { flush() }

    /**
     * Clicks and scrolls go first and are never dropped; for aim only the
     * newest sample matters, so an old one is simply replaced. One write is in
     * flight at a time. Safe to call from any thread.
     */
    private fun flush() {
        var busy = false
        synchronized(lock) {
            val g = gatt ?: return
            val c = input ?: return
            if (inFlight) {
                if (SystemClock.uptimeMillis() - inFlightSince > 25) inFlight = false else return
            }
            val fromAim = events.isEmpty()
            val next = events.removeFirstOrNull() ?: latestAim?.also { latestAim = null } ?: return
            if (write(g, c, next)) {
                inFlight = true
                inFlightSince = SystemClock.uptimeMillis()
            } else {
                if (fromAim) {
                    if (latestAim == null) latestAim = next
                } else {
                    events.addFirst(next)
                }
                busy = true
            }
        }
        if (busy) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 3)
        }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, d: ByteArray): Boolean {
        val max = maxOf(mtu - 3, 20)
        val data = if (d.size > max) d.copyOf(max) else d
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    companion object {
        private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
