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
            KadbCert.configure(
                store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            connectHost = prefs.getString("connect_host", null)
            connectPort = prefs.getInt("connect_port", 0).takeIf { it > 0 }
            startAutoConnect()
            Log.i(TAG, "Kadb ready host=$connectHost port=$connectPort")
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
                        if (connectHost != null && connectPort != null) {
                            connect(connectHost, connectPort) { ok, message ->
                                if (!ok) Log.d(TAG, "AUTO connect failed: $message")
                            }
                        } else {
                            discoverConnectPort(
                                onFound = { host, port -> connect(host, port) },
                                onError = { Log.d(TAG, "AUTO mDNS: $it") }
                            )
                        }
                    }
                    Thread.sleep(AUTO_CONNECT_RETRY_MS)
                } catch (_: InterruptedException) {
                    break
                }
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
        Log.i(TAG, "PAIR prepared; pairing mDNS discovery started")
    }

    private fun startPairingDiscoveryLoop() {
        if (!pairingDiscoveryLoopActive.compareAndSet(false, true)) return
        Thread {
            try {
                while (pairingInProgress.get()) {
                    if (pairingPort == null && !pairingDiscoveryInFlight.get()) {
                        discoverPairingPort(
                            onFound = { host, port -> Log.i(TAG, "PAIR PORT READY: $host:$port") },
                            onError = { Log.d(TAG, "PAIR discovery: $it") }
                        )
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
                // stopped
            } finally {
                pairingDiscoveryLoopActive.set(false)
            }
        }.start()
    }

    fun stopPairingDiscovery() {
        pairingInProgress.set(false)
        pairingHost = null
        pairingPort = null
        Log.i(TAG, "PAIR state stopped")
    }

    fun discoverPairingPort(onFound: (String, Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!pairingDiscoveryInFlight.compareAndSet(false, true)) return
        discover(PAIRING_SERVICE, onFound, { error ->
            pairingDiscoveryInFlight.set(false)
            onError(error)
        }) { info ->
            val host = resolvedHost(info)
            pairingHost = host
            pairingPort = info.port
            Log.i(TAG, "PAIR resolved $host:${info.port}")
        }
    }

    fun discoverConnectPort(onFound: (String, Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!connectDiscoveryActive.compareAndSet(false, true)) return
        discover(CONNECT_SERVICE, onFound, { error ->
            connectDiscoveryActive.set(false)
            onError(error)
        }) { info ->
            val host = resolvedHost(info)
            connectHost = host
            connectPort = info.port
            Log.i(TAG, "CONNECT resolved $host:${info.port}")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvedHost(info: NsdServiceInfo): String {
        return info.host?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: info.hostAddresses.firstOrNull()?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: throw IllegalStateException("mDNS resolved nhưng không có host address")
    }

    @Suppress("DEPRECATION")
    private fun discover(
        serviceType: String,
        onFound: (String, Int) -> Unit,
        onError: (String) -> Unit,
        save: (NsdServiceInfo) -> Unit
    ) {
        val ctx = appContext ?: return onError("ADB engine chưa khởi tạo")
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = runCatching {
            wifi.createMulticastLock("AppleSeedIMS-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val finished = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            if (serviceType == CONNECT_SERVICE) connectDiscoveryActive.set(false)
            if (serviceType == PAIRING_SERVICE) pairingDiscoveryInFlight.set(false)
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.release() }
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String?) = Log.i(TAG, "mDNS START $type")

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished.get()) return
                val type = serviceInfo.serviceType ?: return
                if (!type.contains(serviceType)) return
                Log.i(TAG, "mDNS FOUND $type name=${serviceInfo.serviceName}")
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "mDNS RESOLVE FAIL $serviceType code=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (finished.get()) return
                            runCatching {
                                val host = resolvedHost(info)
                                Log.i(TAG, "mDNS RESOLVED $serviceType $host:${info.port}")
                                save(info)
                                finish()
                                onFound(host, info.port)
                            }.onFailure {
                                finish()
                                onError("mDNS resolve không có địa chỉ host: ${it.message}")
                            }
                        }
                    })
                }.onFailure { Log.w(TAG, "mDNS resolve request failed: ${it.message}") }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(type: String?) = Log.d(TAG, "mDNS STOP $type")

            override fun onStartDiscoveryFailed(type: String?, errorCode: Int) {
                finish()
                onError("Wireless Debugging mDNS không khởi động được ($errorCode)")
            }

            override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit
        }

        runCatching {
            // Use the legacy API deliberately. It works across the app's min SDK
            // and avoids requiring Android T Extensions at compile time.
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            finish()
            onError("Không bắt đầu được mDNS: ${it.message ?: it.javaClass.simpleName}")
            return
        }

        Thread {
            try {
                Thread.sleep(DISCOVERY_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!finished.get()) {
                finish()
                onError("Không tìm thấy $serviceType trong ${DISCOVERY_TIMEOUT_MS / 1000}s")
            }
        }.start()
    }

    fun pair(code: String, onDone: (Boolean, String) -> Unit) {
        val cleanCode = code.trim()
        if (cleanCode.length != 6 || cleanCode.any { !it.isDigit() }) {
            onDone(false, "Mã Pair phải đủ 6 chữ số")
            return
        }
        pairingInProgress.set(true)
        startPairingDiscoveryLoop()
        connectHost = null
        connectPort = null
        var finished = false

        fun finishPair(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            pairingInProgress.set(false)
            pairingHost = null
            pairingPort = null
            onDone(ok, message)
        }

        Thread {
            val deadline = System.currentTimeMillis() + PAIR_PORT_WAIT_MS
            while (pairingInProgress.get() && pairingPort == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (!pairingInProgress.get() || finished) return@Thread
            val host = pairingHost
            val port = pairingPort
            if (host == null || port == null) {
                finishPair(false, "Không tìm thấy Wireless Debugging pairing service. Hãy mở Pair device with pairing code và giữ màn hình đó mở, rồi thử lại.")
            } else {
                doPair(host, port, cleanCode, ::finishPair)
            }
        }.start()
    }

    private fun doPair(host: String, port: Int, code: String, finishPair: (Boolean, String) -> Unit) {
        Thread {
            runCatching {
                Log.i(TAG, "PAIR START $host:$port")
                runBlocking { Kadb.pair(host, port, code, appContext!!.filesDir.absolutePath) }
                Log.i(TAG, "PAIR OK $host:$port")
                waitForConnect(finishPair)
            }.onFailure { error ->
                Log.e(TAG, "PAIR FAIL on $host:$port", error)
                pairingHost = null
                pairingPort = null
                finishPair(false, "Ghép nối Wireless ADB thất bại: ${error.message ?: error.javaClass.simpleName}")
            }
        }.start()
    }

    private fun waitForConnect(finishPair: (Boolean, String) -> Unit, attempt: Int = 0) {
        if (attempt >= 12) {
            finishPair(false, "Đã ghép nối nhưng chưa thiết lập được Wireless ADB. Hãy giữ Wireless Debugging bật rồi thử lại.")
            return
        }
        Thread {
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                return@Thread
            }
            discoverConnectPort(
                onFound = { host, port ->
                    connect(host, port) { ok, message ->
                        if (ok) finishPair(true, message)
                        else {
                            Log.w(TAG, "CONNECT endpoint $host:$port failed: $message")
                            connectHost = null
                            connectPort = null
                            waitForConnect(finishPair, attempt + 1)
                        }
                    }
                },
                onError = { waitForConnect(finishPair, attempt + 1) }
            )
        }.start()
    }

    fun connect(port: Int? = connectPort, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val host = connectHost
        if (host != null) connect(host, port, onDone)
        else discoverConnectPort(
            onFound = { foundHost, foundPort -> connect(foundHost, foundPort, onDone) },
            onError = { onDone(false, it) }
        )
    }

    private fun connect(host: String, port: Int?, onDone: (Boolean, String) -> Unit) {
        if (activeKadb != null) return onDone(true, "WIRELESS ADB ONLINE")
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val target = port ?: return onDone(false, "ADB port chưa được xác định")
        Thread {
            runCatching {
                Log.i(TAG, "CONNECT START $host:$target")
                activeKadb?.close()
                val adb = Kadb.create(host, target, 15000, 15000)
                val probe = adb.shell("echo APPLE_SEED_ADB_OK")
                check(probe.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) { "ADB shell probe thất bại" }
                activeKadb = adb
                connectHost = host
                connectPort = target
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString("connect_host", host)
                    putInt("connect_port", target)
                }
                Log.i(TAG, "CONNECT ONLINE $host:$target")
                onDone(true, "WIRELESS ADB ONLINE — $host:$target")
            }.onFailure {
                runCatching { activeKadb?.close() }
                activeKadb = null
                Log.e(TAG, "CONNECT FAIL on $host:$target", it)
                onDone(false, "ADB CONNECT FAILED: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString("connect_host", null)
        val port = prefs.getInt("connect_port", 0).takeIf { it > 0 }
        if (host != null && port != null) {
            connect(host, port) { ok, status ->
                if (ok) onDone(true, status)
                else {
                    connectHost = null
                    connectPort = null
                    discoverConnectPort({ foundHost, foundPort -> connect(foundHost, foundPort, onDone) }, { onDone(false, it) })
                }
            }
        } else {
            discoverConnectPort({ foundHost, foundPort -> connect(foundHost, foundPort, onDone) }, { onDone(false, it) })
        }
    }

    fun shell(command: String): String {
        val adb = activeKadb ?: return "ADB OFFLINE"
        return runCatching {
            val result = adb.shell(command)
            buildString {
                append(result.output)
                if (result.exitCode != 0) append("\n[exit ${result.exitCode}]")
            }.trim()
        }.getOrElse {
            runCatching { activeKadb?.close() }
            activeKadb = null
            "ADB SHELL ERROR: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** Security-clean build: the old instrumentation broker remains disabled. */
    fun runBroker(mode: String, subId: Int = -1, patch: String = "", onDone: (Boolean, String) -> Unit) {
        if (activeKadb == null) {
            onDone(false, "WIRELESS ADB OFFLINE")
            return
        }
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
