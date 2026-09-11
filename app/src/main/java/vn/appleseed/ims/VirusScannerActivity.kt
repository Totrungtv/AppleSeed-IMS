package vn.appleseed.ims

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import java.security.MessageDigest

private val VirusBg = Color(0xFF05070A)
private val VirusPanel = Color(0xFF0D1218)
private val VirusText = Color(0xFFF5F7FA)
private val VirusMuted = Color(0xFF9AA7B5)
private val VirusCyan = Color(0xFF46E6FF)
private val VirusGood = Color(0xFF42E6A4)
private val VirusWarn = Color(0xFFFFC857)
private val VirusDanger = Color(0xFFFF667A)

class VirusScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirusScannerScreen() }
    }

    @Composable
    private fun VirusScannerScreen() {
        var scanning by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("Chưa chọn file. Hãy chọn một file để Apple Seed phân tích.") }
        var fileName by remember { mutableStateOf("") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                result = "Đã hủy chọn file."
                return@rememberLauncherForActivityResult
            }
            scanning = true
            result = "Đang đọc và phân tích file..."
            Thread {
                val analysis = analyzeFile(uri)
                runOnUiThread {
                    fileName = analysis.name
                    result = analysis.message
                    scanning = false
                }
            }.start()
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = VirusCyan, background = VirusBg, surface = VirusPanel, onSurface = VirusText, error = VirusDanger)) {
            Column(Modifier.fillMaxSize().background(VirusBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APPLE SEED • QUÉT VIRUS FILE", color = VirusText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("CHỨC NĂNG ĐỘC LẬP • KHÔNG CẦN WIRELESS ADB", color = VirusCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Chọn APK, ZIP hoặc file bất kỳ. Apple Seed đọc file cục bộ, tính SHA-256 và kiểm tra các dấu hiệu cơ bản. Không xóa file tự động.", color = VirusMuted, fontSize = 11.sp)

                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanning) "ĐANG QUÉT FILE..." else "📁 CHỌN FILE ĐỂ QUÉT VIRUS")
                }

                if (fileName.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(fileName, color = VirusText, fontWeight = FontWeight.Bold)
                            Text(result, color = if (result.contains("NGUY HIỂM")) VirusDanger else if (result.contains("NGHI VẤN")) VirusWarn else VirusGood, fontSize = 11.sp)
                        }
                    }
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                        Text(result, color = VirusMuted, modifier = Modifier.padding(14.dp), fontSize = 11.sp)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text("ĐÓNG") }
                }
            }
        }
    }

    private data class Analysis(val name: String, val message: String)

    private fun analyzeFile(uri: Uri): Analysis {
        val name = contentName(uri)
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    total += n
                    if (total > 200L * 1024L * 1024L) throw IllegalArgumentException("File quá lớn, giới hạn 200 MB.")
                }
            } ?: throw IllegalStateException("Không đọc được file.")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val lower = name.lowercase()
            val apk = lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apks")
            val suspiciousName = listOf("crack", "hack", "mod", "keygen", "cheat", "inject", "trojan", "malware", "virus").any { lower.contains(it) }
            val flags = buildList {
                if (apk) add("APK")
                if (suspiciousName) add("tên file đáng ngờ")
            }
            val verdict = if (suspiciousName) "NGUY HIỂM / CẦN KIỂM TRA KỸ" else if (apk) "NGHI VẤN THẤP — APK CẦN ĐỐI CHIẾU NGUỒN" else "CHƯA PHÁT HIỆN DẤU HIỆU ĐÁNG NGỜ CƠ BẢN"
            Analysis(name, "KẾT QUẢ: $verdict\nDung lượng: $total bytes\nSHA-256: $sha256\nDấu hiệu: ${flags.joinToString(", ").ifBlank { "không có" }}")
        }.getOrElse { Analysis(name, "QUÉT THẤT BẠI: ${it.message ?: "Không xác định được lỗi."}") }
    }

    private fun contentName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "file"
        } ?: uri.lastPathSegment ?: "file"
    }.getOrDefault("file")
}
