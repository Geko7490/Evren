package com.vex.evren.code

import android.content.Intent
import java.io.File
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                                        onOpen = { startActivity(Intent(this@TaskManagerActivity, AgentLiveActivity::class.java).putExtra(AgentLiveActivity.EXTRA_TASK_ID, task.id)) },
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
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onShare: (File) -> Unit
) {
    val running = task.status == AgentRunService.STATUS_RUNNING
    Surface(Modifier.fillMaxWidth(), color = AgentPanel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.status, color = if (running) AgentPurple else AgentMuted, fontSize = 11.sp)
                Text(task.model, color = AgentMuted, fontSize = 11.sp)
            }
            Text(task.request, color = Color.White, fontSize = 14.sp)
            Text(task.progress.ifBlank { "Bekleniyor" }, color = AgentMuted, fontSize = 12.sp)
            if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = AgentPurple, trackColor = Color(0xFF383442))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Canlı izle / Görev ayrıntıları") }
            if (task.status == AgentRunService.STATUS_FAILED) task.error?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp) }
        }
    }
}
