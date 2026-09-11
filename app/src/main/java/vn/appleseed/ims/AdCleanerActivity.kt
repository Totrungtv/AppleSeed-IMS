package vn.appleseed.ims

import android.app.AppOpsManager
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
import androidx.activity.compose.rememberLauncherForActivityResult
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

private data class AdRiskApp(
    val packageName: String,
    val label: String,
    val reasons: List<String>,
    val icon: Bitmap
)

class AdCleanerActivity : ComponentActivity() {
    private val pm by lazy { packageManager }
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private var pendingPackage: String? = null
    private var pendingLabel: String? = null

    private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkUninstallResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdCleanerScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (pendingPackage != null) checkUninstallResult()
    }

    private fun checkUninstallResult() {
        val pkg = pendingPackage ?: return
        val label = pendingLabel ?: pkg
        val stillInstalled = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        uninstallMessage = if (stillInstalled) {
            "KHÔNG GỠ ĐƯỢC: $label. Ứng dụng vẫn còn trên máy."
        } else {
            "GỠ THÀNH CÔNG: $label. Ứng dụng không còn trên máy."
        }
        pendingPackage = null
        pendingLabel = null
    }

    private var uninstallMessage by mutableStateOf<String?>(null)

    @Composable
    private fun AdCleanerScreen() {
        val apps = remember { mutableStateListOf<AdRiskApp>() }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Sẵn sàng kiểm tra app có khả năng gây quảng cáo đè màn hình.") }
        var selected by remember { mutableStateOf<AdRiskApp?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        androidx.compose.runtime.LaunchedEffect(uninstallMessage) {
            val result = uninstallMessage ?: return@LaunchedEffect
            message = result
            if (result.startsWith("GỠ THÀNH CÔNG")) selected?.let { removed -> apps.removeAll { it.packageName == removed.packageName } }
            selected = null
            uninstallMessage = null
        }

        fun scan() {
            if (scanning) return
            scanning = true
            apps.clear()
            message = "ĐANG DÒ APP → kiểm tra quyền OVERLAY → kiểm tra AppOps đang được phép..."
            Thread {
                val result = runCatching { scanAdRiskApps() }.getOrElse { emptyList() }
                runOnUiThread {
                    apps.addAll(result)
                    scanning = false
                    message = if (result.isEmpty()) {
                        "KẾT QUẢ: KHÔNG PHÁT HIỆN APP BÊN THỨ BA ĐANG ĐƯỢC CẤP OVERLAY VÀ CÓ DẤU HIỆU QUẢNG CÁO."
                    } else {
                        "KẾT QUẢ: PHÁT HIỆN ${result.size} APP CẦN KIỂM TRA. CHỈ APP OVERLAY ĐANG ĐƯỢC CẤP QUYỀN MỚI HIỆN."
                    }
                }
            }.start()
        }

        fun requestRemove(app: AdRiskApp) {
            confirmRemove = false
            pendingPackage = app.packageName
            pendingLabel = app.label
            runCatching {
                uninstallLauncher.launch(Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:${app.packageName}") })
            }.onFailure {
                pendingPackage = null
                pendingLabel = null
                message = "KHÔNG GỠ ĐƯỢC: ${app.label}. Android không mở được trình gỡ ứng dụng."
                selected = null
            }
        }

        if (confirmRemove && selected != null) {
            val app = selected!!
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("XÁC NHẬN GỠ") },
                text = { Text("${app.label}\n\n${app.reasons.joinToString("\n")}\n\nChỉ app bên thứ ba mới có nút GỠ.") },
                confirmButton = { Button(onClick = { requestRemove(app) }) { Text("GỠ ỨNG DỤNG") } },
                dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text("HỦY") } }
            )
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = CleanerCyan, background = CleanerBg, surface = CleanerPanel, onSurface = CleanerText, error = CleanerDanger)) {
            Column(Modifier.fillMaxSize().background(CleanerBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APPLE SEED • DỌN QUẢNG CÁO", color = CleanerText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("🛡 QUÉT ĐỘC LẬP • KHÔNG CẦN WIRELESS ADB", color = CleanerCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Không liệt kê hàng loạt app chỉ vì có permission. Kết quả chỉ gồm app bên thứ ba có SYSTEM_ALERT_WINDOW và AppOps OVERLAY đang ở trạng thái ALLOWED.", color = CleanerMuted, fontSize = 11.sp)
                OutlinedButton(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "ĐANG DÒ APP..." else "🛡 QUÉT ỨNG DỤNG GÂY QUẢNG CÁO")
                }
                Button(onClick = { startActivity(Intent(this@AdCleanerActivity, VirusScannerActivity::class.java)) }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text("🦠 QUÉT VIRUS FILE — CHỨC NĂNG RIÊNG")
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
                                Text(app.reasons.joinToString(" • "), color = CleanerWarn, fontSize = 10.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${app.packageName}") }) }, modifier = Modifier.weight(1f)) { Text("XEM APP") }
                                    Button(onClick = { selected = app; confirmRemove = true }, modifier = Modifier.weight(1f)) { Text("GỠ") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("APP HỆ THỐNG: FLAG_SYSTEM và FLAG_UPDATED_SYSTEM_APP bị loại ngay từ tầng quét.", color = CleanerMuted, fontSize = 10.sp)
            }
        }
    }

    private fun scanAdRiskApps(): List<AdRiskApp> {
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
            .mapNotNull { analyzeAdRisk(it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun analyzeAdRisk(app: ApplicationInfo): AdRiskApp? {
        val permissions = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (!permissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) return null

        val overlayMode = runCatching {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.uid, app.packageName)
        }.getOrDefault(AppOpsManager.MODE_IGNORED)
        if (overlayMode != AppOpsManager.MODE_ALLOWED) return null

        val reasons = mutableListOf("OVERLAY ĐANG ĐƯỢC CẤP PHÉP")
        if (permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED")) reasons += "TỰ KHỞI ĐỘNG"
        if (permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES")) reasons += "CÓ QUYỀN CÀI APK"
        val installer = runCatching { pm.getInstallSourceInfo(app.packageName).installingPackageName }.getOrNull()
        if (installer == null) reasons += "NGUỒN CÀI ĐẶT KHÔNG RÕ"

        return AdRiskApp(
            packageName = app.packageName,
            label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName),
            reasons = reasons,
            icon = loadAppIcon(app)
        )
    }

    private fun loadAppIcon(app: ApplicationInfo): Bitmap = drawableToBitmap(runCatching { pm.getApplicationIcon(app) }.getOrNull() ?: pm.defaultActivityIcon)

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 128, 128)
        drawable.draw(canvas)
        return bitmap
    }
}
