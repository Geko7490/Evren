# Vex EVREN Code

A native Android coding-agent workspace powered by the EVREN LLM API and GitHub.

## Implemented on `feat/android-agent-foundation`

- Kotlin + Jetpack Compose native Android application
- AES-GCM credential vault backed by Android Keystore
- EVREN API connection validation and live `/v1/models` discovery
- Model selection and OpenAI-compatible chat completions
- Native function calling with a JSON action-protocol fallback
- GitHub PAT validation through the authenticated user endpoint
- Paginated listing of repositories accessible to the token
- Recursive repository tree browsing and text file viewer
- Dedicated agent working-branch creation
- Agent planner with repository tools: list files, read files, propose complete file changes
- Reviewable unified diff viewer
- Explicit approval before writing proposed changes to the working branch
- GitHub Actions debug APK workflow

## Current behavior and safety boundaries

- Credentials are stored locally with an Android Keystore-backed AES-GCM key.
- GitHub PAT is only sent to GitHub; it is never included in EVREN prompts.
- EVREN base URL is configurable in the connection screen. Default: `https://evren.ssyz.org.tr/v1`.
- Agent file edits remain proposals until the user approves them.
- Approved changes are committed only to the dedicated working branch, never directly to the default branch.
- Repository content is treated as untrusted input.
- The app does not claim to run builds or tests unless a real remote execution integration is added.

## Build

Requires JDK 17 and Android SDK 35.

```bash
gradle --no-daemon assembleDebug
```

GitHub Actions builds a debug APK and uploads it as an artifact.

## Next engineering work

- Persistent agent conversation/task history
- More efficient directory-scoped tree loading for very large repositories
- Streaming EVREN responses
- Remote GitHub Actions build monitoring and log analysis
- More complete editor experience and syntax highlighting
- Automated integration tests against a disposable test repository
