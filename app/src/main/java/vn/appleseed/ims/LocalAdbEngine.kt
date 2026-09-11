package vn.appleseed.ims

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
object LocalAdbEngine {
    private const val TAG = "AppleSeedADB"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val PREFS = "apple_seed_adb"
    private const val DISCOVERY_TIMEOUT_MS = 20000L
    private const val AUTO_CONNECT_RETRY_MS = 3000L
    private const val PAIR_PORT_WAIT_MS = 30000L

    private var appContext: Context? = null
    private var activeKadb: Kadb? = null
    private var connectHost: String? = null
    private var connectPort: Int? = null
    private var pairingHost: String? = null
    private var pairingPort: Int? = null
    private val pairingDiscoveryInFlight = AtomicBoolean(false)
    private val connectDiscoveryActive = AtomicBoolean(false)
    private val autoConnectActive = AtomicBoolean(false)
    private val pairingDiscoveryLoopActive = AtomicBoolean(false)
    private val pairingInProgress = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!configured.compareAndSet(false, true)) return
        runCatching {
            val keyFile = File(ctx.filesDir, "apple_seed_kadb_private_key.pem")
            KadbCert.configure(OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()), KadbCertPolicy(), emptyList())
            KadbCert.ensureReady()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            connectHost = prefs.getString("connect_host", null)
            connectPort = prefs.getInt("connect_port", 0).takeIf { it > 0 }
            startAutoConnect()
        }.onFailure { Log.e(TAG, "Kadb init failed", it) }
    }

    fun status(): String = when {
        activeKadb != null -> "WIRELESS ADB ONLINE"
        pairingInProgress.get() -> "PAIRING / CONNECTING..."
        connectHost != null && connectPort != null -> "AUTO CONNECTING..."
        else -> "WIRELESS DEBUGGING OFFLINE"
    }

    fun hasConnection(): Boolean = activeKadb != null

    fun openWirelessDebuggingSettings(context: Context) = runCatching {
        context.startActivity(android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.recoverCatching {
        context.startActivity(android.content.Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun startAutoConnect() {
        if (!autoConnectActive.compareAndSet(false, true)) return
        Thread {
            while (autoConnectActive.get()) {
                try {
                    if (activeKadb == null && !pairingInProgress.get() && !connectDiscoveryActive.get()) {
                        val host = connectHost
                        val port = connectPort
                        if (host != null && port != null) {
                            connectEndpoint(host, port) { ok, message -> if (!ok) Log.d(TAG, message) }
                        } else {
                            discoverConnectPort(
                                { foundHost, foundPort -> connectEndpoint(foundHost, foundPort) },
                                { Log.d(TAG, it) }
                            )
                        }
                    }
                    Thread.sleep(AUTO_CONNECT_RETRY_MS)
                } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    fun preparePairing() {
        pairingInProgress.set(true)
        pairingHost = null
        pairingPort = null
        connectHost = null
        connectPort = null
        startPairingDiscoveryLoop()
    }

    private fun startPairingDiscoveryLoop() {
        if (!pairingDiscoveryLoopActive.compareAndSet(false, true)) return
        Thread {
            try {
                while (pairingInProgress.get()) {
                    if (pairingPort == null && !pairingDiscoveryInFlight.get()) {
                        discoverPairingPort(
                            { host, port -> Log.i(TAG, "PAIR PORT READY $host:$port") },
                            { Log.d(TAG, it) }
                        )
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            } finally { pairingDiscoveryLoopActive.set(false) }
        }.start()
    }

    fun stopPairingDiscovery() {
        pairingInProgress.set(false)
        pairingHost = null
        pairingPort = null
    }

    fun discoverPairingPort(onFound: (String, Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!pairingDiscoveryInFlight.compareAndSet(false, true)) return
        discover(PAIRING_SERVICE, onFound, { pairingDiscoveryInFlight.set(false); onError(it) }) { info ->
            pairingHost = resolvedHost(info)
            pairingPort = info.port
        }
    }

    fun discoverConnectPort(onFound: (String, Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!connectDiscoveryActive.compareAndSet(false, true)) return
        discover(CONNECT_SERVICE, onFound, { connectDiscoveryActive.set(false); onError(it) }) { info ->
            connectHost = resolvedHost(info)
            connectPort = info.port
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvedHost(info: NsdServiceInfo): String {
        return info.host?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: info.hostAddresses.firstOrNull()?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: throw IllegalStateException("mDNS resolved nhưng không có host address")
    }

    @Suppress("DEPRECATION")
    private fun discover(serviceType: String, onFound: (String, Int) -> Unit, onError: (String) -> Unit, save: (NsdServiceInfo) -> Unit) {
        val ctx = appContext ?: return onError("ADB engine chưa khởi tạo")
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = runCatching { wifi.createMulticastLock("AppleSeedIMS-mdns").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val finished = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            if (serviceType == CONNECT_SERVICE) connectDiscoveryActive.set(false)
            if (serviceType == PAIRING_SERVICE) pairingDiscoveryInFlight.set(false)
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "mDNS START $type") }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished.get()) return
                if (!(serviceInfo.serviceType ?: "").contains(serviceType)) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "mDNS RESOLVE FAIL $serviceType $errorCode")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (finished.get()) return
                            runCatching {
                                val host = resolvedHost(info)
                                val port = info.port
                                save(info)
                                finish()
                                onFound(host, port)
                            }.onFailure { finish(); onError("mDNS resolve host failed: ${it.message}") }
                        }
                    })
                }.onFailure { Log.w(TAG, "mDNS resolve request failed", it) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(type: String) { Log.d(TAG, "mDNS STOP $type") }
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { finish(); onError("Wireless Debugging mDNS không khởi động được ($errorCode)") }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }

        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure {
            finish(); onError("Không bắt đầu được mDNS: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        Thread {
            try { Thread.sleep(DISCOVERY_TIMEOUT_MS) } catch (_: InterruptedException) { return@Thread }
            if (!finished.get()) { finish(); onError("Không tìm thấy $serviceType trong ${DISCOVERY_TIMEOUT_MS / 1000}s") }
        }.start()
    }

    fun pair(code: String, onDone: (Boolean, String) -> Unit) {
        val cleanCode = code.trim()
        if (cleanCode.length != 6 || cleanCode.any { !it.isDigit() }) { onDone(false, "Mã Pair phải đủ 6 chữ số"); return }
        pairingInProgress.set(true)
        startPairingDiscoveryLoop()
        var done = false
        fun finishPair(ok: Boolean, message: String) {
            if (done) return
            done = true
            pairingInProgress.set(false)
            pairingHost = null
            pairingPort = null
            onDone(ok, message)
        }
        Thread {
            val deadline = System.currentTimeMillis() + PAIR_PORT_WAIT_MS
            while (pairingInProgress.get() && pairingPort == null && System.currentTimeMillis() < deadline) Thread.sleep(250)
            if (!pairingInProgress.get() || done) return@Thread
            val host = pairingHost
            val port = pairingPort
            if (host == null || port == null) finishPair(false, "Không tìm thấy Wireless Debugging pairing service.")
            else doPair(host, port, cleanCode, ::finishPair)
        }.start()
    }

    private fun doPair(host: String, port: Int, code: String, done: (Boolean, String) -> Unit) {
        Thread {
            runCatching {
                runBlocking { Kadb.pair(host, port, code, appContext!!.filesDir.absolutePath) }
                waitForConnect(done)
            }.onFailure { done(false, "Ghép nối Wireless ADB thất bại: ${it.message ?: it.javaClass.simpleName}") }
        }.start()
    }

    private fun waitForConnect(done: (Boolean, String) -> Unit, attempt: Int = 0) {
        if (attempt >= 12) { done(false, "Đã Pair nhưng chưa thiết lập được Wireless ADB."); return }
        Thread {
            Thread.sleep(700)
            discoverConnectPort(
                { host, port -> connectEndpoint(host, port) { ok, message -> if (ok) done(true, message) else waitForConnect(done, attempt + 1) } },
                { waitForConnect(done, attempt + 1) }
            )
        }.start()
    }

    fun connect(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val host = connectHost
        val port = connectPort
        if (host != null && port != null) {
            connectEndpoint(host, port, onDone)
        } else {
            discoverConnectPort(
                { foundHost, foundPort -> connectEndpoint(foundHost, foundPort, onDone) },
                { onDone(false, it) }
            )
        }
    }

    private fun connectEndpoint(host: String, port: Int, onDone: (Boolean, String) -> Unit) {
        if (activeKadb != null) { onDone(true, "WIRELESS ADB ONLINE"); return }
        val ctx = appContext ?: run { onDone(false, "ADB engine chưa khởi tạo"); return }
        Thread {
            runCatching {
                Log.i(TAG, "CONNECT START $host:$port")
                activeKadb?.close()
                val adb = Kadb.create(host, port, 15000, 15000)
                val probe = adb.shell("echo APPLE_SEED_ADB_OK")
                check(probe.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) { "ADB shell probe thất bại" }
                activeKadb = adb
                connectHost = host
                connectPort = port
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("connect_host", host); putInt("connect_port", port) }
                onDone(true, "WIRELESS ADB ONLINE — $host:$port")
            }.onFailure {
                runCatching { activeKadb?.close() }
                activeKadb = null
                onDone(false, "ADB CONNECT FAILED: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: run { onDone(false, "ADB engine chưa khởi tạo"); return }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString("connect_host", null)
        val port = prefs.getInt("connect_port", 0).takeIf { it > 0 }
        if (host != null && port != null) {
            connectEndpoint(host, port) { ok, status ->
                if (ok) onDone(true, status)
                else {
                    connectHost = null
                    connectPort = null
                    discoverConnectPort({ h, p -> connectEndpoint(h, p, onDone) }, { onDone(false, it) })
                }
            }
        } else connect(onDone)
    }

    fun shell(command: String): String {
        val adb = activeKadb ?: return "ADB OFFLINE"
        return runCatching {
            val result = adb.shell(command)
            buildString { append(result.output); if (result.exitCode != 0) append("\n[exit ${result.exitCode}]") }.trim()
        }.getOrElse {
            runCatching { activeKadb?.close() }
            activeKadb = null
            "ADB SHELL ERROR: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun runBroker(mode: String, subId: Int = -1, patch: String = "", onDone: (Boolean, String) -> Unit) {
        if (activeKadb == null) { onDone(false, "WIRELESS ADB OFFLINE"); return }
        Log.w(TAG, "IMS broker disabled: mode=$mode subId=$subId patchLength=${patch.length}")
        onDone(false, "IMS BROKER ĐÃ TẮT TRONG BẢN SECURITY-CLEAN. Wireless ADB vẫn hoạt động bình thường.")
    }

    fun close() {
        stopPairingDiscovery()
        autoConnectActive.set(false)
        runCatching { activeKadb?.close() }
        activeKadb = null
    }
}
