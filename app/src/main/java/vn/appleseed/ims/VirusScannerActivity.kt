package vn.appleseed.ims

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.FileInputStream
import java.security.MessageDigest

private val VirusBg = Color(0xFF05070A)
private val VirusPanel = Color(0xFF0D1218)
private val VirusText = Color(0xFFF5F7FA)
private val VirusMuted = Color(0xFF9AA7B5)
private val VirusCyan = Color(0xFF46E6FF)
private val VirusGood = Color(0xFF42E6A4)
private val VirusWarn = Color(0xFFFFC857)
private val VirusDanger = Color(0xFFFF667A)

private data class AppScan(
    val label: String,
    val packageName: String,
    val score: Int,
    val reasons: List<String>,
    val sha256: String
)

class VirusScannerActivity : ComponentActivity() {
    private val pm by lazy { packageManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirusScannerScreen() }
    }

    @Composable
    private fun VirusScannerScreen() {
        var scanning by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("Sẵn sàng quét toàn bộ ứng dụng trên máy.") }
        var findings by remember { mutableStateOf<List<AppScan>>(emptyList()) }

        fun scanAll() {
            if (scanning) return
            scanning = true
            result = "ĐANG QUÉT TOÀN BỘ ỨNG DỤNG... Không cần chọn file, không cần Wireless ADB."
            Thread {
                val scanResult = runCatching { scanInstalledApps() }
                    .getOrElse { ScanResult(emptyList(), "QUÉT THẤT BẠI: ${it.message ?: "Lỗi không xác định."}") }
                runOnUiThread {
                    findings = scanResult.findings
                    result = scanResult.message
                    scanning = false
                }
            }.start()
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = VirusCyan, background = VirusBg, surface = VirusPanel, onSurface = VirusText, error = VirusDanger)) {
            Column(Modifier.fillMaxSize().background(VirusBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APPLE SEED • QUÉT VIRUS", color = VirusText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("🦠 TỰ ĐỘNG QUÉT ỨNG DỤNG TRÊN MÁY • KHÔNG CẦN ADB", color = VirusCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Apple Seed tự lấy danh sách app, kiểm tra quyền nguy hiểm, dấu hiệu tự khởi động/hiển thị đè, nguồn cài đặt và SHA-256 của APK. Đây là kiểm tra nguy cơ cục bộ, không phải cơ sở dữ liệu antivirus thương mại.", color = VirusMuted, fontSize = 11.sp)

                Button(onClick = { scanAll() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "🔎 ĐANG QUÉT..." else "🦠 QUÉT TOÀN BỘ MÁY")
                }
                Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                    Text(result, color = when {
                        result.startsWith("QUÉT THẤT BẠI") -> VirusDanger
                        findings.isNotEmpty() -> VirusWarn
                        else -> VirusGood
                    }, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
                }

                findings.forEach { app ->
                    Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(app.label, color = VirusText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(app.packageName, color = VirusMuted, fontSize = 10.sp)
                            Text("MỨC NGHI VẤN: ${app.score}/10", color = if (app.score >= 7) VirusDanger else VirusWarn, fontWeight = FontWeight.Black)
                            Text(app.reasons.joinToString(" • "), color = VirusMuted, fontSize = 10.sp)
                            if (app.sha256.isNotBlank()) Text("SHA-256: ${app.sha256}", color = VirusMuted, fontSize = 8.sp)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text("ĐÓNG") }
                }
            }
        }
    }

    private data class ScanResult(val findings: List<AppScan>, val message: String)

    private fun scanInstalledApps(): ScanResult {
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        val userApps = installed.filter {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                it.packageName != packageName
        }
        val findings = userApps.mapNotNull { analyzeApp(it) }.sortedByDescending { it.score }
        val message = if (findings.isEmpty()) {
            "QUÉT XONG: ${userApps.size} ứng dụng bên thứ ba được kiểm tra. Chưa thấy dấu hiệu nguy cơ cao theo bộ kiểm tra cục bộ."
        } else {
            "QUÉT XONG: ${userApps.size} ứng dụng được kiểm tra. Có ${findings.size} ứng dụng cần kiểm tra thêm. Không tự ý kết luận đây là virus."
        }
        return ScanResult(findings, message)
    }

    private fun analyzeApp(app: ApplicationInfo): AppScan? {
        val permissions = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        var score = 0
        val reasons = mutableListOf<String>()
        fun flag(permission: String, points: Int, reason: String) {
            if (permissions.contains(permission)) { score += points; reasons += reason }
        }
        flag("android.permission.SYSTEM_ALERT_WINDOW", 3, "HIỂN THỊ ĐÈ LÊN ỨNG DỤNG")
        flag("android.permission.REQUEST_INSTALL_PACKAGES", 2, "CÓ QUYỀN CÀI APK")
        flag("android.permission.RECEIVE_BOOT_COMPLETED", 1, "TỰ KHỞI ĐỘNG")
        flag("android.permission.PACKAGE_USAGE_STATS", 1, "THEO DÕI ỨNG DỤNG")
        flag("android.permission.READ_SMS", 2, "ĐỌC SMS")
        flag("android.permission.RECEIVE_SMS", 2, "NHẬN SMS")
        flag("android.permission.READ_CALL_LOG", 2, "ĐỌC LỊCH SỬ CUỘC GỌI")
        flag("android.permission.RECORD_AUDIO", 1, "MICRO")
        flag("android.permission.CAMERA", 1, "CAMERA")

        val installer = runCatching { pm.getInstallSourceInfo(app.packageName).installingPackageName }.getOrNull()
        if (installer == null || installer == "com.android.packageinstaller") { score += 1; reasons += "NGUỒN CÀI ĐẶT KHÔNG RÕ" }

        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
        val suspiciousName = listOf("adware", "reward", "crack", "hack", "mod", "trojan", "malware", "virus", "booster").any {
            label.contains(it, ignoreCase = true) || app.packageName.contains(it, ignoreCase = true)
        }
        if (suspiciousName) { score += 2; reasons += "TÊN ỨNG DỤNG ĐÁNG NGỜ" }

        if (score < 3) return null
        return AppScan(label, app.packageName, score.coerceAtMost(10), reasons.distinct(), sha256OfApk(app.sourceDir))
    }

    private fun sha256OfApk(path: String?): String = runCatching {
        if (path.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
