# Vex EVREN Code

Native Android coding agent powered by the EVREN LLM API and GitHub.

## Current milestone

The initial native Android foundation is implemented on the `feat/android-agent-foundation` branch.

Included:
- Kotlin + Jetpack Compose Android application
- Dark Vex visual identity
- EVREN API key and GitHub PAT entry screen
- AES-GCM credential vault backed by Android Keystore
- Android GitHub Actions debug build workflow

Next:
- EVREN model discovery and chat API
- GitHub authentication and full repository pagination
- Repository tree and file reader
- Persistent Agent task orchestration
- Validated tools, code diffs, branch workflow, and remote builds

## Build

Requires JDK 17 and Android SDK 35.

```bash
gradle assembleDebug
```

## Security

Never commit API keys, GitHub tokens, environment files, or private project data. Credentials are stored locally using an Android Keystore-backed AES-GCM key. Repository content is untrusted input and must never be treated as executable agent instructions.
