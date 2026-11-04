package com.evren.vex.agent.policy

/** Outcome of asking the [ApprovalGate] whether an action may run. */
sealed interface AuthorizationResult {

    /** The action may execute immediately (full-access mode). */
    data class Approved(val request: ActionRequest) : AuthorizationResult

    /** The action is parked until the user approves it (safe mode). */
    data class PendingApproval(val request: ActionRequest) : AuthorizationResult

    /** The action was refused outright. */
    data class Rejected(val request: ActionRequest, val reason: String) : AuthorizationResult
}

/**
 * The single decision point for "may the agent do this on GitHub?".
 *
 * This is the whole two-mode feature:
 *
 *  - [PushMode.FULL_ACCESS]      -> [AuthorizationResult.Approved], the agent acts on its own.
 *  - [PushMode.APPROVAL_REQUIRED] -> [AuthorizationResult.PendingApproval], the user is asked.
 *
 * Nothing else in the app is allowed to call GitHub directly; everything funnels
 * through here so the mode cannot be bypassed by a new code path.
 */
class ApprovalGate(
    private val modeStore: PushModeStore,
    private val queue: ApprovalQueue
) {

    fun authorize(request: ActionRequest): AuthorizationResult =
        when (modeStore.mode.value) {
            PushMode.FULL_ACCESS ->
                AuthorizationResult.Approved(request.copy(status = ActionStatus.APPROVED))

            PushMode.APPROVAL_REQUIRED ->
                AuthorizationResult.PendingApproval(queue.enqueue(request))
        }
}
