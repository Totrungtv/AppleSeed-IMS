package vn.appleseed.ims

data class DiagnosticResult(
    val name: String,
    val state: String,
    val detail: String = ""
)

object ImsDiagnostics {
    fun parseDump(text: String): List<DiagnosticResult> {
        val lower = text.lowercase()
        val results = mutableListOf<DiagnosticResult>()
        results += result("IMS service", if (lower.contains("imsservice") || lower.contains("ims service")) "DETECTED" else "NOT DETECTED")
        results += result("IMS registration", registrationState(text))
        results += result("VoLTE capability", capabilityState(lower, "volte"))
        results += result("VoWiFi capability", capabilityState(lower, "vowifi"))
        results += result("VoNR capability", capabilityState(lower, "vonr"))
        results += result("RCS", capabilityState(lower, "rcs"))
        return results
    }

    private fun registrationState(text: String): String {
        val s = text.lowercase()
        return when {
            "registered" in s && "unregistered" !in s -> "REGISTERED"
            "unregistered" in s -> "UNREGISTERED"
            "registering" in s -> "REGISTERING"
            else -> "UNKNOWN"
        }
    }

    private fun capabilityState(text: String, key: String): String = when {
        Regex("$key[^\\n]{0,80}(true|enabled|available|supported)").containsMatchIn(text) -> "ENABLED / AVAILABLE"
        Regex("$key[^\\n]{0,80}(false|disabled|unavailable|unsupported)").containsMatchIn(text) -> "DISABLED / UNAVAILABLE"
        else -> "UNKNOWN"
    }

    private fun result(name: String, state: String) = DiagnosticResult(name, state)

    fun classify(results: List<DiagnosticResult>): String {
        val registration = results.firstOrNull { it.name == "IMS registration" }?.state
        return when (registration) {
            "REGISTERED" -> "IMS đang REGISTERED — có thể tiếp tục kiểm tra VoLTE call."
            "UNREGISTERED" -> "IMS chưa đăng ký — cần kiểm tra Carrier Config, SIM/operator và provisioning."
            else -> "Chưa đủ bằng chứng để kết luận IMS registration."
        }
    }
}
