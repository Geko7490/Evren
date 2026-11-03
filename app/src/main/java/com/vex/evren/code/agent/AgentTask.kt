package com.vex.evren.code.agent

enum class AgentTaskStatus {
    QUEUED,
    ANALYZING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentTask(
    val id: String,
    val repositoryFullName: String,
    val branch: String,
    val userRequest: String,
    val status: AgentTaskStatus = AgentTaskStatus.QUEUED,
    val plan: List<String> = emptyList(),
    val completedSteps: List<String> = emptyList(),
    val modifiedPaths: List<String> = emptyList(),
    val lastError: String? = null
)
