# Changelog

## [0.1.0] — 2026-09-18

- Send SSE heartbeat comments on streaming endpoints (`message:stream`, `tasks/{id}:subscribe`) at a configurable
  interval (`a2at.server.heartbeat-interval-seconds`, default 15 seconds, `0` disables). Keeps intermediaries
  from idle-timing-out long-running SSE connections during agent execution; the heartbeat integrates with the
  emitter lifecycle and stops on stream completion, error, timeout, or client disconnect.
- Make collection fields on model types defensively copied and expose them through unmodifiable
  views (`Workflow`, `WorkflowStep`, `WorkflowSearchResult`, `ExecutionResult`, `SendMessageResult`,
  `TaskResult`, `BusinessFailure`, and the message records). Source- and binary-compatible; callers
  that mutate the collections returned by these getters now receive `UnsupportedOperationException`
  instead of silently corrupting engine-owned snapshots.
- Template the sample OMC agent credentials file: the tracked
  `samples/src/main/resources/spn_agent_credentials.json` is replaced by
  `spn_agent_credentials.example.json` (real file is now git-ignored; copy the example to run the
  demos). Missing credentials now fail with an actionable message instead of a bare NPE.

## [0.0.10] — 2026-09-16

- Upgrade the A2A-T SDK dependency to 1.1.1 (patch release, no breaking API changes).
- Make the fallback task poll interval configurable via `WorkflowEngineClientConfig.taskPollIntervalMillis`
  (default 20 seconds). Polling only triggers when a streaming response is interrupted before the task
  reaches a terminal state; while SSE is alive, polling does not start.
- Wire host-declared `TaskAuthorizationProvider` beans into the starter's autoconfigured request handler.
  Every A2A operation (message send/stream, task query, cancel, subscribe) is offered to the provider
  before the agent executor runs; rejections surface as standard A2A error envelopes.

## [0.0.9] — 2026-09-14

- Make Negotiation-T activation host-owned. The business activates Negotiation-T per send via the new
  `MessageContent.withExtension(...)`; the engine mirrors that selection into `message.extensions` and the
  `A2A-Extensions` request header and validates that the target AgentCard declares the extension before dispatch.
  An unsolicited negotiation (host did not activate Negotiation-T) fails fast instead of silently entering the
  auto-loop.
- Accept Negotiation-T Propose carried on a taskless bare message (A2A-T pre-task negotiation, where the remote
  proposes before creating any task): `onNegotiation` now also fires for a valid Propose without an
  `INPUT_REQUIRED` task status. The exchange is correlated by contextId and negotiation id, and the follow-up send
  carries no taskId. Invalid negotiation metadata on a bare message now fails explicitly instead of being silently
  treated as a normal successful response.
- Converge non-functional formatting and javadoc drift between `main` and `dev` so the branches differ only in the
  instruction-platform integration.

## [0.0.8] — 2026-09-13

- Scope task lifecycle callbacks to each `sendTask` invocation so concurrent workflow executions
  cannot overwrite or receive one another's protocol events.
- Cancel pending task polls and acknowledgement timers when an invocation, subscription, client or
  transport finishes; this prevents delayed callbacks from surviving resource shutdown.
- Track raw transport activity separately from decoded Notification-T business events. Standard SSE
  comment heartbeats refresh `heartbeat().lastEventAt()` without increasing `eventCount`; use the
  new `lastBusinessEventAt()` accessor for business-event liveness.
- Add configurable connection/read deadlines to `LoadPsop`, and reject ambiguous simultaneous
  `credentialsConfigPath` and inline `credentialsConfig` configuration.
- Clarify `DefaultExtensionSender` ownership: its public constructor owns the supplied transport;
  callers that share a transport must use `DefaultExtensionSender.nonOwning(...)`.

## [0.0.7] — 2026-09-13

- Set the source-build version to `0.0.7-SNAPSHOT`. Maven Central version `0.0.6` was published from the same source
  commit as `0.0.5`; it does not contain the APIs listed in this `0.0.7` section.
- Preserve the published `spring-boot-starter` artifact coordinate. The temporary, unpublished
  `spring-boot-a2a-starter` name is not a consumable Maven Central coordinate.

- Add `WorkflowEngineClient.sendTask(...)` for task execution outside a DAG. It reuses the normal
  task state machine, keeps one task/context through Negotiation-T, and supports a per-call
  `NegotiationStrategy`. Local interaction failures cancel a known non-final remote task before
  releasing the conversation. The internal workflow dispatch operation and the former
  `sendMessage(...)` alias are no longer part of the public `WorkflowEngineClient` contract.
- Increase the default Notification-T acknowledgement timeout from 5 seconds to 5 minutes and expose the sample Spring
  setting as `a2a.notification-ack-timeout-seconds` /
  `A2A_NOTIFICATION_ACK_TIMEOUT_SECONDS`.

## [0.0.6] — 2026-09-09

- Published from the same source commit as `0.0.5`; no APIs or behavior changed.

## [0.0.5] — 2026-09-09

### Breaking: per-edge conditional routing

- `onRoute` now evaluates one conditional edge at a time: `RouteRequest` describes a single edge (step, condition,
  upstream window) and the callback returns `RouteDecision.allow()` /
  `deny()` for that edge. Unconditional edges always run and bypass the callback; allowed conditional edges activate in
  parallel with them. This replaces the previous N-choose-1 candidate selection (`RouteDecision.nextStep` and
  `RouteRequest.candidates` are removed)
  and fixes mixed-edge steps, where all unconditional edges were not previously guaranteed to activate.
- Workflow validation now rejects duplicate outgoing targets on one step, null edges and blank targets.
- After all route decisions succeed, `route_decision` is emitted once per outgoing edge, including unconditional edges,
  instead of once per step. A callback failure emits the workflow error and no partial per-edge decisions.

## [0.0.4] — 2026-09-08

- Make the Spring Boot A2A server auto-configuration opt-in: server beans are registered only
  when `a2at.server.enabled=true` (previously on by default). Host applications that expose A2A
  endpoints must now set the property explicitly.
- Open `A2ASlashActionAliasController` for host-side registration: public, non-final class with
  a public constructor, so applications outside the starter package can instantiate, replace or
  proxy it (`@ConditionalOnMissingBean` replacement was previously unreachable from host code).

## [0.0.3] — 2026-09-07

- Release streaming resources when publisher subscription setup throws synchronously.
- Align published dependency examples with 0.0.2 and refresh neutral architecture terminology.
- Update GitHub Actions to Node 24-compatible action versions.
- Add optional slash-style alias endpoints for A2A actions, disabled unless
  `a2at.server.slash-action-aliases-enabled=true`.

## [0.0.2] — 2026-09-07

- Publish the main-branch baseline under `net.openan.workflow.sdk`, including the parent POM,
  workflow-engine and spring-boot-starter. This tag has the same source tree as 0.0.1.

## [0.0.1] — 2026-09-04

- Use published A2A-T 1.1.0 from Maven Central; remove SDK source checkout/install from CI; move content generation,
  validation, templates and SDK initialization to the host.
- onTask returns final MessageContent; onNegotiation returns Send/Stop. Remove engine profiles and content handlers.
- Preserve complete ReceivedMessage and metadata layers alongside deterministic convenience outputs; keep local nested
  multi-output values.
- Deduplicate negotiation rounds, separate resource budgets from protocol rounds, and suppress late sends after
  timeout/cancellation.
- Separate authorization/notification lifecycle from workflow outcomes; never synthesize a subscription ACK.
- Observe serialized HTTP/JSON-RPC, real gRPC metadata/protobuf and dev vendor SDK traffic with mandatory redaction and
  bounded SSE.
- Add missing-input negotiation and independent transport-path regression tests; live model and production endpoint
  validation remains separate.
- Refresh bilingual callback, architecture and integration contracts. No older SDK compatibility layer.

### Release hardening

- Propagate workflow cancellation and close execution-owned resources exactly once.
- Anonymize orchestration access tokens and bound registry response deadlines.
- Preserve multi-value results from arbitrary terminal workflow nodes.
- Map pre-task failures from the standard A2A error envelope while keeping post-creation failures in task state.
- Publish a compiled host integration example and require sample regression tests.
- Keep documentation host-neutral; sample class and AgentCard identifiers remain unchanged.

Published Maven artifacts use the version selected by the release tag. The default source-build
`revision=0.0.9-SNAPSHOT` is not a Maven Central release version.
