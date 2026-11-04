package com.evren.vex.agent.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGateTest {

    private val pushAction = RemoteAction.PushCommit(
        repository = "Geko7490/Evren",
        branch = "main",
        message = "Add push modes",
        files = mapOf("README.md" to "# Evren")
    )

    private fun gate(mode: PushMode, queue: ApprovalQueue) =
        ApprovalGate(InMemoryPushModeStore(mode), queue)

    @Test
    fun fullAccessApprovesImmediatelyAndRecordsAuditEntry() {
        val queue = ApprovalQueue()
        val result = gate(PushMode.FULL_ACCESS, queue).authorize(ActionRequest(action = pushAction))

        assertTrue(result is AuthorizationResult.Approved)
        val approved = (result as AuthorizationResult.Approved).request
        assertEquals(ActionStatus.APPROVED, approved.status)
        assertEquals(1, queue.requests.value.size)
        assertEquals(approved.id, queue.requests.value.single().id)
    }

    @Test
    fun approvalRequiredParksTheRequest() {
        val queue = ApprovalQueue()
        val result = gate(PushMode.APPROVAL_REQUIRED, queue)
            .authorize(ActionRequest(action = pushAction))

        assertTrue(result is AuthorizationResult.PendingApproval)
        assertEquals(1, queue.pending().size)
        assertEquals(ActionStatus.PENDING, queue.pending().first().status)
    }

    @Test
    fun approvingMovesRequestOutOfPending() {
        val queue = ApprovalQueue()
        val pending = (
            gate(PushMode.APPROVAL_REQUIRED, queue).authorize(ActionRequest(action = pushAction))
                as AuthorizationResult.PendingApproval
            ).request

        val approved = queue.approve(pending.id)
        assertEquals(ActionStatus.APPROVED, approved?.status)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun rejectingMovesRequestOutOfPending() {
        val queue = ApprovalQueue()
        val pending = (
            gate(PushMode.APPROVAL_REQUIRED, queue).authorize(ActionRequest(action = pushAction))
                as AuthorizationResult.PendingApproval
            ).request

        val rejected = queue.reject(pending.id)
        assertEquals(ActionStatus.REJECTED, rejected?.status)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun doubleApprovalIsANoOp() {
        val queue = ApprovalQueue()
        val pending = (
            gate(PushMode.APPROVAL_REQUIRED, queue).authorize(ActionRequest(action = pushAction))
                as AuthorizationResult.PendingApproval
            ).request

        queue.approve(pending.id)
        assertNull(queue.approve(pending.id))
    }

    @Test
    fun missingOrUnknownPreferenceDefaultsToFullAccess() {
        assertEquals(PushMode.FULL_ACCESS, PushMode.DEFAULT)
        assertEquals(PushMode.FULL_ACCESS, PushMode.fromStorage("something-else"))
        assertEquals(PushMode.FULL_ACCESS, PushMode.fromStorage(null))
        assertEquals(PushMode.FULL_ACCESS, PushMode.fromStorage("full_access"))
        assertEquals(PushMode.APPROVAL_REQUIRED, PushMode.fromStorage("approval_required"))
    }
}
