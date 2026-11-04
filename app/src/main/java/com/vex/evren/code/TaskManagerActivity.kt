package com.vex.evren.code

import android.content.Intent
import java.io.File
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vex.evren.code.agent.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val AgentBackground = Color(0xFF0F0F13)
private val AgentPanel = Color(0xFF1C1B23)
private val AgentPurple = Color(0xFFA855F7)
private val AgentMuted = Color(0xFFA1A1AA)

class TaskManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AgentTaskDatabase.get(this).tasks()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = AgentPurple, background = AgentBackground, surface = AgentPanel)) {
                val tasks by dao.observeAll().collectAsState(initial = emptyList())
                val activeCount = tasks.count { it.status == AgentRunService.STATUS_RUNNING || it.status == AgentRunService.STATUS_QUEUED }
                Surface(Modifier.fillMaxSize(), color = AgentBackground) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("EVREN Agent", color = Color.White, fontSize = 23.sp)
                        Text("Görevlerin ne yaptığını canlı takip et", color = AgentMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryTile("Toplam görev", tasks.size.toString(), Modifier.weight(1f))
                            SummaryTile("Aktif / sırada", activeCount.toString(), Modifier.weight(1f))
                            SummaryTile("Tamamlanan", tasks.count { it.status == AgentRunService.STATUS_COMPLETED }.toString(), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        if (tasks.isEmpty()) {
                            Surface(Modifier.fillMaxWidth(), color = AgentPanel, shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Henüz görev yok", color = Color.White)
                                    Text("Bir Agent görevi başlattığında ilerleme ve yaptığı işlemler burada görünecek.", color = AgentMuted, fontSize = 12.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(tasks, key = { it.id }) { task ->
                                    AgentTaskCard(
                                        task = task,
                                        onPause = {
                                            startService(Intent(this@TaskManagerActivity, AgentRunService::class.java).setAction(AgentRunService.ACTION_PAUSE))
                                        },
                                        onCancel = {
                                            startService(Intent(this@TaskManagerActivity, AgentRunService::class.java).setAction(AgentRunService.ACTION_CANCEL))
                                        },
                                        onResume = {
                                            ContextCompat.startForegroundService(
                                                this@TaskManagerActivity,
                                                Intent(this@TaskManagerActivity, AgentRunService::class.java)
                                                    .setAction(AgentRunService.ACTION_RESUME)
                                                    .putExtra(AgentRunService.EXTRA_ID, task.id)
                                            )
                                        },
                                        onShare = { file ->
                                            val uri = FileProvider.getUriForFile(
                                                this@TaskManagerActivity,
                                                "${packageName}.fileprovider",
                                                file
                                            )
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = if (file.extension.equals("apk", true)) "application/vnd.android.package-archive" else "application/zip"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            startActivity(Intent.createChooser(share, "Artifact paylaş"))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = AgentPanel, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(value, color = Color.White, fontSize = 20.sp)
            Text(label, color = AgentMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AgentTaskCard(
    task: AgentTaskRecord,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onShare: (File) -> Unit
) {
    val isRunning = task.status == AgentRunService.STATUS_RUNNING
    val isQueued = task.status == AgentRunService.STATUS_QUEUED
    val stepMatch = Regex("·\\s*(\\d+)\\s*/\\s*(\\d+)").find(task.progress)
    val currentStep = stepMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val totalSteps = stepMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
    val fraction = if (currentStep != null && totalSteps != null && totalSteps > 0) {
        (currentStep.toFloat() / totalSteps).coerceIn(0f, 1f)
    } else null
    val statusLabel = when (task.status) {
        AgentRunService.STATUS_RUNNING -> "ÇALIŞIYOR"
        AgentRunService.STATUS_QUEUED -> "SIRADA"
        AgentRunService.STATUS_PAUSED -> "DURAKLATILDI"
        AgentRunService.STATUS_COMPLETED -> "TAMAMLANDI"
        AgentRunService.STATUS_FAILED -> "HATA"
        AgentRunService.STATUS_CANCELLED -> "İPTAL EDİLDİ"
        else -> task.status
    }
    val statusColor = when (task.status) {
        AgentRunService.STATUS_COMPLETED -> Color(0xFF4ADE80)
        AgentRunService.STATUS_FAILED -> Color(0xFFF87171)
        AgentRunService.STATUS_PAUSED -> Color(0xFFFBBF24)
        AgentRunService.STATUS_RUNNING -> AgentPurple
        else -> AgentMuted
    }

    Surface(Modifier.fillMaxWidth(), color = AgentPanel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(statusLabel, color = statusColor, fontSize = 11.sp)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(task.updatedAt)), color = AgentMuted, fontSize = 10.sp)
            }
            Text(task.request, color = Color.White, fontSize = 14.sp)
            if (isRunning || isQueued || task.status == AgentRunService.STATUS_PAUSED) {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = AgentPurple,
                        trackColor = Color(0xFF383442)
                    )
                    Text("Agent adımı: ${currentStep}/${totalSteps} · Bu, görevin tamamlanma yüzdesi değildir.", color = AgentMuted, fontSize = 10.sp)
                } else if (isRunning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = AgentPurple,
                        trackColor = Color(0xFF383442)
                    )
                    Text("Canlı işlem sürüyor · Kesin yüzde hesaplanamıyor", color = AgentMuted, fontSize = 10.sp)
                }
            }
            Text("ŞU ANDA NE YAPIYOR?", color = AgentMuted, fontSize = 10.sp)
            Text(task.progress.ifBlank { "Görev durumu bekleniyor" }, color = Color(0xFFE4E4E7), fontSize = 13.sp)
            if (task.status == AgentRunService.STATUS_FAILED) {
                task.error?.let { Text("Hata: $it", color = Color(0xFFFCA5A5), fontSize = 12.sp) }
            }
            task.answer?.takeIf { it.isNotBlank() }?.let { answer ->
                Divider(color = Color(0xFF34313D))
                Text("SONUÇ", color = AgentMuted, fontSize = 10.sp)
                Text(answer, color = Color(0xFFD4D4D8), fontSize = 12.sp)
                val artifactPath = Regex("Dosya: (.+?) ·").find(answer)?.groupValues?.getOrNull(1)
                val artifactFile = artifactPath?.let(::File)
                if (artifactFile?.isFile == true) {
                    TextButton(onClick = { onShare(artifactFile) }) { Text("APK / Artifact paylaş") }
                }
            }
            val recentLogs = recentAgentLogs(task.checkpointJson)
            if (recentLogs.isNotEmpty()) {
                Divider(color = Color(0xFF34313D))
                Text("SON İŞLEMLER", color = AgentMuted, fontSize = 10.sp)
                recentLogs.forEach { line ->
                    Text("• $line", color = Color(0xFFBDB8C8), fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    TextButton(onClick = onPause) { Text("Duraklat") }
                    TextButton(onClick = onCancel) { Text("İptal et") }
                }
                if (task.status == AgentRunService.STATUS_PAUSED || task.status == AgentRunService.STATUS_FAILED) {
                    TextButton(onClick = onResume) { Text("Devam et") }
                }
            }
        }
    }
}

private fun recentAgentLogs(checkpoint: String?): List<String> = runCatching {
    val logs = JSONObject(checkpoint ?: return emptyList()).optJSONArray("logs") ?: return emptyList()
    (maxOf(0, logs.length() - 3) until logs.length()).mapNotNull { index ->
        logs.optString(index).takeIf { it.isNotBlank() }
    }
}.getOrDefault(emptyList())
