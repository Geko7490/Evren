package com.evren.vex.agent.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds every [ActionRequest] the agent has produced, in creation order.
 *
 * In [PushMode.APPROVAL_REQUIRED] this is the inbox the user reviews. In
 * [PushMode.FULL_ACCESS] requests pass through it too, so the audit trail is the
 * same in both modes.
 *
 * All state transitions are guarded by the expected source status, which makes
 * double-approval (e.g. a double tap) a no-op instead of a duplicate push.
 */
class ApprovalQueue {

    private val _requests = MutableStateFlow<List<ActionRequest>>(emptyList())
    val requests: StateFlow<List<ActionRequest>> = _requests.asStateFlow()

    /** Requests still waiting for the user, oldest first. */
    fun pending(): List<ActionRequest> =
        _requests.value.filter { it.status == ActionStatus.PENDING }

    /** Adds [request] unless an identical id is already tracked. */
    fun enqueue(request: ActionRequest): ActionRequest {
        _requests.update { current ->
            if (current.any { it.id == request.id }) current else current + request
        }
        return _requests.value.first { it.id == request.id }
    }

    /** PENDING -> APPROVED. Returns null if the request is unknown or not pending. */
    fun approve(id: String): ActionRequest? =
        transition(id, from = ActionStatus.PENDING, to = ActionStatus.APPROVED)

    /** PENDING -> REJECTED. Returns null if the request is unknown or not pending. */
    fun reject(id: String): ActionRequest? =
        transition(id, from = ActionStatus.PENDING, to = ActionStatus.REJECTED)

    /** APPROVED -> EXECUTED. */
    fun markExecuted(id: String): ActionRequest? =
        transition(id, from = ActionStatus.APPROVED, to = ActionStatus.EXECUTED)

    /** APPROVED -> FAILED. */
    fun markFailed(id: String): ActionRequest? =
        transition(id, from = ActionStatus.APPROVED, to = ActionStatus.FAILED)

    fun clear() {
        _requests.value = emptyList()
    }

    private fun transition(id: String, from: ActionStatus, to: ActionStatus): ActionRequest? {
        var updated: ActionRequest? = null
        _requests.update { current ->
            current.map { request ->
                if (request.id == id && request.status == from) {
                    request.copy(status = to).also { updated = it }
                } else {
                    request
                }
            }
        }
        return updated
    }
}
