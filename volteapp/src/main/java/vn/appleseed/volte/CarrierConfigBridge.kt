package vn.appleseed.volte

import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.ITelephony
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

    private fun carrierConfigLoader(): ICarrierConfigLoader {
        val binder = runCatching {
            SystemServiceHelper.getSystemService(Context.CARRIER_CONFIG_SERVICE)
        }.getOrElse {
            ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE)
        } ?: throw IllegalStateException("Không lấy được carrier_config service.")
        return ICarrierConfigLoader.Stub.asInterface(ShizukuBinderWrapper(binder))
    }

    private fun telephony(): ITelephony {
        val binder = runCatching {
            SystemServiceHelper.getSystemService(Context.TELEPHONY_SERVICE)
        }.getOrElse {
            ServiceManager.getService(Context.TELEPHONY_SERVICE)
        } ?: throw IllegalStateException("Không lấy được phone service.")
        return ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))
    }

    private fun subId(): Int {
        val id = SubscriptionManager.getDefaultSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(id)) return id
        throw IllegalStateException("Không tìm thấy subscription ID hợp lệ.")
    }

    fun applyVoLTE(context: Context): String {
        requireShizuku()
        val subId = subId()
        val loader = carrierConfigLoader()

        val values = Bundle().apply {
            putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
            putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
            putBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, true)
        }

        val persistable = android.os.PersistableBundle()
        for (key in values.keySet()) {
            persistable.putBoolean(key, values.getBoolean(key))
        }

        Log.i(TAG, "CarrierConfig override subId=" + subId + " keys=" + persistable.keySet())
        loader.overrideConfig(subId, persistable, true)

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
        telephony().resetIms(slot)

        return "CarrierConfig OK · subId=" + subId + " · slot=" + slot + " · IMS reset OK"
    }
}
