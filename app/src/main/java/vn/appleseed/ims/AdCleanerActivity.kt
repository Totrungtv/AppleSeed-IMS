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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CleanerBg = Color(0xFF05070A)
private val CleanerPanel = Color(0xFF0D1218)
private val CleanerPanel2 = Color(0xFF131A22)
private val CleanerText = Color(0xFFF5F7FA)
private val CleanerMuted = Color(0xFF9AA7B5)
private val CleanerCyan = Color(0xFF46E6FF)
private val CleanerBlue = Color(0xFF557CFF)
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
        var message by remember { mutableStateOf("Sẵn sàng quét. Apple Seed chỉ hiển thị app bên thứ ba có dấu hiệu đáng kiểm tra.") }
        var selected by remember { mutableStateOf<AdRiskApp?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        LaunchedEffect(uninstallMessage) {
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
            message = "ĐANG QUÉT → quyền OVERLAY → AppOps → dấu hiệu tự khởi động/cài APK..."
            Thread {
                val result = runCatching { scanAdRiskApps() }.getOrElse { emptyList() }
                runOnUiThread {
                    apps.addAll(result)
                    scanning = false
                    message = if (result.isEmpty()) {
                        "SẠCH: Không phát hiện app bên thứ ba vừa có SYSTEM_ALERT_WINDOW vừa được AppOps OVERLAY cho phép."
                    } else {
                        "CẢNH BÁO: Phát hiện ${result.size} app cần kiểm tra. Không tự động gỡ bất kỳ app nào."
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
                containerColor = CleanerPanel,
                titleContentColor = CleanerText,
                textContentColor = CleanerText,
                title = { Text("XÁC NHẬN GỠ APP", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.label, color = CleanerText, fontWeight = FontWeight.Bold)
                        Text(app.packageName, color = CleanerMuted, fontSize = 10.sp)
                        Text(app.reasons.joinToString("\n"), color = CleanerWarn, fontSize = 11.sp)
                        Text("Android sẽ mở màn hình gỡ ứng dụng. Apple Seed không tự xoá app.", color = CleanerText, fontSize = 11.sp)
                    }
                },
                confirmButton = { Button(onClick = { requestRemove(app) }, colors = ButtonDefaults.buttonColors(containerColor = CleanerDanger)) { Text("GỠ ỨNG DỤNG", fontWeight = FontWeight.Black) } },
                dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text("HỦY") } }
            )
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = CleanerCyan, secondary = CleanerBlue, background = CleanerBg, surface = CleanerPanel, onSurface = CleanerText, error = CleanerDanger)) {
            Column(
                Modifier.fillMaxSize().background(CleanerBg).verticalScroll(rememberScrollState())
            ) {
                // VIP header
                Box(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF122033), CleanerBg))).padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(CleanerCyan, CleanerBlue))), contentAlignment = Alignment.Center) {
                                Text("AS", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("APPLE SEED", color = CleanerText, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                Text("VIP TECHNICIAN CONSOLE", color = CleanerCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            }
                            Box(Modifier.background(CleanerGood.copy(alpha = .12f), RoundedCornerShape(50.dp)).border(1.dp, CleanerGood.copy(alpha = .35f), RoundedCornerShape(50.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
                                Text("● READY", color = CleanerGood, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("AD CLEANER", color = CleanerText, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("Detect → Explain → Confirm → Remove", color = CleanerCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("QUÉT ĐỘC LẬP", color = CleanerCyan, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text("Không cần Wireless ADB", color = CleanerText, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            Text("Chỉ đánh dấu app bên thứ ba có SYSTEM_ALERT_WINDOW và AppOps OVERLAY = ALLOWED. Không tự động gỡ.", color = CleanerMuted, fontSize = 11.sp)
                            Button(
                                onClick = { scan() },
                                enabled = !scanning,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CleanerCyan, contentColor = Color.Black)
                            ) {
                                Text(if (scanning) "⟳  ĐANG QUÉT APP..." else "🛡  QUÉT APP GÂY QUẢNG CÁO", fontWeight = FontWeight.Black)
                            }
                            OutlinedButton(
                                onClick = { startActivity(Intent(this@AdCleanerActivity, VirusScannerActivity::class.java)) },
                                enabled = !scanning,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("🦠  QUÉT VIRUS FILE", fontWeight = FontWeight.Bold) }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel2), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (scanning) "◌" else if (apps.isEmpty()) "✓" else "!", color = if (scanning) CleanerCyan else if (apps.isEmpty()) CleanerGood else CleanerWarn, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            Text(message, color = if (message.startsWith("KHÔNG")) CleanerDanger else if (apps.isNotEmpty()) CleanerWarn else CleanerGood, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (apps.isNotEmpty()) {
                        Text("APPS CẦN KIỂM TRA  •  ${apps.size}", color = CleanerText, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        apps.forEach { app ->
                            RiskAppCard(app, onDetails = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${app.packageName}") })
                            }, onRemove = {
                                selected = app
                                confirmRemove = true
                            })
                        }
                    } else if (!scanning) {
                        Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("CHƯA CÓ KẾT QUẢ", color = CleanerMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Bấm QUÉT APP GÂY QUẢNG CÁO để kiểm tra thiết bị.", color = CleanerText, fontSize = 12.sp)
                            }
                        }
                    }

                    Divider(color = CleanerMuted.copy(alpha = .15f))
                    Text("BẢO VỆ HỆ THỐNG", color = CleanerCyan, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("APP HỆ THỐNG, FLAG_SYSTEM và FLAG_UPDATED_SYSTEM_APP bị loại ngay từ tầng quét. Nút GỠ chỉ mở trình uninstall của Android.", color = CleanerMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    @Composable
    private fun RiskAppCard(app: AdRiskApp, onDetails: () -> Unit, onRemove: () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(bitmap = app.icon.asImageBitmap(), contentDescription = app.label, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.label, color = CleanerText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(app.packageName, color = CleanerMuted, fontSize = 9.sp)
                    }
                    Box(Modifier.background(CleanerWarn.copy(alpha = .12f), RoundedCornerShape(50.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Text("REVIEW", color = CleanerWarn, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                }
                Text(app.reasons.joinToString("  •  "), color = CleanerWarn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).height(44.dp)) { Text("XEM APP", fontWeight = FontWeight.Bold) }
                    Button(onClick = onRemove, modifier = Modifier.weight(1f).height(44.dp), colors = ButtonDefaults.buttonColors(containerColor = CleanerDanger)) { Text("GỠ", fontWeight = FontWeight.Black) }
                }
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
