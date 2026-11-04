package com.evren.vex.agent.policy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much authority the agent has over remote GitHub operations.
 *
 * The mode is a single global switch so the user always knows which contract is
 * active. It is intentionally coarse: there is no per-action override, because a
 * half-open policy is harder to reason about than a clear "all" or "ask me".
 */
enum class PushMode {

    /**
     * "Allow everything" mode.
     *
     * The agent may push commits, create repositories, open pull requests and
     * perform any other remote action on its own, without asking the user.
     * Remote changes made in this mode are irreversible, so the UI must show a
     * persistent warning while it is active.
     */
    FULL_ACCESS,

    /**
     * Safe mode (default).
     *
     * Every remote action is turned into an [ActionRequest] and parked in the
     * [ApprovalQueue]. Nothing leaves the device until the user approves it.
     */
    APPROVAL_REQUIRED;

    val isFullAccess: Boolean get() = this == FULL_ACCESS

    companion object {
        /** Safe by default: a fresh install never grants full access. */
        val DEFAULT: PushMode = APPROVAL_REQUIRED

        /** Tolerant parsing so a corrupted/renamed preference never grants access. */
        fun fromStorage(raw: String?): PushMode =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

/** Observable holder for the currently selected [PushMode]. */
interface PushModeStore {
    val mode: StateFlow<PushMode>

    fun setMode(mode: PushMode)
}

/** Non-persistent store, useful for tests and previews. */
class InMemoryPushModeStore(initial: PushMode = PushMode.DEFAULT) : PushModeStore {

    private val _mode = MutableStateFlow(initial)
    override val mode: StateFlow<PushMode> = _mode.asStateFlow()

    override fun setMode(mode: PushMode) {
        _mode.value = mode
    }
}

/**
 * Persists the mode in [android.content.SharedPreferences].
 *
 * The mode is not a secret, so it does not need Keystore-backed storage; the
 * GitHub token and EVREN API key do and are handled elsewhere.
 */
class SharedPreferencesPushModeStore(context: Context) : PushModeStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(PushMode.fromStorage(prefs.getString(KEY_MODE, null)))
    override val mode: StateFlow<PushMode> = _mode.asStateFlow()

    override fun setMode(mode: PushMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val PREFS_NAME = "evren_agent_policy"
        const val KEY_MODE = "push_mode"
    }
}
