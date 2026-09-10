package vn.appleseed.ims

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method

/**
 * Apple Seed privileged broker.
 *
 * The reference project uses the same core idea: an instrumentation process
 * adopts shell permissions, talks to CarrierConfigManager, applies an override,
 * resets IMS and returns evidence. Apple Seed deliberately keeps its own
 * Wireless-ADB front end and does not depend on Shizuku.
 */
class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "AppleSeedBroker"
        private const val RESULT_FILE = "apple_seed_carrier_result.txt"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Thread {
            var code = 0
            val result = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    error("Android 10+ is required")
                }
                HiddenApiBypass.addHiddenApiExemptions("L")
                getUiAutomation().adoptShellPermissionIdentity()
                try {
                    execute(arguments ?: Bundle())
                } finally {
                    runCatching { getUiAutomation().dropShellPermissionIdentity() }
                }
            }.getOrElse { error ->
                code = 1
                Log.e(TAG, "Broker failed", error)
                "ERROR: ${error.message ?: error.javaClass.simpleName}"
            }
            runCatching {
                File(targetContext.filesDir, RESULT_FILE).writeText(result)
            }
            finish(code, Bundle().apply { putString("result", result) })
        }.start()
    }

    private fun execute(args: Bundle): String {
        val mode = args.getString("mode", "read")
        val requestedSubId = args.getInt("subId", -1)
        val subscriptions = getSubscriptions()
        if (subscriptions.isEmpty()) return "NO ACTIVE SIM SUBSCRIPTION"

        val selected = subscriptions.filter { requestedSubId < 0 || it.subscriptionId == requestedSubId }
        if (selected.isEmpty()) return "SUBSCRIPTION $requestedSubId NOT FOUND"

        return buildString {
            appendLine("APPLE SEED CARRIER ENGINE")
            appendLine("MODE=$mode")
            appendLine("ANDROID=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT}")
            appendLine()
            selected.forEach { info ->
                appendLine("=== SIM SLOT ${info.simSlotIndex} / SUBID ${info.subscriptionId} ===")
                when (mode) {
                    "read" -> dumpConfig(info.subscriptionId)
                    "patch" -> {
                        val patch = args.getString("patch", "")
                        appendLine(applyPatch(info.subscriptionId, patch))
                    }
                    "clear" -> {
                        overrideConfig(info.subscriptionId, null)
                        resetIms(info.simSlotIndex)
                        appendLine("OVERRIDE=CLEARED")
                        appendLine("IMS=RESET REQUESTED")
                    }
                    "verify" -> dumpConfig(info.subscriptionId, selectedOnly = true)
                    else -> appendLine("UNKNOWN MODE=$mode")
                }
                appendLine()
            }
        }.trim()
    }

    @Suppress("MissingPermission")
    private fun getSubscriptions(): List<SubscriptionInfo> {
        val manager = targetContext.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()
        return runCatching { manager.activeSubscriptionInfoList ?: emptyList() }.getOrDefault(emptyList())
    }

    @Suppress("MissingPermission")
    private fun dumpConfig(subId: Int, selectedOnly: Boolean = false) {
        // This overload exists only to keep the builder readable.
    }

    @Suppress("MissingPermission")
    private fun dumpConfig(subId: Int, selectedOnly: Boolean = false): String {
        val manager = targetContext.getSystemService(CarrierConfigManager::class.java)
            ?: return "CARRIER CONFIG MANAGER UNAVAILABLE"
        val config = runCatching { manager.getConfigForSubId(subId) }.getOrNull()
            ?: return "CONFIG UNAVAILABLE FOR SUBID=$subId"
        val keys = if (selectedOnly) IMPORTANT_KEYS else config.keySet().toList().sorted()
        return buildString {
            appendLine("KEY_COUNT=${config.keySet().size}")
            keys.forEach { key ->
                if (config.containsKey(key)) appendLine("$key=${formatValue(config.get(key))}")
            }
        }.trim()
    }

    @Suppress("MissingPermission")
    private fun applyPatch(subId: Int, patch: String): String {
        val manager = targetContext.getSystemService(CarrierConfigManager::class.java)
            ?: return "CARRIER CONFIG MANAGER UNAVAILABLE"
        val current = runCatching { manager.getConfigForSubId(subId) }.getOrNull()
            ?: return "CONFIG UNAVAILABLE FOR SUBID=$subId"
        val bundle = PersistableBundle()
        val changed = mutableListOf<String>()

        patch.split(";;").map { it.trim() }.filter { it.isNotEmpty() }.forEach { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@forEach
            val key = item.substring(0, separator).trim()
            val raw = item.substring(separator + 1).trim()
            putTyped(bundle, key, raw, current.get(key))
            changed += "$key=$raw"
        }

        if (changed.isEmpty()) return "NO VALID PATCH VALUES"
        overrideConfig(subId, bundle)
        val slot = getSubscriptions().firstOrNull { it.subscriptionId == subId }?.simSlotIndex ?: -1
        if (slot >= 0) resetIms(slot)
        return buildString {
            appendLine("OVERRIDE=APPLIED")
            changed.forEach { appendLine(it) }
            appendLine("IMS=RESET REQUESTED")
            appendLine("VERIFY=RUN CARRIER READ AGAIN")
        }.trim()
    }

    private fun putTyped(bundle: PersistableBundle, key: String, raw: String, existing: Any?) {
        when (existing) {
            is Boolean -> bundle.putBoolean(key, raw.equals("true", true))
            is Int -> bundle.putInt(key, raw.toInt())
            is Long -> bundle.putLong(key, raw.toLong())
            is Double -> bundle.putDouble(key, raw.toDouble())
            is String -> bundle.putString(key, raw)
            is BooleanArray -> bundle.putBooleanArray(key, parseList(raw).map { it.equals("true", true) }.toBooleanArray())
            is IntArray -> bundle.putIntArray(key, parseList(raw).map { it.toInt() }.toIntArray())
            is LongArray -> bundle.putLongArray(key, parseList(raw).map { it.toLong() }.toLongArray())
            is DoubleArray -> bundle.putDoubleArray(key, parseList(raw).map { it.toDouble() }.toDoubleArray())
            is Array<*> -> bundle.putStringArray(key, parseList(raw).toTypedArray())
            else -> bundle.putBoolean(key, raw.equals("true", true))
        }
    }

    private fun parseList(raw: String): List<String> =
        raw.removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun formatValue(value: Any?): String = when (value) {
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        else -> value?.toString() ?: "null"
    }

    private fun overrideConfig(subId: Int, bundle: PersistableBundle?) {
        val manager = targetContext.getSystemService(CarrierConfigManager::class.java)
            ?: error("CarrierConfigManager unavailable")
        val method = findMethod(manager, "overrideConfig") ?: error("overrideConfig unavailable")
        when (method.parameterTypes.size) {
            2 -> method.invoke(manager, subId, bundle)
            3 -> method.invoke(manager, subId, bundle, false)
            else -> error("Unexpected overrideConfig signature")
        }
    }

    private fun resetIms(slot: Int) {
        val telephony = targetContext.getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            val method = findMethod(telephony, "resetIms") ?: return
            when (method.parameterTypes.size) {
                1 -> method.invoke(telephony, slot)
                2 -> method.invoke(telephony, slot, false)
            }
        }.onFailure { Log.w(TAG, "resetIms failed", it) }
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

    companion object Keys {
        val IMPORTANT_KEYS = listOf(
            "carrier_volte_available_bool",
            "enhanced_4g_lte_on_by_default_bool",
            "editable_enhanced_4g_lte_bool",
            "hide_enhanced_4g_lte_bool",
            "carrier_volte_provisioned_bool",
            "carrier_volte_provisioning_required_bool",
            "carrier_wfc_ims_available_bool",
            "carrier_default_wfc_ims_enabled_bool",
            "carrier_wfc_ims_provisioned_bool",
            "editable_wfc_mode_bool",
            "editable_wfc_roaming_mode_bool",
            "carrier_default_wfc_ims_roaming_enabled_bool",
            "vonr_enabled_bool",
            "vonr_setting_visibility_bool",
            "carrier_supports_ss_over_ut_bool",
            "carrier_vt_available_bool",
            "show_ims_registration_status_bool"
        )
    }
}
