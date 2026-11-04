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
    fun fullAccessApprovesImmediatelyAndSkipsQueue() {
        val queue = ApprovalQueue()
        val result = gate(PushMode.FULL_ACCESS, queue).authorize(ActionRequest(action = pushAction))

        assertTrue(result is AuthorizationResult.Approved)
        assertEquals(ActionStatus.APPROVED, (result as AuthorizationResult.Approved).request.status)
        assertTrue(queue.pending().isEmpty())
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
    fun unknownModeFallsBackToSafeDefault() {
        assertEquals(PushMode.APPROVAL_REQUIRED, PushMode.fromStorage("something-else"))
        assertEquals(PushMode.APPROVAL_REQUIRED, PushMode.fromStorage(null))
        assertEquals(PushMode.FULL_ACCESS, PushMode.fromStorage("full_access"))
    }
}
