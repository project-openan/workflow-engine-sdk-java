# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-07-25

### Added
- `EnvFileLoader`: loads `.env` file entries into system properties, bridging
  A2A-T SDK's internal `.env` loading with `CredentialCrypto` and other
  components that read from `System.getenv()` / `System.getProperty()`
- `ProtocolLogger`: dedicated `PROTOCOL` SLF4J logger for full protocol-level
  request/response dumps (headers + body), enabling verification against
  real network captures
- `ExtensionInterceptor`: metadata-aware `A2A-Extensions` header injection;
  only advertises extensions actually present in the current message metadata
- `AuthProvider` interface: custom authentication for non-standard auth
  mechanisms (SSO, API keys, custom headers)
- `sendExtensionMessage()` on `WorkflowEngineClient`: one-shot extension
  messages for Authorization-T pre-positioning and Notification-T subscription
- `ExecutePsop.Builder`: fluent builder API replacing the 14-argument static
  `execute()` method
- AgentCard Jackson module for deserializing AgentCard JSON with security
  scheme normalization
- HTTPS/TLS support with configurable verification and CA trust store
- AES-GCM credential encryption (`enc:iv:ciphertext` format) with
  `A2AT_CRED_KEY` environment variable
- SSE timeout configurable via `sendTimeoutSeconds` (default 600s)
- Complete Chinese and English documentation:
  - [Integration Guide](docs/INTEGRATION_GUIDE.md) / [中文](docs/INTEGRATION_GUIDE_zh.md)
  - [API Reference](docs/API_REFERENCE.md) / [中文](docs/API_REFERENCE_zh.md)
  - [Developer Guide](docs/DEVELOPER_GUIDE.md)
  - [Contributing Guide](CONTRIBUTING.md)

### Changed
- `A2A-T extension content placed in artifact `metadata` (not `parts.text`)
  per protocol specification in `调用过程.md`
- Negotiation-T metadata URI aligned with AgentCard declaration (uppercase,
  no `/v1` suffix)
- Negotiation text passed to SDK's `startNegotiation` uses short request
  text, not full task input
- AgentCard is now strongly typed (`org.a2aproject.sdk.spec.AgentCard`)
  throughout the codebase; `List<?>` and `Map` replaced with typed equivalents
- Default SSL verify changed from `false` to `true` for production safety
- Default send timeout increased to 600 seconds (10 minutes)
- `DEVELOPER_GUIDE.md` moved from project root to `docs/`

### Fixed
- Authentication 401 failures caused by `A2AT_CRED_KEY` not being available
  to `System.getenv()` when `.env` file is used (loaded by A2A-T SDK
  internally but not propagated to OS environment)
- All four extensions no longer injected into every request's
  `A2A-Extensions` header; only active extensions are advertised
- `chunked transfer encoding, state: READING_LENGTH` errors after terminal
  SSE events downgraded to DEBUG level (expected behavior)
- SDK `receiveNegotiation` "Unsupported negotiation type: FULFILLMENT"
  warning downgraded to DEBUG (known SDK limitation, fallback works)

### Removed
- 14-argument static `ExecutePsop.execute()` method (replaced by Builder)
- `AgentCardMapper` (replaced by Jackson + `AgentCardJacksonModule`)
- Redundant blank lines in method bodies
- `AuthorizationTHandler` and `NotificationTHandler` (these are now
  pre-positioning operations done via `sendExtensionMessage`, not part
  of the workflow extension handler chain)
