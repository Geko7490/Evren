package com.evren.vex.agent.policy

/** Outcome of asking the [ApprovalGate] whether an action may run. */
sealed interface AuthorizationResult {
    /** The action may execute immediately. */
    data class Approved(val request: ActionRequest) : AuthorizationResult

    /** The action is parked until the user approves it. */
    data class PendingApproval(val request: ActionRequest) : AuthorizationResult

    /** The action was refused outright. */
    data class Rejected(val request: ActionRequest, val reason: String) : AuthorizationResult
}

/**
 * Single decision point for remote GitHub operations.
 *
 * FULL_ACCESS never asks for per-action approval. Both modes still enqueue
 * requests so the activity history remains complete and execution outcomes can
 * transition APPROVED -> EXECUTED/FAILED.
 */
class ApprovalGate(
    private val modeStore: PushModeStore,
    private val queue: ApprovalQueue
) {
    fun authorize(request: ActionRequest): AuthorizationResult =
        when (modeStore.mode.value) {
            PushMode.FULL_ACCESS -> {
                val approved = queue.enqueue(request.copy(status = ActionStatus.APPROVED))
                AuthorizationResult.Approved(approved)
            }
            PushMode.APPROVAL_REQUIRED ->
                AuthorizationResult.PendingApproval(queue.enqueue(request))
        }
}
