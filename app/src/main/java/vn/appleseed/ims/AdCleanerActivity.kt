package vn.appleseed.ims

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    val reasons: List<String>,
    val icon: Bitmap
)

class AdCleanerActivity : ComponentActivity() {
    private val pm by lazy { packageManager }
    private var pendingUninstallPackage: String? = null
    private var pendingUninstallLabel: String? = null
    private var uninstallResult by mutableStateOf<String?>(null)

    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkUninstallResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdCleanerScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (pendingUninstallPackage != null) checkUninstallResult()
    }

    private fun checkUninstallResult() {
        val packageName = pendingUninstallPackage ?: return
        val label = pendingUninstallLabel ?: packageName
        val stillInstalled = runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
        if (stillInstalled) {
            uninstallResult = "KHÔNG GỠ ĐƯỢC: $label. Ứng dụng vẫn còn trên máy. Có thể bạn đã HỦY hoặc Android không cho phép gỡ ứng dụng này."
        } else {
            uninstallResult = "GỠ THÀNH CÔNG: $label. Android đã gỡ ứng dụng khỏi máy."
        }
        pendingUninstallPackage = null
        pendingUninstallLabel = null
    }

    @Composable
    private fun AdCleanerScreen() {
        val apps = remember { mutableStateListOf<SuspiciousApp>() }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Sẵn sàng quét ứng dụng gây quảng cáo.") }
        var selected by remember { mutableStateOf<SuspiciousApp?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        androidx.compose.runtime.LaunchedEffect(uninstallResult) {
            uninstallResult?.let { text ->
                val success = text.startsWith("GỠ THÀNH CÔNG")
                message = text
                if (success) selected?.let { removed -> apps.removeAll { it.packageName == removed.packageName } }
                selected = null
                uninstallResult = null
            }
        }

        fun scan() {
            if (scanning) return
            scanning = true
            message = "Đang quét ứng dụng bên thứ ba trực tiếp trên điện thoại..."
            Thread {
                val result = runCatching { scanInstalledApps() }.getOrElse { emptyList() }
                runOnUiThread {
                    apps.clear()
                    apps.addAll(result)
                    scanning = false
                    message = if (result.isEmpty()) {
                        "ĐÃ QUÉT XONG: không thấy ứng dụng bên thứ ba có mức nghi vấn cao."
                    } else {
                        "ĐÃ QUÉT XONG: phát hiện ${result.size} ứng dụng cần kiểm tra. APP HỆ THỐNG ĐÃ BỊ LOẠI KHỎI DANH SÁCH."
                    }
                }
            }.start()
        }

        fun requestRemove(app: SuspiciousApp) {
            confirmRemove = false
            pendingUninstallPackage = app.packageName
            pendingUninstallLabel = app.label
            runCatching {
                uninstallLauncher.launch(Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${app.packageName}")
                })
            }.onFailure {
                pendingUninstallPackage = null
                pendingUninstallLabel = null
                message = "KHÔNG GỠ ĐƯỢC: ${app.label}. Android không mở được trình gỡ ứng dụng: ${it.message ?: "lỗi không xác định"}."
                selected = null
            }
        }

        if (confirmRemove && selected != null) {
            val app = selected!!
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("XÁC NHẬN GỠ ỨNG DỤNG") },
                text = { Text("${app.label}\n\nMức nghi vấn: ${app.score}/10\n\nApple Seed sẽ mở trình gỡ chính thức của Android. Chỉ khi app thực sự biến khỏi danh sách cài đặt thì Apple Seed mới báo GỠ THÀNH CÔNG.") },
                confirmButton = { Button(onClick = { requestRemove(app) }) { Text("XÁC NHẬN GỠ") } },
                dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text("HỦY") } }
            )
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = CleanerCyan, background = CleanerBg, surface = CleanerPanel, onSurface = CleanerText, error = CleanerDanger)) {
            Column(Modifier.fillMaxSize().background(CleanerBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APPLE SEED • DỌN QUẢNG CÁO", color = CleanerText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("🛡 QUÉT APP ĐỘC LẬP • KHÔNG CẦN WIRELESS ADB", color = CleanerCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Chỉ quét app bên thứ ba. App hệ thống và app hệ thống được cập nhật bị loại ở tầng lọc, không xuất hiện nút GỠ.", color = CleanerMuted, fontSize = 11.sp)

                Button(onClick = { startActivity(Intent(this@AdCleanerActivity, VirusScannerActivity::class.java)) }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text("🦠 QUÉT VIRUS TOÀN BỘ ỨNG DỤNG")
                }
                OutlinedButton(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "ĐANG QUÉT QUẢNG CÁO..." else "🛡 QUÉT ỨNG DỤNG GÂY QUẢNG CÁO")
                }

                Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), modifier = Modifier.fillMaxWidth()) {
                    Text(message, color = if (message.startsWith("KHÔNG")) CleanerDanger else if (apps.isNotEmpty()) CleanerWarn else CleanerGood, modifier = Modifier.padding(14.dp), fontSize = 11.sp)
                }

                apps.forEach { app ->
                    Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(bitmap = app.icon.asImageBitmap(), contentDescription = app.label, modifier = Modifier.size(58.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(app.label, color = CleanerText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(app.packageName, color = CleanerMuted, fontSize = 10.sp)
                                Text("Mức nghi vấn: ${app.score}/10", color = if (app.score >= 7) CleanerDanger else CleanerWarn, fontWeight = FontWeight.Bold)
                                Text(app.reasons.joinToString(" • "), color = CleanerMuted, fontSize = 10.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${app.packageName}") }) }, modifier = Modifier.weight(1f)) { Text("XEM APP") }
                                    Button(onClick = { selected = app; confirmRemove = true }, modifier = Modifier.weight(1f)) { Text("GỠ") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("AN TOÀN: Apple Seed không bao giờ đưa app có FLAG_SYSTEM hoặc FLAG_UPDATED_SYSTEM_APP vào danh sách. Kết quả là phân tích nguy cơ, không phải kết luận malware tuyệt đối.", color = CleanerMuted, fontSize = 10.sp)
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
            .filter { app ->
                val flags = app.flags
                (flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                    app.packageName != packageName
            }
            .mapNotNull { analyzeApp(it) }
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
            if (permissions.contains(permission)) { score += points; reasons += reason }
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
        if (installer == null || installer == "com.android.packageinstaller") { score += 1; reasons += "NGUỒN CÀI ĐẶT KHÔNG RÕ" }
        val apkPath = app.sourceDir.orEmpty()
        if (apkPath.contains("/data/local/tmp", ignoreCase = true)) { score += 2; reasons += "APK TỪ VÙNG TẠM" }
        val suspiciousName = listOf("adware", "reward", "cleaner", "update", "security", "virus", "booster").any {
            label.contains(it, ignoreCase = true) || packageName.contains(it, ignoreCase = true)
        }
        if (suspiciousName && score > 0) { score += 1; reasons += "TÊN ỨNG DỤNG ĐÁNG NGỜ" }
        return if (score >= 3) SuspiciousApp(packageName, label, score.coerceAtMost(10), reasons.distinct(), loadAppIcon(app)) else null
    }

    private fun loadAppIcon(app: ApplicationInfo): Bitmap = drawableToBitmap(runCatching { pm.getApplicationIcon(app) }.getOrNull() ?: pm.defaultActivityIcon)

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas -> drawable.setBounds(0, 0, 128, 128); drawable.draw(canvas) }
        return bitmap
    }
}
