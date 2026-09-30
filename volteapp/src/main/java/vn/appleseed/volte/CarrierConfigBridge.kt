package vn.appleseed.volte

import android.content.Context
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object CarrierConfigBridge {
    private const val TAG = "AppleSeedCarrier"

    private fun requireShizuku() {
        if (!runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)) {
            throw IllegalStateException("Shizuku chưa chạy.")
        }
        if (runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false).not()) {
            throw SecurityException("Apple Seed chưa được cấp quyền Shizuku.")
        }
    }

    /*
     * Không import com.android.internal.telephony.* hoặc android.os.ServiceManager
     * trực tiếp vì đó là API nội bộ và sẽ làm Gradle/Kotlin compile fail.
     * Shizuku vẫn cung cấp binder shell; phần interface nội bộ được resolve
     * bằng reflection khi chạy trên Android.
     */
    private fun getSystemServiceBinder(context: Context, name: String): android.os.IBinder {
        return runCatching {
            SystemServiceHelper.getSystemService(name)
        }.getOrElse {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? android.os.IBinder
                ?: throw IllegalStateException("Không lấy được service: $name")
        } ?: throw IllegalStateException("Không lấy được service: $name")
    }

    private fun asInternalInterface(
        className: String,
        binder: android.os.IBinder
    ): Any {
        val stub = Class.forName("${className}\$Stub")
        val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
        return asInterface.invoke(null, ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("Không tạo được interface: $className")
    }

    private fun shellCommand(vararg args: String): String {
        val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val remote = method.invoke(null, args, null, null)
            ?: throw IllegalStateException("Shizuku không tạo được process.")
        return try {
            val input = remote.javaClass.getMethod("getInputStream").invoke(remote) as java.io.InputStream
            val error = remote.javaClass.getMethod("getErrorStream").invoke(remote) as java.io.InputStream
            remote.javaClass.getMethod("waitFor").invoke(remote)
            val out = input.bufferedReader().use { it.readText() }.trim()
            val err = error.bufferedReader().use { it.readText() }.trim()
            if (err.isNotEmpty()) "ERROR: $err" else out
        } finally {
            runCatching { remote.javaClass.getMethod("destroy").invoke(remote) }
        }
    }

    private fun shellSetProp(key: String, value: String) {
        val result = shellCommand("setprop", key, value)
        if (result.startsWith("ERROR:", ignoreCase = true)) throw IllegalStateException(result)
    }

    private fun subId(): Int {
        val id = SubscriptionManager.getDefaultSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(id)) return id
        throw IllegalStateException("Không tìm thấy subscription ID hợp lệ.")
    }

    fun applyVoLTE(context: Context): String {
        requireShizuku()
        val subId = subId()

        // Force the carrier configuration values that control the VoLTE UI.
        val persistable = android.os.PersistableBundle().apply {
            putBoolean("carrier_volte_available_bool", true)
            putBoolean("carrier_volte_provisioned_bool", true)
            putBoolean("carrier_volte_provisioning_required_bool", false)
            putBoolean("carrier_volte_tty_supported_bool", true)
            putBoolean("carrier_vt_available_bool", true)
            putBoolean("carrier_wfc_ims_available_bool", true)
            putBoolean("carrier_allow_turnoff_ims_bool", true)
            putBoolean("carrier_ims_gba_required_bool", false)
            putBoolean("hide_enhanced_4g_lte_bool", false)
            putBoolean("editable_enhanced_4g_lte_bool", true)
            putBoolean("enhanced_4g_lte_on_by_default_bool", true)
            putBoolean("hide_ims_apn_bool", false)
        }

        val loader = runCatching { getSystemServiceBinder(context, Context.CARRIER_CONFIG_SERVICE) }
            .mapCatching {
                asInternalInterface("com.android.internal.telephony.ICarrierConfigLoader", it)
            }
            .getOrThrow()

        Log.i(TAG, "CarrierConfig override subId=$subId keys=${persistable.keySet()}")

        loader.javaClass.getMethod(
            "overrideConfig",
            Int::class.javaPrimitiveType,
            android.os.PersistableBundle::class.java,
            Boolean::class.javaPrimitiveType
        ).invoke(loader, subId, persistable, true)

        for (key in listOf(
            "volte_vt_enabled",
            "enhanced_4g_mode_enabled",
            "volte_enabled",
            "carrier_vt_enabled"
        )) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, key, 1)
            }
        }

        val slot = SubscriptionManager.getSlotIndex(subId)

        val telephony = runCatching { getSystemServiceBinder(context, Context.TELEPHONY_SERVICE) }
            .mapCatching {
                asInternalInterface("com.android.internal.telephony.ITelephony", it)
            }
            .getOrThrow()

        telephony.javaClass.getMethod(
            "resetIms",
            Int::class.javaPrimitiveType
        ).invoke(telephony, slot)

        // ColorOS/Qualcomm may also consult IMS debug properties for the UI.
        val propResults = listOf(
            "persist.dbg.ims_volte_enable" to "1",
            "persist.dbg.volte_avail_ovr" to "1",
            "persist.dbg.vt_avail_ovr" to "1",
            "persist.dbg.wfc_avail_ovr" to "1"
        ).map { (key, value) -> runCatching { shellSetProp(key, value) }.isSuccess }

        // Reload the Phone process so Settings sees the new carrier/IMS state.
        runCatching { shellCommand("am", "force-stop", "com.android.phone") }

        val propOk = propResults.count { it }
        return "CarrierConfig OK · subId=$subId · slot=$slot · IMS reset OK · props=$propOk/4"
    }
}
