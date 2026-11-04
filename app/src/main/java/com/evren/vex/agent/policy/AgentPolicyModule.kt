package com.evren.vex.agent.policy

import android.content.Context
import com.evren.vex.agent.github.GitHubPushService
import com.evren.vex.agent.github.GitHubRemote

/**
 * Manual dependency wiring for the agent policy stack.
 *
 * Kept as a plain class (no DI framework) so the policy layer stays testable and
 * the app has exactly one place where the mode store, queue, gate and push
 * service are constructed together.
 */
class AgentPolicyModule(
    context: Context,
    remote: GitHubRemote
) {
    val modeStore: PushModeStore = SharedPreferencesPushModeStore(context)
    val approvalQueue: ApprovalQueue = ApprovalQueue()
    val gate: ApprovalGate = ApprovalGate(modeStore, approvalQueue)
    val pushService: GitHubPushService = GitHubPushService(remote, gate, approvalQueue)
}
