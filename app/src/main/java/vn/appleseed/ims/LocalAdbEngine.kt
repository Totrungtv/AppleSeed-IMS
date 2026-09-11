package vn.appleseed.ims

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
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
    private const val DISCOVERY_TIMEOUT_MS = 15000L
    private const val AUTO_CONNECT_RETRY_MS = 3000L

    private var appContext: Context? = null
    private var activeKadb: Kadb? = null
    private var connectPort: Int? = null
    private var pairingPort: Int? = null
    private val pairingDiscoveryInFlight = AtomicBoolean(false)
    private val connectDiscoveryActive = AtomicBoolean(false)
    private val autoConnectActive = AtomicBoolean(false)
    private val pairingInProgress = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!configured.compareAndSet(false, true)) return

        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
            Log.i(TAG, "HiddenApiBypass ready")
        }.onFailure {
            Log.w(TAG, "HiddenApiBypass unavailable: ${it.message}")
        }

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
            Log.i(TAG, "Kadb certificate ready")
        }.onFailure {
            Log.e(TAG, "Kadb certificate initialization failed", it)
        }
    }

    fun status(): String = when {
        activeKadb != null -> "WIRELESS ADB ONLINE"
        pairingInProgress.get() -> "PAIRING / CONNECTING..."
        connectPort != null -> "AUTO CONNECTING..."
        else -> "WIRELESS DEBUGGING OFFLINE"
    }

    fun hasConnection(): Boolean = activeKadb != null

    fun openWirelessDebuggingSettings(context: Context) = runCatching {
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
                                if (!ok) Log.d(TAG, "AUTO saved-port connect failed: $message")
                            }
                        } else {
                            discoverConnectPort(
                                onFound = { found -> connect(found) },
                                onError = { Log.d(TAG, "AUTO connect discovery: $it") }
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
        pairingPort = null
        connectPort = null
        Log.i(TAG, "PAIR prepared; waiting for pairing-code submission before mDNS discovery")
    }

    fun stopPairingDiscovery() {
        pairingInProgress.set(false)
        pairingPort = null
        Log.i(TAG, "PAIR state stopped")
    }

    fun discoverPairingPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!pairingDiscoveryInFlight.compareAndSet(false, true)) {
            onError("PAIR discovery đang chạy")
            return
        }
        discover(PAIRING_SERVICE, onFound, { error ->
            pairingDiscoveryInFlight.set(false)
            onError(error)
        }) { info ->
            pairingPort = info.port
            Log.i(TAG, "PAIR service resolved: ${info.host}:${info.port}")
        }
    }

    fun discoverConnectPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!connectDiscoveryActive.compareAndSet(false, true)) {
            onError("CONNECT discovery đang chạy")
            return
        }
        discover(CONNECT_SERVICE, onFound, { error ->
            connectDiscoveryActive.set(false)
            onError(error)
        }) { info ->
            connectPort = info.port
            Log.i(TAG, "CONNECT service resolved: ${info.host}:${info.port}")
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

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            if (serviceType == CONNECT_SERVICE) connectDiscoveryActive.set(false)
            if (serviceType == PAIRING_SERVICE) pairingDiscoveryInFlight.set(false)
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.release() }
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String?) {
                Log.i(TAG, "mDNS START: $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished.get()) return
                val advertisedType = serviceInfo.serviceType ?: return
                if (!advertisedType.contains(serviceType)) return
                Log.i(TAG, "mDNS FOUND: $advertisedType name=${serviceInfo.serviceName}")
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "mDNS RESOLVE FAIL: $serviceType code=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (finished.get()) return
                            Log.i(TAG, "mDNS RESOLVED: $serviceType host=${info.host} port=${info.port}")
                            save(info)
                            finish()
                            onFound(info.port)
                        }
                    })
                }.onFailure {
                    Log.w(TAG, "mDNS resolve request failed: ${it.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(type: String?) {
                Log.d(TAG, "mDNS STOP: $type")
            }

            override fun onStartDiscoveryFailed(type: String?, errorCode: Int) {
                finish()
                onError("Wireless Debugging mDNS không khởi động được ($errorCode)")
            }

            override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit
        }

        runCatching {
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
        connectPort = null
        val cachedPort = pairingPort
        var finished = false

        fun finishPair(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            pairingInProgress.set(false)
            pairingPort = null
            onDone(ok, message)
        }

        fun waitForConnect(attempt: Int = 0) {
            if (attempt >= 12) {
                finishPair(false, "PAIR thành công nhưng chưa tìm thấy phiên Wireless ADB. Hãy giữ Wireless Debugging bật rồi thử lại.")
                return
            }
            Thread {
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                discoverConnectPort(
                    onFound = { found ->
                        connect(found) { ok, message ->
                            if (ok) {
                                finishPair(true, message)
                            } else {
                                Log.w(TAG, "CONNECT endpoint $found failed: $message")
                                connectPort = null
                                waitForConnect(attempt + 1)
                            }
                        }
                    },
                    onError = {
                        waitForConnect(attempt + 1)
                    }
                )
            }.start()
        }

        fun doPair(port: Int, allowRediscover: Boolean) {
            Thread {
                runCatching {
                    Log.i(TAG, "PAIR START: $LOOPBACK:$port")
                    runBlocking {
                        Kadb.pair(LOOPBACK, port, cleanCode, appContext!!.filesDir.absolutePath)
                    }
                    Log.i(TAG, "PAIR OK: $LOOPBACK:$port")
                    waitForConnect()
                }.onFailure { error ->
                    Log.e(TAG, "PAIR FAIL on $port", error)
                    if (allowRediscover) {
                        pairingPort = null
                        pairingDiscoveryInFlight.set(false)
                        discoverPairingPort(
                            onFound = { fresh -> doPair(fresh, false) },
                            onError = { message ->
                                finishPair(false, "PAIR thất bại: ${error.message ?: message}")
                            }
                        )
                    } else {
                        finishPair(false, "PAIR thất bại: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }.start()
        }

        if (cachedPort != null) {
            doPair(cachedPort, true)
            return
        }

        pairingDiscoveryInFlight.set(false)
        discoverPairingPort(
            onFound = { found -> doPair(found, true) },
            onError = { error -> finishPair(false, error) }
        )
    }

    fun connect(port: Int? = connectPort, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (activeKadb != null) {
            onDone(true, "WIRELESS ADB ONLINE")
            return
        }
        val ctx = appContext ?: run {
            onDone(false, "ADB engine chưa khởi tạo")
            return
        }
        val target = port ?: run {
            discoverConnectPort(
                onFound = { found -> connect(found, onDone) },
                onError = { error -> onDone(false, error) }
            )
            return
        }

        Thread {
            runCatching {
                Log.i(TAG, "CONNECT START: $LOOPBACK:$target")
                activeKadb?.close()
                activeKadb = Kadb.create(LOOPBACK, target, 15000, 15000)
                val probe = activeKadb?.shell("echo APPLE_SEED_ADB_OK")
                check(probe?.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) {
                    "ADB shell probe thất bại"
                }
                connectPort = target
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt("connect_port", target).apply()
                Log.i(TAG, "CONNECT ONLINE: $LOOPBACK:$target")
                onDone(true, "WIRELESS ADB ONLINE")
            }.onFailure {
                Log.e(TAG, "CONNECT FAIL on $target", it)
                runCatching { activeKadb?.close() }
                activeKadb = null
                if (connectPort == target) connectPort = null
                onDone(false, "ADB CONNECT FAILED: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("connect_port", 0)
        if (saved > 0) {
            connect(saved) { ok, status ->
                if (ok) {
                    onDone(true, status)
                } else {
                    connectPort = null
                    discoverConnectPort(
                        onFound = { found -> connect(found, onDone) },
                        onError = { error -> onDone(false, error) }
                    )
                }
            }
        } else {
            discoverConnectPort(
                onFound = { found -> connect(found, onDone) },
                onError = { error -> onDone(false, error) }
            )
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
                if (result.exitCode == 0 && evidence != null) {
                    onDone(true, evidence)
                } else {
                    onDone(false, "BROKER EXIT ${result.exitCode}: ${result.output.trim()}")
                }
            }.onFailure {
                onDone(false, "BROKER ERROR: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun close() {
        stopPairingDiscovery()
        autoConnectActive.set(false)
        runCatching { activeKadb?.close() }
        activeKadb = null
    }
}
