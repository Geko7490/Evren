# Vex EVREN Code

Native Android coding agent powered by the EVREN LLM API and GitHub.

## Project status

The repository is being initialized. The first milestone is a native Kotlin + Jetpack Compose application with secure credential storage, EVREN model discovery, GitHub repository browsing, and an autonomous agent foundation.

**Implemented so far:** the GitHub push-policy layer, its unit tests, and the GitHub Actions Android CI workflow. The Gradle project, Compose app shell, EVREN API client and GitHub REST client are still to come.

## Agent authority

EVREN is **autonomous by default**. A fresh installation uses `PushMode.FULL_ACCESS`, so supported GitHub actions execute immediately without a per-action approval prompt.

| Mode | Behaviour |
| --- | --- |
| **Full access** (`PushMode.FULL_ACCESS`, default) | Executes every GitHub action currently implemented by the remote adapter without asking for approval. |
| **Ask first** (`PushMode.APPROVAL_REQUIRED`, opt-in) | Parks remote actions in the approval queue until the user approves or rejects them. |

Full access is intentionally the default. Users can still switch to Ask first in settings when they want a review gate. Both modes write action records to the activity queue so the agent's work can be audited.

### Currently supported remote actions

The policy model currently supports:
- Push commits containing file changes to a branch
- Create a GitHub repository
- Open a pull request

**Important implementation boundary:** the current `GitHubRemote` is only an interface. The real GitHub REST adapter, credential integration, and broader GitHub operation set (repository/file management, branches, issues, workflow dispatches, pull-request management, and other API operations) have not been implemented yet. Full-access mode removes EVREN's local approval barrier; it cannot grant permissions that the connected GitHub token or GitHub App installation does not have.

### How authorization works

1. The agent submits a typed `RemoteAction` to `GitHubPushService`.
2. Full access → the action is marked approved and executed immediately.
3. Ask first → the action is stored as pending until the user approves or rejects it.
4. Both modes record the request; successful and failed executions update the audit status.

## Key files

- `app/src/main/java/com/evren/vex/agent/policy/PushMode.kt` — autonomy-first mode and persisted setting
- `app/src/main/java/com/evren/vex/agent/policy/RemoteAction.kt` — typed remote actions and action history model
- `app/src/main/java/com/evren/vex/agent/policy/ApprovalGate.kt` — authorization decision point
- `app/src/main/java/com/evren/vex/agent/policy/ApprovalQueue.kt` — pending requests and audit status transitions
- `app/src/main/java/com/evren/vex/agent/github/GitHubPushService.kt` — remote action execution entry point
- `app/src/main/java/com/evren/vex/ui/PushModeSettings.kt` — autonomy setting UI
- `app/src/test/java/com/evren/vex/agent/policy/ApprovalGateTest.kt` — permission mode tests

## Planned architecture

- Kotlin + Jetpack Compose
- MVVM / Clean Architecture
- EVREN OpenAI-compatible API client
- Full GitHub REST API adapter using the permissions granted by the connected account
- Android Keystore-backed credential protection
- Room task history and repository metadata
- GitHub Actions Android builds

## Security and credentials

Never commit API keys, GitHub tokens, `.env` files, or private project data. Keep tokens outside model prompts and tool output; the agent should receive operation results, not credential material. Request the GitHub permissions required for the features the user enables, and report missing scopes clearly instead of silently pretending an operation succeeded.

Full-access mode permits the agent to make supported remote changes without a confirmation prompt. Use it with repositories where autonomous changes are acceptable. GitHub account permissions, organization policies, branch protection, and token scopes remain authoritative.

## Build

The Android Gradle project is still being added. Until `./gradlew` exists, the `Android CI` workflow detects the missing wrapper and skips Android compilation and unit tests.
