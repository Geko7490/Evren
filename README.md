# Vex EVREN Code

Native Android coding agent powered by the EVREN LLM API and GitHub.

## Project status

The repository is being initialized. The first milestone is a native Kotlin + Jetpack Compose application with secure credential storage, EVREN model discovery, GitHub repository browsing, and a persistent agent foundation.

> **Repository scan note:** as of this commit the repository contains only this
> `README.md`. There is no `app/`, `src/`, Gradle build script, or GitHub Actions
> workflow yet, so no source-level bug scan or build verification is possible.
> The issues listed below are the ones that are already known from the design and
> from observed runtime behaviour.

## Planned architecture

- Kotlin + Jetpack Compose
- MVVM / Clean Architecture
- EVREN OpenAI-compatible API client
- GitHub REST API integration
- Android Keystore-backed credential protection
- Room task history and repository metadata
- GitHub Actions Android builds

## Known issues

### 1. OCR model routed to the chat endpoint (HTTP 400)

**Symptom**

```
EVREN HTTP 400: {"message":"Model 'deepseek-ocr-2' sohbet arayüzünü desteklemez.
Taranmış belgeler için lütfen POST /v1/ocr ucunu kullanın.",
"type":"invalid_request_error","param":null,"code":"endpoint_mismatch"}
```

**Cause**

The client sends every model to the OpenAI-compatible chat endpoint
(`POST /v1/chat/completions`). `deepseek-ocr-2` is an OCR-only model and is not
served by the chat interface, so the request is rejected with
`endpoint_mismatch`.

**Required behaviour**

- Chat/reasoning models → `POST /v1/chat/completions`
- `deepseek-ocr-2` (and any other OCR-only model) → `POST /v1/ocr`

**Implementation guidance for the API client**

- Keep an explicit model → endpoint capability map instead of assuming every
  model is chat-capable.
- Validate the selected model against the endpoint *before* sending the request
  and fail fast with a clear, user-facing message.
- Treat `code == "endpoint_mismatch"` as a recoverable error: surface the
  suggested endpoint from the error body rather than retrying the same call.
- Do not silently fall back to a different model; the user must see which model
  and endpoint were used.

### 2. No build or CI pipeline yet

The Gradle project and the GitHub Actions Android workflow described above do not
exist yet, so there is currently no automated compile/lint gate. This should be
added before feature work continues.

## Troubleshooting

| Error | Meaning | Fix |
| --- | --- | --- |
| `endpoint_mismatch` | Model is not supported by the chosen endpoint | Route OCR models to `/v1/ocr`, chat models to `/v1/chat/completions` |
| `invalid_request_error` | Malformed request body or unsupported parameter | Check the payload against the endpoint's schema |
| HTTP 401 / 403 | Missing or invalid API key / GitHub token | Re-enter credentials; they are stored via Android Keystore |

## Security

Never commit API keys, GitHub tokens, `.env` files, or private project data. AI-generated changes must be reviewed before remote commits or other irreversible actions.

## Build

The Android Gradle project and GitHub Actions workflow are being added in the initial implementation milestone.
