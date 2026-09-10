package vn.appleseed.ims

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
    private const val PREFS = "apple_seed_adb"
    private var appContext: Context? = null
    private var activeKadb: Kadb? = null
    private var connectPort: Int? = null
    private var pairingPort: Int? = null
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
            // Try to reconnect automatically when the app opens.
            reconnectSaved()
        }.onFailure { Log.e(TAG, "Kadb certificate initialization failed", it) }
    }

    fun status(): String = when {
        activeKadb != null -> "WIRELESS ADB ONLINE"
        connectPort != null -> "PAIRED — READY TO CONNECT"
        else -> "WIRELESS DEBUGGING OFFLINE"
    }

    fun hasConnection(): Boolean = activeKadb != null

    fun openWirelessDebuggingSettings(context: Context) = runCatching {
        context.startActivity(android.content.Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun discoverPairingPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) =
        discover(PAIRING_SERVICE, onFound, onError) { pairingPort = it.port }

    fun discoverConnectPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) =
        discover(CONNECT_SERVICE, onFound, onError) { connectPort = it.port }

    private fun discover(serviceType: String, onFound: (Int) -> Unit, onError: (String) -> Unit, save: (NsdServiceInfo) -> Unit) {
        val ctx = appContext ?: return onError("ADB engine chưa khởi tạo")
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(serviceType) != true) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) = onError("Không resolve được ADB service ($errorCode)")
                        override fun onServiceResolved(info: NsdServiceInfo) { save(info); onFound(info.port) }
                    })
                }.onFailure { onError("Resolve service lỗi: ${it.message}") }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = onError("Wireless Debugging service không khả dụng ($errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onError("Không bắt đầu được mDNS: ${it.message}") }
    }

    fun pair(code: String, onDone: (Boolean, String) -> Unit) {
        val port = pairingPort ?: return onDone(false, "Chưa tìm thấy pairing port")
        Thread {
            runCatching {
                runBlocking { Kadb.pair("127.0.0.1", port, code.trim(), "Apple Seed IMS") }
                discoverConnectPort(
                    onFound = { found -> onDone(true, "PAIR OK — connect port $found") },
                    onError = { error -> onDone(false, error) }
                )
            }.onFailure { onDone(false, "PAIR FAILED: ${it.message ?: it.javaClass.simpleName}") }
        }.start()
    }

    fun connect(port: Int? = connectPort, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val target = port ?: return discoverConnectPort(
            onFound = { found -> connect(found, onDone) },
            onError = { error -> onDone(false, error) }
        )
        Thread {
            runCatching {
                activeKadb?.close()
                activeKadb = Kadb.create("127.0.0.1", target, 15000, 15000)
                val probe = activeKadb?.shell("echo APPLE_SEED_ADB_OK")
                check(probe?.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) { "ADB shell probe thất bại" }
                connectPort = target
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("connect_port", target).apply()
                onDone(true, "WIRELESS ADB ONLINE")
            }.onFailure {
                activeKadb?.close()
                activeKadb = null
                connectPort = null
                onDone(false, "ADB CONNECT FAILED: ${it.message ?: it.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("connect_port", 0)
        if (saved > 0) {
            // First try the last known port. If it changed, automatically rediscover it.
            connect(saved) { ok, status ->
                if (ok) {
                    onDone(true, status)
                } else {
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
        runCatching { activeKadb?.close() }
        activeKadb = null
    }
}
