# Vex EVREN Code

Native Android coding agent powered by the EVREN LLM API and GitHub.

## Current milestone: Android foundation

Implemented on `feat/android-agent-foundation`:

- Native Kotlin + Jetpack Compose application
- Vex dark visual identity and credential entry screen
- AES-GCM credential vault backed by Android Keystore
- EVREN model discovery and non-streaming chat API client
- GitHub REST client with full pagination for accessible repositories
- Initial Agent task and lifecycle domain model
- GitHub Actions Android debug build workflow

## Next implementation steps

- Wire connection validation and model/repository screens into the UI
- Persist task history and repository metadata with Room
- Implement repository tree and file retrieval
- Add Agent planning, validated tool calling, and task resume
- Add isolated branch editing, diff approval, commits, and pull requests
- Integrate Actions monitoring, build logs, and artifacts

## Build

Requires JDK 17 and Android SDK 35.

```bash
gradle assembleDebug
```

## Security

Never commit API keys, GitHub tokens, environment files, or private project data. Credentials are stored locally using an Android Keystore-backed AES-GCM key. GitHub tokens are used only by the GitHub client and are never included in EVREN prompts. Repository content is untrusted input and must not be treated as executable agent instructions.
