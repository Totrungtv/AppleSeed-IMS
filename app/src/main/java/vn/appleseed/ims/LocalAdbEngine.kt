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
        if (configured.compareAndSet(false, true)) {
            try {
                val keyFile = File(ctx.filesDir, "apple_seed_kadb_private_key.pem")
                KadbCert.configure(
                    store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()),
                    policy = KadbCertPolicy(),
                    additionalPrivateKeysPem = emptyList()
                )
                KadbCert.ensureReady()
            } catch (e: Exception) {
                Log.e(TAG, "Kadb certificate initialization failed", e)
            }
        }
    }

    fun status(): String {
        if (activeKadb != null) return "WIRELESS ADB ONLINE"
        if (connectPort != null) return "PAIRED — READY TO CONNECT"
        return "WIRELESS DEBUGGING OFFLINE"
    }

    fun hasConnection(): Boolean = activeKadb != null

    fun openWirelessDebuggingSettings(context: Context) {
        runCatching {
            context.startActivity(android.content.Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun discoverPairingPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        val ctx = appContext ?: return onError("ADB engine chưa khởi tạo")
        stopDiscovery(PAIRING_SERVICE)
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(PAIRING_SERVICE) != true) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            onError("Không resolve được cổng pairing ($errorCode)")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            pairingPort = info.port
                            onFound(info.port)
                            runCatching { nsd.stopServiceDiscovery(this@object) }
                        }
                    })
                } catch (e: Exception) {
                    onError("Resolve pairing lỗi: ${e.message}")
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onError("Wireless Debugging chưa quảng bá pairing service ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onError("Không bắt đầu được mDNS: ${e.message}")
        }
    }

    fun pair(code: String, onDone: (Boolean, String) -> Unit) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val port = pairingPort ?: return onDone(false, "Chưa tìm thấy pairing port")
        Thread {
            try {
                Kadb.pair("127.0.0.1", port, code.trim(), ctx.filesDir.absolutePath)
                discoverConnectPort { foundPort ->
                    connectPort = foundPort
                    onDone(true, "PAIR OK — connect port $foundPort")
                } { error -> onDone(false, error) }
            } catch (e: Exception) {
                onDone(false, "PAIR FAILED: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    fun discoverConnectPort(onFound: (Int) -> Unit, onError: (String) -> Unit = {}) {
        val ctx = appContext ?: return onError("ADB engine chưa khởi tạo")
        stopDiscovery(CONNECT_SERVICE)
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(CONNECT_SERVICE) != true) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                            onError("Không resolve được ADB port ($errorCode)")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            connectPort = info.port
                            onFound(info.port)
                            runCatching { nsd.stopServiceDiscovery(this@object) }
                        }
                    })
                } catch (e: Exception) {
                    onError("Resolve ADB lỗi: ${e.message}")
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                onError("Không tìm thấy Wireless Debugging ADB service ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onError("Không bắt đầu được ADB discovery: ${e.message}")
        }
    }

    fun connect(port: Int? = connectPort, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val targetPort = port ?: return discoverConnectPort { connect(it, onDone) } { onDone(false, it) }
        Thread {
            try {
                activeKadb?.close()
                activeKadb = Kadb.create("127.0.0.1", targetPort, 15000, 15000)
                val probe = activeKadb?.shell("echo APPLE_SEED_ADB_OK")
                if (probe?.exitCode == 0 && probe.output.contains("APPLE_SEED_ADB_OK")) {
                    connectPort = targetPort
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("connect_port", targetPort).apply()
                    onDone(true, "WIRELESS ADB ONLINE")
                } else {
                    activeKadb?.close()
                    activeKadb = null
                    onDone(false, "ADB shell probe thất bại")
                }
            } catch (e: Exception) {
                activeKadb = null
                onDone(false, "ADB CONNECT FAILED: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    fun reconnectSaved(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val ctx = appContext ?: return onDone(false, "ADB engine chưa khởi tạo")
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("connect_port", 0)
        if (saved > 0) connect(saved, onDone) else discoverConnectPort { connect(it, onDone) } { onDone(false, it) }
    }

    fun shell(command: String): String {
        val adb = activeKadb ?: return "ADB OFFLINE"
        return try {
            val result = adb.shell(command)
            buildString {
                append(result.output)
                if (result.exitCode != 0) append("\n[exit ${result.exitCode}]")
            }.trim()
        } catch (e: Exception) {
            activeKadb = null
            "ADB SHELL ERROR: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun runInstrumentation(clear: Boolean, onDone: (Boolean, String) -> Unit) {
        val adb = activeKadb ?: return onDone(false, "WIRELESS ADB OFFLINE")
        Thread {
            try {
                val cmd = "am instrument -w -e clear $clear vn.appleseed.ims/vn.appleseed.ims.BrokerInstrumentation"
                val result = adb.shell(cmd)
                if (result.exitCode == 0) onDone(true, result.output.trim())
                else onDone(false, "INSTRUMENTATION EXIT ${result.exitCode}: ${result.output.trim()}")
            } catch (e: Exception) {
                onDone(false, "INSTRUMENTATION ERROR: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    fun close() {
        runCatching { activeKadb?.close() }
        activeKadb = null
    }

    private fun stopDiscovery(serviceType: String) {
        // Discovery listeners are intentionally short-lived; Android handles completed listeners.
        // This method exists to keep the engine API explicit and safe for repeated scans.
    }
}
