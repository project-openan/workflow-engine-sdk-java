# Business callback integration contract

Release `0.1.0` API; A2A-T SDK `1.1.1`, A2A Java `1.2.0.Final`. The engine owns DAG scheduling, standard A2A envelopes,
auth, transport and task waiting. The host owns content, schemas, templates, semantic validation and any LLM calls.
The business contracts are independent of the transport runtime.

## 1. Callbacks

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask returns final parts/metadata/extensions; the engine sends them without generating or rewriting content.
onSelfTask returns local TaskResult, onRoute independently allows or denies one conditional edge, and onNegotiation
returns Send or Stop. Unconditional edges bypass onRoute and always run. Unimplemented callbacks fail explicitly. No
echo-success, implicit route approval or automatic consent.
See [Business callback contract](BUSINESS_CALLBACKS.md) for fields and working examples.

```java
ControlPoint callbacks = ControlPoint.builder()
    .onTask(request -> CompletableFuture.completedFuture(
        MessageContent.text(request.getInstruction())))
    .onSelfTask(request -> CompletableFuture.completedFuture(
        TaskResult.success(List.of(Map.of(
            "sourceResults", request.getWorkflowInput().upstreamResults())))))
    .onRoute(request -> CompletableFuture.completedFuture(
        RouteDecision.deny("Replace with host condition evaluation")))
    .onNegotiation(request -> CompletableFuture.completedFuture(
        new NegotiationReply.Stop("manual.required", "Manual confirmation required")))
    .build();
```

This example deliberately sends ordinary text, not Task-T. Replace the local projection and route/stop policies with
real business behavior. onTask must not send a second network request itself. Import `control.ControlPoint` and
`model.*` from `dev.openan.workflow.engine`, plus `java.util.List/Map` and `java.util.concurrent.CompletableFuture`.

## 2. TaskRequest and dependency input

TaskRequest uses getXxx() accessors:

| Field                        | Meaning                                                                           |
|------------------------------|-----------------------------------------------------------------------------------|
| executionId / taskId         | Local execution/logical task identities, not remote protocol IDs                  |
| stepName / agentName / skill | Current workflow step, destination and skill                                      |
| instruction / language       | Current instruction only; no appended history                                     |
| input                        | BusinessInput: exactly one of text or arbitrary JSON-serializable data; no schema |
| workflowInput                | WorkflowInput(runtimeIntent, upstreamResults), separate from current input        |

BusinessInput.text(value) / BusinessInput.data(value) create input snapshots. WorkflowInput, UpstreamStepResult,
ReceivedMessage and NegotiationRequest use record field() accessors.

| contextFrom             | Upstream selection                           |
|-------------------------|----------------------------------------------|
| absent / null           | Available direct predecessors                |
| []                      | No upstream results; runtimeIntent remains   |
| ["*"]                   | Available ancestors                          |
| explicit ancestor names | Available named results in declaration order |

contextFrom selects evidence, not scheduling edges; define dependencies with next. Unknown/non-ancestor names or mixing
"*" with names is invalid. Unselected/inactive branches are not fabricated. The engine never appends upstream results to
instruction/parts or invokes an LLM to map them. The host decides how to consume the window or map it to the next
agent's input.

The window is stepName → taskResults[] → outputs[] / receivedMessages[]. TaskExecutionResult also retains agentName,
skill, logical taskId, taskDescription, status, error, errorCode and errorDetails. Multiple subtasks remain
distinguishable. Nested arrays remain one output value; there is no requirement that outputs originated from an LLM.

## 3. Final message and complete response

```java
record MessageContent(List<Part<?>> parts, Map<String,Object> metadata, Set<String> extensions) {}
record ReceivedMessage(MessageContent message, Map<String,Object> taskMetadata, List<Artifact> artifacts) {}
```

MessageContent.text(text), MessageContent.parts(parts), or the canonical constructor create snapshots. TextPart,
DataPart and FilePart preserve order and part metadata. File references are not fetched. MessageContent has no role,
target, messageId, taskId, contextId or auth headers. A business metadata key named contextId does not override the A2A
envelope.

ReceivedMessage preserves message metadata, task metadata and each artifact's identity/parts/metadata separately.
message can be null; metadata-only results remain accessible through the complete view. For convenience, outputs
projects TextPart values and DataPart data from artifacts in order; if there are no artifacts, a successful
final/standalone message can supply these values. FilePart remains available only in the complete view. No text parsing,
adjacent-part concatenation or nested-array flattening. Failed task status messages are evidence, not outputs; valid
partial artifacts are retained. Streaming append/replace is assembled per artifact; terminal snapshots do not duplicate
earlier chunks.

TaskResult.success(List<Object>) represents local output, including an empty list. TaskResult.failure(code, message)
separates failure from output; its builder can retain valid partial outputs. TaskResult includes receivedMessages for
remote evidence; remote convenience outputs derive from that view. A remote task succeeds only in COMPLETED, or a
standalone A2A Message can complete the interaction. Progress and negotiation are never a successful workflow task
merely because they have text.

## 4. Host-owned A2A-T calls

Pure engine consumers only need a2a-t-core; hosts generating content explicitly depend on a2a-t-client. Create/configure
A2ATClient in the host, not in WorkflowEngineClientConfig or ExecutePsop.

```java
// sdk, data, schema and template selection are owned by host-agent code.
MetadataContent generated = sdk.generateTaskPromptFromDataWithSchema(
    data, schema,
    Objects.requireNonNull(
        System.getProperty("host.task-template-uri"), "host.task-template-uri"));
MessageContent outgoing = A2atMessages.from(generated, List.of(new TextPart("Process this task")));
```

Natural-language equivalent: `sdk.generateTaskPromptFromText(text, templateUri)`, then use the same A2atMessages.from
conversion. The host agent selects `templateUri` for its business domain.

Natural-language generation/validation and all other A2A-T content APIs are called directly on the host's SDK.
A2atMessages.from copies generated metadata and activates exactly its extension; supplied parts stay unchanged. It does
not generate, validate or parse business content. Select a URI published by the active A2A-T SDK rather than inventing
one. Sample initialization helpers are host-side examples, not engine dependencies.

## 5. Negotiation

The host business explicitly decides whether the current task allows negotiation. An AgentCard declaration only says
that the target supports Negotiation-T. To allow it, call
`withExtension(A2ATExtension.NEGOTIATION_T.uri())` on the initial Task-T content. The engine then writes both
`message.extensions` and the `A2A-Extensions` request header without adding premature Negotiation-T metadata. Merely
implementing `onNegotiation` or supplying a `NegotiationStrategy` does not activate negotiation; the target must not
start Negotiation-T when it was not activated.

NegotiationRequest(task, originalSubmission, received, previousExchanges, remainingWait):
task is the original TaskRequest; originalSubmission is the exact initial content; received is the complete current
response; previousExchanges contains only this session's completed Exchange (received, reply); remainingWait is the
remaining local interaction deadline. There are no engine-defined business proposal schemas.

Only a remote `INPUT_REQUIRED` or a taskless bare message carrying a valid Negotiation-T Propose enters
`onNegotiation` (the latter is the A2A-T pre-task mode: correlated by contextId and negotiation id, with taskId-less
follow-up sends). Terminal responses never restart negotiation; ordinary `INPUT_REQUIRED` fails explicitly. Invalid
negotiation metadata on a bare message also fails explicitly instead of being treated as a normal response. The host validates/interprets the proposal and
generates the final Accept/Reject/Abort with its own A2A-T client. Use `A2atMessages.contextOf(request.received())` to
obtain the received context; reply with the same id, round and maxRounds. The last allowed round can still be answered.
Do not call nextRound for an ending reply or return a new Propose.

Return `new NegotiationReply.Send(content)` to send that exact content. Return `new NegotiationReply.Stop(code, reason)`
to stop without generating Abort content; the engine then cancels the known non-final A2A task as lifecycle cleanup.
Repeated task/session/round events do not repeat the callback or submission.
Unchanged waiting state is observed with getTask.
`maxNegotiationExchanges` (default 3) bounds local interactions, independently of the SDK context's maxRounds. Timeout,
exhausted budget or a missing handler fails locally; no implicit Accept or synthesized Abort. Accept/Reject ACKs in
SUBMITTED/WORKING remain pending and are observed without resending the command. A business-sent Abort is never
task success, even if the dispatched agent acknowledges it with COMPLETED.

Host policy may call validateProposePromptAndDataFilling and typed fromData or natural-language SDK generation. It must
not invent missing business facts. Supply only requested fields from the current task's authoritative input. Map SDK
business exceptions to BusinessFailure(code, safeMessage, safeDetails) in host code. The engine does not recognize
SDK-specific content exception classes.

## 6. Routing, concurrency and failures

RouteRequest (executionId, stepName, nextStep, condition, workflowInput, currentResults) describes one conditional edge.
Return RouteDecision.allow (reason) to activate it or RouteDecision.deny (reason) to skip it. Null, empty and
whitespace-only conditions are unconditional: they bypass onRoute and always activate. Each nonblank edge is evaluated
independently, so the result can activate zero, one or many conditional targets together with all unconditional targets.
The engine waits for every route decision before dispatching any successor; a missing, null, exceptional or timed-out
decision fails the workflow and cannot cause partial successor dispatch. Route failure messages identify the source and
target edge while preserving the original cause.

### 6.1 Declaring conditional edges (workflow side)

Each `next` entry is a `JumpCondition(step, condition)`; an empty condition makes the edge unconditional:

```java
WorkflowStep diagnosis = WorkflowStep.builder()
    .name("diagnosis")
    .subtasks(List.of(Task.builder().agent("SPN Domain Agent City1").description("diagnose").build()))
    .next(List.of(
        new JumpCondition("notify", ""),                       // unconditional: always active, never reaches onRoute
        new JumpCondition("hardware_recovery", "fault.hardware"),
        new JumpCondition("escalate", "fault.unresolved")))
    .build();
```

### 6.2 Implementing the per-edge decision (host side)

onRoute receives one conditional edge per call and reads this step's fresh results from `currentResults()`:

```java
.onRoute(request -> {
    // Different edges may call back concurrently: read only the request, share no mutable state
    String diagnosis = request.currentResults().stream()
        .flatMap(r -> r.outputs().stream().map(String::valueOf))
        .findFirst()
        .orElse("");
    boolean hardwareFault = diagnosis.contains("hardware fault");

    return switch (request.condition()) {
      case "fault.hardware" -> CompletableFuture.completedFuture(
          hardwareFault
              ? RouteDecision.allow("hardware fault confirmed")
              : RouteDecision.deny("no hardware fault in: " + diagnosis));
      case "fault.unresolved" -> CompletableFuture.completedFuture(
          diagnosis.isBlank()
              ? RouteDecision.allow("diagnosis inconclusive")
              : RouteDecision.deny("diagnosis conclusive: " + diagnosis));
      default -> CompletableFuture.completedFuture(
          RouteDecision.deny("unknown condition: " + request.condition()));
    };
})
```

In this example the unconditional `notify` edge always activates; the two conditional edges are judged independently
and may fan out together with it. Replace the business check with real parsing of `currentResults()` outputs (structured
output comes as JSON in outputs; natural-language output calls for text matching or the host's own judgement).

Callbacks for different conditional edges may run concurrently; do not hold shared mutable current-task state. Each
workflow task activation
(preparation and dispatch combined) is bounded by the client timeout (default sendTimeoutSeconds=600). Routing has a
callback timeout; task interaction/negotiation additionally has its own total wait deadline. Cancellation/timeout
prevents late
sends; it does not automatically stop external business/LLM work. Hosts own cancellation of their resources. Synchronous
callback entry points must return promptly; place blocking operations in an asynchronous executor.
Missing/null/exceptional callbacks fail; uncertain sends are not blindly retried.

### 6.1 Standalone task

Use `WorkflowEngineClient.sendTask(agentName, finalContent)` for a task outside the DAG. The overload with
`NegotiationStrategy` supplies only that call's negotiation decisions. The host still owns content generation and
semantic validation; the engine preserves the remote task/context, waits for a final state and cleans up a known
non-final task when local interaction cannot continue.

## 7. Independent extensions

ExtensionSender.sendAuthorization(agentName, finalContent) returns CompletableFuture<SendMessageResult>.
ExtensionSender.openNotification(agentName, finalContent, (handle, received) -> ...) returns the handle immediately.
Use host-generated Authorization-T or Notification-T content with the matching activated extension. The handle is
registered before I/O; early callbacks can call handle.close() without capturing an unassigned variable.
acknowledgement() reports actual ACK or failure; timeout is not a synthesized success. close() requests shutdown;
completion() completes after the stream actually exits. Retain it until the host-defined terminal event, explicit
cancellation, or host-agent shutdown, not just workflow completion.

Task, authorization and notification use separate transports/runtimes/contexts. Neither extension success nor failure is
a prerequisite for workflow execution. Authorization controls only the host-defined action associated with that policy.

## 8. Verification and logs

Run `mvn -B clean verify`. The reactor covers callback contracts, complete response assembly, negotiation association,
independent extension lifecycles, failure propagation, cancellation, and protocol redaction. Tests use controlled
fixtures and do not shadow SDK schemas/templates. They are release regression evidence, not certification of production
endpoints or live model semantics. See [Integration guide](INTEGRATION_GUIDE.md) for logging and authentication.

## 9. Business-side A2A-T 1.1.1 reference

Content generation, validation, filling and domain decisions belong to host-agent and dispatched-agent business code,
not the engine. Each role calls only the APIs needed for the message it owns.

| Business path                         | SDK generation and receiving validation                                                                                                      | Consumed result                                                          |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Host-agent inbound task               | validateTaskPromptAndDataFilling                                                                                                             | Use validated `filled.data` for workflow selection and business input    |
| onTask → dispatched agent           | generateTaskPromptFromDataWithSchema or generateTaskPromptFromText → validateTaskPromptAndDataFilling                                    | Build dispatched-agent input from validated data, not raw extension text |
| Dispatched agent → host negotiation | generateNegotiationProposePromptFromData or FromText → validateProposePromptAndDataFilling                                               | Answer only requested items from authoritative task data                 |
| Host Accept → dispatched agent      | generateNegotiationAcceptPromptFromData or FromText → validateAcceptPromptAndDataFilling                                                 | Merge validated requested fields and re-check domain requirements        |
| Host Reject / Abort → dispatched agent | Phase-specific generation → corresponding validation                                                                                   | Validate the reason and terminate the current task path                  |
| Independent authorization             | generateAuthPromptFromDataWithSchema → validateAuthPromptAndDataFilling                                                                  | Apply AuthorizationPolicy from `filled.data`                             |
| Independent subscription              | generateNotificationPromptFromDataWithSchema → validateNotificationPromptAndDataFilling                                                  | Create NotificationPolicy and keep a separate stream open                |

For natural-language inputs choose generateTaskPromptFromText(text, templateUri), generateAuthPromptFromText(text,
templateUri), or generateNotificationPromptFromText(text, templateUri). Negotiation also offers phase-specific FromText
and typed FromData alternatives. Structured generation may still call the SDK's configured LLM. Wrap the returned
MetadataContent with A2atMessages.from(generated, List.of (new TextPart("business summary"))). Preserve full metadata,
including templateUri and negotiationContext; provide nonempty parts. The parts summary is not the formal extension
validation input.

Replies must match A2A task/context IDs and negotiation id/round/maxRounds. Formal text comes from Negotiation-T
metadata. Validated replies are consumed once; malformed, incomplete, mismatched-task, or cross-negotiation replies
must not execute business work. Missing authoritative information produces SDK Abort rather than invented values; a
host disclosure denial produces SDK Reject. Authorization policy is a separate concern.

Authorization and Notification generation APIs create requests, not arbitrary business results. Authorization receipts
and notification events are dispatched-agent business outputs and must follow the selected template and application
contract. A subscription closes only on a host-defined terminal event, explicit cancellation, or shutdown; an ACK alone
is not a terminal business event. SDK rejection should preserve its safe code, slot errors, and extracted parameter keys.
Authorization and subscription failures still do not block workflow execution.
