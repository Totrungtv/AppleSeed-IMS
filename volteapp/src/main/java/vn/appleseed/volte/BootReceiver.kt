package vn.appleseed.volte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Re-apply VoLTE after ColorOS has finished loading the SIM/CarrierConfig.
 *
 * The boot log on CPH1905 shows CarrierConfig is rebuilt after BOOT_COMPLETED:
 * SIM -> IMSI -> LOADED -> carrier config handlers. Therefore writing Global
 * settings immediately at boot is too early and gets overwritten.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppleSeedBoot"
        private const val ACTION_FIX_VOLTE = "vn.appleseed.volte.action.FIX_VOLTE"
        private val VOLTE_KEYS = listOf(
            "volte_vt_enabled",
            "enhanced_4g_mode_enabled",
            "volte_enabled",
            "carrier_vt_enabled"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val app = context.applicationContext

        // Never keep the BroadcastReceiver alive. Schedule short delayed
        // passes while the phone/SIM finishes telephony initialization.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                restoreAfterBoot(app)
            }.onFailure {
                Log.e(TAG, "BOOT restore failed", it)
            }.also {
                pending.finish()
            }
        }, 15_000L)
    }

    private fun restoreAfterBoot(context: Context) {
        // Keep retrying because on this ColorOS build SIM becomes LOADED well
        // after BOOT_COMPLETED and CarrierConfig may rebuild several times.
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0

        fun pass() {
            attempt++
            val simReady = isSimReady(context)

            if (simReady) {
                writeGlobalFlags(context)

                // If Shizuku survived/was started by the user's setup, invoke
                // the exact same FIX_VOLTE action used by the main tool.
                if (shizukuReady()) {
                    runCatching {
                        context.startActivity(
                            Intent(ACTION_FIX_VOLTE).apply {
                                setPackage(context.packageName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        Log.i(TAG, "BOOT pass $attempt: FIX_VOLTE dispatched")
                    }.onFailure {
                        Log.w(TAG, "BOOT pass $attempt: FIX_VOLTE unavailable: ${it.message}")
                    }
                } else {
                    Log.i(TAG, "BOOT pass $attempt: SIM ready, Shizuku not running; flags restored")
                }
            } else {
                Log.i(TAG, "BOOT pass $attempt: SIM not ready yet")
            }

            // 6 passes: 15s, 25s, 35s, 45s, 60s, 75s after boot.
            if (attempt < 6) {
                handler.postDelayed(::pass, 10_000L)
            }
        }

        pass()
    }

    private fun isSimReady(context: Context): Boolean {
        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            when (tm.simState) {
                TelephonyManager.SIM_STATE_READY,
                TelephonyManager.SIM_STATE_LOADED -> true
                else -> {
                    val state = Settings.Global.getString(
                        context.contentResolver,
                        "gsm.sim.state"
                    ) ?: ""
                    state.contains("READY", true) || state.contains("LOADED", true)
                }
            }
        }.getOrDefault(false)
    }

    private fun writeGlobalFlags(context: Context) {
        VOLTE_KEYS.forEach { key ->
            Settings.Global.putInt(context.contentResolver, key, 1)
        }
        Log.i(TAG, "VoLTE global flags restored after SIM/CarrierConfig")
    }

    private fun shizukuReady(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            runCatching {
                Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
    }

}
