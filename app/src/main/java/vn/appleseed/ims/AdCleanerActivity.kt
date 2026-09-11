package vn.appleseed.ims

import android.content.Intent
import android.os.Bundle
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
private val CleanerWarn = Color(0xFFFFC857)
private val CleanerDanger = Color(0xFFFF667A)

private data class SuspiciousApp(
    val packageName: String,
    val score: Int,
    val reasons: List<String>
)

class AdCleanerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalAdbEngine.init(this)
        setContent { AdCleanerScreen() }
    }

    @Composable
    private fun AdCleanerScreen() {
        val apps = remember { mutableStateListOf<SuspiciousApp>() }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Sẵn sàng quét.") }
        var selected by remember { mutableStateOf<SuspiciousApp?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        fun scan() {
            if (scanning) return
            if (!LocalAdbEngine.hasConnection()) {
                message = "CHƯA KẾT NỐI: hãy kết nối Wireless ADB với điện thoại trước."
                return
            }
            scanning = true
            message = "Đang kiểm tra ứng dụng có dấu hiệu gây quảng cáo..."
            Thread {
                val command = """
                    pm list packages -3 | cut -d: -f2 | while read p; do
                      d="$(dumpsys package "$p" 2>/dev/null)";
                      score=0; reasons="";
                      echo "$d" | grep -q "android.permission.SYSTEM_ALERT_WINDOW" && { score=$((score+3)); reasons="$reasons|HIỂN THỊ TRÊN ỨNG DỤ"; };
                      echo "$d" | grep -q "android.permission.RECEIVE_BOOT_COMPLETED" && { score=$((score+1)); reasons="$reasons|TỰ KHỞI ĐỘNG"; };
                      echo "$d" | grep -q "android.permission.REQUEST_INSTALL_PACKAGES" && { score=$((score+2)); reasons="$reasons|CÓ QUYỀN CÀI APK"; };
                      echo "$d" | grep -q "android.permission.PACKAGE_USAGE_STATS" && { score=$((score+1)); reasons="$reasons|THEO DÕI ỨNG DỤNG"; };
                      if [ $score -ge 3 ]; then echo "AS|$score|$p|$reasons"; fi;
                    done
                """.trimIndent().replace("\n", " ")
                    .replace("$", "${'$'}")
                val result = LocalAdbEngine.shell(command)
                val parsed = result.lineSequence().mapNotNull { line ->
                    if (!line.startsWith("AS|")) return@mapNotNull null
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    val score = parts[1].toIntOrNull() ?: return@mapNotNull null
                    SuspiciousApp(parts[2], score, parts[3].split("|").filter { it.isNotBlank() })
                }.distinctBy { it.packageName }.sortedByDescending { it.score }
                runOnUiThread {
                    apps.clear()
                    apps.addAll(parsed)
                    scanning = false
                    message = if (parsed.isEmpty()) {
                        "Không phát hiện ứng dụng bên thứ ba có dấu hiệu quảng cáo mạnh theo bộ lọc hiện tại."
                    } else {
                        "Phát hiện ${parsed.size} ứng dụng cần kiểm tra. Đây là danh sách NGHI VẤN, không phải kết luận virus."
                    }
                }
            }.start()
        }

        fun remove(app: SuspiciousApp) {
            confirmRemove = false
            Thread {
                val result = LocalAdbEngine.shell("pm uninstall --user 0 '${app.packageName.replace("'", "")}'")
                val ok = result.contains("Success", ignoreCase = true) && !result.contains("Failure", ignoreCase = true)
                runOnUiThread {
                    if (ok) {
                        apps.removeAll { it.packageName == app.packageName }
                        selected = null
                        message = "ĐÃ GỠ: ${app.packageName}."
                    } else {
                        message = "Không gỡ được ${app.packageName}. Nếu đây là app hệ thống, Android có thể không cho gỡ theo user 0."
                    }
                }
            }.start()
        }

        if (confirmRemove && selected != null) {
            val app = selected!!
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("XÁC NHẬN GỠ ỨNG DỤNG") },
                text = { Text("Apple Seed sẽ gỡ ${app.packageName} khỏi user hiện tại. Chỉ gỡ khi bạn xác định đây là ứng dụng gây quảng cáo.") },
                confirmButton = { Button(onClick = { remove(app) }) { Text("GỠ ỨNG DỤNG") } },
                dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text("HỦY") } }
            )
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = CleanerCyan, background = CleanerBg, surface = CleanerPanel, onSurface = CleanerText, error = CleanerDanger)) {
            Column(Modifier.fillMaxSize().background(CleanerBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APPLE SEED • DỌN QUẢNG CÁO", color = CleanerText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("Quét ứng dụng bên thứ ba có dấu hiệu dùng overlay / tự khởi động / cài APK để phát quảng cáo.", color = CleanerMuted, fontSize = 12.sp)
                Button(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "ĐANG QUÉT..." else "QUÉT MÁY TÌM ỨNG DỤNG GÂY QUẢNG CÁO") }
                Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), modifier = Modifier.fillMaxWidth()) { Text(message, color = if (apps.isNotEmpty()) CleanerWarn else CleanerText, modifier = Modifier.padding(14.dp)) }
                apps.forEach { app ->
                    Card(colors = CardDefaults.cardColors(containerColor = CleanerPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(app.packageName, color = CleanerText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Mức nghi vấn: ${app.score}/7", color = if (app.score >= 5) CleanerDanger else CleanerWarn, fontWeight = FontWeight.Bold)
                            Text(app.reasons.joinToString(" • "), color = CleanerMuted, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.parse("package:${app.packageName}") }) }, modifier = Modifier.weight(1f)) { Text("XEM ỨNG DỤNG") }
                                Button(onClick = { selected = app; confirmRemove = true }, modifier = Modifier.weight(1f)) { Text("GỠ") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Lưu ý: quyền SYSTEM_ALERT_WINDOW chỉ là dấu hiệu. Một số ứng dụng hợp lệ cũng dùng overlay. Apple Seed không tự gỡ app ngay khi quét.", color = CleanerMuted, fontSize = 10.sp)
            }
        }
    }
}
