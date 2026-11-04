package com.vex.evren.code

import android.content.Intent
import android.net.Uri
import java.io.File
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vex.evren.code.agent.*

class TaskManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AgentTaskDatabase.get(this).tasks()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary=Color(0xFFA855F7), background=Color(0xFF0F0F13), surface=Color(0xFF1C1B23))) {
                val tasks by dao.observeAll().collectAsState(initial= emptyList())
                Surface(Modifier.fillMaxSize(), color=Color(0xFF0F0F13)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Agent Task Manager", color=Color.White, fontSize=21.sp)
                        Text("Görevler cihazda saklanır. Android'in pil ve servis sınırları geçerlidir.", color=Color.LightGray, fontSize=11.sp)
                        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(tasks, key={it.id}) { task ->
                                Column(Modifier.fillMaxWidth().background(Color(0xFF1C1B23)).padding(12.dp)) {
                                    Text(task.request, color=Color.White, fontSize=13.sp)
                                    Text("${task.status} · ${task.progress}", color=Color(0xFFA855F7), fontSize=11.sp)
                                    task.error?.let { Text(it, color=Color(0xFFFCA5A5), fontSize=11.sp) }
                                    task.answer?.takeIf { it.isNotBlank() }?.let { answer ->
                                        Text(answer, color=Color(0xFFD4D4D8), fontSize=11.sp)
                                        val artifactPath = Regex("Dosya: (.+?) ·").find(answer)?.groupValues?.getOrNull(1)
                                        val artifactFile = artifactPath?.let(::File)
                                        if (artifactFile?.isFile == true) {
                                            TextButton(onClick = {
                                                val uri = FileProvider.getUriForFile(this@TaskManagerActivity,
                                                    "${BuildConfig.APPLICATION_ID}.fileprovider", artifactFile)
                                                val share = Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/zip"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    clipData = android.content.ClipData.newUri(contentResolver, artifactFile.name, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                startActivity(Intent.createChooser(share, "Artifact paylaş"))
                                            }) { Text("APK / Artifact paylaş") }
                                        }
                                    }
                                    Row {
                                        if(task.status==AgentRunService.STATUS_PAUSED || task.status==AgentRunService.STATUS_FAILED) {
                                            TextButton(onClick={
                                                ContextCompat.startForegroundService(this@TaskManagerActivity,
                                                    Intent(this@TaskManagerActivity,AgentRunService::class.java).setAction(AgentRunService.ACTION_RESUME).putExtra(AgentRunService.EXTRA_ID,task.id))
                                            }) { Text("Devam et") }
                                        }
                                        if(task.status==AgentRunService.STATUS_RUNNING) {
                                            TextButton(onClick={ startService(Intent(this@TaskManagerActivity,AgentRunService::class.java).setAction(AgentRunService.ACTION_PAUSE)) }) { Text("Duraklat") }
                                        }
                                        if(task.status==AgentRunService.STATUS_RUNNING || task.status==AgentRunService.STATUS_PAUSED) {
                                            TextButton(onClick={ startService(Intent(this@TaskManagerActivity,AgentRunService::class.java).setAction(AgentRunService.ACTION_CANCEL)) }) { Text("İptal") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}