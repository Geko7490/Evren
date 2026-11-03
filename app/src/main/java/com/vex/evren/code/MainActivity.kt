package com.vex.evren.code

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private val VexPurple = Color(0xFFA855F7)
private val VexBackground = Color(0xFF0F0F13)
private val VexSurface = Color(0xFF1C1B23)

class MainActivity : ComponentActivity() {
    private lateinit var vault: CredentialVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CredentialVault(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = VexPurple,
                    background = VexBackground,
                    surface = VexSurface,
                    onBackground = Color(0xFFF5F5F7),
                    onSurface = Color(0xFFF5F5F7)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = VexBackground) {
                    ConnectionScreen { evrenKey, githubToken ->
                        lifecycleScope.launch {
                            if (evrenKey.isNotBlank()) vault.save("evren_api_key", evrenKey)
                            if (githubToken.isNotBlank()) vault.save("github_pat", githubToken)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(onSave: (String, String) -> Unit) {
    var evrenKey by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(VexBackground)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VEX", color = VexPurple, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text("EVREN CODE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Your mobile AI software engineering agent.", color = Color(0xFFA1A1AA), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text("Connect your services", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = evrenKey, onValueChange = { evrenKey = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("EVREN API key") },
            placeholder = { Text("evren_llm_…") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        OutlinedTextField(
            value = githubToken, onValueChange = { githubToken = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("GitHub Personal Access Token") },
            placeholder = { Text("github_pat_…") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        Button(
            onClick = { onSave(evrenKey.trim(), githubToken.trim()) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            enabled = evrenKey.isNotBlank() || githubToken.isNotBlank()
        ) { Text("Save credentials") }
        Text(
            "Credentials are encrypted with an Android Keystore-backed key. They are never included in AI prompts.",
            color = Color(0xFFA1A1AA), fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Text("Agent workspace", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Repository discovery, task planning, code changes, diff review and remote builds " +
                "will be enabled in the next implementation milestones.",
            color = Color(0xFFA1A1AA), fontSize = 13.sp
        )
    }
}
