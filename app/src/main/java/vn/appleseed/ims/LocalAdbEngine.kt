package vn.appleseed.ims

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object LocalAdbEngine {
    private const val TAG = "AppleSeedADB"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val LOOPBACK = "127.0.0.1"
    private const val PREFS = "apple_seed_adb"
    private const val DISCOVERY_TIMEOUT_MS = 20000L
    private const val AUTO_CONNECT_RETRY_MS = 3000L
    private const val PAIR_PORT_WAIT_MS = 30000L

    private var appContext: Context? = null
    private var activeKadb: Kadb? = null
    private var connectPort: Int? = null
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

        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            .onFailure { Log.w(TAG, "HiddenApiBypass unavailable: ${it.message}") }

        runCatching {
            val keyFile = File(ctx.filesDir, "apple_seed_kadb_private_key.pem")
            KadbCert.configure(
                store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
            connectPort = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("connect_port", 0).takeIf { it > 0 }
            startAutoConnect()
            Log.i(TAG, "Kadb ready")
        }.onFailure { Log.e(TAG, "Kadb init failed", it) }
    }

    fun status(): String = when {
        activeKadb != null -> "WIRELESS ADB ONLINE"
        pairingInProgress.get() -> "PAIRING / CONNECTING..."
        connectPort != null -> "AUTO CONNECTING..."
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
                        val saved = connectPort
                        if (saved != null) {
                            connect(saved) { ok, message ->
                                if (!ok) Log.d(TAG, "AUTO connect failed: $message")
                            }
                        } else {
                            discoverConnectPort(
                                onFound = { found -> connect(found) },
                                onError = { Log.d(TAG, "AUTO mDNS: $it") }
                            )
                        }
                    }
                    Thread.sleep(AUTO_CONNECT_RETRY_MS)
                } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    // Start pairing discovery BEFORE the user submits the 6-digit code.
    // Wireless Debugging advertises the pairing service only while its pairing UI is active.
    fun preparePairing() {
        pairingInProgress.set(true)
        pairingPort = null
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
                            onFound = { found -> Log.i(TAG, "PAIR PORT READY: $found") },
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
        pairingPort = null
        Log.i(TAG, "PAIR state stopped")
    }

    fun discoverPairingPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!pairingDiscoveryInFlight.compareAndSet(false, true)) return
        discover(PAIRING_SERVICE, onFound, { error ->
            pairingDiscoveryInFlight.set(false)
            onError(error)
        }) { info ->
            pairingPort = info.port
            Log.i(TAG, "PAIR resolved ${info.host}:${info.port}")
        }
    }

    fun discoverConnectPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!connectDiscoveryActive.compareAndSet(false, true)) return
        discover(CONNECT_SERVICE, onFound, { error ->
            connectDiscoveryActive.set(false)
            onError(error)
        }) { info ->
            connectPort = info.port
            Log.i(TAG, "CONNECT resolved ${info.host}:${info.port}")
        }
    }

    private fun discover(
        serviceType: String,
        onFound: (Int) -> Unit,
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
        val executor = ContextCompat.getMainExecutor(ctx)

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
                    nsd.resolveService(serviceInfo, executor, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "mDNS RESOLVE FAIL $serviceType code=$errorCode")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (finished.get()) return
                            Log.i(TAG, "mDNS RESOLVED $serviceType ${info.host}:${info.port}")
                            save(info)
                            finish()
                            onFound(info.port)
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
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                if (network != null && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
                } else {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            } else {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }.onFailure {
            finish()
            onError("Không bắt đầu được mDNS: ${it.message ?: it.javaClass.simpleName}")
            return
        }

        Thread {
            try { Thread.sleep(DISCOVERY_TIMEOUT_MS) } catch (_: InterruptedException) { return@Thread }
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
        connectPort = null
        var finished = false

        fun finishPair(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            pairingInProgress.set(false)
            pairingPort = null
            onDone(ok, message)
        }

        Thread {
            val deadline = System.currentTimeMillis() + PAIR_PORT_WAIT_MS
            while (pairingInProgress.get() && pairingPort == null && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
            }
            if (!pairingInProgress.get() || finished) return@Thread
            val port = pairingPort
            if (port == null) {
                finishPair(false, "Không tìm thấy Wireless Debugging pairing service. Hãy mở Pair device with pairing code và giữ màn hình đó mở, rồi thử lại.")
            } else {
                doPair(port, cleanCode, ::finishPair)
            }
        }.start()
    }

    private fun doPair(port: Int, code: String, finishPair: (Boolean, String) -> Unit) {
        Thread {
            runCatching {
                Log.i(TAG, "PAIR START $LOOPBACK:$port")
                runBlocking { Kadb.pair(LOOPBACK, port, code, appContext!!.filesDir.absolutePath) }
                Log.i(TAG, "PAIR OK $LOOPBACK:$port")
                waitForConnect(finishPair)
            }.onFailure { error ->
                Log.e(TAG, "PAIR FAIL on $port", error)
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
            try { Thread.sleep(700) } catch (_: InterruptedException) { return@Thread }
            discoverConnectPort(
                onFound = { found ->
                    connect(found) { ok, message ->
                        if (ok) finishPair(true, message)
                        else {
                            Log.w(TAG, "CONNECT endpoint $found failed: $message")
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
        if (activeKadb != null) return onDone(true, "WIRELESS ADB ONLINE")
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val target = port ?: return discoverConnectPort(
            onFound = { found -> connect(found, onDone) },
            onError = { onDone(false, it) }
        )

        Thread {
            runCatching {
                Log.i(TAG, "CONNECT START $LOOPBACK:$target")
                activeKadb?.close()
                val adb = Kadb.create(LOOPBACK, target, 15000, 15000)
                val probe = adb.shell("echo APPLE_SEED_ADB_OK")
                check(probe.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) { "ADB shell probe thất bại" }
                activeKadb = adb
                connectPort = target
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("connect_port", target).apply()
                Log.i(TAG, "CONNECT ONLINE $LOOPBACK:$target")
                onDone(true, "WIRELESS ADB ONLINE")
            }.onFailure {
                runCatching { activeKadb?.close() }
                activeKadb = null
                if (connectPort == target) connectPort = null
                Log.e(TAG, "CONNECT FAIL on $target", it)
                onDone(false, "ADB CONNECT FAILED: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("connect_port", 0)
        if (saved > 0) {
            connect(saved) { ok, status ->
                if (ok) onDone(true, status)
                else {
                    connectPort = null
                    discoverConnectPort({ found -> connect(found, onDone) }, { onDone(false, it) })
                }
            }
        } else discoverConnectPort({ found -> connect(found, onDone) }, { onDone(false, it) })
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
            activeKadb = null
            "ADB SHELL ERROR: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun runBroker(mode: String, subId: Int = -1, patch: String = "", onDone: (Boolean, String) -> Unit) {
        val adb = activeKadb ?: return onDone(false, "WIRELESS ADB OFFLINE")
        Thread {
            runCatching {
                val safePatch = patch.replace("'", "")
                val cmd = "am instrument -w -e mode $mode -e subId $subId -e patch '$safePatch' vn.appleseed.ims/vn.appleseed.ims.BrokerInstrumentation"
                val result = adb.shell(cmd)
                val evidence = result.output.lineSequence()
                    .firstOrNull { it.startsWith("INSTRUMENTATION_STATUS: evidence_b64=") }
                    ?.substringAfter("evidence_b64=")
                    ?.let { android.util.Base64.decode(it.trim(), android.util.Base64.DEFAULT).toString(Charsets.UTF_8) }
                if (result.exitCode == 0 && evidence != null) onDone(true, evidence)
                else onDone(false, "BROKER EXIT ${result.exitCode}: ${result.output.trim()}")
            }.onFailure { onDone(false, "BROKER ERROR: ${it.message ?: it.javaClass.simpleName}") }
        }.start()
    }

    fun close() {
        stopPairingDiscovery()
        autoConnectActive.set(false)
        runCatching { activeKadb?.close() }
        activeKadb = null
    }
}
