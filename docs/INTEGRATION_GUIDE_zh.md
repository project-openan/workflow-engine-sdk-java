# A2A-T 工作流执行引擎 - 二次开发集成指南

## 1. 概述

A2A-T 工作流执行引擎（artifact: `a2at-engine`）是一个 Java SDK，用于基于 A2A 协议和 A2A-T 电信扩展编排多智能体工作流。它在应用层与底层 `a2a-java-sdk` + `a2a-t-sdk-java` 之间，负责：

- 通过 SSE 流式通道收发 A2A 消息
- Task-T 结构化任务提示词生成（LLM + 模板，通过 a2a-t-sdk）
- Negotiation-T 自动协商循环
- Authorization-T 前置下发（白名单策略）
- Notification-T 前置订阅（结果上报通道）
- AgentCard 解析、认证令牌管理、HTTPS/TLS
- PSOP 工作流执行（步骤路由、上下文组装）

引擎采用二次开发模式：用户实现 `ControlPoint` 做业务决策，引擎负责全部协议机制。

## 2. 环境要求

| 要求 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.6+ |
| a2a-java-sdk | 1.0.0.Beta1 |
| a2a-t-sdk-java | 1.0.0 |
| Jackson | 2.20.1 |
| SLF4J + Log4j2 | 2.0.17 / 2.24.3 |

## 3. Maven 依赖

```xml
<dependency>
    <groupId>com.openan.a2at</groupId>
    <artifactId>a2at-engine</artifactId>
    <version>0.3.0</version>
</dependency>
```

源码编译：

```bash
mvn -o clean install -DskipTests
```

## 4. 快速上手

### 4.1 定义工作流（PSOP）

```java
Workflow workflow = Workflow.builder()
    .id("my-workflow")
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
RegistryClient registry = new RegistryClient(
    "https://127.0.0.1:5001", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 实现 ControlPoint

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
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        // 路由决策逻辑
        return CompletableFuture.completedFuture(
            RouteDecision.builder()
                .nextStep(conditions.get(0).getStep())
                .build());
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        // 提供协商补充信息
        return CompletableFuture.completedFuture(
            "请使用现有信息继续执行。");
    }
}
```

### 4.4 执行

```java
CompletableFuture<ExecutionResult> future = ExecutePsop.builder()
    .psop(workflow)
    .agentCards(List.of(card1, card2))
    .controlPoint(new MyControlPoint())
    .runtimeIntent("SPN跨城专线故障诊断与抢通")
    .lang("zh")
    .a2atEnvPath(".env")
    .credentialsConfigPath("credentials.json")
    .sslVerify(false)
    .onFinish((result, history) -> {
        System.out.println("执行结果: " + result.isSuccess());
    })
    .execute();

ExecutionResult result = future.get(10, TimeUnit.MINUTES);
```

## 5. 配置

### 5.1 .env 文件（A2A-T SDK）

`.env` 配置 A2A-T SDK 的 LLM 和提示词运行时。引擎通过 `EnvFileLoader` 加载并设为系统属性。

```ini
# 语言
A2AT_LANGUAGE=zh-CN

# LLM（OpenAI 兼容接口）
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60

# 提示词
A2AT_PROMPT_SOURCE_TYPE=classpath
A2AT_PROMPT_COMPLIANCE_ENABLED=false

# 凭证加密密钥（32 字节十六进制）
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

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

- 加密密码使用 `enc:<iv>:<ciphertext>` 格式（AES-GCM 加密）
- 密钥从 `A2AT_CRED_KEY` 读取（环境变量或系统属性）
- 也接受明文密码（不加 `enc:` 前缀）
- Token 自动缓存，到期前自动刷新

### 5.3 自定义认证提供器

当 AgentCard 没有声明 securitySchemes，或使用非标准认证方式时：

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        String token = mySsoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Custom-Header", "custom-value");
    })
    .sslVerify(false)
    .a2atEnvPath(".env")
    .build();
```

`AuthProvider` 对每次消息发送都会调用。如果同时配置了凭证文件和 `AuthProvider`，两者都会执行（自定义提供器先执行，基于凭证的认证后执行）。

## 6. AgentCard 定义

AgentCard 通过 `capabilities.extensions` 数组声明扩展点：

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
    { "schemes": { "bearerAuth": [] } }
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

扩展 URI 必须与 a2a-t-sdk 定义完全一致。

## 7. A2A-T 扩展集成

引擎处理四个 A2A-T 扩展：

### Task-T

**自动处理。** 发送消息前，引擎调用 `a2atClient.generateTaskPrompt()` 生成结构化任务提示词。提示词文本放入 `message.metadata` 的 Task-T URI 键下，`A2A-Extensions` 头声明 `Task-T/v1`。无需用户代码介入。

### Negotiation-T

**自动处理。** 当智能体返回 `INPUT_REQUIRED` 状态时，引擎从响应 metadata 提取协商文本，调用 `ControlPoint.onNegotiation()` 获取用户的补充信息，然后作为后续消息发回。自动循环最多 `maxNegotiationRounds` 次（默认 3）。

### Authorization-T

**前置下发。** 工作流开始前，工作台智能体通过 `sendExtensionMessage()` 向每个 SPN 智能体下发白名单授权策略。SPN 智能体存储策略，后续操作与白名单比对，在策略内直接执行。

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Authorization-T pre-positioning",
    "任务类型：新增授权，操作：业务抢通，操作类型：光模块更换，...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1"
);
```

### Notification-T

**前置订阅。** 工作流开始前，工作台智能体通过 `sendExtensionMessage()` 向每个 SPN 智能体订阅抢通结果通知。SPN 智能体通过通知通道上报抢通结果（成功或拒绝）。

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Notification-T subscription",
    "通知主题：service-recovery-execution-result，...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1"
);
```

## 8. HTTPS/TLS 配置

```java
// 开发环境：自签证书跳过验证
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(false)
    .build();

// 生产环境：启用验证 + 自定义 CA 证书
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(true)
    .caCertsPath("/path/to/ca-certs.pem")
    .build();
```

`sslVerify=false` 时使用信任所有证书的 SSL 上下文，仅适用于开发环境。

## 9. 日志配置

引擎使用 SLF4J，并设有专用 `PROTOCOL` 日志器输出协议层报文。在 `log4j2.properties` 中配置：

```properties
# 协议层请求/响应报文打印
logger.PROTOCOL.name = PROTOCOL
logger.PROTOCOL.level = info
logger.PROTOCOL.additivity = false
logger.PROTOCOL.appenderRef = console

# 引擎客户端
logger.engine.name = com.openan.a2at.engine.client
logger.engine.level = debug
```

设 `logger.PROTOCOL.level = debug` 可查看完整请求/响应体（含 Header）。

## 10. 自定义扩展处理器

扩展引擎支持自定义 A2A-T 扩展：

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
        // 发送前注入自定义 metadata
        metadata.put("https://example.com/extensions/My-Extension/v1",
            "custom value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        // 处理响应 metadata
        return CompletableFuture.completedFuture(result);
    }
}
```

通过配置注册：

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .customHandlers(List.of(new MyExtensionHandler()))
    .build();
```

## 11. 协议消息格式

引擎遵循 A2A-T 协议。请求消息包含：

- `parts[].text`：简短自然语言消息
- `metadata[extension-uri]`：结构化扩展内容
- `A2A-Extensions` 头：当前消息实际使用的扩展 URI 列表（逗号分隔）
- `Authorization` 头：Bearer 令牌（配置认证时）

响应消息（来自智能体）应包含：

- 简短摘要放在 `artifact.parts[].text`
- 完整扩展内容放在 `artifact.metadata[extension-uri]`
- 协商文本放在 `status.metadata[NEGOTIATION-T]`
- SDK 内部协商上下文放在 `status.metadata[DATA-NEGOTIATION-T/v1]`（不是扩展点；携带 negotiationType/round/negotiationId/status）

## 12. 事件回调

订阅执行事件实现实时监控：

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
        case EventType.STEP_START:
            System.out.println("步骤开始: " + data.get("step"));
            break;
        case EventType.AGENT_STATUS_UPDATE:
            System.out.println("智能体 " + data.get("agent")
                + " 状态: " + data.get("state"));
            break;
        case EventType.NEGOTIATION_REQUEST:
            System.out.println("协商请求来自 " + data.get("agent"));
            break;
        case EventType.COMPLETE:
            System.out.println("工作流执行完成");
            break;
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

事件类型常量定义在 `EventType` 类中。

## 13. 从编排中心加载工作流

从远程编排中心加载 PSOP 工作流：

```java
// 按意图搜索
List<WorkflowSearchResult> results = LoadPsop.search(
    "https://127.0.0.1:5001",
    "SPN跨城专线故障诊断",
    5,      // topN
    null,   // access token
    false   // ssl verify
);

// 按 ID 加载完整工作流
Workflow workflow = LoadPsop.load(
    "https://127.0.0.1:5001",
    results.get(0).getWorkflowId(),
    null,   // access token
    false   // ssl verify
);
```

## 14. 架构

```
com.openan.a2at.engine
  +-- client          A2A 消息传输、认证、扩展
  +-- control         用户决策点（ControlPoint、事件系统）
  +-- core            内部工作流执行（包私有）
  +-- model           数据模型（Workflow、Task、Result 等）
  +-- registry        PSOP 加载 + AgentCard 注册中心
  +-- runner          ExecutePsop（入口）
```

**公开 API**（用户面向）：
- `WorkflowEngineClient` / `WorkflowEngineClientConfig`
- `ControlPoint` / `DefaultControlPoint`
- `ExecutePsop.Builder`
- `AuthProvider`
- `ExtensionHandler`
- `LoadPsop` / `RegistryClient`
- `EventCallback` / `EventType`
- Model 类（`Workflow`、`Task`、`ExecutionResult` 等）

**内部实现**（包私有，不对外暴露）：
- `DefaultWorkflowEngineClient`、`DefaultA2AJavaClientRuntime`
- `TaskTHandler`、`NegotiationTHandler`、`ExtensionInterceptor`
- `AgentAuthManager`、`AgentCredentialService`、`CredentialCrypto`
- `ContextBuilder`、`WorkflowExecutor`
- `ProtocolLogger`、`SslContextFactory`、`EnvFileLoader`
