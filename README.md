# Vex EVREN Code

Native Android coding agent powered by the EVREN LLM API and GitHub.

## Project status

The repository is being initialized. The first milestone is a native Kotlin + Jetpack Compose application with secure credential storage, EVREN model discovery, GitHub repository browsing, and a persistent agent foundation.

**Implemented so far:** the agent push-policy layer (the two push modes described below), its unit tests, and the GitHub Actions Android CI workflow. The Gradle project, Compose app shell, EVREN API client and GitHub REST client are still to come.

## Push modes

The agent can only touch GitHub through `GitHubPushService`, which asks `ApprovalGate` for permission on every single action. The gate reads one global switch, so the contract is always unambiguous.

| Mode | Behaviour |
| --- | --- |
| **Allow everything** (`PushMode.FULL_ACCESS`) | The AI has full authority. It pushes commits, creates repositories, opens pull requests and writes code on its own, without asking. |
| **Ask me first** (`PushMode.APPROVAL_REQUIRED`, default) | Every remote action is turned into an `ActionRequest` and parked in the approval queue. Nothing leaves the device until you approve it. |

A fresh install always starts in **Ask me first**; full access is opt-in and the UI shows a persistent warning while it is active.

### How an action flows

1. The agent calls `GitHubPushService.submit(action)` with a typed `RemoteAction` (`PushCommit`, `CreateRepository`, `OpenPullRequest`).
2. `ApprovalGate.authorize()` decides:
   - full access → `Approved`, executed immediately;
   - safe mode → `PendingApproval`, queued and returned to the UI.
3. In safe mode the user calls `approveAndExecute(requestId)` or `reject(requestId)`.
4. Every request is recorded in `ApprovalQueue` with a status (`PENDING`, `APPROVED`, `REJECTED`, `EXECUTED`, `FAILED`), so the audit trail is identical in both modes.

### Key files

- `app/src/main/java/com/evren/vex/agent/policy/PushMode.kt` — the mode enum and its persisted store
- `app/src/main/java/com/evren/vex/agent/policy/RemoteAction.kt` — typed actions and `ActionRequest`
- `app/src/main/java/com/evren/vex/agent/policy/ApprovalGate.kt` — the single permission decision point
- `app/src/main/java/com/evren/vex/agent/policy/ApprovalQueue.kt` — pending requests and status transitions
- `app/src/main/java/com/evren/vex/agent/github/GitHubPushService.kt` — the only component that mutates GitHub
- `app/src/main/java/com/evren/vex/ui/PushModeSettings.kt` — the Compose toggle
- `app/src/test/java/com/evren/vex/agent/policy/ApprovalGateTest.kt` — mode behaviour tests

## Planned architecture

- Kotlin + Jetpack Compose
- MVVM / Clean Architecture
- EVREN OpenAI-compatible API client
- GitHub REST API integration
- Android Keystore-backed credential protection
- Room task history and repository metadata
- GitHub Actions Android builds

## Security

Never commit API keys, GitHub tokens, `.env` files, or private project data. AI-generated changes must be reviewed before remote commits or other irreversible actions.

Full-access mode deliberately removes that review step for the agent's own GitHub operations. Enable it only on repositories where unreviewed pushes are acceptable.

## Build

The Android Gradle project is being added in the initial implementation milestone. Until `./gradlew` exists, the `Android CI` workflow detects the missing wrapper and skips the build instead of failing.
