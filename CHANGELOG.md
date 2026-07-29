# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-07-28

First public release. The SDK ships a clean transport-facade architecture with single-responsibility decision interfaces
and full A2A-T extension support. Feature parity with the Python SDK is maintained.

### Added

- `A2ATransport`: shared wire layer owning the A2A client runtime, auth manager, agent-card map, and streaming event
  extraction
- `ExtensionSender` / `DefaultExtensionSender`: one-shot pre-positioning facade for Authorization-T and Notification-T
  (long-lived SSE subscription)
- `ControlPoint` / `ExtensionCallback` split: flow decisions (`onTask` / `onSelfTask` / `onRoute` / `onNegotiation`) and
  reactive hooks (`onAuthorization` / `onNotification`) on separate interfaces
- `NegotiationStrategy`: pluggable clarification strategy injected into
  `DefaultControlPoint`
- `SELF_LOOP` step type for local task handling without an A2A-T message
- `ANY_SUCCESS` step policy with early cancellation of remaining subtasks
- Parallel DAG step dispatch and context assembly (`ContextBuilder`)
- `EventType` constants covering runner lifecycle, step/task execution, agent traffic, and A2A-T extension events
- `ExecutePsop.Builder`: fluent builder with event stream, lifecycle bracket, and `onFinish` persistence hook
- `A2ATExtension` enum encapsulating all extension URIs (no hardcoded strings)
- `DefaultExtensionSender` prompt-generation dispatch (Task-T via the A2A-T SDK; Authorization-T / Notification-T /
  Negotiation-T reserved for SDK support)
- [DESIGN.md](docs/DESIGN.md) architecture document

### Changed

- `DefaultWorkflowEngineClient` is now a facade over `A2ATransport`, owning only the workflow send path (Task-T prompt
  generation, Negotiation-T auto-loop, event callback, ControlPoint/ExtensionCallback wiring)
- Pre-positioning sends moved from `WorkflowEngineClient` to `ExtensionSender`
- `ExtensionRegistry` auto-registers only Task-T and Negotiation-T (in-workflow handlers); Authorization-T /
  Notification-T are one-shot pre-positioning operations

## [0.3.0] - 2026-07-25

### Added

- `EnvFileLoader`: loads `.env` file entries into system properties, bridging A2A-T SDK's internal `.env` loading with
  `CredentialCrypto` and other components that read from `System.getenv()` / `System.getProperty()`
- `ProtocolLogger`: dedicated `PROTOCOL` SLF4J logger for full protocol-level request/response dumps (headers + body),
  enabling verification against real network captures
- `ExtensionInterceptor`: metadata-aware `A2A-Extensions` header injection; only advertises extensions actually present
  in the current message metadata
- `AuthProvider` interface: custom authentication for non-standard auth mechanisms (SSO, API keys, custom headers)
- `sendExtensionMessage()` on `WorkflowEngineClient`: one-shot extension messages for Authorization-T pre-positioning
  and Notification-T subscription
- `ExecutePsop.Builder`: fluent builder API replacing the 14-argument static
  `execute()` method
- AgentCard Jackson module for deserializing AgentCard JSON with security scheme normalization
- HTTPS/TLS support with configurable verification and CA trust store
- AES-GCM credential encryption (`enc:iv:ciphertext` format) with
  `A2AT_CRED_KEY` environment variable
- SSE timeout configurable via `sendTimeoutSeconds` (default 600s)
- Complete Chinese and English documentation:
    - [Integration Guide](docs/en/INTEGRATION_GUIDE.md) / [中文](docs/zh/INTEGRATION_GUIDE.md)
    - [API Reference](docs/en/API_REFERENCE.md) / [中文](docs/zh/API_REFERENCE.md)
    - [Developer Guide](docs/en/DEVELOPER_GUIDE.md)
    - [Contributing Guide](CONTRIBUTING.md)

### Changed

- `A2A-T extension content placed in artifact `metadata` (not `parts.text`)
  per protocol specification in `调用过程.md`
- Negotiation-T metadata URI aligned with AgentCard declaration (uppercase, no `/v1` suffix)
- Negotiation text passed to SDK's `startNegotiation` uses short request text, not full task input
- AgentCard is now strongly typed (`org.a2aproject.sdk.spec.AgentCard`)
  throughout the codebase; `List<?>` and `Map` replaced with typed equivalents
- Default SSL verify changed from `false` to `true` for production safety
- Default send timeout increased to 600 seconds (10 minutes)
- `DEVELOPER_GUIDE.md` moved from project root to `docs/`

### Fixed

- Authentication 401 failures caused by `A2AT_CRED_KEY` not being available to `System.getenv()` when `.env` file is
  used (loaded by A2A-T SDK internally but not propagated to OS environment)
- All four extensions no longer injected into every request's
  `A2A-Extensions` header; only active extensions are advertised
- `chunked transfer encoding, state: READING_LENGTH` errors after terminal SSE events downgraded to DEBUG level
  (expected behavior)
- SDK `receiveNegotiation` "Unsupported negotiation type: FULFILLMENT"
  warning downgraded to DEBUG (known SDK limitation, fallback works)

### Removed

- 14-argument static `ExecutePsop.execute()` method (replaced by Builder)
- `AgentCardMapper` (replaced by Jackson + `AgentCardJacksonModule`)
- Redundant blank lines in method bodies
- `AuthorizationTHandler` and `NotificationTHandler` (these are now pre-positioning operations done via
  `sendExtensionMessage`, not part of the workflow extension handler chain)
