package com.evren.vex.agent.github

import com.evren.vex.agent.policy.ActionRequest
import com.evren.vex.agent.policy.ApprovalGate
import com.evren.vex.agent.policy.ApprovalQueue
import com.evren.vex.agent.policy.AuthorizationResult
import com.evren.vex.agent.policy.RemoteAction

/** What happened to a submitted [RemoteAction]. */
sealed interface PushOutcome {

    /** The action ran and GitHub accepted it. */
    data class Executed(val request: ActionRequest, val result: String) : PushOutcome

    /** Safe mode: the action is waiting for the user in the approval queue. */
    data class AwaitingApproval(val request: ActionRequest) : PushOutcome

    /** The action was refused and will not run. */
    data class Rejected(val reason: String) : PushOutcome

    /** The action was approved but the GitHub call failed. */
    data class Failed(val request: ActionRequest, val error: String) : PushOutcome
}

/**
 * The only component allowed to mutate GitHub state.
 *
 * Flow in both modes:
 *  1. [submit] wraps the action in an [ActionRequest] and asks the [ApprovalGate].
 *  2. Full access  -> executed immediately.
 *     Safe mode    -> parked in the [ApprovalQueue] and returned as
 *                     [PushOutcome.AwaitingApproval].
 *  3. The UI calls [approveAndExecute] when the user accepts a parked request.
 */
class GitHubPushService(
    private val remote: GitHubRemote,
    private val gate: ApprovalGate,
    private val queue: ApprovalQueue
) {

    /** Entry point used by the agent for every remote operation. */
    suspend fun submit(action: RemoteAction): PushOutcome {
        val request = ActionRequest(action = action)
        return when (val result = gate.authorize(request)) {
            is AuthorizationResult.Approved -> execute(result.request)
            is AuthorizationResult.PendingApproval -> PushOutcome.AwaitingApproval(result.request)
            is AuthorizationResult.Rejected -> PushOutcome.Rejected(result.reason)
        }
    }

    /** User accepted a parked request: mark it approved and run it now. */
    suspend fun approveAndExecute(requestId: String): PushOutcome {
        val approved = queue.approve(requestId)
            ?: return PushOutcome.Rejected("No pending request with id $requestId")
        return execute(approved)
    }

    /** User declined a parked request. */
    fun reject(requestId: String): ActionRequest? = queue.reject(requestId)

    private suspend fun execute(request: ActionRequest): PushOutcome {
        val result = when (val action = request.action) {
            is RemoteAction.PushCommit -> remote.pushCommit(
                repository = action.repository,
                branch = action.branch,
                message = action.message,
                files = action.files
            )

            is RemoteAction.CreateRepository -> remote.createRepository(
                name = action.name,
                isPrivate = action.isPrivate,
                description = action.description
            )

            is RemoteAction.OpenPullRequest -> remote.openPullRequest(
                repository = action.repository,
                head = action.head,
                base = action.base,
                title = action.title,
                body = action.body
            )
        }

        return result.fold(
            onSuccess = { value ->
                queue.markExecuted(request.id)
                PushOutcome.Executed(request, value)
            },
            onFailure = { error ->
                queue.markFailed(request.id)
                PushOutcome.Failed(request, error.message ?: "Unknown GitHub error")
            }
        )
    }
}
