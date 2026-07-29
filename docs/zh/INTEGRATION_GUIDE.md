# A2A-T 工作流执行引擎 - 二次开发集成指南

## 1. 概述

A2A-T 工作流执行引擎是一个 Java SDK，用于基于 A2A 协议和 A2A-T 电信扩展编排多智能体工作流。

引擎自动处理 A2A 协议层的全部机制（消息收发、SSE 流式传输、Task-T 提示词生成、Negotiation-T 协商循环、认证、TLS），你只需关注业务决策。

## 2. 环境要求

| 要求  | 版本 |
|-------|------|
| JDK   | 17+  |
| Maven | 3.6+ |

## 3. 引入依赖

```xml

<dependency>
    <groupId>com.openan.a2at</groupId>
    <artifactId>a2at-engine</artifactId>
<version>1.0.0</version>
</dependency>
```

## 4. 快速上手

整个集成过程分四步：定义工作流 -> 加载 AgentCard -> 实现 ControlPoint -> 执行。

### 4.1 定义工作流

```java
Workflow workflow = Workflow.builder()
        .name("故障诊断")
        .steps(List.of(
                WorkflowStep.builder()
                        .name("diagnose")
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("SPN Domain Agent")
                                        .skill("diagnosis")
                                        .description("诊断故障")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("merge")
                                        .condition("success")
                                        .build()))
                        .layer(0)
                        .build(),
                WorkflowStep.builder()
                        .name("merge")
                        .stepType(StepType.SELF_LOOP)   // 自环节点：工作台本地汇总，不发 A2A-T 给自己
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Transport Workbench Agent")
                                        .skill("aggregate")
                                        .description("汇总结果")
                                        .build()))
                        .next(List.of(
                                JumpCondition.builder()
                                        .step("end")
                                        .condition("success")
                                        .build()))
                        .layer(1)
                        .contextFrom(List.of("*"))
                        .build()
        ))
        .build();
```

### 4.2 加载 AgentCard

```java
// 方式一：从 JSON 文件加载
ObjectMapper mapper = new ObjectMapper()
                .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);

// 方式二：从注册中心拉取
RegistryClient registry = new RegistryClient("https://127.0.0.1:5001", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 实现 ControlPoint

继承 `DefaultControlPoint`，按需覆盖以下方法：

```java
public class MyControlPoint extends DefaultControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        return engineClient.sendMessage(
                        request.getAgentName(), request.getMessage())
                .thenApply(r -> TaskResponse.builder()
                        .success(r.getText() != null && !r.getText().isEmpty())
                        .output(r.getText())
                        .build());
    }

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        // SELF_LOOP 步骤在这里本地处理，不需要 engineClient，不发 A2A-T 消息。
        // request.getMessage() 已包含上游步骤的执行结果上下文。
        String summary = summarizeLocally(request.getMessage());
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(summary).build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .build());
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        return CompletableFuture.completedFuture("请使用现有信息继续执行。");
    }
}
```

| 方法              | 何时调用                       | 你需要做什么                                             |
|-------------------|--------------------------------|----------------------------------------------------------|
| `onTask`          | 步骤向其他智能体分派任务时     | 调用 `engineClient.sendMessage()` 发送消息，返回执行结果 |
| `onSelfTask`      | `SELF_LOOP` 步骤本地执行时     | 本地处理并返回结果（不发 A2A-T 消息）                    |
| `onRoute`         | 步骤完成后、决定下一步前       | 从候选分支中选择下一步                                   |
| `onNegotiation`   | 智能体返回 `INPUT_REQUIRED` 时 | 返回补充说明文本                                         |

`onNegotiation` 默认返回通用文本。只需覆盖你关心的方法。授权和通知的响应钩子 (`onAuthorization` / `onNotification`) 位于 `ExtensionCallback` 接口，不在 `ControlPoint` 上。如需自定义授权审批或通知处理，实现 `ExtensionCallback` 并通过 `engineClient.setExtensionCallback()` 挂载。

**自环节点（SelfLoop）**：当一个步骤是工作流执行智能体自身的任务（例如汇总多个智能体的诊断结果），把 `stepType` 设为
`SELF_LOOP`。引擎会调用 `onSelfTask` 本地处理，而不是通过 A2A-T 协议给智能体自己发消息。`onSelfTask` 不接收 `engineClient`
参数——从契约上保证自环任务不会误发 A2A-T。只有发给其他智能体的步骤才走 `onTask` + A2A-T 协议。

### 4.4 执行

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(List.of(card1, card2))
        .controlPoint(new MyControlPoint())
        .runtimeIntent("SPN跨城专线故障诊断与抢通")
        .lang("zh")
        .a2atEnvPath(".env")
        .credentialsConfigPath("credentials.json")
        .sslVerify(false)
        .onFinish((r, history) -> {
            System.out.println("执行结果: " + r.isSuccess());
        })
        .execute()
        .get(10, TimeUnit.MINUTES);
```

必填项：`psop`、`controlPoint`。其余配置项都有默认值。

## 5. 配置

### 5.1 .env 文件

配置 LLM 和提示词运行时：

```ini
A2AT_LANGUAGE=zh-CN
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

不配置 `.env` 时，Task-T 提示词生成不可用，其余功能不受影响。

### 5.2 凭证配置文件

需要认证的智能体，提供 JSON 凭证文件：

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:26335/rest/plat/smapp/v1/oauth/token",
      "method": "PUT",
      "request_fields": {
        "grantType": "password",
        "userName": "admin",
        "value": "enc:<base64-iv>:<base64-ciphertext>",
        "ipaddr": "*"
      },
      "token_field": "accessSession",
      "token_ttl": 3600
    }
  }
}
```

- 加密密码使用 `enc:<iv>:<ciphertext>` 格式，密钥来自 `A2AT_CRED_KEY`
- 也接受明文密码（不加 `enc:` 前缀）
- Token 自动缓存和刷新

### 5.2.1 凭证加密与密钥管理

凭证文件中的密码支持加密存储，避免明文泄露。

**生成密钥**

```bash
openssl rand -hex 32
```

输出示例：

```
4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

将生成的密钥写入 `.env` 文件：

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

**加密密码**

```bash
# 方式一：先设置环境变量
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp a2at-engine.jar com.openan.a2at.engine.client.CredentialCrypto "Admin@123"

# 方式二：密钥作为第二个参数
java -cp a2at-engine.jar com.openan.a2at.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

输出：

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

将输出结果填入凭证 JSON 的 `value` 字段。

**更换密钥**

1. 生成新密钥：`openssl rand -hex 32`
2. 更新 `.env` 中的 `A2AT_CRED_KEY`
3. 用新密钥重新加密所有密码：`java -cp a2at-engine.jar com.openan.a2at.engine.client.CredentialCrypto "明文密码" 新密钥`
4. 将新的 `enc:...` 结果更新到凭证 JSON 文件

> `.env` 文件不应提交到版本库，建议加入 `.gitignore`。
### 5.3 自定义认证（AuthProvider）

当 AgentCard 没有声明 `securitySchemes`，或使用非标准认证方式时，实现 `AuthProvider` 接口。接口只有一个方法：

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
```

每次发消息前都会调用 `applyAuth`，实现方往 `headers` 里塞认证头即可。

**场景 1：企业 SSO / 外部 Token 服务**

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

// 注册
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider(new SsoAuthProvider(mySsoClient))
        .sslVerify(false)
        .a2atEnvPath(".env")
        .build();
```

**场景 2：AgentCard 没声明 securitySchemes，但服务端要求认证**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            headers.put("X-API-Key", "static-api-key-value");
        })
        .build();
```

**场景 3：自定义 Header 名称（非标准 Authorization）**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            String token = refreshTokenIfNeeded(agentName);
            headers.put("X-Auth-Token", token);
            headers.put("X-Tenant-Id", "tenant-001");
        })
        .build();
```

**注意事项：**

- `applyAuth` 每次发消息都会调用，内部可自行实现 token 缓存和刷新逻辑
- 如果同时配了凭证文件和 `AuthProvider`，两者都生效：`AuthProvider` 先执行，凭证文件的认证后执行
- 认证失败时（如 token 获取异常），抛出的异常会传播到 `send()` 方法，请求会被拦截，不会发出
## 6. AgentCard 定义

AgentCard 通过 `capabilities.extensions` 声明扩展点：

```json
{
  "name": "SPN Domain Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "结构化任务提示",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T",
        "description": "协商文本交换",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "授权白名单",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "结果通知订阅",
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

扩展 URI 必须与 A2A-T 定义完全一致。

## 7. A2A-T 扩展能力

引擎自动处理四个 A2A-T 扩展，你无需关心协议细节：

### Task-T（自动）

给智能体发消息时，引擎自动调用 A2A-T SDK 生成结构化任务提示词，放入消息 metadata。你在 `onTask` 里只需调用 `sendMessage()`
，提示词生成是透明的。

### Negotiation-T（自动）

智能体返回 `INPUT_REQUIRED` 时，引擎自动提取协商文本，调用你的 `onNegotiation()` 获取补充信息，然后发回后续消息。自动循环最多
`maxNegotiationRounds` 次（默认 3）。

### Authorization-T（前置下发）

工作流开始前，向 SPN 智能体下发白名单授权策略。前置操作使用基于同一 transport 的 `ExtensionSender` 门面，而非工作流客户端：

```java
ExtensionSender sender = new DefaultExtensionSender(transport);
sender.

sendAuthorization(
    "SPN Domain Agent",
            "Authorization-T pre-positioning",
            "任务类型：新增授权，操作：业务抢通，操作类型：光模块更换，..."
);
```

内部使用 `A2ATExtension.AUTHORIZATION_T`，勿硬编码 URI。SPN 智能体收到后存储策略，后续操作与白名单比对，在策略内直接执行，不在则拒绝。

### Notification-T（前置订阅）

工作流开始前，向 SPN 智能体订阅抢通结果通知：

```java
sender.sendNotification(
    "SPN Domain Agent",
            "Notification-T subscription",
            "通知主题：service-recovery-execution-result，..."
);
```

`A2ATExtension.NOTIFICATION_T` 打开长连接 SSE 流。如需接收后续抢通结果，传入第四个参数 `Consumer<Map<String, Object>>` 回调：

```java
sender.sendNotification(
    "SPN Domain Agent",
            "Notification-T subscription",
            "通知主题：service-recovery-execution-result，...",
    event -> {
        // event 包含 agent, text, metadata, state
        Object text = event.get("text");
        if (text != null) {
            System.out.println("抢通结果: " + text);
        }
    }
);
```

不传回调（null）时后续事件被丢弃。SPN 智能体通过通知通道上报抢通结果。

## 8. HTTPS 配置

```java
// 开发环境：自签证书跳过验证
.sslVerify(false)

// 生产环境：启用验证 + 自定义 CA 证书
.

sslVerify(true).

caCertsPath("/path/to/ca-certs.pem")
```

## 9. 日志

引擎设有专用 `PROTOCOL` 日志器，输出完整的协议层请求/响应报文（含 Header 和 Body）。在 `log4j2.properties` 中配置：

```properties
logger.PROTOCOL.name=PROTOCOL
logger.PROTOCOL.level=info
logger.PROTOCOL.additivity=false
logger.PROTOCOL.appenderRef=console
```

设为 `debug` 可查看完整报文体。

## 10. 事件回调

订阅执行事件实现实时监控：

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case EventType.STEP_START -> System.out.println("步骤开始: " + data.get("step"));
            case EventType.AGENT_STATUS_UPDATE -> System.out.println(
                    data.get("agent") + " 状态: " + data.get("state"));
            case EventType.NEGOTIATION_REQUEST -> System.out.println(
                    "协商请求来自 " + data.get("agent"));
            case EventType.COMPLETE -> System.out.println("工作流执行完成");
        }
    }
};

ExecutePsop.

builder()
    .

eventCallback(callback)
// ...
```

常用事件类型：`STEP_START`、`STEP_COMPLETE`、`AGENT_REQUEST`、`AGENT_RESPONSE`、`NEGOTIATION_REQUEST`、`NEGOTIATION_RESOLVED`、
`COMPLETE`、`ERROR`。

## 11. 从编排中心加载工作流

```java
// 按意图搜索
List<WorkflowSearchResult> results = LoadPsop.search(
                "https://127.0.0.1:5001", "SPN跨城专线故障诊断", 5, null, false);

// 按 ID 加载完整工作流
Workflow workflow = LoadPsop.load(
        "https://127.0.0.1:5001", results.get(0).getWorkflowId(), null, false);
```

## 12. 自定义扩展

如需扩展新的 A2A-T 扩展点，实现 `ExtensionHandler` 接口：

```java
public class MyExtensionHandler implements ExtensionHandler {
    @Override
    public String extensionKeyword() {
        return "My-Extension";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint) {
        metadata.put("https://example.com/extensions/My-Extension/v1", "value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        return CompletableFuture.completedFuture(result);
    }
}
```

通过配置注册：

```java
WorkflowEngineClientConfig.builder()
    .

customHandlers(List.of(new MyExtensionHandler()))
        .

build();
```

## 13. 你需要使用的接口一览

| 接口/类                                                | 用途                                                          |
|--------------------------------------------------------|---------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | 工作流执行入口                                                |
| `ControlPoint` / `DefaultControlPoint`                 | 业务决策实现（onTask、onSelfTask、onRoute、onNegotiation 等） |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | 工作流发送（sendMessage、认证、扩展）                         |
| `ExtensionSender` / `DefaultExtensionSender`           | 一次性前置（sendAuthorization、sendNotification）             |
| `A2ATransport`                                         | 共享通信层（httpx runtime、认证、SSE 消费）                   |
| `WorkflowEngineClientConfig`                           | 配置（SSL、认证、A2A-T、协商轮数、自定义 Handler）            |
| `AuthProvider`                                         | 自定义认证                                                    |
| `ExtensionHandler`                                     | 自定义扩展                                                    |
| `EventCallback` / `EventType`                          | 事件回调                                                      |
| `LoadPsop` / `RegistryClient`                          | 工作流加载 / AgentCard 获取                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | 工作流定义                                                    |
| `ExecutionResult`                                      | 执行结果                                                      |
| `SendMessageResult` / `TaskResponse`                   | 消息/任务响应                                                 |
