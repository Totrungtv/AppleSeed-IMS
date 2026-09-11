package vn.appleseed.ims

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CleanerBg = Color(0xFF05070A)
private val CleanerPanel = Color(0xFF0D1218)
private val CleanerText = Color(0xFFF5F7FA)
private val CleanerMuted = Color(0xFF9AA7B5)
private val CleanerCyan = Color(0xFF46E6FF)
private val CleanerGood = Color(0xFF42E6A4)
private val CleanerWarn = Color(0xFFFFC857)
private val CleanerDanger = Color(0xFFFF667A)

private data class SuspiciousApp(
    val packageName: String,
    val label: String,
    val score: Int,
    val reasons: List<String>
)

class AdCleanerActivity : ComponentActivity() {
    private val pm by lazy { packageManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdCleanerScreen() }
    }

    @Composable
    private fun AdCleanerScreen() {
        val apps = remember { mutableStateListOf<SuspiciousApp>() }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Sẵn sàng quét virus / quảng cáo.") }
        var selected by remember { mutableStateOf<SuspiciousApp?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        fun scan() {
            if (scanning) return
            scanning = true
            message = "Đang quét ứng dụng trực tiếp trên điện thoại..."
            Thread {
                val result = scanInstalledApps()
                runOnUiThread {
                    apps.clear()
                    apps.addAll(result)
                    scanning = false
                    message = if (result.isEmpty()) {
                        "Không thấy ứng dụng có mức nghi vấn cao theo bộ kiểm tra cục bộ."
                    } else {
                        "Phát hiện ${result.size} ứng dụng cần kiểm tra. Đây là kết quả phân tích nguy cơ, không phải kết luận virus tuyệt đối."
                    }
                }
            }.start()
        }

        fun requestRemove(app: SuspiciousApp) {
            confirmRemove = false
            runCatching {
                startActivity(Intent(Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.parse("package:${app.packageName}")
                })
            }.onFailure {
                message = "Android không mở được màn hình gỡ ứng dụng cho ${app.label}."
            }
        }

        if (confirmRemove && selected != null) {
            val app = selected!!
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("XÁC NHẬN GỠ ỨNG DỤNG") },
                text = { Text("Ứng dụng ${app.label} có mức nghi vấn ${app.score}/10. Apple Seed sẽ mở màn hình gỡ ứng dụng của Android để bạn xác nhận.") },
                confirmButton = { Button(onClick = { requestRemove(app) }) { Text("GỠ ỨNG DỤNG") } },
                dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text("HỦY") } }
            )
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = CleanerCyan, background = CleanerBg, surface = CleanerPanel, onSurface = CleanerText, error = CleanerDanger)) {
            Column(Modifier.fillMaxSize().background(CleanerBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APPLE SEED • QUÉT VIRUS & DỌN QUẢNG CÁO", color = CleanerText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("Chạy trực tiếp trên điện thoại — KHÔNG CẦN Wireless ADB.", color = CleanerCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Phân tích app bên thứ ba theo quyền nguy hiểm, khả năng overlay, tự khởi động, cài APK, accessibility và nguồn cài đặt.", color = CleanerMuted, fontSize = 11.sp)
                Button(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "ĐANG QUÉT..." else "🔍 QUÉT VIRUS & QUẢNG CÁO")
                }
                Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), modifier = Modifier.fillMaxWidth()) {
                    Text(message, color = if (apps.isNotEmpty()) CleanerWarn else CleanerGood, modifier = Modifier.padding(14.dp))
                }
                apps.forEach { app ->
                    Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(app.label, color = CleanerText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(app.packageName, color = CleanerMuted, fontSize = 10.sp)
                            Text("Mức nghi vấn: ${app.score}/10", color = if (app.score >= 7) CleanerDanger else CleanerWarn, fontWeight = FontWeight.Bold)
                            Text(app.reasons.joinToString(" • "), color = CleanerMuted, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.parse("package:${app.packageName}") }) }, modifier = Modifier.weight(1f)) {
                                    Text("XEM APP")
                                }
                                Button(onClick = { selected = app; confirmRemove = true }, modifier = Modifier.weight(1f)) {
                                    Text("GỠ")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Lưu ý: đây là bộ quét cục bộ của Apple Seed. Quyền nguy hiểm chỉ là dấu hiệu; muốn xác định malware chắc chắn cần cơ sở dữ liệu/engine antivirus chuyên dụng.", color = CleanerMuted, fontSize = 10.sp)
            }
        }
    }

    private fun scanInstalledApps(): List<SuspiciousApp> {
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        return installed.asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { app -> analyzeApp(app) }
            .sortedByDescending { it.score }
            .toList()
    }

    private fun analyzeApp(app: ApplicationInfo): SuspiciousApp? {
        val packageName = app.packageName
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(packageName)
        val permissions = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        var score = 0
        val reasons = mutableListOf<String>()
        fun flag(permission: String, points: Int, reason: String) {
            if (permissions.contains(permission)) {
                score += points
                reasons += reason
            }
        }

        flag("android.permission.SYSTEM_ALERT_WINDOW", 3, "HIỂN THỊ ĐÈ LÊN ỨNG DỤNG")
        flag("android.permission.REQUEST_INSTALL_PACKAGES", 2, "CÓ QUYỀN CÀI APK")
        flag("android.permission.RECEIVE_BOOT_COMPLETED", 1, "TỰ KHỞI ĐỘNG")
        flag("android.permission.PACKAGE_USAGE_STATS", 1, "THEO DÕI ỨNG DỤNG")
        flag("android.permission.READ_SMS", 2, "ĐỌC SMS")
        flag("android.permission.RECEIVE_SMS", 2, "NHẬN SMS")
        flag("android.permission.READ_CALL_LOG", 2, "ĐỌC LỊCH SỬ CUỘC GỌI")
        flag("android.permission.READ_CONTACTS", 1, "ĐỌC DANH BẠ")
        flag("android.permission.RECORD_AUDIO", 1, "MICRO")
        flag("android.permission.CAMERA", 1, "CAMERA")

        val installer = runCatching { pm.getInstallSourceInfo(packageName).installingPackageName }.getOrNull()
        if (installer == null || installer == "com.android.packageinstaller") {
            score += 1
            reasons += "NGUỒN CÀI ĐẶT KHÔNG RÕ"
        }

        val apkPath = app.sourceDir.orEmpty()
        if (apkPath.contains("/data/local/tmp", ignoreCase = true)) {
            score += 2
            reasons += "APK TỪ VÙNG TẠM"
        }

        val suspiciousName = listOf("adware", "reward", "cleaner", "update", "security", "virus", "booster").any {
            label.contains(it, ignoreCase = true) || packageName.contains(it, ignoreCase = true)
        }
        if (suspiciousName && score > 0) {
            score += 1
            reasons += "TÊN ỨNG DỤNG ĐÁNG NGỜ"
        }

        return if (score >= 3) SuspiciousApp(packageName, label, score.coerceAtMost(10), reasons.distinct()) else null
    }
}
