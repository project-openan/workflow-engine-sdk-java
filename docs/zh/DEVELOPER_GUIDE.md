# 开发者指南

本指南面向贡献者和希望理解内部架构、扩展 SDK 或提交补丁的高级用户。

## 1. 安装

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>net.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

引擎会传递性引入 A2A 协议 SDK（`a2a-java-sdk-client`，含 REST、JSON-RPC、gRPC 传输） 和最小 A2A-T 核心（`a2a-t-core`）。生成 A2A-T 内容的宿主智能体显式依赖 a2a-t-client，校验接收内容的被调度智能体服务另引入 a2a-t-server。

## 2. 核心概念

| 层级    | 入口                    | 职责                                        | 你需要提供                             |
|---------|-------------------------|---------------------------------------------|----------------------------------------|
| 2（高） | `ExecutePsop.builder()` | 事件收集、生命周期、onFinish                | ControlPoint + AgentCards + 配置       |
| 1（中） | `WorkflowExecutor`      | DAG 遍历、上下文、下发（onTask/onSelfTask） | ControlPoint + EngineClient + Workflow |
| 0（低） | `WorkflowEngineClient`  | A2A 发送、响应提取                          | AgentCards + A2AJavaClientRuntime      |

## 3. 实现 ControlPoint

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask 返回最终 parts/metadata/extensions，引擎封装发送，不再生成或改写内容。 onSelfTask 返回本地 TaskResult；onRoute
选择允许的候选；onNegotiation 返回 Send 或 Stop。 未实现的回调明确失败，不回显成功、不选首分支、不自动同意。
字段与完整示例见 [业务回调集成契约](BUSINESS_CALLBACKS.md)。

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

## 4. 通过 Builder 执行（推荐）

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(agentCards)
        .controlPoint(new MyControlPoint())
        .runtimeIntent("分析服务异常")
        .lang("zh")
        .sslVerify(false)
        .credentialsConfigPath("agent_credentials.json")
        .eventCallback(new EventCallback())
        .onFinish((r, e) -> {
            persist(r);
            return CompletableFuture.completedFuture(null);
        })
        .execute()
        .join();
```

必填：`psop`、`controlPoint`。其余有合理默认值。
`onFinish` 接受异步 `BiFunction<..., CompletableFuture<Void>>`
和同步 `BiConsumer` 两种重载。

## 5. 事件类型

事件来自三层：运行器（生命周期括号）、执行器（步骤/任务/路由）、引擎客户端（智能体流量、协商）。

| 事件                    | 层级           | 触发时机                                           | 关键数据                                              |
|-------------------------|----------------|----------------------------------------------------|-------------------------------------------------------|
| `start`                 | 运行器         | 工作流开始                                         | `workflow`、`steps`                                   |
| `step_start`            | 执行器         | 步骤开始                                           | `step`                                                |
| `task_request`          | 执行器         | 子任务下发到 `onTask`/`onSelfTask`                 | `step`、`agent`、`task`                               |
| `task_response`         | 执行器         | 远端任务完成或 onSelfTask 返回 TaskResult          | `step`、`agent`、`task`、`outputs`                    |
| `task_status_changed`   | 执行器         | 任务状态变更（pending → running → success/failed） | `step`、`agent`、`task`、`status`                     |
| `route_decision`        | 执行器         | 分支选择                                           | `step`、`next`、`reason`                              |
| `step_complete`         | 执行器         | 步骤完成                                           | `step`、`results`                                     |
| `workflow_complete`     | 执行器         | 所有步骤完成                                       | `history`、`step_outputs`                             |
| `agent_request`         | engine client  | 准备下发（不是 wire 日志）                         | `agent`, `content`                                    |
| `agent_response`        | engine client  | 远端响应已组装                                     | `agent`, `response`, `receivedMessages`               |
| `agent_status_update`   | 引擎客户端     | 智能体 SSE 状态更新                                | `agent`、`state`、`is_final`                          |
| `agent_artifact_update` | 引擎客户端     | 智能体 SSE 产物更新                                | `agent`、`artifact_name`、`text`                      |
| `negotiation_request`   | engine client  | 有效 Propose 进入业务接管                          | `agent`, `request`, `exchange`                        |
| `negotiation_resolved`  | engine client  | 宿主 Send 通过关联检查；不等于任务成功             | `agent`, `reply`, `exchange`                          |
| `negotiation_failed`    | engine client  | 本地协商交互失败                                   | `agent`, `exchange`, `errorType`                      |
| `complete`              | 运行器         | 工作流成功                                         | `history`、`step_outputs`                             |
| `error`                 | 运行器或执行器 | 工作流失败                                         | 运行器：`error`、`history`；执行器：`step`、`results` |
| `close`                 | 运行器         | 清理完成                                           | （空）                                                |

## 6. 中间层（Layer 1: WorkflowExecutor）

```java
try (var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime,
        WorkflowEngineClientConfig.builder()
                .sslVerify(false)
                .credentialsConfigPath("etc/conf/agent_credentials.json")
                .build())) {
    WorkflowExecutor executor = new WorkflowExecutor(
            workflow,
            new MyControlPoint(),
            client,
            new EventCallback(),
            "分析请求",
            "zh"
    );
    ExecutionResult result = executor.run().join();
}
```

### 6.1 协商自动循环

只有远端 `INPUT_REQUIRED` 携带有效 Negotiation-T Propose 才进入 `onNegotiation`。 终态不会重启协商，普通 INPUT_REQUIRED
明确报告不支持的交互。 宿主自行校验、理解 Propose，并用自己的 A2A-T client 生成最终 Accept/Reject/Abort。 通过
`A2atMessages.contextOf(request.received())` 取得收到的上下文； 结束回复保持相同 id、round、maxRounds，最后允许的一轮仍可回答，不自行
nextRound 或返回新 Propose。

返回 `new NegotiationReply.Send(content)` 发送最终内容； 返回 `new NegotiationReply.Stop(code, reason)` 只在本地停止，不生成
Abort。 同一任务／会话／轮次的重复等待事件不会重复回调、重复提交；未变化状态通过 getTask 观察。
`maxNegotiationExchanges` 默认 3，是独立于 SDK context.maxRounds 的本地交互资源预算。 超时、预算耗尽、回调缺失均明确失败，不默认
Accept，也不自动生成 Abort。 Accept/Reject 的 SUBMITTED/WORKING ACK 仍需等待任务结果，不重发原命令。 业务发送 Abort 后，即使远端用
COMPLETED 确认，也不能判为任务成功。

### 6.2 工作流模型字段

| 字段                  | 位置                  | 含义                                                                                                                                                        |
|-----------------------|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `steps[].stepType`    | `WorkflowStep`        | `AllSuccess`（默认）：所有子任务必须成功；`AnySuccess`：任一成功即可；`SelfLoop`：宿主智能体通过 `onSelfTask` 本地处理（不向命名智能体发送 A2A-T 消息）。 |
| `steps[].subtasks[]`  | `Task`                | 每个含 `agent`、`skill`、`description`。每个子任务触发一次 `onTask`（或 SelfLoop 的 `onSelfTask`）调用。                                                    |
| `steps[].next[]`      | `List<JumpCondition>` | 分支目标。`step` = 下一步名称；`condition` = 规则文本。                                                                                                     |
| `steps[].layer`       | `WorkflowStep`        | 编排层级提示；实际就绪条件由 DAG 前驱关系决定。                                                                                                             |
| `steps[].contextFrom` | `WorkflowStep`        | 选择传入 `workflowInput.upstreamResults` 的步骤；省略 = 直接前驱，`[]` = 不聚合，`"*"` = 所有祖先，或显式指定祖先名称。                                     |

### 6.3 AgentCard 类型

Java SDK 全程使用 `org.a2aproject.sdk.spec.AgentCard`（强类型 record）。
`RegistryClient.fetchAgentCards()` 返回 `List<Map<String, Object>>`（从 OpenAPI 格式规范化）。 使用
`AgentCardJacksonModule` 和 Jackson 将 JSON 反序列化为 `AgentCard`：

```java
ObjectMapper mapper = new ObjectMapper()
        .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(json, AgentCard.class);
```

## 7. 智能体认证

当 AgentCard 声明 `securitySchemes` 时，`DefaultWorkflowEngineClient` 通过
`AgentCredentialService` 登录，缓存 token `token_ttl` 秒，并将认证头附加到出站请求。

### 7.1 凭证文件

```json
{
  "Dispatched Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:8080/auth/login",
      "method": "POST",
      "request_fields": {
        "username": "...",
        "password": "..."
      },
      "token_field": "access_token",
      "token_ttl": 3600
    }
  }
}
```

密码支持 AES-GCM 加密（`enc:<iv>:<ciphertext>` 前缀）。解密密钥从 `A2AT_CRED_KEY`
（显式实例配置 > OS 环境变量 > JVM 属性）读取；集成方负责配置加载，引擎不自动读取 `.env`。

### 7.2 自定义 AuthProvider

对于非标准认证（SSO、API Key、自定义 Header）：

```java
WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        headers.put("Authorization", "Bearer " + mySsoToken);
        headers.put("X-Custom", "value");
    })
    .build();
```

### 7.3 凭证文件字段

| 字段                 | 必填 | 默认值             | 描述                                 |
|----------------------|------|--------------------|--------------------------------------|
| `login_url`          | 是   | -                  | 获取 access token 的 URL             |
| `method`             | 否   | `POST`             | HTTP 方法                            |
| `content_type`       | 否   | `application/json` | 内容类型                             |
| `request_fields`     | 否   | -                  | 请求体字段（覆盖 username/password） |
| `token_field`        | 否   | `accessSession`    | 点分隔的 token 路径                  |
| `token_ttl`          | 否   | `3600`             | Token 缓存 TTL（秒）                 |
| `auth_header`        | 否   | `Authorization`    | 自定义 Header 名                     |
| `auth_header_prefix` | 否   | （空）             | Token 前的前缀                       |
| `accept_header`      | 否   | -                  | 自定义 Accept Header                 |

## 8. SSL / TLS

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
        .build();
```

仅在开发环境使用自签证书时设 `sslVerify=false`。

## 9. A2A-T 环境（.env）

引擎不读取 A2A-T .env，也不创建 LLM client。宿主智能体的业务回调需要 A2A-T 时，由宿主使用自有环境文件初始化 A2ATClient/A2ATServer，配置 provider/model/key/base URL 和 A2AT_LANGUAGE。样例中的 a2atEnvPath 不是引擎 builder 参数。被调度智能体凭据解密不与
LLM 配置耦合：内置凭据模式通过 WorkflowEngineClientConfig.builder().credentialEncryptionKey (key) 显式提供密钥， 再将配置好的
engineClient 传给 ExecutePsop。自定义 AuthProvider 自行管理 token 和配置。 测试使用当前 SDK SPI 的离线
provider，不覆盖模板，也不是生产失败兜底。

## 10. 集成模式

### SSE Server（Spring WebFlux）

```java
@GetMapping("/execute/{psopId}")
public Flux<String> execute(@PathVariable String psopId) {
    Workflow workflow = LoadPsop.load(baseUrl, psopId, token, false);

    return Flux.create(sink -> {
        ExecutePsop.builder()
                .psop(workflow)
                .agentCards(cards)
                .controlPoint(cp)
                .eventCallback(new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        sink.next("data: " + toJson(type, data) + "\n\n");
                    }
                })
                .onFinish((r, e) -> {
                    sink.complete();
                    return CompletableFuture.completedFuture(null);
                })
                .execute();
    });
}
```

### 取消

`ExecutePsop.builder().execute()` 返回 `CompletableFuture`。`cancel(true)` 停止后续本地调度、忽略迟到回调并清理执行器拥有的资源。
集成方需要自行取消其 LLM/业务操作；已提交到远端的任务可能需要显式 cancelTask，本地取消不等于协议 Abort 或业务回滚。
`get(timeout)` 超时不会自动取消 Future，应在 finally 中显式取消未完成的执行。取消不会关闭独立授权/订阅通道。

## 11. 检查清单

1. 添加 Maven 依赖
2. 实现 `ControlPoint`（onTask 必须返回最终内容；包含本地任务、条件路由或协商时也实现对应回调）
3. 获取 AgentCards（从注册中心或 JSON 文件）
4. 加载 Workflow（通过 `LoadPsop` 或自行构建）
5. 配置 `.env` 和凭证文件
6. 调用 `ExecutePsop.builder().execute()`
7. 处理事件 + onFinish 持久化

### 协议日志 pretty 展示

协议日志默认以 pretty 形式展示：逐行打印头字段，缩进 JSON 正文。SSE 保留 id/event 等事件信息， JSON 在
`=== SSE data(JSON display; not wire text) ===` 与 `=== End SSE data ===` 之间单独展示， 不再为展开后的每行 JSON 添加
`data:` 前缀。多个事件各自保留边界。
`WORKFLOW_ENGINE_PROTOCOL_PRETTY=false`（环境变量或同名 JVM 属性）可保留脱敏后的原始正文展示。 pretty 只影响显示（JSON 缩进及
SSE 数据区标记），不改实际发送字节、metadata、数值或扩展头； 原始观测正文仍保留，SSE pretty 展示不是可直接重放的抓包。 JSON
字符串中的 `\\n` 保留转义，避免把业务字符串误写成非法 JSON；非 JSON／不完整正文保持原样。日志仍执行脱敏和容量限制。验证协商流量时，
检查 `A2A-Extensions: .../Negotiation-T/v1`；不存在专用的 Negotiation-T HTTP 头。正文检查对应 URI 的 metadata 和
`negotiationContext`，不要只检索大写 `NEGOTIATION-T`。
