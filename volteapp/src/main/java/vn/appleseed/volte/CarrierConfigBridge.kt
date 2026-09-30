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

    private fun subId(): Int {
        val id = SubscriptionManager.getDefaultSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(id)) return id
        throw IllegalStateException("Không tìm thấy subscription ID hợp lệ.")
    }

    fun applyVoLTE(context: Context): String {
        requireShizuku()
        val subId = subId()

        val persistable = android.os.PersistableBundle().apply {
            putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
            putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, true)
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

        return "CarrierConfig OK · subId=$subId · slot=$slot · IMS reset OK"
    }
}
