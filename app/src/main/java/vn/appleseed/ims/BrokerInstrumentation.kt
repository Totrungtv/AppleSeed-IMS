package vn.appleseed.ims

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.app.UiAutomation
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

class BrokerInstrumentation : Instrumentation() {
    companion object { private const val TAG = "AppleSeedBroker" }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread {
            var automation: UiAutomation? = null
            var resultCode = 0
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw IllegalStateException("IMS broker requires Android 10 or newer")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.addHiddenApiExemptions("L")
                }
                automation = getUiAutomation()
                automation.adoptShellPermissionIdentity()
                val clear = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true
                if (clear) restoreAll() else applyAll()
            } catch (e: Exception) {
                resultCode = 1
                Log.e(TAG, "Broker failed", e)
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { automation?.dropShellPermissionIdentity() }
                }
                finish(resultCode, Bundle().apply {
                    putBoolean("success", resultCode == 0)
                })
            }
        }.start()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        runCatching { super.finish(resultCode, results) }
    }

    @SuppressLint("MissingPermission")
    private fun applyAll() {
        val subManager = service(SubscriptionManager::class.java) ?: return
        val carrier = service(CarrierConfigManager::class.java) ?: return
        val telephony = service(TelephonyManager::class.java) ?: return
        val prefs = targetContext.getSharedPreferences("apple_seed_ims", Context.MODE_PRIVATE)
        val subs = runCatching { subManager.activeSubscriptionInfoList ?: emptyList() }.getOrDefault(emptyList())

        for (info in subs) {
            val subId = info.subscriptionId
            val slot = info.simSlotIndex
            val config = PersistableBundle().apply {
                putBoolean("carrier_volte_available_bool", prefs.getBoolean("volte", true))
                putBoolean("enhanced_4g_lte_on_by_default_bool", prefs.getBoolean("volte", true))
                putBoolean("hide_enhanced_4g_lte_bool", false)
                putBoolean("editable_enhanced_4g_lte_bool", true)
                putBoolean("carrier_volte_provisioned_bool", true)
                putBoolean("carrier_volte_provisioning_required_bool", false)
                putBoolean("vonr_enabled_bool", prefs.getBoolean("vonr", true))
                putBoolean("vonr_setting_visibility_bool", prefs.getBoolean("vonr", true))
                putBoolean("carrier_wfc_ims_available_bool", prefs.getBoolean("vowifi", true))
                putBoolean("carrier_default_wfc_ims_enabled_bool", prefs.getBoolean("vowifi", true))
                putBoolean("carrier_wfc_ims_provisioned_bool", prefs.getBoolean("vowifi", true))
                putBoolean("editable_wfc_mode_bool", prefs.getBoolean("vowifi", true))
                putBoolean("editable_wfc_roaming_mode_bool", prefs.getBoolean("vowifi", true))
                putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true)
                putBoolean("carrier_supports_ss_over_ut_bool", true)
                putBoolean("show_ims_registration_status_bool", true)
            }
            invokeOverrideConfig(carrier, subId, config)
            resetIms(telephony, slot)
            FileStore.write(targetContext.filesDir, slot, "APPLIED")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAll() {
        val subManager = service(SubscriptionManager::class.java) ?: return
        val carrier = service(CarrierConfigManager::class.java) ?: return
        val telephony = service(TelephonyManager::class.java) ?: return
        val subs = runCatching { subManager.activeSubscriptionInfoList ?: emptyList() }.getOrDefault(emptyList())
        for (info in subs) {
            val subId = info.subscriptionId
            val slot = info.simSlotIndex
            invokeOverrideConfig(carrier, subId, null)
            resetIms(telephony, slot)
            FileStore.write(targetContext.filesDir, slot, "RESTORED")
        }
    }

    private fun resetIms(telephony: TelephonyManager, slot: Int) {
        runCatching {
            val reset = findMethod(telephony, "resetIms") ?: return
            when (reset.parameterTypes.size) {
                1 -> reset.invoke(telephony, slot)
                2 -> reset.invoke(telephony, slot, false)
            }
        }
    }

    private fun invokeOverrideConfig(manager: CarrierConfigManager, subId: Int, bundle: PersistableBundle?) {
        val method = findMethod(manager, "overrideConfig") ?: throw IllegalStateException("CarrierConfigManager.overrideConfig not available")
        when (method.parameterTypes.size) {
            2 -> method.invoke(manager, subId, bundle)
            3 -> method.invoke(manager, subId, bundle, false)
            else -> throw IllegalStateException("Unexpected overrideConfig signature")
        }
    }

    private fun findMethod(target: Any, name: String): Method? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val found = runCatching {
                type.declaredMethods.firstOrNull { it.name == name }?.also { it.isAccessible = true }
            }.getOrNull()
            if (found != null) return found
            type = type.superclass
        }
        return null
    }

    private fun <T> service(clazz: Class<T>): T? =
        runCatching { targetContext.getSystemService(clazz) }.getOrNull()

    private object FileStore {
        fun write(dir: java.io.File, slot: Int, state: String) {
            runCatching { java.io.File(dir, "ims_action_$slot.txt").writeText(state) }
        }
    }
}