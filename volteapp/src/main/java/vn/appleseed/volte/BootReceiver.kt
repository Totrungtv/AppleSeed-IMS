package vn.appleseed.volte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Android 8/ColorOS boot persistence.
 *
 * CPH1905/API 27 does not expose the newer persistent CarrierConfig override
 * API. The settings below are nevertheless persistent system-global switches
 * when WRITE_SECURE_SETTINGS has been granted to this APK.
 *
 * We deliberately do not claim CarrierConfig/IMS was restored here: Shizuku
 * is normally not running after reboot on an unrooted Android 8 device.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppleSeedBoot"
        private val VOLTE_KEYS = listOf(
            "volte_vt_enabled",
            "enhanced_4g_mode_enabled",
            "volte_enabled",
            "carrier_vt_enabled"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        runCatching {
            val resolver = context.contentResolver
            VOLTE_KEYS.forEach { key ->
                Settings.Global.putInt(resolver, key, 1)
            }

            Log.i(TAG, "BOOT: restored VoLTE global flags")
        }.onFailure {
            Log.e(TAG, "BOOT: cannot restore VoLTE flags", it)
        }
    }
}
