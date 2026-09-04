# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine is a Java SDK for orchestrating multi-agent workflows using the A2A protocol with
A2A-T telecom extensions.

The engine schedules A2A tasks, envelopes final content, manages authentication/transport and waits for results. Host
callbacks own A2A-T generation, semantic validation, schemas and any LLM calls.

## 2. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK         | 17+     |
| Maven       | 3.6+    |

## 3. Maven Dependency

```xml

<dependency>
    <groupId>net.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
<version>1.0.0</version>
</dependency>
```

## 4. Quick Start

Four steps: define workflow -> load AgentCard -> implement ControlPoint -> execute.

Complete runnable source: [HostQuickStart.java](../../samples/src/main/java/dev/openan/workflow/engine/examples/demo/HostQuickStart.java).
It is compiled and its remote-task/local-aggregation flow is tested by HostQuickStartTest.
Copy that source into your host project or run it from samples in IDEA (registry URL, target agent name, credentials path).
The snippets below explain the same API; use one AgentCard loading option, and implement domain-specific content for Task-T.
Nonempty business conditions require an onRoute policy; this minimal workflow uses unconditional edges.

### 4.1 Define a Workflow

```java
Workflow workflow = Workflow.builder()
        .name("Service Analysis")
        .steps(List.of(
                WorkflowStep.builder()
                        .name("analyze")
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Dispatched Agent")
                                        .skill("analysis")
                                        .description("Analyze the request")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("merge")
                                        .condition("")
                                        .build()))
                        .layer(0)
                        .build(),
                WorkflowStep.builder()
                        .name("merge")
                        .stepType(StepType.SELF_LOOP)   // host-agent local step; no A2A-T send
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Host Agent")
                                        .skill("aggregate")
                                        .description("Merge results")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("end")
                                        .condition("")
                                        .build()))
                        .layer(1)
                        .contextFrom(List.of("*"))
                        .build()
        ))
        .build();
```

### 4.2 Load AgentCards

```java
// Option A: From JSON files
ObjectMapper mapper = new ObjectMapper()
                .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);

// Option B: From Registry Center
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
ObjectMapper cardMapper = new ObjectMapper().registerModule(new AgentCardJacksonModule());
List<AgentCard> cards = registry.fetchAgentCards().stream()
        .map(raw -> cardMapper.convertValue(raw, AgentCard.class)).toList();
```



RegistryClient defaults to a 30-second deadline including response-body consumption. For another budget, use
`new RegistryClient(url, true, Duration.ofSeconds(15))`; only positive durations are accepted. Query access tokens
are logged as `<anonymous>`; do not log raw discovery URLs from host code.

### 4.3 Implement ControlPoint

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask returns final parts/metadata/extensions; the engine sends them without generating or rewriting content.
onSelfTask returns local TaskResult, onRoute selects an allowed candidate, and onNegotiation returns Send or Stop.
Unimplemented callbacks fail explicitly. No echo-success, first-branch choice or automatic consent.
See [Business callback contract](BUSINESS_CALLBACKS.md) for fields and working examples.

```java
ControlPoint callbacks = ControlPoint.builder()
    .onTask(request -> CompletableFuture.completedFuture(
        MessageContent.text(request.getInstruction())))
    .onSelfTask(request -> CompletableFuture.completedFuture(
        TaskResult.success(List.of(Map.of(
            "sourceResults", request.getWorkflowInput().upstreamResults())))))
    .onRoute(request -> CompletableFuture.failedFuture(
        new IllegalStateException("Supply a routing policy for " + request.stepName())))
    .onNegotiation(request -> CompletableFuture.completedFuture(
        new NegotiationReply.Stop("manual.required", "Manual confirmation required")))
    .build();
```

### 4.4 Execute

```java
CompletableFuture<ExecutionResult> execution = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(callbacks)
        .runtimeIntent("Analyze the service request")
        .lang("zh")
        .credentialsConfigPath("credentials.json")
        .sslVerify(true)
        .onFinish((r, history) -> {
            System.out.println("Result: " + r.isSuccess());
        })
        .execute();
try {
    ExecutionResult result = execution.get(10, TimeUnit.MINUTES);
    System.out.println(result.getStepOutputs());
} finally {
    if (!execution.isDone()) execution.cancel(true);
}
```

Required: `psop`, `controlPoint`. All other config items have defaults.

## 5. Configuration

### 5.1 .env File

The engine does not read A2A-T .env files or create LLM clients. If host-agent callbacks use A2A-T, initialize
A2ATClient/A2ATServer with a host-owned environment file containing provider/model/key/base URL and A2AT_LANGUAGE. A
sample-specific a2atEnvPath is not an engine builder option. Keep dispatched-agent credential decryption separate from
LLM configuration: pass the secret explicitly through WorkflowEngineClientConfig.builder()
.credentialEncryptionKey (key) when using encrypted built-in credentials, then pass that configured engineClient to
ExecutePsop. Custom AuthProvider owns its own token/configuration. Tests use the current SDK SPI with an offline
provider, not template overrides or production fallbacks.

### 5.2 Credentials File

For agents requiring authentication, provide a JSON credentials file:

```json
{
  "Dispatched Agent": {
    "bearerAuth": {
      "login_url": "https://agent.example.com/oauth/token",
      "method": "POST",
      "request_fields": {
        "username": "service-account",
        "password": "enc:<base64-iv>:<base64-ciphertext>"
      },
      "token_field": "accessSession",
      "token_ttl": 3600
    }
  }
}
```

- Encrypted passwords use `enc:<iv>:<ciphertext>` format, key from `A2AT_CRED_KEY`
- Plaintext values (no `enc:` prefix) are also accepted
- Tokens are cached and refreshed automatically

### 5.2.1 Credential Encryption and Key Management

Passwords in the credentials file support encrypted storage to avoid plaintext exposure.

**Generate a key**

```bash
openssl rand -hex 32
```

Example output:

```
4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Supply the key explicitly as `credentialEncryptionKey`, or set the OS environment variable below.
Resolution order is explicit instance key > OS environment > JVM property. The engine does not load `.env` automatically:

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

For instance-local encryption, call `CredentialCrypto.encrypt(plaintext, keyHex)`. An explicit key overrides OS/JVM
configuration without modifying system properties. Never log plaintext or the key.

**Encrypt a password**

Build the jar with `mvn -pl workflow-engine -am package`; commands below run from the repository root.
`set` is Windows cmd syntax (PowerShell: `$env:A2AT_CRED_KEY='...'`). This CLI needs only the SDK jar and JDK.
Use disposable example values here: command-line passwords/keys can appear in shell history and process listings.
For production, obtain secrets securely in the host and use the Java encryption API.

```bash
# Option 1: set env var first
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"

# Option 2: pass key as second argument
java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Output:

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

Paste the output into the `value` field of the credentials JSON.

**Rotating the key**

1. Generate a new key: `openssl rand -hex 32`
2. Update the host secret store / explicit `credentialEncryptionKey`, or its OS/JVM `A2AT_CRED_KEY`
3. Re-encrypt all passwords:
   `java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "plaintext" new-key`
4. Update the `enc:...` results in the credentials JSON file

> The `.env` file should not be committed to version control. Add it to `.gitignore`.

### 5.3 Custom Authentication (AuthProvider)

When tokens are obtained by the integrator or an external identity service, or the mechanism is non-standard, implement
the `AuthProvider` interface. It has a single method:

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
```

`applyAuth` is called before every message send. The implementation adds auth headers to the mutable `headers` map.

**Scenario 1: Enterprise SSO / External Token Service**

```java
public class SsoAuthProvider implements AuthProvider {
    private final SsoClient ssoClient;

    public SsoAuthProvider(SsoClient ssoClient) {
        this.ssoClient = ssoClient;
    }

    @Override
    public void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers) {
        String token = ssoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
    }
}

// Register
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider(new SsoAuthProvider(mySsoClient))
        .sslVerify(true)
        .build();
```

**Scenario 2: AgentCard has no securitySchemes, but server requires auth**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            headers.put("X-API-Key", "static-api-key-value");
        })
        .build();
```

**Scenario 3: Custom header name (non-standard Authorization)**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            String token = refreshTokenIfNeeded(agentName);
            headers.put("X-Auth-Token", token);
            headers.put("X-Tenant-Id", "tenant-001");
        })
        .build();
```

**Notes:**

- `applyAuth` is called on every message send; implement token caching/refresh logic inside
- `securitySchemes` lists authentication methods the agent supports; `securityRequirements` marks the methods required
  by this integration. Empty `securityRequirements` disables built-in credential authentication, but `AuthProvider` is
  still called
- `AuthProvider` can be the sole authentication source even when `securityRequirements` is non-empty
- If both credentials and `AuthProvider` are configured, their headers are generated independently and merged; different
  values for the same header fail fast
- On auth failure(e.g. token retrieval throws), the exception propagates to `send()` and the request is blocked

## 6. AgentCard Definition

AgentCards declare extensions via `capabilities.extensions`:

```json
{
  "name": "Dispatched Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "Structured task prompt",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
        "description": "Negotiation text exchange",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "Authorization operation",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "Notification subscription",
        "required": false
      }
    ]
  },
  "securitySchemes": {
    "bearerAuth": {
      "type": "http",
      "scheme": "bearer"
    }
  },
  "securityRequirements": [
    {
      "schemes": {
        "bearerAuth": []
      }
    }
  ],
  "supportedInterfaces": [
    {
      "protocolBinding": "HTTP+JSON",
      "protocolVersion": "1.0",
      "url": "https://127.0.0.1:26335/a2a/json"
    }
  ]
}
```

Extension URIs must match the A2A-T definitions exactly.

Both `securitySchemes` and `securityRequirements` are optional. The former lists authentication methods the agent
supports; the latter marks methods required by this integration. `securityRequirements: []` disables built-in credential
authentication.

## 7. A2A-T Extensions

Only a remote `INPUT_REQUIRED` carrying valid Negotiation-T Propose enters `onNegotiation`. Terminal responses never
restart negotiation; ordinary `INPUT_REQUIRED` fails explicitly. The host validates/interprets the proposal and
generates the final Accept/Reject/Abort with its own A2A-T client. Use `A2atMessages.contextOf(request.received())` to
obtain the received context; reply with the same id, round and maxRounds. The last allowed round can still be answered.
Do not call nextRound for an ending reply or return a new Propose.

Return `new NegotiationReply.Send(content)` to send that exact content. Return `new NegotiationReply.Stop(code, reason)`
to stop locally without a generated Abort. Repeated task/session/round events do not repeat the callback or submission.
Unchanged waiting state is observed with getTask.
`maxNegotiationExchanges` (default 3) bounds local interactions, independently of the SDK context's maxRounds. Timeout,
exhausted budget or a missing handler fails locally; no implicit Accept or synthesized Abort. Accept/Reject ACKs in
SUBMITTED/WORKING remain pending and are observed without resending the command. A business-sent Abort is never
task success, even if the dispatched agent acknowledges it with COMPLETED.

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);
NotificationSubscription openNotification(String agentName, MessageContent content,
    BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

The host agent generates final Authorization-T/Notification-T content and calls these methods on separate
transport/runtime/context instances. The listener receives the handle and complete ReceivedMessage, and closes on the
host-defined terminal event. acknowledgement() and completion() separately represent ACK and actual stream exit;
neither is a workflow prerequisite.

## 8. HTTPS Configuration

```java
// Controlled local diagnostics only: skip chain validation, but still verify the host name
.sslVerify(false)

// Production: enable verification + custom CA certs
.sslVerify(true)
.caCertsPath("/path/to/ca-certs.pem")

// Optional mTLS and CRL. Private keys support PKCS#8 PEM/DER; encrypted keys require a password
.clientCertPath("/path/to/client-cert.pem")
.clientKeyPath("/path/to/client-key.pem")
.clientKeyPassword("change-me")
.crlPath("/path/to/revocations.crl")
```

For HTTP/JSON-RPC, TLS policy is scoped to the current client and never changes JVM-wide hostname verification;
disabling chain verification still loads the mTLS client identity. Keep `sslVerify(true)` in production and trust
self-signed certificates through `caCertsPath`. The default gRPC runtime uses plaintext when `sslVerify(false)` is set,
so mTLS and `crlPath` cannot be combined with that mode and fail fast instead of being ignored.

## 9. Logging

The `PROTOCOL` logger at DEBUG records observations at the actual transport boundary. HTTP/JSON-RPC logs preserve the
serialized body and application headers after A2A SDK processing, including A2A-Version when actually present. gRPC
records actual metadata and a protobuf JSON view; that view is not an HTTP JSON body. The engine never adds a missing
header just to make logs look uniform. Automatic network headers, HTTP/2 frames, TLS records and server-side bytes are
not captured.

`MODEL_PREVIEW` is optional, disabled by default, and never wire proof.

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
logger.protocol.additivity=true
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
```

Body observation defaults to enabled when DEBUG is enabled; disable it explicitly for sensitive deployments. JVM
properties take precedence over same-named environment variables. Header credentials/cookies/tokens and recognized
secret body fields are always redacted; this cannot be disabled. This is field-based redaction, not a classifier for all
personal/business-sensitive content. Bodies are bounded (raw collectors use the configured numeric limit as bytes;
emitted text uses characters). Oversized SSE frames are dropped whole until the next delimiter and marked
`dropped-capacity`; disabled, truncated and interrupted observations are labeled. UTF-8 is decoded after assembling
chunks. Observers cannot fail delivery. File references are recorded as references and are never downloaded for logging.
requestId correlates each call; workflow calls additionally carry executionId/logicalTaskId/attempt,
agent/contextId/channel and remoteTaskId when known. These are local log fields, not wire metadata.

### Verify protocol and negotiation logs

Use a controlled test in which one dispatched agent returns `INPUT_REQUIRED` with a valid Negotiation-T Propose. The
expected sequence is request → Propose → `onNegotiation` → final Send/Stop → terminal task result. The `PROTOCOL` logger
must show the observed request/response boundaries and correlation fields without exposing credentials. A declared
Negotiation-T capability alone does not force negotiation.

## 10. Event Callback

Subscribe to execution events for real-time monitoring:

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case EventType.STEP_START -> System.out.println("Step started: " + data.get("step"));
            case EventType.TASK_STATUS_CHANGED -> System.out.println(
                    data.get("agent") + " state: " + data.get("state"));
            case EventType.NEGOTIATION_REQUEST -> System.out.println(
                    "Negotiation from " + data.get("agent"));
            case EventType.COMPLETE -> System.out.println("Workflow complete");
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

Common event types: `START`, `STEP_START`, `TASK_REQUEST`,
`TASK_RESPONSE`, `TASK_STATUS_CHANGED`, `STEP_COMPLETE`,
`NEGOTIATION_REQUEST`, `NEGOTIATION_RESOLVED`, `NEGOTIATION_FAILED`,
`ROUTE_DECISION`, `WORKFLOW_COMPLETE`, `COMPLETE`, `ERROR`, `CLOSE`.

## 11. Load Workflows from Orchestration Center

```java
// Search by intent
List<WorkflowSearchResult> results = LoadPsop.search(
                "https://orchestration.example.com", "analyze service request", 5, null, true);

// Load full workflow by ID
Workflow workflow = LoadPsop.load(
        "https://orchestration.example.com", results.get(0).getWorkflowId(), null, true);
```

## 12. Custom Extensions

Construct final MessageContent(parts, metadata, extensions), with host-owned content generation/validation. No engine
handler or SDK instance registration. A2atMessages.from copies A2A-T metadata; other extensions can directly supply
metadata and activation URIs. AgentCard declarations never cause implicit generation.

## 13. External endpoints and startup ownership

### 13.1 Orchestration-center HTTPS development

`LoadPsop.search/load` receives an explicit `sslVerify` argument. Keep it `true` in production. In a controlled
development environment, `false` disables certificate-chain and hostname checks for that connection only and emits a
`[Registry] INSECURE_TLS` warning. It does not modify JVM-wide TLS defaults.

The HTTPS server must still present a certificate, and client-certificate requirements (mTLS) still apply.
This mode cannot authenticate the server and is vulnerable to interception; do not use it in production.
Keep verification enabled in production, configure matching server SANs and trust its CA in the running JVM.
The dispatched-agent `caCertsPath` setting is not forwarded to LoadPsop.
LoadPsop never changes JVM-wide SSLContext, SocketFactory or HostnameVerifier defaults.
Dispatched-agent HTTP/JSON-RPC retains hostname checks when chain verification is disabled.

### 13.2 Dispatched-agent endpoints

The engine consumes AgentCards and never starts dispatched-agent servers. The host agent owns AgentCard discovery,
endpoint reachability checks, and any development fixtures. When using externally managed dispatched agents, do not
start local fixtures on the same endpoints. Keep production AgentCards and credentials outside tracked sample resources.

### 13.3 Demo preflight task cleanup

The demo queries each dispatched agent for `SUBMITTED`, `WORKING`, `INPUT_REQUIRED` and `AUTH_REQUIRED` tasks before
opening extension channels or starting a workflow, then cancels every visible result through the standard A2A task API.
It follows `nextPageToken`, de-duplicates tasks that change state during the scan, and accepts the race where a task
becomes terminal before cancellation. Query and cancellation use a dedicated short-lived authenticated transport; they
never reuse workflow, authorization or notification lifecycles.

Cleanup is enabled and fail-fast by default so stale tasks cannot silently lead to a capacity error. Configure it with
`A2A_TASK_CLEANUP_ENABLED`, `A2A_TASK_CLEANUP_FAIL_FAST`, `A2A_TASK_CLEANUP_PAGE_SIZE` (1–100), and
`A2A_TASK_CLEANUP_MAX_TASKS`. The list operation is authorization-scoped. If several installations share one identity,
the demo may cancel active tasks created by another installation; use an isolated identity or disable cleanup after
providing an equivalent ownership-aware policy.

## 14. A2A errors and task failures

Keep failures before and after task creation separate. A request rejected before a task is created uses a
non-2xx HTTP response with the standard A2A `google.rpc.Status` JSON envelope:

```json
{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"A required parameter is missing","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"INVALID_PARAMS","domain":"a2a-protocol.org","metadata":{"field":"port"}}]}}
```

`RemoteA2AErrorException` preserves the observed HTTP status, envelope code/status/message, typed details,
ErrorInfo reason/domain and safe response headers. `findIn(Throwable)` also projects typed A2A Java SDK
errors. The direct transport checks ordinary responses and defensively detects a top-level error envelope if
an SDK exposes a pre-stream rejection as SSE data. Nested `error` objects inside a task, message or artifact
remain business content.

If an SSE call closes before delivering any A2A event, the transport fails immediately instead of waiting for the
workflow send timeout. The underlying SDK does not expose the HTTP status of an empty streaming response, so this case
is reported as a transport/protocol failure rather than a fabricated HTTP error code.

Workflow history and TASK_RESPONSE expose a stable code such as `a2a.invalid_params`; unknown HTTP errors
fall back to `a2a.http.<status>`. `errorDetails` retains protocol facts and `retryAfter` when observed. Such an
error creates no successful output, does not invoke onNegotiation and is not retried automatically.

After a task has been created, a business execution failure is not an HTTP protocol error. The agent returns
HTTP 200 with a Task or status update in `TASK_STATE_FAILED`; the TaskStatus message, task metadata and
artifacts carry the available failure evidence. The engine marks the workflow task failed, keeps that evidence
in `receivedMessages`, and does not interpret an extension-specific result schema. Independent authorization
and subscription failures remain independent of workflow outcome.

For an `ALL_SUCCESS` step, one failed task does not retroactively cancel a peer call that was already dispatched: the
peer result or timeout is collected before the workflow returns `success=false`. Downstream steps are not invoked;
successful peer results remain in history rather than being turned into a fabricated aggregate. There is no automatic
retry, queuing, or partial-success merge. `ANY_SUCCESS` nodes retain their declared first-success semantics.

Observe failures through TASK_RESPONSE or inspect result.history in `onFinish(result, events)`;
onTask prepares outbound content and is not an error-retry callback.
Executor node events include executionId; TASK_RESPONSE and history also include the logical taskId.
The host agent decides how to expose a failed execution to its caller. It must not convert a failed execution into a
success artifact. A persistent host owns notification subscriptions independently of workflow results.

Logging responsibilities: PROTOCOL captures observed traffic, correlated by requestId rather than
adjacent log entries; A2A_ERROR reports a protocol rejection; TASK_FAILED identifies the execution,
step, logical task, agent, code and reason; WORKFLOW_STOPPED lists unexecuted steps.
Sample TASK_RESPONSE logs bridge contextId and executionId. Recognized A2A errors use WARN summaries,
while unexpected exceptions retain ERROR stack traces. Known credentials and Bearer/Basic values are redacted.
Logging configuration, pretty display and observer callbacks do not determine task outcomes.

## 15. Interface Reference

| Interface/Class                                        | Purpose                                                                 |
|--------------------------------------------------------|-------------------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | Workflow execution entry point                                          |
| `ControlPoint` / `DefaultControlPoint`                 | Business decisions (onTask, onSelfTask, onRoute, onNegotiation, etc.)   |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | Workflow send (sendMessage, auth, extensions)                           |
| `ExtensionSender` / `DefaultExtensionSender`           | Independent Authorization-T operations and Notification-T subscriptions |
| `A2ATransport`                                         | Shared wire layer (A2A Java client runtime, auth, SSE consumer)         |
| `WorkflowEngineClientConfig`                           | Configuration (TLS, auth, deadlines, executor limits, negotiation exchange budget)   |
| `AuthProvider`                                         | Custom authentication                                                   |
| `EventCallback` / `EventType`                          | Event callback                                                          |
| `LoadPsop` / `RegistryClient`                          | Workflow loading / AgentCard fetching                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | Workflow definition                                                     |
| `ExecutionResult`                                      | Execution result                                                        |
| `SendMessageResult` / `TaskResult`                     | Message/task response                                                   |
