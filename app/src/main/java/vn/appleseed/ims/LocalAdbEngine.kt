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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object LocalAdbEngine {
    private const val TAG = "AppleSeedADB"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val LOOPBACK = "127.0.0.1"
    private const val PREFS = "apple_seed_adb"
    private const val DISCOVERY_TIMEOUT_MS = 10000L
    private const val AUTO_CONNECT_RETRY_MS = 3000L

    private var appContext: Context? = null
    private var activeKadb: Kadb? = null
    private var connectPort: Int? = null
    private var pairingPort: Int? = null
    private val pairingDiscoveryActive = AtomicBoolean(false)
    private val pairingDiscoveryInFlight = AtomicBoolean(false)
    private val connectDiscoveryActive = AtomicBoolean(false)
    private val autoConnectActive = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (configured.compareAndSet(false, true)) runCatching {
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
        }.onFailure { Log.e(TAG, "Kadb certificate initialization failed", it) }
    }

    fun status(): String = when {
        activeKadb != null -> "WIRELESS ADB ONLINE"
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
                if (activeKadb == null && !connectDiscoveryActive.get()) {
                    val saved = connectPort
                    if (saved != null) {
                        connect(saved) { ok, message ->
                            if (!ok) Log.d(TAG, "AUTO saved-port connect failed: $message")
                        }
                    } else {
                        discoverConnectPort(
                            onFound = { found -> connect(found) },
                            onError = { Log.d(TAG, "AUTO CONNECT discovery retry: $it") }
                        )
                    }
                }
                Thread.sleep(AUTO_CONNECT_RETRY_MS)
            }
        }.start()
    }

    fun preparePairing() {
        pairingDiscoveryActive.set(true)
        pairingPort = null
        discoverPairingPort(
            onFound = { found ->
                pairingPort = found
                pairingDiscoveryActive.set(false)
                Log.i(TAG, "PAIR endpoint ready: $LOOPBACK:$found")
            },
            onError = { error ->
                if (pairingDiscoveryActive.get()) Log.d(TAG, "PAIR discovery failed: $error")
            }
        )
    }

    fun stopPairingDiscovery() {
        pairingDiscoveryActive.set(false)
    }

    fun discoverPairingPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!pairingDiscoveryInFlight.compareAndSet(false, true)) {
            return onError("PAIR discovery đang chạy")
        }
        discover(PAIRING_SERVICE, onFound, { error ->
            pairingDiscoveryInFlight.set(false)
            onError(error)
        }) { info ->
            pairingPort = info.port
            Log.i(TAG, "Resolved pairing service: name=${info.serviceName}, host=${info.host}, port=${info.port}")
        }
    }

    fun discoverConnectPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        if (!connectDiscoveryActive.compareAndSet(false, true)) return
        discover(CONNECT_SERVICE, onFound, { error ->
            connectDiscoveryActive.set(false)
            onError(error)
        }) { info ->
            connectPort = info.port
            Log.i(TAG, "Resolved connect service: name=${info.serviceName}, host=${info.host}, port=${info.port}")
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
                Log.d(TAG, "mDNS discovery started: $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished.get()) return
                val advertisedType = serviceInfo.serviceType ?: return
                if (!advertisedType.contains(serviceType)) return
                Log.d(TAG, "mDNS service found: type=$advertisedType, name=${serviceInfo.serviceName}")
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "mDNS resolve failed: type=$serviceType code=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (finished.get()) return
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
                Log.d(TAG, "mDNS discovery stopped: $type")
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
            onError("Không bắt đầu được mDNS: ${it.message}")
            return
        }

        Thread {
            Thread.sleep(DISCOVERY_TIMEOUT_MS)
            if (!finished.get()) {
                finish()
                onError("Không tìm thấy $serviceType trong ${DISCOVERY_TIMEOUT_MS / 1000}s")
            }
        }.start()
    }

    fun pair(code: String, onDone: (Boolean, String) -> Unit) {
        val cleanCode = code.trim()
        if (cleanCode.length != 6 || cleanCode.any { !it.isDigit() }) {
            return onDone(false, "Mã Pair phải đủ 6 chữ số")
        }

        pairingDiscoveryActive.set(false)
        connectPort = null
        val cachedPort = pairingPort

        fun waitForPairingPort(attempt: Int = 0) {
            if (pairingPort != null) {
                doPair(pairingPort!!, true)
                return
            }
            if (attempt >= 12) {
                onDone(false, "Không tìm thấy cổng PAIRING. Hãy mở Pair device with pairing code rồi thử lại.")
                return
            }
            Thread {
                Thread.sleep(500)
                waitForPairingPort(attempt + 1)
            }.start()
        }

        fun waitForConnect(attempt: Int = 0) {
            if (attempt >= 12) {
                onDone(false, "PAIR OK nhưng chưa tìm thấy cổng CONNECT. Hãy giữ Wireless debugging ON rồi thử lại.")
                return
            }
            Thread {
                Thread.sleep(700)
                discoverConnectPort(
                    onFound = { found -> connect(found, onDone) },
                    onError = {
                        Thread {
                            Thread.sleep(500)
                            waitForConnect(attempt + 1)
                        }.start()
                    }
                )
            }.start()
        }

        fun doPair(port: Int, allowRediscover: Boolean) {
            Thread {
                runCatching {
                    Log.i(TAG, "PAIR start: $LOOPBACK:$port")
                    runBlocking { Kadb.pair(LOOPBACK, port, cleanCode, "Apple Seed IMS") }
                    pairingPort = null
                    Log.i(TAG, "PAIR handshake OK")
                    waitForConnect()
                }.onFailure { error ->
                    Log.e(TAG, "PAIR failed on port $port", error)
                    if (allowRediscover) {
                        pairingPort = null
                        pairingDiscoveryActive.set(true)
                        if (pairingDiscoveryInFlight.compareAndSet(false, true)) {
                            discover(PAIRING_SERVICE,
                                onFound = { fresh -> doPair(fresh, false) },
                                onError = { message -> onDone(false, "PAIR FAILED: ${error.message ?: message}") },
                                save = { info ->
                                    pairingPort = info.port
                                    Log.i(TAG, "Rediscovered pairing service: ${info.port}")
                                }
                            )
                        } else {
                            onDone(false, "PAIR FAILED: ${error.message ?: error.javaClass.simpleName}")
                        }
                    } else {
                        onDone(false, "PAIR FAILED: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }.start()
        }

        if (cachedPort != null) {
            doPair(cachedPort, true)
        } else if (pairingDiscoveryInFlight.get()) {
            waitForPairingPort()
        } else {
            pairingDiscoveryActive.set(true)
            if (!pairingDiscoveryInFlight.compareAndSet(false, true)) {
                waitForPairingPort()
            } else {
                discover(PAIRING_SERVICE,
                    onFound = { found -> doPair(found, true) },
                    onError = { error -> onDone(false, error) },
                    save = { info ->
                        pairingPort = info.port
                        Log.i(TAG, "Resolved pairing service: name=${info.serviceName}, host=${info.host}, port=${info.port}")
                    }
                )
            }
        }
    }

    fun connect(port: Int? = connectPort, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (activeKadb != null) return onDone(true, "WIRELESS ADB ONLINE")
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val target = port ?: return discoverConnectPort(
            onFound = { found -> connect(found, onDone) },
            onError = { error -> onDone(false, error) }
        )
        Thread {
            runCatching {
                Log.i(TAG, "CONNECT start: $LOOPBACK:$target")
                activeKadb?.close()
                activeKadb = Kadb.create(LOOPBACK, target, 15000, 15000)
                val probe = activeKadb?.shell("echo APPLE_SEED_ADB_OK")
                check(probe?.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) { "ADB shell probe thất bại" }
                connectPort = target
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("connect_port", target)
                    .apply()
                onDone(true, "WIRELESS ADB ONLINE")
            }.onFailure {
                Log.e(TAG, "CONNECT failed", it)
                activeKadb?.close()
                activeKadb = null
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
