package com.evren.vex.agent.policy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much authority the agent has over remote GitHub operations.
 *
 * FULL_ACCESS is the default: the agent can execute supported GitHub actions
 * without a per-action approval prompt. APPROVAL_REQUIRED remains an explicit
 * opt-in restriction that users can enable at any time.
 */
enum class PushMode {
    /** Execute supported GitHub actions immediately and record them in history. */
    FULL_ACCESS,

    /** Park remote actions until the user explicitly approves them. */
    APPROVAL_REQUIRED;

    val isFullAccess: Boolean get() = this == FULL_ACCESS

    companion object {
        /** EVREN is autonomous by default; users may opt into approval mode. */
        val DEFAULT: PushMode = FULL_ACCESS

        /** Unknown or missing preferences retain the autonomy-first default. */
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

/** Persists the selected mode in SharedPreferences. */
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
