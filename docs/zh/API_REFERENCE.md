# A2A-T 工作流执行引擎 - 接口说明书

## 包总览

| 包                                | 说明                                  |
|-----------------------------------|---------------------------------------|
| `com.openan.a2at.engine.client`   | A2A 消息传输、认证、扩展、配置        |
| `com.openan.a2at.engine.control`  | 用户决策点和事件系统                  |
| `com.openan.a2at.engine.model`    | 数据模型（Workflow、Task、Result 等） |
| `com.openan.a2at.engine.registry` | PSOP 加载和 AgentCard 注册            |
| `com.openan.a2at.engine.runner`   | 工作流执行入口                        |

---

## com.openan.a2at.engine.runner

### ExecutePsop

工作流执行入口。使用 Builder 模式。

#### ExecutePsop.Builder

| 方法                                     | 类型 | 默认值      | 说明                              |
|------------------------------------------|------|-------------|-----------------------------------|
| `psop(Workflow)`                         | 必填 | -           | PSOP 工作流定义                   |
| `agentCards(List<AgentCard>)`            | 必填 | `List.of()` | 工作流中所有智能体的 AgentCard    |
| `engineClient(WorkflowEngineClient)`    | 可选 | null        | 预配置客户端（null=自动创建）   |
| `controlPoint(ControlPoint)`             | 必填 | -           | 用户决策实现                      |
| `runtimeIntent(String)`                  | 可选 | `""`        | 自然语言意图，用于上下文组装      |
| `lang(String)`                           | 可选 | `"zh"`      | 语言提示（`"zh"` 或 `"en"`）      |
| `a2atEnvPath(String)`                    | 可选 | null        | `.env` 文件路径（A2A-T SDK 配置） |
| `credentialsConfigPath(String)`          | 可选 | null        | 凭证 JSON 文件路径                |
| `sslVerify(boolean)`                     | 可选 | `true`      | 是否验证 TLS 证书                 |
| `caCertsPath(String)`                    | 可选 | null        | CA 证书 PEM 文件路径              |
| `a2aClientRuntime(A2AJavaClientRuntime)` | 可选 | null        | 自定义运行时（null = 自动创建）   |
| `eventCallback(EventCallback)`           | 可选 | null        | 实时事件回调                      |
| `onFinish(BiConsumer)`                   | 可选 | null        | 执行完成回调                      |
| `onEvent(Function)`                      | 可选 | null        | 单事件转换钩子                    |

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(cp)
        .runtimeIntent("诊断故障")
        .a2atEnvPath(".env")
        .sslVerify(false)
        .execute()
        .get(10, TimeUnit.MINUTES);
```

**返回：** `CompletableFuture<ExecutionResult>`

---

## com.openan.a2at.engine.client

### WorkflowEngineClient

发送 A2A 消息的核心接口。

```java
public interface WorkflowEngineClient {
    // 发送消息（可选 contextId 和预设 metadata）
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message,
            String contextId, Map<String, Object> metadata);

    // 便捷方法：无 contextId，无 metadata
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message);

    void setControlPoint(ControlPoint controlPoint);


    void setEventCallback(EventCallback callback);

    void close();
}
```

> 一次性预置（Authorization-T / Notification-T）位于 `ExtensionSender`，不在本接口。见下方 `ExtensionSender` 章节。

#### sendMessage

| 参数        | 类型                  | 说明                                    |
|-------------|-----------------------|-----------------------------------------|
| `agentName` | `String`              | 目标智能体名称（须匹配 AgentCard.name） |
| `message`   | `String`              | 完整组装后的消息文本                    |
| `contextId` | `String`              | 可选上下文 ID（null = 自动生成）        |
| `metadata`  | `Map<String, Object>` | 可选预设 metadata                       |

**返回：** `CompletableFuture<SendMessageResult>`，包含响应文本、Task 对象、metadata、任务状态。

引擎内部在发送前自动处理：

1. Task-T 提示词生成（如果 AgentCard 声明了 Task-T）
2. Negotiation-T metadata 注入（后续协商消息）
3. 认证头注入（来自凭证文件或 AuthProvider）
4. A2A-Extensions 头（仅 metadata 中实际存在的扩展）

接收后自动处理：

1. 从 SSE 事件提取响应文本
2. 提取 metadata（task 级 + artifact 级合并）
3. Negotiation-T 自动循环（如果 `INPUT_REQUIRED`）

### ExtensionSender

基于同一 `A2ATransport` 的一次性预置门面。在工作流开始前向 Agent 发送 Authorization-T / Notification-T（及任何一次性扩展）。跳过
Task-T 提示词生成和 Negotiation-T 自动循环，也不通过全局 `EventCallback` 发送事件（返回的 `CompletableFuture` 即为回调）。

```java
public interface ExtensionSender {
    CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction,
            String naturalLanguageInput, A2ATExtension extension);

    // 便捷方法：Authorization-T
    CompletableFuture<SendMessageResult> sendAuthorization(
            String agentName, String instruction, String naturalLanguageInput);

    // 便捷方法：Notification-T（长连接 SSE）
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction, String naturalLanguageInput);
}
```

| 参数                   | 类型            | 说明                                |
|------------------------|-----------------|-------------------------------------|
| `agentName`            | `String`        | 目标智能体名称                      |
| `instruction`          | `String`        | 简短指令文本（成为 `parts[].text`） |
| `naturalLanguageInput` | `String`        | SDK 提示词生成的自然语言输入        |
| `extension`            | `A2ATExtension` | 扩展枚举（勿硬编码 URI）            |

metadata 值由 A2A-T SDK 生成；SDK 不可用时回退为原始自然语言输入。

### WorkflowEngineClientConfig

工作流引擎客户端的 Builder 配置。

| 属性                    | 类型                     | 默认值 | 说明                       |
|-------------------------|--------------------------|--------|----------------------------|
| `sslVerify`             | `boolean`                | `true` | TLS 证书验证               |
| `caCertsPath`           | `String`                 | null   | CA 证书 PEM 文件路径       |
| `sendTimeoutSeconds`    | `long`                   | `600`  | SSE 流超时（默认 10 分钟） |
| `authProvider`          | `AuthProvider`           | null   | 自定义认证提供器           |
| `credentialsConfigPath` | `String`                 | null   | 凭证 JSON 文件路径         |
| `credentialsConfig`     | `Map`                    | null   | 内联凭证配置               |
| `a2atEnvPath`           | `String`                 | null   | `.env` 文件路径            |
| `maxNegotiationRounds`  | `int`                    | `3`    | 协商自动循环最大轮数       |
| `customHandlers`        | `List<ExtensionHandler>` | null   | 自定义扩展处理器           |

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(false)
        .sendTimeoutSeconds(900)
        .a2atEnvPath(".env")
        .credentialsConfigPath("creds.json")
        .maxNegotiationRounds(5)
        .authProvider(myProvider)
        .build();
```

### AuthProvider

非标准认证机制的自定义认证提供器。

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard,
                   Map<String, String> headers);
}
```

每次消息发送时调用。`headers` 是可变 Map；直接添加 `Authorization`、自定义头等。先于基于凭证的认证执行。

### ExtensionHandler

自定义 A2A-T 扩展的处理器接口。

```java
public interface ExtensionHandler {
    String extensionKeyword();

    CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint);

    CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback);
}
```

内置：Task-T 和 Negotiation-T 自动处理。可通过配置的 customHandlers 注册自定义处理器。

### A2AJavaClientRuntime

A2A SDK 消息传输运行时接口。实现此类可自定义 HTTP 传输行为。

```java
public interface A2AJavaClientRuntime {
    Iterable<ClientEvent> sendMessage(
            AgentCard agentCard, MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink);

    void close();
}
```

引擎提供默认实现。仅在需要自定义 HTTP 传输时实现此接口。

---

## com.openan.a2at.engine.control

### ControlPoint

用户决策接口。每个方法单一职责。

```java
public interface ControlPoint {
    // 向智能体发送 Task-T 消息。直接调用 sendMessage。
    CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient);

    // 自环节点：本地处理，不发 A2A-T 消息给自己。
    default CompletableFuture<TaskResponse> onSelfTask(TaskRequest request);

    // 条件分支决策。只决定下一步去哪个 step。
    CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions);

    // 授权审批决策。默认：自动通过。
    default CompletableFuture<Boolean> onAuthorization(
            String agentName, Map<String, Object> authRequest);

    // 处理通知。默认：空操作。
    default CompletableFuture<Void> onNotification(
            String agentName, Map<String, Object> notification);

    // 提供协商补充信息。默认：返回通用文本。
    default CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult);
}
```

| 方法              | 触发时机                               | 返回值                        |
|-------------------|----------------------------------------|-------------------------------|
| `onTask`          | 步骤向其他智能体分派任务时             | `TaskResponse`（成功 + 输出） |
| `onSelfTask`      | `SELF_LOOP` 步骤本地执行（不走 A2A-T） | `TaskResponse`（成功 + 输出） |
| `onRoute`         | 步骤完成后、下一步前                   | `RouteDecision`（nextStep）   |
| `onAuthorization` | 智能体请求授权时                       | `Boolean`（true=通过）        |
| `onNotification`  | 智能体推送通知时                       | `Void`                        |
| `onNegotiation`   | 智能体返回 `INPUT_REQUIRED` 时         | `String` 补充信息文本         |

### DefaultControlPoint

默认实现，提供合理默认值：

- `onTask`：调用 `sendMessage()`，返回 success/output
- `onSelfTask`：原样回传任务消息（本地逻辑请覆盖实现）
- `onRoute`：选第一个非终止分支
- `onAuthorization`：自动通过
- `onNotification`：记录日志并返回
- `onNegotiation`：返回通用补充信息

继承此类，只需覆盖需要自定义的方法。

### EventCallback

```java
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {
    }
}
```

重写此方法以接收实时执行事件。事件类型定义在 `EventType` 常量中。

### EventType

| 常量                     | 说明                     |
|--------------------------|--------------------------|
| `STEP_START`             | 工作流步骤开始           |
| `STEP_COMPLETE`          | 工作流步骤完成           |
| `TASK_REQUEST`           | 任务分派给智能体         |
| `TASK_RESPONSE`          | 收到任务响应             |
| `AGENT_REQUEST`          | 消息发送给智能体         |
| `AGENT_RESPONSE`         | 收到智能体响应           |
| `AGENT_STATUS_UPDATE`    | 智能体 SSE 状态更新      |
| `AGENT_ARTIFACT_UPDATE`  | 智能体 SSE artifact 更新 |
| `AGENT_MESSAGE_EVENT`    | 智能体 SSE 消息事件      |
| `NEGOTIATION_REQUEST`    | 智能体请求协商           |
| `NEGOTIATION_RESOLVED`   | 补充信息已发送           |
| `NEGOTIATION_FAILED`     | 协商无法解决             |
| `AUTHORIZATION_REQUEST`  | 智能体请求授权           |
| `AUTHORIZATION_RESOLVED` | 授权决策已做出           |
| `NOTIFICATION`           | 收到智能体通知           |
| `ROUTE_DECISION`         | 路由决策已做出           |
| `START`                  | 工作流执行开始           |
| `COMPLETE`               | 工作流执行成功完成       |
| `ERROR`                  | 工作流执行失败           |
| `CLOSE`                  | 引擎客户端已关闭         |

---

## com.openan.a2at.engine.registry

### LoadPsop

从编排中心加载和搜索 PSOP 工作流。

#### load

```java
static Workflow load(String baseUrl, String psopId,
                     String accessToken, boolean sslVerify)

static Workflow load(String baseUrl, String psopId)
```

GET `/api/v1/orchestrate/psop/{psop_id}`。返回完整工作流（含步骤、子任务、路由条件）。

#### search

```java
static List<WorkflowSearchResult> search(
        String baseUrl, String intent, int topN,
        String accessToken, boolean sslVerify)

static List<WorkflowSearchResult> search(
        String baseUrl, String intent)
```

POST `/api/v1/orchestrate/search`。返回按自然语言意图匹配的工作流摘要列表。

### RegistryClient

从注册中心获取和注册 AgentCard。

```java
new RegistryClient("https://127.0.0.1:5001",false)

List<Map<String, Object>> fetchAgentCards()

Map<String, Object> fetchAgentCard(String name)

Map<String, Object> fetchAgentCard(String name, String organization)

Map<String, Object> registerAgentCard(Map<String, Object> agentCard)
```

- `fetchAgentCards`：获取所有 AgentCard
- `fetchAgentCard`：按名称（可选按组织）获取单个 AgentCard
- `registerAgentCard`：注册 AgentCard

---

## com.openan.a2at.engine.model

### Workflow

| 字段          | 类型                 | 说明           |
|---------------|----------------------|----------------|
| `id`          | `String`             | 工作流 ID      |
| `name`        | `String`             | 工作流名称     |
| `description` | `String`             | 描述           |
| `steps`       | `List<WorkflowStep>` | 有序工作流步骤 |

静态方法：`Workflow.fromMap(Map<String, Object>)` 从编排中心 API 响应解析。

### WorkflowStep

| 字段          | 类型                  | 默认值        | 说明                                     |
|---------------|-----------------------|---------------|------------------------------------------|
| `name`        | `String`              | -             | 步骤名（工作流内唯一）                   |
| `subtasks`    | `List<Task>`          | `List.of()`   | 此步骤分派的子任务                       |
| `next`        | `List<JumpCondition>` | `List.of()`   | 条件后续步骤                             |
| `layer`       | `int`                 | `0`           | 上下文层（0 = 仅运行时意图）             |
| `contextFrom` | `List<String>`        | null          | 继承上下文的步骤（`"*"` = 所有前驱步骤） |
| `stepType`    | `StepType`            | `ALL_SUCCESS` | 执行模式                                 |

### StepType

| 值            | 说明                                                                                    |
|---------------|-----------------------------------------------------------------------------------------|
| `ALL_SUCCESS` | 所有子任务必须成功                                                                      |
| `ANY_SUCCESS` | 任一子任务成功即可                                                                      |
| `SELF_LOOP`   | 工作流执行智能体通过 `onSelfTask` 本地处理，不发 A2A-T 消息。成功语义同 `ALL_SUCCESS`。 |

### Task

| 字段          | 类型     | 说明                              |
|---------------|----------|-----------------------------------|
| `agent`       | `String` | 智能体名称（匹配 AgentCard.name） |
| `skill`       | `String` | 智能体技能 ID                     |
| `description` | `String` | 任务描述                          |

### JumpCondition

| 字段        | 类型     | 说明                                   |
|-------------|----------|----------------------------------------|
| `step`      | `String` | 下一步名称（`"end"` 表示终止）         |
| `condition` | `String` | 条件表达式（`"success"`、`"fail"` 等） |

### TaskRequest

| 字段           | 类型     | 说明             |
|----------------|----------|------------------|
| `agentName`    | `String` | 目标智能体       |
| `skill`        | `String` | 智能体技能       |
| `message`      | `String` | 完整消息文本     |
| `description`  | `String` | 任务描述         |
| `context`      | `String` | 上下文消息       |
| `stepName`     | `String` | 源步骤名         |
| `subtaskIndex` | `int`    | 步骤内子任务索引 |

### TaskResponse

| 字段       | 类型      | 说明               |
|------------|-----------|--------------------|
| `success`  | `boolean` | 任务是否成功       |
| `output`   | `String`  | 响应文本           |
| `error`    | `String`  | 错误信息（失败时） |
| `metadata` | `Map`     | 响应 metadata      |

### SendMessageResult

| 字段        | 类型     | 说明                                      |
|-------------|----------|-------------------------------------------|
| `text`      | `String` | 提取的响应文本                            |
| `task`      | `Task`   | SDK Task 对象                             |
| `metadata`  | `Map`    | 响应 metadata（task + artifact 合并）     |
| `taskState` | `String` | 最终任务状态（如 `TASK_STATE_COMPLETED`） |

### ExecutionResult

| 字段          | 类型               | 说明               |
|---------------|--------------------|--------------------|
| `success`     | `boolean`          | 工作流是否成功     |
| `history`     | `List<Map>`        | 每步执行历史       |
| `stepOutputs` | `Map<String, Map>` | 按步骤名索引的输出 |
| `error`       | `String`           | 错误信息（失败时） |

### RouteDecision

| 字段       | 类型     | 说明       |
|------------|----------|------------|
| `nextStep` | `String` | 下一步执行 |
| `reason`   | `String` | 决策原因   |

### WorkflowSearchResult

| 字段             | 类型           | 说明         |
|------------------|----------------|--------------|
| `workflowId`     | `String`       | 工作流 ID    |
| `workflowType`   | `String`       | 类型         |
| `name`           | `String`       | 名称         |
| `description`    | `String`       | 描述         |
| `tags`           | `List<String>` | 标签         |
| `createdAt`      | `String`       | 创建时间     |
| `score`          | `double`       | 相关度评分   |
| `userIntent`     | `String`       | 匹配的意图   |
| `relatedPreflow` | `String`       | 关联 preflow |
| `tasksSummary`   | `String`       | 任务摘要     |

---

## 扩展 URI 常量

| 扩展            | URI                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------|
| Task-T          | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1`          |
| Negotiation-T   | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T`      |
| Authorization-T | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1` |
| Notification-T  | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1`  |

> **注意：** `DATA-NEGOTIATION-T/v1` **不是** A2A-T 扩展。它是 A2A-T SDK
> 协商模块内部使用的 metadata key，用于携带结构化协商上下文
> （`negotiationType`、`round`、`negotiationId`、`status`）。
> 不在 AgentCard 上声明，不被 ExtensionHandler 处理，也不出现在
> `A2A-Extensions` 头中。

---

## 线程安全

- 引擎客户端线程安全，内部使用并发集合。
- `ControlPoint` 实现若在多工作流并发执行中使用，需自行保证线程安全。
- `EventCallback.onEvent` 从多个线程调用（主线程 + SSE 工作线程），需要时使用同步。

## 错误处理

- 智能体调用失败抛出 `RuntimeException`（包装原始异常）。
- 协商失败在 `maxNegotiationRounds` 轮后终止。
- 认证失败（401）记为 `ERROR` 日志，认证头不设置，请求继续发出。
- SSE 流在终态事件后的连接关闭日志为 `DEBUG` 级别（预期行为）。
