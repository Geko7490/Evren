package com.vex.evren.code

import android.os.Bundle
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
import com.vex.evren.code.agent.AgentEngine
import com.vex.evren.code.agent.AgentRunResult
import com.vex.evren.code.agent.ProposedChange
import com.vex.evren.code.data.EvrenApiClient
import com.vex.evren.code.data.EvrenModel
import com.vex.evren.code.data.GitHubApiClient
import com.vex.evren.code.data.GitHubFile
import com.vex.evren.code.data.GitHubRepository
import com.vex.evren.code.data.GitHubTreeEntry
import kotlinx.coroutines.launch

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

    LaunchedEffect(Unit) {
        evrenKey = vault.read("evren_api_key").orEmpty()
        githubToken = vault.read("github_pat").orEmpty()
        apiUrl = vault.read("evren_base_url") ?: EvrenApiClient.DEFAULT_BASE_URL
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
                                    modelMenu = false
                                }
                            )
                        }
                    }
                }
            }
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

        when (tab) {
            WorkspaceTab.CONNECT -> ConnectionPanel(
                evrenKey, { evrenKey = it }, githubToken, { githubToken = it },
                apiUrl, { apiUrl = it }, connectionMessage, busy,
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
                            val user = github.validateToken()
                            githubLogin = user.login
                            githubConnected = true
                            connectionMessage = "Connected as $githubLogin · ${models.size} EVREN models found."
                            val repos = github.listAccessibleRepositories()
                            repositories.clear()
                            repositories.addAll(repos)
                            tab = WorkspaceTab.REPOS
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
                branch = workingBranch,
                model = selectedModel,
                input = taskInput,
                onInput = { taskInput = it },
                status = agentStatus,
                result = agentResult,
                busy = busy,
                onRun = {
                    val repo = selectedRepository
                    if (repo == null) errorMessage = "Select a repository first."
                    else if (selectedModel.isBlank()) errorMessage = "Connect EVREN and select a model."
                    else if (workingBranch == repo.defaultBranch) errorMessage = "Create a dedicated working branch in Files before running code changes."
                    else scope.launch {
                        busy = true
                        agentResult = null
                        proposals.clear()
                        try {
                            val result = AgentEngine(evren, github).run(
                                selectedModel, taskInput, repo, workingBranch
                            ) { agentStatus = it }
                            agentResult = result
                            proposals.addAll(result.changes)
                            agentStatus = "Task finished · ${result.changes.size} proposed file change(s)."
                        } catch (e: Exception) {
                            agentStatus = e.message ?: "Agent failed."
                            errorMessage = agentStatus
                        } finally { busy = false }
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
                        val repo = selectedRepository ?: return@launch
                        busy = true
                        try {
                            github.updateFile(
                                repo.fullName, change.path, change.after,
                                "agent: update ${change.path.substringAfterLast('/')}",
                                workingBranch, change.originalSha
                            )
                            proposals.remove(change)
                            if (activeChange == change) activeChange = null
                            connectionMessage = "Applied ${change.path} to $workingBranch"
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
        errorMessage?.let { message ->
            TextButton(onClick = { errorMessage = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Error: $message  ·  Dismiss", color = Color(0xFFFCA5A5), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    evrenKey: String, onEvrenKey: (String) -> Unit,
    githubToken: String, onGithubToken: (String) -> Unit,
    apiUrl: String, onApiUrl: (String) -> Unit,
    status: String, busy: Boolean, onSaveAndValidate: () -> Unit
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
    repository: GitHubRepository?, branch: String, model: String,
    input: String, onInput: (String) -> Unit, status: String,
    result: AgentRunResult?, busy: Boolean, onRun: () -> Unit, onViewChanges: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Autonomous coding agent", color = VexText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${repository?.fullName ?: "No repository selected"} · $branch · ${model.ifBlank { "No model" }}", color = VexMuted, fontSize = 11.sp)
        OutlinedTextField(input, onInput, Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Describe a coding task") },
            placeholder = { Text("Analyze the project, find the bug, propose a fix…") })
        Button(onClick = onRun, enabled = !busy && repository != null && model.isNotBlank() && input.isNotBlank(),
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
                items(proposals, key = { it.path }) { change ->
                    OutlinedButton(onClick = { onSelect(change) }, modifier = Modifier.fillMaxWidth()) {
                        Text(change.path, color = if (active?.path == change.path) VexPurple else VexText)
                    }
                }
            }
            active?.let { change ->
                Text("Diff · ${change.path}", color = VexText, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).fillMaxWidth().background(VexSurface, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(unifiedDiff(change.before, change.after), color = Color(0xFFD4D4D8), fontSize = 10.sp, lineHeight = 14.sp)
                }
                Button(onClick = { onApply(change) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Approve & commit to $branch")
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
