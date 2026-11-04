package com.evren.vex.agent.policy

import java.util.UUID

/** The kinds of remote GitHub operations the agent is allowed to attempt. */
enum class RemoteActionType {
    PUSH_COMMIT,
    CREATE_REPOSITORY,
    OPEN_PULL_REQUEST
}

/**
 * A typed description of something the agent wants to do on GitHub.
 *
 * Keeping this as a sealed hierarchy (instead of a stringly-typed payload map)
 * means the executor cannot misinterpret an action, and the approval UI can
 * render an accurate summary without guessing.
 */
sealed interface RemoteAction {

    val type: RemoteActionType

    /** `owner/repo` for repository-scoped actions, the new name for creation. */
    val repository: String

    /** One-line, human readable description shown in the approval prompt. */
    val summary: String

    data class PushCommit(
        override val repository: String,
        val branch: String,
        val message: String,
        val files: Map<String, String>
    ) : RemoteAction {

        override val type: RemoteActionType get() = RemoteActionType.PUSH_COMMIT

        override val summary: String
            get() = "Push ${files.size} file(s) to $repository@$branch — \"$message\""
    }

    data class CreateRepository(
        val name: String,
        val isPrivate: Boolean,
        val description: String? = null
    ) : RemoteAction {

        override val type: RemoteActionType get() = RemoteActionType.CREATE_REPOSITORY

        override val repository: String get() = name

        override val summary: String
            get() = "Create ${if (isPrivate) "private" else "public"} repository \"$name\""
    }

    data class OpenPullRequest(
        override val repository: String,
        val head: String,
        val base: String,
        val title: String,
        val body: String
    ) : RemoteAction {

        override val type: RemoteActionType get() = RemoteActionType.OPEN_PULL_REQUEST

        override val summary: String
            get() = "Open pull request $head → $base on $repository — \"$title\""
    }
}

/** Lifecycle of a single [ActionRequest]. */
enum class ActionStatus {
    /** Waiting for the user (only possible in [PushMode.APPROVAL_REQUIRED]). */
    PENDING,

    /** Cleared to execute. */
    APPROVED,

    /** The user declined; never executed. */
    REJECTED,

    /** Executed successfully against GitHub. */
    EXECUTED,

    /** Execution was attempted and failed. */
    FAILED
}

/**
 * A concrete, auditable request to perform a [RemoteAction].
 *
 * Every remote operation goes through one of these, in both modes, so the
 * history is identical regardless of how much autonomy was granted.
 */
data class ActionRequest(
    val id: String = UUID.randomUUID().toString(),
    val action: RemoteAction,
    val status: ActionStatus = ActionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
) {
    val type: RemoteActionType get() = action.type
    val repository: String get() = action.repository
    val summary: String get() = action.summary
}
