package com.evren.vex.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evren.vex.agent.policy.PushMode

/**
 * The user-facing switch for the two push modes.
 *
 * Stateless on purpose: the caller owns the [PushMode] (from
 * [com.evren.vex.agent.policy.PushModeStore]) and the pending count (from
 * [com.evren.vex.agent.policy.ApprovalQueue]).
 */
@Composable
fun PushModeSettings(
    mode: PushMode,
    pendingCount: Int,
    onModeChange: (PushMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Allow everything mode",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (mode.isFullAccess) {
                        "The AI can push, create repositories and open pull requests on its own."
                    } else {
                        "Every remote action is sent to you for approval first."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = mode.isFullAccess,
                onCheckedChange = { enabled ->
                    onModeChange(
                        if (enabled) PushMode.FULL_ACCESS else PushMode.APPROVAL_REQUIRED
                    )
                }
            )
        }

        if (mode.isFullAccess) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Warning: the agent can make irreversible remote changes without asking.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (pendingCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$pendingCount action(s) waiting for your approval.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
