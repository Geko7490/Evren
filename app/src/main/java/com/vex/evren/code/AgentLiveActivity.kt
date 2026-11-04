package com.vex.evren.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vex.evren.code.agent.*
import org.json.JSONObject

private val background = Color(0xFF101014)
private val panel = Color(0xFF20202A)
private val purple = Color(0xFFA855F7)
private val muted = Color(0xFFA1A1AA)

class AgentLiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val dao = AgentTaskDatabase.get(this).tasks()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = background, surface = panel)) {
                val tasks by dao.observeAll().collectAsState(initial = emptyList())
                val task = tasks.firstOrNull { it.id == taskId }
                LiveScreen(
                    task = task,
                    onBack = { finish() },
                    onPause = { startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.ACTION_PAUSE)) },
                    onCancel = { startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.ACTION_CANCEL)) },
                    onResume = {
                        ContextCompat.startForegroundService(this, Intent(this, AgentRunService::class.java)
                            .setAction(AgentRunService.ACTION_RESUME).putExtra(AgentRunService.EXTRA_ID, taskId))
                    }
                )
            }
        }
    }

    companion object { const val EXTRA_TASK_ID = "agent_live_task_id" }
}

@Composable
private fun LiveScreen(
    task: AgentTaskRecord?,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("← Görevler") }
                Text("Agent Live", color = Color.White, fontSize = 21.sp)
            }
            if (task == null) {
                Text("Görev bulunamadı veya yükleniyor.", color = muted)
            } else {
                val running = task.status == AgentRunService.STATUS_RUNNING
                val steps = Regex("·\\s*(\\d+)\\s*/\\s*(\\d+)").find(task.progress)
                val current = steps?.groupValues?.getOrNull(1)?.toIntOrNull()
                val total = steps?.groupValues?.getOrNull(2)?.toIntOrNull()
                Surface(color = panel, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.status, color = if (running) purple else muted, fontSize = 12.sp)
                        Text(task.request, color = Color.White, fontSize = 16.sp)
                        Text("ŞU ANDA NE YAPIYOR?", color = muted, fontSize = 10.sp)
                        Text(task.progress.ifBlank { "Görev bekleniyor" }, color = Color.White, fontSize = 13.sp)
                        if (running || task.status == AgentRunService.STATUS_PAUSED) {
                            if (current != null && total != null && total > 0) {
                                LinearProgressIndicator(progress = { (current.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = purple)
                                Text("Agent adımı $current/$total · Tamamlanma yüzdesi değildir", color = muted, fontSize = 11.sp)
                            } else if (running) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = purple)
                                Text("Çalışıyor · Kesin ilerleme yüzdesi bilinmiyor", color = muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = onPause) { Text("Duraklat") }
                        OutlinedButton(onClick = onCancel) { Text("İptal et") }
                    }
                    if (task.status == AgentRunService.STATUS_PAUSED || task.status == AgentRunService.STATUS_FAILED) {
                        Button(onClick = onResume) { Text("Devam et") }
                    }
                }
                Text("İşlem geçmişi", color = Color.White, fontSize = 16.sp)
                val logs = remember(task.checkpointJson) {
                    runCatching {
                        val array = JSONObject(task.checkpointJson ?: "{}").optJSONArray("logs")
                        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                    }.getOrDefault(emptyList())
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (logs.isEmpty()) {
                        item { Text("Henüz kaydedilmiş işlem yok. Yeni kayıtlar burada görünecek.", color = muted, fontSize = 12.sp) }
                    }
                    itemsIndexed(logs) { index, entry ->
                        Surface(color = panel, shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${index + 1}.", color = purple, fontSize = 12.sp)
                                Text(entry, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    if (!task.error.isNullOrBlank()) item {
                        Text("Hata: ${task.error}", color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                    if (!task.answer.isNullOrBlank()) item {
                        Text("SONUÇ", color = muted, fontSize = 11.sp)
                        Text(task.answer, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
