package com.vex.evren.code

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.vex.evren.code.agent.AgentEngine
import com.vex.evren.code.agent.AgentRunService
import com.vex.evren.code.agent.AgentTarget
import com.vex.evren.code.agent.AgentRunResult
import com.vex.evren.code.agent.ProposedChange
import com.vex.evren.code.data.EvrenApiClient
import com.vex.evren.code.data.EvrenModel
import com.vex.evren.code.data.EvrenModelDetails
import com.vex.evren.code.data.GitHubApiClient
import com.vex.evren.code.data.GitHubFile
import com.vex.evren.code.data.GitHubRepository
import com.vex.evren.code.data.GitHubTreeEntry
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val VexPurple = Color(0xFFA855F7)
private val VexBackground = Color(0xFF0F0F13)
private val VexSurface = Color(0xFF1C1B23)
private val VexMuted = Color(0xFFA1A1AA)
private val VexText = Color(0xFFF5F5F7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = CredentialVault(this)
        val evren = EvrenApiClient(
            apiKey = { vault.read("evren_api_key") },
            baseUrl = { vault.read("evren_base_url") ?: EvrenApiClient.DEFAULT_BASE_URL }
        )
        val github = GitHubApiClient(token = { vault.read("github_pat") })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = VexPurple, background = VexBackground, surface = VexSurface,
                onBackground = VexText, onSurface = VexText
            )) {
                Surface(Modifier.fillMaxSize(), color = VexBackground) {
                    VexWorkspace(vault, evren, github)
                }
            }
        }
    }
}

private enum class WorkspaceTab(val label: String) {
    CONNECT("Connect"), REPOS("Repos"), FILES("Files"), AGENT("Agent"), CHANGES("Diff")
}

@Composable
private fun VexWorkspace(vault: CredentialVault, evren: EvrenApiClient, github: GitHubApiClient) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current
    var tab by remember { mutableStateOf(WorkspaceTab.CONNECT) }
    var evrenKey by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf(EvrenApiClient.DEFAULT_BASE_URL) }
    var connectionMessage by remember { mutableStateOf("Connect EVREN and GitHub to start.") }
    var busy by remember { mutableStateOf(false) }
    var evrenConnected by remember { mutableStateOf(false) }
    var githubConnected by remember { mutableStateOf(false) }
    var githubLogin by remember { mutableStateOf("") }
    val models = remember { mutableStateListOf<EvrenModel>() }
    var selectedModel by remember { mutableStateOf("") }
    val repositories = remember { mutableStateListOf<GitHubRepository>() }
    var selectedRepository by remember { mutableStateOf<GitHubRepository?>(null) }
    var selectedSecondaryRepository by remember { mutableStateOf<GitHubRepository?>(null) }
    var secondaryWorkingBranch by remember { mutableStateOf("") }
    var secondaryBranchRepository by remember { mutableStateOf("") }
    var agentMaxPower by remember { mutableStateOf(false) }
    var agentAutopilot by remember { mutableStateOf(false) }
    var agentFullAccess by remember { mutableStateOf(false) }
    var selectedBranch by remember { mutableStateOf("") }
    var workingBranch by remember { mutableStateOf("") }
    val tree = remember { mutableStateListOf<GitHubTreeEntry>() }
    var fileContent by remember { mutableStateOf<GitHubFile?>(null) }
    var fileLoading by remember { mutableStateOf(false) }
    var fileFilter by remember { mutableStateOf("") }
    var taskInput by remember { mutableStateOf("") }
    var agentStatus by remember { mutableStateOf("Agent is idle.") }
    var agentResult by remember { mutableStateOf<AgentRunResult?>(null) }
    val proposals = remember { mutableStateListOf<ProposedChange>() }
    var activeChange by remember { mutableStateOf<ProposedChange?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelDetails by remember { mutableStateOf<EvrenModelDetails?>(null) }
    var termsDialogVisible by remember { mutableStateOf(false) }
    var termsVersion by remember { mutableStateOf(-1) }
    var termsContent by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var termsStatusMessage by remember { mutableStateOf("Terms status has not been checked.") }

    suspend fun finishServiceConnection() {
        val user = github.validateToken()
        githubLogin = user.login
        githubConnected = true
        connectionMessage = "Connected as ${githubLogin} · ${models.size} EVREN models found."
        val repos = github.listAccessibleRepositories()
        repositories.clear()
        repositories.addAll(repos)
        tab = WorkspaceTab.REPOS
    }

    LaunchedEffect(Unit) {
        evrenKey = vault.read("evren_api_key").orEmpty()
        githubToken = vault.read("github_pat").orEmpty()
        val savedApiUrl = vault.read("evren_base_url")
        apiUrl = if (savedApiUrl.isNullOrBlank() || savedApiUrl == "https://evren.ssyz.org.tr/v1") {
            vault.save("evren_base_url", EvrenApiClient.DEFAULT_BASE_URL)
            EvrenApiClient.DEFAULT_BASE_URL
        } else savedApiUrl
        if (evrenKey.isNotBlank() || githubToken.isNotBlank()) connectionMessage = "Saved credentials found. Validate connections to continue."
    }

    Column(Modifier.fillMaxSize().background(VexBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VEX", color = VexPurple, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("EVREN CODE", color = VexText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
                Text(
                    (if (evrenConnected) "EVREN online" else "EVREN offline") + " · " +
                        (if (githubConnected) "GitHub connected" else "GitHub offline"),
                    color = VexMuted, fontSize = 11.sp
                )
            }
            if (models.isNotEmpty()) {
                Column {
                    TextButton(onClick = { modelMenu = true }) {
                        Text(selectedModel.ifBlank { "Select model" }, color = VexPurple, maxLines = 1)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    selectedModel = model.id
                                    modelDetails = null
                                    modelMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        TextButton(onClick = { appContext.startActivity(Intent(appContext, TaskManagerActivity::class.java)) }) {
            Text("Agent Tasks", color = VexPurple)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            WorkspaceTab.entries.forEach { item ->
                TextButton(onClick = { tab = item }, modifier = Modifier.weight(1f)) {
                    Text(item.label, color = if (tab == item) VexPurple else VexMuted, fontSize = 11.sp,
                        fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        errorMessage?.let { message ->
            Surface(
                color = Color(0xFF3A2028),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, color = Color(0xFFFCA5A5), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { errorMessage = null }) { Text("Kapat", color = VexText) }
                }
            }
        }

        when (tab) {
            WorkspaceTab.CONNECT -> ConnectionPanel(
                evrenKey, { evrenKey = it }, githubToken, { githubToken = it },
                apiUrl, { apiUrl = it }, connectionMessage, termsStatusMessage, termsAccepted, busy,
                onSaveAndValidate = {
                    scope.launch {
                        busy = true
                        errorMessage = null
                        try {
                            vault.save("evren_api_key", evrenKey.trim())
                            vault.save("github_pat", githubToken.trim())
                            vault.save("evren_base_url", apiUrl.trim())
                            val discovered = evren.listModels()
                            models.clear()
                            models.addAll(discovered)
                            if (selectedModel !in discovered.map { it.id }) {
                                selectedModel = discovered.first().id
                            }
                            evrenConnected = true
                            val termsStatus = evren.getTermsStatus()
                            termsAccepted = termsStatus.optBoolean("accepted", false)
                            val currentVersion = termsStatus.optInt("current_version", -1)
                            termsStatusMessage = if (termsAccepted) {
                                "EVREN terms accepted (version ${termsStatus.optInt("accepted_version", currentVersion)})."
                            } else {
                                "Action required: read and accept EVREN terms before using the API."
                            }
                            if (termsAccepted) {
                                finishServiceConnection()
                            } else {
                                val (documentVersion, content) = evren.getTermsText()
                                termsVersion = if (currentVersion >= 0) currentVersion else documentVersion
                                termsContent = content
                                termsDialogVisible = true
                                connectionMessage = "Review the EVREN LLM API terms and explicitly accept them."
                            }
                        } catch (e: Exception) {
                            connectionMessage = e.message ?: "Connection validation failed."
                            errorMessage = connectionMessage
                        } finally { busy = false }
                    }
                }
            )
            WorkspaceTab.REPOS -> RepositoryPanel(
                repositories = repositories,
                selected = selectedRepository,
                busy = busy,
                onRefresh = {
                    scope.launch {
                        busy = true
                        try {
                            repositories.clear()
                            repositories.addAll(github.listAccessibleRepositories())
                        } catch (e: Exception) { errorMessage = e.message }
                        finally { busy = false }
                    }
                },
                onSelect = { repo ->
                    scope.launch {
                        selectedRepository = repo
                        selectedBranch = repo.defaultBranch
                        workingBranch = repo.defaultBranch
                        fileContent = null
                        busy = true
                        try {
                            tree.clear()
                            tree.addAll(github.getTree(repo.fullName, repo.defaultBranch))
                            tab = WorkspaceTab.FILES
                        } catch (e: Exception) { errorMessage = e.message }
                        finally { busy = false }
                    }
                }
            )
            WorkspaceTab.FILES -> FileBrowserPanel(
                repository = selectedRepository,
                branch = selectedBranch,
                entries = tree,
                filter = fileFilter,
                onFilter = { fileFilter = it },
                file = fileContent,
                loading = fileLoading,
                onOpen = { entry ->
                    if (entry.type == "blob") {
                        scope.launch {
                            fileLoading = true
                            try { fileContent = github.readFile(selectedRepository!!.fullName, entry.path, selectedBranch) }
                            catch (e: Exception) { errorMessage = e.message }
                            finally { fileLoading = false }
                        }
                    } else fileFilter = entry.path
                },
                onBack = { fileContent = null; fileFilter = "" },
                onCreateBranch = {
                    scope.launch {
                        val repo = selectedRepository ?: return@launch
                        busy = true
                        try {
                            val name = "vex-agent-${System.currentTimeMillis()}"
                            github.createBranch(repo.fullName, name, repo.defaultBranch)
                            workingBranch = name
                            selectedBranch = name
                            tree.clear()
                            tree.addAll(github.getTree(repo.fullName, name))
                            connectionMessage = "Working branch created: $name"
                        } catch (e: Exception) { errorMessage = e.message }
                        finally { busy = false }
                    }
                },
                workingBranch = workingBranch,
                defaultBranch = selectedRepository?.defaultBranch.orEmpty(),
                busy = busy
            )
            WorkspaceTab.AGENT -> AgentPanel(
                repository = selectedRepository,
                availableRepositories = repositories,
                secondaryRepository = selectedSecondaryRepository,
                onSecondaryRepository = { repo ->
                    selectedSecondaryRepository = repo
                    if (repo?.fullName != secondaryBranchRepository) {
                        secondaryWorkingBranch = ""
                        secondaryBranchRepository = repo?.fullName.orEmpty()
                    }
                },
                branch = workingBranch,
                model = selectedModel,
                modelDetails = modelDetails,
                termsAccepted = termsAccepted,
                evren = evren,
                maxPower = agentMaxPower,
                onMaxPower = { agentMaxPower = it },
                autopilot = agentAutopilot,
                onAutopilot = { agentAutopilot = it },
                fullAccess = agentFullAccess,
                onFullAccess = { agentFullAccess = it },
                input = taskInput,
                onInput = { taskInput = it },
                status = agentStatus,
                result = agentResult,
                busy = busy,
                onRun = { planningContext ->
                    val repo = selectedRepository
                    when {
                        !termsAccepted -> errorMessage = "EVREN şartlarını kabul etmeden Agent çalıştırılamaz."
                        repo == null -> errorMessage = "Önce bir repo seçin."
                        selectedModel.isBlank() -> errorMessage = "EVREN'e bağlanıp model seçin."
                        taskInput.isBlank() -> errorMessage = "Önce yapılacak görevi yazın."
                        else -> scope.launch {
                            busy = true
                            errorMessage = null
                            agentResult = null
                            proposals.clear()
                            try {
                                var activeBranch = workingBranch
                                if (activeBranch.isBlank() || activeBranch == repo.defaultBranch) {
                                    agentStatus = "Güvenli çalışma dalı oluşturuluyor"
                                    val newBranch = "vex-agent-${System.currentTimeMillis()}"
                                    github.createBranch(repo.fullName, newBranch, repo.defaultBranch)
                                    activeBranch = newBranch
                                    workingBranch = newBranch
                                    selectedBranch = newBranch
                                    tree.clear()
                                    tree.addAll(github.getTree(repo.fullName, newBranch))
                                }

                                agentStatus = "Model yetenekleri kontrol ediliyor"
                                val details = modelDetails ?: if (selectedModel == "auto") null else evren.getModelDetails(selectedModel)
                                agentStatus = "Repo erişimi ve çalışma dalları kontrol ediliyor"
                                if (details?.tools == false) {
                                    throw IllegalStateException("Seçilen EVREN modeli Tool Calling desteklemiyor.")
                                }
                                val tokenBudget = if (agentMaxPower) {
                                    details?.defaultMaxOutputTokens?.coerceAtLeast(1) ?: 4096
                                } else {
                                    details?.recommendedAgentBudget() ?: 4096
                                }
                                val targets = mutableListOf(AgentTarget(repo, activeBranch))
                                selectedSecondaryRepository?.let { secondRepo ->
                                    if (secondaryWorkingBranch.isBlank() || secondaryBranchRepository != secondRepo.fullName) {
                                        agentStatus = "İkinci repo için güvenli çalışma dalı oluşturuluyor"
                                        val newBranch = "vex-agent-${System.currentTimeMillis()}-secondary"
                                        github.createBranch(secondRepo.fullName, newBranch, secondRepo.defaultBranch)
                                        secondaryWorkingBranch = newBranch
                                        secondaryBranchRepository = secondRepo.fullName
                                    }
                                    targets += AgentTarget(secondRepo, secondaryWorkingBranch)
                                }
                                val fullRequest = buildString {
                                    append(taskInput.trim())
                                    if (planningContext.isNotBlank()) {
                                        appendLine()
                                        appendLine()
                                        appendLine("Planning conversation and agreed decisions:")
                                        append(planningContext)
                                    }
                                }
                                val targetsJson = JSONArray().apply {
                                    targets.forEach { target ->
                                        put(JSONObject().put("id", target.repository.id)
                                            .put("fullName", target.repository.fullName)
                                            .put("description", target.repository.description ?: "")
                                            .put("defaultBranch", target.repository.defaultBranch)
                                            .put("isPrivate", target.repository.isPrivate)
                                            .put("htmlUrl", target.repository.htmlUrl)
                                            .put("language", target.repository.language ?: "")
                                            .put("branch", target.branch))
                                    }
                                }
                                val taskId = UUID.randomUUID().toString()
                                val serviceIntent = Intent(appContext, AgentRunService::class.java).apply {
                                    action = AgentRunService.ACTION_START
                                    putExtra(AgentRunService.EXTRA_ID, taskId)
                                    putExtra(AgentRunService.EXTRA_MODEL, selectedModel)
                                    putExtra(AgentRunService.EXTRA_REQUEST, fullRequest)
                                    putExtra(AgentRunService.EXTRA_TARGETS, targetsJson.toString())
                                    putExtra(AgentRunService.EXTRA_MAX_TOKENS, tokenBudget)
                                    putExtra(AgentRunService.EXTRA_MAX_STEPS, if (agentAutopilot) 30 else 12)
                                    putExtra(AgentRunService.EXTRA_FULL_ACCESS, agentFullAccess)
                                    putExtra(AgentRunService.EXTRA_CONTEXT_LENGTH, details?.contextLength ?: -1)
                                }
                                appContext.startForegroundService(serviceIntent)
                                agentStatus = "Arka plan görevi başlatıldı · $taskId"
                                agentStatus = e.message ?: "Agent başarısız."
                                errorMessage = agentStatus
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                onViewChanges = { tab = WorkspaceTab.CHANGES }
            )
            WorkspaceTab.CHANGES -> ChangesPanel(
                repository = selectedRepository,
                branch = workingBranch,
                proposals = proposals,
                active = activeChange,
                onSelect = { activeChange = it },
                onApply = { change ->
                    scope.launch {
                        val repo = repositories.firstOrNull { it.fullName == change.repositoryFullName }
                            ?: return@launch
                        busy = true
                        try {
                            github.updateFile(
                                repo.fullName, change.path, change.after,
                                "agent: update ${change.path.substringAfterLast('/')}",
                                change.branch, change.originalSha
                            )
                            proposals.remove(change)
                            if (activeChange == change) activeChange = null
                            connectionMessage = "Applied ${change.path} to ${change.branch}"
                        } catch (e: Exception) { errorMessage = e.message }
                        finally { busy = false }
                    }
                },
                busy = busy
            )
        }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Working…", color = VexMuted, fontSize = 12.sp)
            }
        }
        LaunchedEffect(selectedModel, evrenConnected) {
            modelDetails = null
            if (evrenConnected && selectedModel.isNotBlank() && selectedModel != "auto") {
                runCatching { evren.getModelDetails(selectedModel) }
                    .onSuccess { modelDetails = it }
                    .onFailure { errorMessage = "Model details unavailable: ${it.message}" }
            }
        }

        if (termsDialogVisible) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("EVREN LLM API · Kullanım Şartları") },
                text = {
                    Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState())) {
                        Text("Sürüm: ${termsVersion}", color = VexPurple, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(termsContent, color = VexText, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = termsVersion >= 0 && termsContent.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    evren.acceptTerms(termsVersion)
                                    termsAccepted = true
                                    termsDialogVisible = false
                                    termsStatusMessage = "EVREN terms accepted (version ${termsVersion})."
                                    finishServiceConnection()
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Could not accept EVREN terms."
                                } finally { busy = false }
                            }
                        }
                    ) { Text("Okudum ve kabul ediyorum") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        termsDialogVisible = false
                        termsStatusMessage = "Terms not accepted. EVREN API calls remain locked."
                        connectionMessage = "EVREN terms must be accepted before using chat or Agent."
                    }) { Text("Şimdi değil") }
                }
            )
        }
    }
}

@Composable
private fun ConnectionPanel(
    evrenKey: String, onEvrenKey: (String) -> Unit,
    githubToken: String, onGithubToken: (String) -> Unit,
    apiUrl: String, onApiUrl: (String) -> Unit,
    status: String, termsStatus: String, termsAccepted: Boolean,
    busy: Boolean, onSaveAndValidate: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Connect your services", color = VexText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Credentials are encrypted locally. GitHub tokens are never sent to EVREN.", color = VexMuted, fontSize = 12.sp)
        OutlinedTextField(apiUrl, onApiUrl, Modifier.fillMaxWidth(), label = { Text("EVREN API base URL") }, singleLine = true)
        OutlinedTextField(evrenKey, onEvrenKey, Modifier.fillMaxWidth(), label = { Text("EVREN API key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        OutlinedTextField(githubToken, onGithubToken, Modifier.fillMaxWidth(), label = { Text("GitHub Personal Access Token") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = onSaveAndValidate, enabled = !busy && evrenKey.isNotBlank() && githubToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Text("Save & validate connections")
        }
        Text(status, color = VexMuted, fontSize = 12.sp)
        Text(termsStatus, color = if (termsAccepted) Color(0xFF86EFAC) else Color(0xFFFCD34D), fontSize = 12.sp)
        Text("EVREN model catalog is loaded from /v1/models after successful validation. GitHub repositories are paginated until every accessible page has been read.", color = VexMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RepositoryPanel(
    repositories: List<GitHubRepository>, selected: GitHubRepository?, busy: Boolean,
    onRefresh: () -> Unit, onSelect: (GitHubRepository) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Accessible repositories", Modifier.weight(1f), color = VexText, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh", color = VexPurple) }
        }
        Text("${repositories.size} repositories loaded", color = VexMuted, fontSize = 11.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(repositories, key = { it.id }) { repo ->
                Column(
                    Modifier.fillMaxWidth().background(VexSurface, RoundedCornerShape(8.dp))
                        .clickable { onSelect(repo) }.padding(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(repo.fullName, Modifier.weight(1f), color = VexText, fontWeight = FontWeight.SemiBold)
                        Text(if (repo.isPrivate) "PRIVATE" else "PUBLIC", color = VexPurple, fontSize = 9.sp)
                    }
                    Text(repo.description ?: "No description", color = VexMuted, fontSize = 12.sp, maxLines = 2)
                    Text("${repo.language ?: "Unknown"} · ${repo.defaultBranch}", color = VexMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FileBrowserPanel(
    repository: GitHubRepository?, branch: String, entries: List<GitHubTreeEntry>,
    filter: String, onFilter: (String) -> Unit, file: GitHubFile?, loading: Boolean,
    onOpen: (GitHubTreeEntry) -> Unit, onBack: () -> Unit, onCreateBranch: () -> Unit,
    workingBranch: String, defaultBranch: String, busy: Boolean
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(repository?.fullName ?: "Select repository", Modifier.weight(1f), color = VexText, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onCreateBranch, enabled = !busy && repository != null && workingBranch == defaultBranch) {
                Text("Create agent branch", color = VexPurple, fontSize = 11.sp)
            }
        }
        Text("Branch: $branch", color = VexMuted, fontSize = 11.sp)
        if (file != null) {
            TextButton(onClick = onBack) { Text("← Back to files", color = VexPurple) }
            Text(file.path, color = VexText, fontWeight = FontWeight.SemiBold)
            if (loading) CircularProgressIndicator()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(VexSurface, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(file.content, color = Color(0xFFD4D4D8), fontSize = 11.sp, lineHeight = 16.sp)
            }
        } else {
            OutlinedTextField(filter, onFilter, Modifier.fillMaxWidth(), label = { Text("Filter path / directory prefix") }, singleLine = true)
            val visible = entries.filter { entry ->
                val prefix = filter.trim('/')
                if (prefix.isBlank()) !entry.path.contains("/")
                else entry.path.startsWith("$prefix/") && !entry.path.removePrefix("$prefix/").contains("/")
            }.take(500)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(visible, key = { it.path }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().background(VexSurface, RoundedCornerShape(6.dp))
                            .clickable { onOpen(entry) }.padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (entry.type == "tree") "▸" else "{}", color = VexPurple, modifier = Modifier.width(26.dp))
                        Text(entry.path, color = VexText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (entry.type == "blob") Text(formatSize(entry.size), color = VexMuted, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPanel(
    repository: GitHubRepository?,
    availableRepositories: List<GitHubRepository>,
    secondaryRepository: GitHubRepository?,
    onSecondaryRepository: (GitHubRepository?) -> Unit,
    branch: String, model: String,
    modelDetails: EvrenModelDetails?, termsAccepted: Boolean,
    evren: EvrenApiClient,
    maxPower: Boolean, onMaxPower: (Boolean) -> Unit,
    autopilot: Boolean, onAutopilot: (Boolean) -> Unit,
    fullAccess: Boolean, onFullAccess: (Boolean) -> Unit,
    input: String, onInput: (String) -> Unit, status: String,
    result: AgentRunResult?, busy: Boolean, onRun: (String) -> Unit, onViewChanges: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val plannerMessages = remember { mutableStateListOf<Pair<String, String>>() }
    var plannerInput by remember { mutableStateOf("") }
    var plannerBusy by remember { mutableStateOf(false) }
    var repoMenu by remember { mutableStateOf(false) }
    var planningContext by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Autonomous coding agent", color = VexText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${repository?.fullName ?: "No repository"} · $branch · ${model.ifBlank { "No model" }}", color = VexMuted, fontSize = 11.sp)
        if (repository != null && branch == repository.defaultBranch) {
            Text("Çalışma dalı henüz yok. Run Agent ilk çalıştırmada güvenli bir dalı otomatik oluşturur.",
                color = VexMuted, fontSize = 10.sp)
        }
        if (busy) {
            Surface(
                color = VexSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp).height(20.dp),
                        strokeWidth = 2.dp
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Agent çalışıyor", color = VexText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = VexPurple, fontSize = 11.sp)
                    }
                }
            }
        }
        if (model == "auto") {
            Text("Auto routing · Model output ceiling is provider-managed; 4096-token Agent request.", color = VexMuted, fontSize = 11.sp)
        } else if (modelDetails != null) {
            Text(
                "Context: ${modelDetails.contextLength ?: "?"} · Model output cap: ${modelDetails.defaultMaxOutputTokens ?: "?"} · Standard budget: ${modelDetails.recommendedAgentBudget()}",
                color = VexMuted, fontSize = 11.sp
            )
            Text(
                "Adaptive context manager: automatic history compression, context-aware output budgeting, 5,000-path prompt index and 60,000-character per-file safety limit.",
                color = VexMuted, fontSize = 10.sp
            )
        } else {
            Text("Loading model capabilities…", color = VexMuted, fontSize = 11.sp)
        }

        Text("Connected repositories", color = VexText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("1. ${repository?.fullName ?: "Not selected"}", color = VexMuted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("2. ${secondaryRepository?.fullName ?: "Optional second repo"}", modifier = Modifier.weight(1f), color = VexMuted, fontSize = 11.sp)
            TextButton(onClick = { repoMenu = true }) { Text(if (secondaryRepository == null) "Add repo" else "Change", color = VexPurple) }
            DropdownMenu(expanded = repoMenu, onDismissRequest = { repoMenu = false }) {
                DropdownMenuItem(text = { Text("No second repo") }, onClick = {
                    onSecondaryRepository(null); repoMenu = false
                })
                availableRepositories.filter { it.fullName != repository?.fullName }.forEach { repo ->
                    DropdownMenuItem(text = { Text(repo.fullName) }, onClick = {
                        onSecondaryRepository(repo); repoMenu = false
                    })
                }
            }
        }

        Surface(color = VexSurface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent behavior", color = VexText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Max Power", color = VexText, fontSize = 12.sp)
                        Text("Modelin izin verdiği en yüksek çıktı limitini kullanır.", color = VexMuted, fontSize = 10.sp)
                    }
                    androidx.compose.material3.Switch(checked = maxPower, onCheckedChange = onMaxPower)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Autopilot · Serbest mod", color = VexText, fontSize = 12.sp)
                        Text("Yol haritasını ve adımları kendisi seçer; daha uzun süre ilerler.", color = VexMuted, fontSize = 10.sp)
                    }
                    androidx.compose.material3.Switch(checked = autopilot, onCheckedChange = onAutopilot)
                }
                Text(if (autopilot) "Autopilot: uzun görevler için genişletilmiş Agent çalışma döngüsü." else "Standart: kontrollü Agent çalışma döngüsü.", color = VexMuted, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full Access Mode", color = VexText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Dosya oluşturma, değiştirme ve silme işlemlerini seçili çalışma dalına doğrudan commit eder. Dosya başına onay istemez.", color = VexMuted, fontSize = 10.sp)
                    }
                    androidx.compose.material3.Switch(checked = fullAccess, onCheckedChange = onFullAccess)
                }
                Text(if (fullAccess) "FULL ACCESS AÇIK · Çalışma dallarında doğrudan yazma/silme." else "Full Access kapalı · Değişiklikler manuel onay bekler.", color = if (fullAccess) VexPurple else VexMuted, fontSize = 10.sp)
            }
        }

        OutlinedTextField(input, onInput, Modifier.fillMaxWidth().height(110.dp),
            label = { Text("Describe a coding task") },
            placeholder = { Text("Analyze both repos, find the issue, coordinate the fix…") })

        Surface(color = VexSurface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Planlama sohbeti", color = VexText, fontWeight = FontWeight.SemiBold)
                Text("Agent çalışmadan önce hedefi, mimariyi ve yol haritasını burada birlikte netleştirin.", color = VexMuted, fontSize = 10.sp)
                plannerMessages.forEach { (role, message) ->
                    Text(if (role == "user") "Sen: $message" else "EVREN: $message",
                        color = if (role == "user") VexPurple else VexText, fontSize = 11.sp)
                }
                OutlinedTextField(plannerInput, { plannerInput = it }, Modifier.fillMaxWidth().height(76.dp),
                    label = { Text("Plan hakkında konuş…") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            plannerMessages.clear()
                            planningContext = ""
                        },
                        enabled = plannerMessages.isNotEmpty() && !plannerBusy
                    ) { Text("Clear") }
                    Button(
                        onClick = {
                            val prompt = plannerInput.trim()
                            if (prompt.isNotBlank()) {
                                plannerMessages.add("user" to prompt)
                                plannerInput = ""
                                plannerBusy = true
                                scope.launch {
                                    try {
                                        val history = listOf(
                                            "system" to "You are the planning partner for Vex EVREN Code. Help clarify requirements, propose a practical roadmap and risks. Do not claim code was changed. Keep replies concise."
                                        ) + plannerMessages.toList()
                                        val answer = evren.chat(model, history)
                                        plannerMessages.add("assistant" to answer)
                                        planningContext = plannerMessages.joinToString("\n") { (role, text) ->
                                            "${if (role == "user") "User" else "EVREN"}: $text"
                                        }
                                    } catch (error: Exception) {
                                        plannerMessages.add("assistant" to "Planlama hatası: ${error.message}")
                                    } finally { plannerBusy = false }
                                }
                            }
                        },
                        enabled = !plannerBusy && !busy && termsAccepted && model.isNotBlank() && plannerInput.isNotBlank()
                    ) { Text(if (plannerBusy) "Thinking…" else "Send") }
                }
            }
        }

        Button(onClick = { onRun(planningContext) },
            enabled = !busy && termsAccepted && repository != null && model.isNotBlank() && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Run Agent") }
        Text(status, color = VexPurple, fontSize = 12.sp)
        result?.let { output ->
            Text("Plan", color = VexText, fontWeight = FontWeight.SemiBold)
            output.plan.forEach { Text("• $it", color = VexMuted, fontSize = 12.sp) }
            Text("Tool activity", color = VexText, fontWeight = FontWeight.SemiBold)
            output.toolLog.forEach { Text("✓ $it", color = VexMuted, fontSize = 11.sp) }
            Text("Agent response", color = VexText, fontWeight = FontWeight.SemiBold)
            Text(output.answer, color = Color(0xFFD4D4D8), fontSize = 12.sp)
            if (output.changes.isNotEmpty()) {
                Button(onClick = onViewChanges, modifier = Modifier.fillMaxWidth()) {
                    Text("Review ${output.changes.size} proposed change(s)")
                }
            }
        }
    }
}

@Composable
private fun ChangesPanel(
    repository: GitHubRepository?, branch: String, proposals: List<ProposedChange>,
    active: ProposedChange?, onSelect: (ProposedChange) -> Unit,
    onApply: (ProposedChange) -> Unit, busy: Boolean
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review changes", color = VexText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${repository?.fullName ?: "No repository"} · $branch", color = VexMuted, fontSize = 11.sp)
        if (proposals.isEmpty()) {
            Text("No pending proposals. Run an Agent task to prepare code changes.", color = VexMuted, fontSize = 13.sp)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(proposals, key = { "${it.repositoryFullName}:${it.path}" }) { change ->
                    OutlinedButton(onClick = { onSelect(change) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${change.repositoryFullName} · ${change.path}", color = if (active?.let { it.repositoryFullName == change.repositoryFullName && it.path == change.path } == true) VexPurple else VexText)
                    }
                }
            }
            active?.let { change ->
                Text("Diff · ${change.repositoryFullName} · ${change.path}", color = VexText, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).fillMaxWidth().background(VexSurface, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(unifiedDiff(change.before, change.after), color = Color(0xFFD4D4D8), fontSize = 10.sp, lineHeight = 14.sp)
                }
                Button(onClick = { onApply(change) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Approve & commit to ${change.branch}")
                }
                Text("This writes only to the working branch, not the default branch.", color = VexMuted, fontSize = 10.sp)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun unifiedDiff(before: String, after: String): String {
    val oldLines = before.lines()
    val newLines = after.lines()
    if (oldLines.size * newLines.size > 90_000) return "--- ORIGINAL ---\n${before.take(12000)}\n+++ PROPOSED ---\n${after.take(12000)}"
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.size - 1 downTo 0) for (j in newLines.size - 1 downTo 0) {
        dp[i][j] = if (oldLines[i] == newLines[j]) 1 + dp[i + 1][j + 1]
        else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
    val output = StringBuilder("--- original\n+++ proposed\n")
    var i = 0
    var j = 0
    while (i < oldLines.size || j < newLines.size) {
        when {
            i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> {
                output.append("  ").appendLine(oldLines[i]); i++; j++
            }
            j < newLines.size && (i == oldLines.size || dp[i][j + 1] >= dp[i + 1][j]) -> {
                output.append("+ ").appendLine(newLines[j]); j++
            }
            i < oldLines.size -> {
                output.append("- ").appendLine(oldLines[i]); i++
            }
        }
    }
    return output.toString()
}
