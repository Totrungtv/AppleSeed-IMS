package vn.appleseed.ims

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuShell {
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun authorized(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun run(command: String): String {
        if (!authorized()) return "Shizuku chưa được kết nối/cấp quyền."
        return runCatching {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            buildString {
                append(output)
                if (error.isNotBlank()) {
                    if (isNotEmpty()) append("\\n")
                    append("[stderr] ").append(error)
                }
            }.trim()
        }.getOrElse { "Shell error: ${it.message ?: it.javaClass.simpleName}" }
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
    }
}
