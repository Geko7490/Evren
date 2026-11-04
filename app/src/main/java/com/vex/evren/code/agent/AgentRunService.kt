package com.vex.evren.code.agent

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vex.evren.code.CredentialVault
import com.vex.evren.code.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.IOException

class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AgentTaskDatabase.get(this).tasks() }
    private var runner: Job? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelRequested = false

    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("EVREN Agent", "Görev hazırlanıyor"))
        if (intent == null) {
            scope.launch { dao.latestRunning()?.let { execute(it) } ?: stopService() }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> scope.launch { val task = fromIntent(intent); dao.upsert(task); execute(task) }
            ACTION_RESUME -> scope.launch { dao.get(intent.getStringExtra(EXTRA_ID).orEmpty())?.let { execute(it) } ?: stopService() }
            ACTION_PAUSE -> { cancelRequested = false; runner?.cancel(CancellationException("pause")) }
            ACTION_CANCEL -> { cancelRequested = true; runner?.cancel(CancellationException("cancel")) }
            else -> scope.launch { dao.latestRunning()?.let { execute(it) } ?: stopService() }
        }
        return START_STICKY
    }

    private fun fromIntent(i: Intent): AgentTaskRecord {
        val now = System.currentTimeMillis()
        return AgentTaskRecord(i.getStringExtra(EXTRA_ID) ?: java.util.UUID.randomUUID().toString(),
            i.getStringExtra(EXTRA_MODEL).orEmpty(), i.getStringExtra(EXTRA_REQUEST).orEmpty(),
            i.getStringExtra(EXTRA_TARGETS).orEmpty(), i.getIntExtra(EXTRA_MAX_TOKENS,4096),
            i.getIntExtra(EXTRA_MAX_STEPS,12), i.getBooleanExtra(EXTRA_FULL_ACCESS,false),
            i.getIntExtra(EXTRA_CONTEXT_LENGTH,-1).takeIf { it > 0 }, STATUS_QUEUED,
            "Görev kuyruğa alındı", null, null, null, now, now)
    }

    private suspend fun execute(task: AgentTaskRecord) {
        if (runner?.isActive == true) return
        cancelRequested = false
        acquireCpuWakeLock()
        dao.setState(task.id, STATUS_RUNNING, "Agent arka planda çalışıyor")
        notifyProgress("Agent çalışıyor", task.request.take(80))
        runner = scope.launch {
            var checkpoint = task.checkpointJson
            try {
                val vault = CredentialVault(this@AgentRunService)
                val evren = EvrenApiClient({ vault.read("evren_api_key") }, { vault.read("evren_base_url") ?: EvrenApiClient.DEFAULT_BASE_URL })
                val github = GitHubApiClient { vault.read("github_pat") }
                val targets = parseTargets(task.targetsJson)
                var attempt = 0
                var result: AgentRunResult? = null
                while (isActive && result == null) {
                    try {
                        result = AgentEngine(evren, github).run(
                            model = task.model, request = task.request, targets = targets,
                            maxTokens = task.maxTokens, maxSteps = task.maxSteps,
                            fullAccess = task.fullAccess, contextLength = task.contextLength,
                            resumeCheckpoint = checkpoint,
                            onCheckpoint = { saved ->
                                checkpoint = saved
                                runBlocking(Dispatchers.IO) { dao.saveCheckpoint(task.id, "Checkpoint kaydedildi", saved) }
                            },
                            onProgress = { message ->
                                runBlocking(Dispatchers.IO) { dao.saveCheckpoint(task.id, message, checkpoint) }
                                notifyProgress("EVREN Agent", message)
                            }
                        )
                    } catch (e: IOException) {
                        val detail = e.message.orEmpty().lowercase()
                        val transientNetworkFailure = listOf(
                            "timeout", "timed out", "failed to connect", "unable to resolve host",
                            "network is unreachable", "connection reset", "unexpected end of stream",
                            "software caused connection abort"
                        ).any { detail.contains(it) }
                        if (!transientNetworkFailure) throw e
                        attempt++
                        if (attempt > 8) throw e
                        val delaySeconds = (5L shl (attempt - 1)).coerceAtMost(60L)
                        dao.saveCheckpoint(task.id, "Bağlantı bekleniyor · tekrar $attempt/8", checkpoint)
                        notifyProgress("Bağlantı bekleniyor", "İnternet gelince otomatik devam edecek")
                        while (isActive && !hasInternet()) delay(2500)
                        if (isActive) delay(delaySeconds * 1000)
                    }
                }
                result?.let { dao.finish(task.id, STATUS_COMPLETED, "Görev tamamlandı", it.answer, null, checkpoint) }
            } catch (e: CancellationException) {
                val status = if (cancelRequested) STATUS_CANCELLED else STATUS_PAUSED
                withContext(NonCancellable + Dispatchers.IO) {
                    dao.finish(task.id, status, if (cancelRequested) "İptal edildi" else "Duraklatıldı · checkpoint saklandı", null, null, checkpoint)
                }
                throw e
            } catch (e: Exception) {
                dao.finish(task.id, STATUS_FAILED, "Görev başarısız", null, e.message, checkpoint)
                notifyProgress("Agent hatası", e.message ?: "Bilinmeyen hata")
            } finally {
                runner = null
                val nextTask = withContext(NonCancellable + Dispatchers.IO) { dao.nextQueued() }
                if (nextTask != null && !cancelRequested) {
                    scope.launch { execute(nextTask) }
                } else {
                    releaseCpuWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun parseTargets(raw: String): List<AgentTarget> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { n ->
            val j = a.getJSONObject(n)
            val repo = GitHubRepository(j.optLong("id"), j.getString("fullName"),
                j.optString("description").takeIf { it.isNotBlank() && it != "null" },
                j.optString("defaultBranch","main"), j.optBoolean("isPrivate"),
                j.optString("htmlUrl"), j.optString("language").takeIf { it != "null" })
            AgentTarget(repo, j.getString("branch"))
        }
    }

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EVREN:Agent").apply {
            setReferenceCounted(false)
            acquire()
        }
    }
    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "EVREN Agent", NotificationManager.IMPORTANCE_LOW))
    }
    private fun buildNotification(title: String, text: String): Notification {
        val pause = PendingIntent.getService(this, 31, Intent(this, AgentRunService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 32, Intent(this, AgentRunService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Duraklat", pause)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", cancel).build()
    }
    private fun notifyProgress(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title,text))
    }
    private suspend fun stopService() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { runner?.cancel(); releaseCpuWakeLock(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.vex.evren.agent.START"
        const val ACTION_RESUME = "com.vex.evren.agent.RESUME"
        const val ACTION_PAUSE = "com.vex.evren.agent.PAUSE"
        const val ACTION_CANCEL = "com.vex.evren.agent.CANCEL"
        const val EXTRA_ID="task_id"; const val EXTRA_MODEL="model"; const val EXTRA_REQUEST="request"
        const val EXTRA_TARGETS="targets"; const val EXTRA_MAX_TOKENS="max_tokens"; const val EXTRA_MAX_STEPS="max_steps"
        const val EXTRA_FULL_ACCESS="full_access"; const val EXTRA_CONTEXT_LENGTH="context_length"
        const val STATUS_QUEUED="QUEUED"; const val STATUS_RUNNING="RUNNING"; const val STATUS_PAUSED="PAUSED"
        const val STATUS_COMPLETED="COMPLETED"; const val STATUS_FAILED="FAILED"; const val STATUS_CANCELLED="CANCELLED"
        private const val CHANNEL="evren_agent"; private const val NOTIFICATION_ID=7412
    }
}