package vn.appleseed.ims

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuShell {

    private const val SERVICE_VERSION = 2

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName("vn.appleseed.ims", UserService::class.java.name)
    )
        .tag("apple_seed_ims_shell")
        .daemon(false)
        .processNameSuffix("ims_shell")
        .version(SERVICE_VERSION)
        .debuggable(true)

    @Volatile
    private var bridge: AppleSeedImsBridge? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = if (binder != null && binder.pingBinder()) {
                AppleSeedImsBridge.Stub.asInterface(binder)
            } else {
                null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
        }
    }

    fun available(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun authorized(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun connect(): String {
        if (!available()) return "Shizuku chưa chạy."
        if (!authorized()) return "Shizuku chưa được cấp quyền cho Apple Seed."
        if (bridge != null) return "Shizuku UserService: CONNECTED"

        return runCatching {
            Shizuku.bindUserService(serviceArgs, connection)
            "Đang kết nối Shizuku UserService..."
        }.getOrElse {
            "Không thể khởi động UserService: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun status(): String = when {
        !available() -> "SHIZUKU OFFLINE"
        !authorized() -> "SHIZUKU CHƯA CẤP QUYỀN"
        bridge != null -> "SHIZUKU USER SERVICE ONLINE"
        else -> "SHIZUKU ĐÃ CẤP QUYỀN"
    }

    fun run(command: String): String {
        if (!available()) return "Shizuku chưa được kết nối."
        if (!authorized()) return "Shizuku chưa được cấp quyền."

        if (bridge == null) {
            connect()
            return "UserService đang khởi động. Bấm lại SCAN sau vài giây."
        }

        return runCatching {
            bridge?.exec(command) ?: "UserService chưa sẵn sàng."
        }.getOrElse {
            bridge = null
            "Shell error: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun collectImsDump(): String = run("dumpsys ims")

    fun collectTelephonyRegistry(): String = run("dumpsys telephony.registry")

    fun collectCarrierConfig(): String = run("dumpsys carrier_config")

    fun collectRadioLogs(): String = run("dumpsys radio")

    fun collectAll(): String = buildString {
        appendLine("===== APPLE SEED IMS DUMP =====")
        appendLine("===== dumpsys ims =====")
        appendLine(collectImsDump())
        appendLine("===== telephony.registry =====")
        appendLine(collectTelephonyRegistry())
        appendLine("===== carrier_config =====")
        appendLine(collectCarrierConfig())
        appendLine("===== radio =====")
        appendLine(collectRadioLogs())
    }
}
