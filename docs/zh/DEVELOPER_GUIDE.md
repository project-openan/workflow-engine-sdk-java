# 开发者指南

本指南面向贡献者和希望理解内部架构、扩展 SDK 或提交补丁的高级用户。

## 1. 安装

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>engine-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

引擎会传递性引入 A2A 协议 SDK（`a2a-java-sdk-client`，含 REST、JSON-RPC、gRPC 传输）
和 A2A-T 扩展 SDK（`a2a-t-client`）。无需额外依赖。

## 2. 核心概念

| 层级    | 入口                     | 职责                                       | 你需要提供                          |
|---------|-------------------------|-------------------------------------------|-------------------------------------|
| 2（高） | `ExecutePsop.builder()` | 事件收集、生命周期、onFinish                | ControlPoint + AgentCards + 配置    |
| 1（中） | `WorkflowExecutor`      | DAG 遍历、上下文、下发（onTask/onSelfTask） | ControlPoint + EngineClient + Workflow |
| 0（低） | `WorkflowEngineClient`  | A2A 发送、响应提取                          | AgentCards + A2AJavaClientRuntime   |

## 3. 实现 ControlPoint

只需实现两个方法：

```java
public class MyControlPoint implements ControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient client) {
        return client.sendMessage(request.getAgentName(), request.getMessage())
                .thenApply(result -> TaskResponse.builder()
                        .success(true)
                        .output(result.getText())
                        .build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .reason("picked first")
                        .build());
    }
}
```

`onAuthorization` 和 `onNotification` 有默认实现。
`onNegotiation` 有默认实现，返回通用澄清文本。授权和通知的响应钩子 (`onAuthorization` / `onNotification`) 位于 `ExtensionCallback` 接口，不在 `ControlPoint` 上。

## 4. 通过 Builder 执行（推荐）

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(agentCards)
        .controlPoint(new MyControlPoint())
        .runtimeIntent("诊断SPN故障")
        .lang("zh")
        .sslVerify(false)
        .a2atEnvPath(".env")
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

| 事件                    | 层级          | 触发时机                                        | 关键数据                                                |
|-------------------------|-------------|------------------------------------------------|-------------------------------------------------------|
| `start`                 | 运行器        | 工作流开始                                       | `workflow`、`steps`                                   |
| `step_start`            | 执行器        | 步骤开始                                         | `step`                                                |
| `task_request`          | 执行器        | 子任务下发到 `onTask`/`onSelfTask`               | `step`、`agent`、`task`                               |
| `task_response`         | 执行器        | `onTask`/`onSelfTask` 返回 `TaskResponse`        | `step`、`agent`、`task`、`output`                     |
| `route_decision`        | 执行器        | 分支选择                                         | `step`、`next`、`reason`                              |
| `step_complete`         | 执行器        | 步骤完成                                         | `step`、`results`                                     |
| `agent_request`         | 引擎客户端    | 消息发送到智能体                                  | `agent`、`request`、`metadata`                       |
| `agent_response`        | 引擎客户端    | 收到智能体响应                                    | `agent`、`response`                                   |
| `agent_status_update`   | 引擎客户端    | 智能体 SSE 状态更新                               | `agent`、`state`、`is_final`                         |
| `agent_artifact_update` | 引擎客户端    | 智能体 SSE 产物更新                               | `agent`、`artifact_name`、`text`                     |
| `negotiation_request`   | 引擎客户端    | 智能体需要澄清                                    | `agent`、`round`、`concern`                           |
| `negotiation_resolved`  | 引擎客户端    | 澄清已提供                                       | `agent`、`round`、`clarification`                    |
| `negotiation_failed`    | 引擎客户端    | 协商失败                                         | `agent`、`round`、`reason`                            |
| `complete`              | 运行器        | 工作流成功                                       | `history`、`step_outputs`                            |
| `error`                 | 运行器或执行器 | 工作流失败                                       | 运行器：`error`、`history`；执行器：`step`、`results` |
| `close`                 | 运行器        | 清理完成                                         | （空）                                                 |

## 6. 中间层（Layer 1: WorkflowExecutor）

```java
try (var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime,
        WorkflowEngineClientConfig.builder()
                .sslVerify(false)
                .credentialsConfigPath("etc/conf/agent_credentials.json")
                .a2atEnvPath(".env")
                .build())) {
    WorkflowExecutor executor = new WorkflowExecutor(
            workflow,
            new MyControlPoint(),
            client,
            new EventCallback(),
            "诊断故障",
            "zh"
    );
    ExecutionResult result = executor.run().join();
}
```

### 6.1 协商自动循环

引擎客户端的 `sendMessage()` 自动处理协商：当智能体返回 `INPUT_REQUIRED` 时，
引擎从响应 metadata 中提取协商文本，调用 `ControlPoint.onNegotiation()` 获取澄清，
然后作为后续消息发回。循环重复直到 `maxNegotiationRounds`（默认 3）。

覆盖 `onNegotiation()` 提供业务特定的澄清：

```java
@Override
public CompletableFuture<String> onNegotiation(
        String agentName, String negotiationText,
        Map<String, Object> receiveResult) {
    return myLlm.generate("Agent " + agentName + " needs: " + negotiationText)
            .thenApply(Response::text);
}
```

返回空/null 字符串会失败该轮（发射 `negotiation_failed` 事件，循环重试）。

### 6.2 工作流模型字段

| 字段                   | 位置                  | 含义                                                                                                                                                       |
|------------------------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `steps[].stepType`     | `WorkflowStep`       | `AllSuccess`（默认）：所有子任务必须成功；`AnySuccess`：任一成功即可；`SelfLoop`：工作流智能体通过 `onSelfTask` 本地处理（不向命名智能体发送 A2A-T 消息）。 |
| `steps[].subtasks[]`   | `Task`               | 每个含 `agent`、`skill`、`description`。每个子任务触发一次 `onTask`（或 SelfLoop 的 `onSelfTask`）调用。                                                    |
| `steps[].next[]`       | `List<JumpCondition>` | 分支目标。`step` = 下一步名称；`condition` = 规则文本。                                                                                                    |
| `steps[].layer`        | `WorkflowStep`       | `layer == 0` 开始 DAG（上下文 = 运行时意图）。更高层获取上游结果。                                                                                          |
| `steps[].contextFrom`  | `WorkflowStep`       | 可选步骤名，其输出折叠到上下文中。`"*"` = 所有祖先。                                                                                                       |

### 6.3 AgentCard 类型

Java SDK 全程使用 `org.a2aproject.sdk.spec.AgentCard`（强类型 record）。
`RegistryClient.fetchAgentCards()` 返回 `List<Map<String, Object>>`（从 OpenAPI 格式规范化）。
使用 `AgentCardJacksonModule` 和 Jackson 将 JSON 反序列化为 `AgentCard`：

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
  "SPN Domain Agent": {
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
（环境变量或系统属性，由 `EnvFileLoader` 从 `.env` 加载）读取。

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

| 字段                  | 必填 | 默认值              | 描述                         |
|----------------------|------|--------------------|-----------------------------|
| `login_url`          | 是   | -                  | 获取 access token 的 URL     |
| `method`             | 否   | `POST`             | HTTP 方法                    |
| `content_type`       | 否   | `application/json` | 内容类型                      |
| `request_fields`     | 否   | -                  | 请求体字段（覆盖 username/password）|
| `token_field`        | 否   | `accessSession`    | 点分隔的 token 路径           |
| `token_ttl`          | 否   | `3600`             | Token 缓存 TTL（秒）         |
| `auth_header`        | 否   | `Authorization`    | 自定义 Header 名              |
| `auth_header_prefix` | 否   | （空）              | Token 前的前缀               |
| `accept_header`      | 否   | -                  | 自定义 Accept Header          |

## 8. SSL / TLS

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
        .build();
```

仅在开发环境使用自签证书时设 `sslVerify=false`。

## 9. A2A-T 环境（.env）

```ini
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-chat
A2AT_LLM_API_KEY=sk-...
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LANGUAGE=zh-CN
A2AT_CRED_KEY=<32字节hex>
```

`a2atEnvPath` 为 null 时，Task-T 提示词生成跳过。

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

`ExecutePsop.builder().execute()` 返回 `CompletableFuture`。可以 `cancel(true)`，
但内部执行器不会主动中断运行中的 A2A 调用。对于 SSE，丢弃订阅者，让 future 自然完成。

## 11. 检查清单

1. 添加 Maven 依赖
2. 实现 `ControlPoint`（onTask + onSelfTask + onRoute）
3. 获取 AgentCards（从注册中心或 JSON 文件）
4. 加载 Workflow（通过 `LoadPsop` 或自行构建）
5. 配置 `.env` 和凭证文件
6. 调用 `ExecutePsop.builder().execute()`
7. 处理事件 + onFinish 持久化
