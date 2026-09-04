# A2A-T 工作流执行引擎 - 二次开发集成指南

## 1. 概述

A2A-T 工作流执行引擎是一个 Java SDK，用于基于 A2A 协议和 A2A-T 电信扩展编排多智能体工作流。

引擎处理 A2A 信封、消息收发、流式响应、协商关联、认证和 TLS；宿主负责最终内容生成、语义校验和业务决策。

## 2. 环境要求

| 要求  | 版本 |
|-------|------|
| JDK   | 17+  |
| Maven | 3.6+ |

## 3. 引入依赖

```xml

<dependency>
    <groupId>net.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
<version>1.0.0</version>
</dependency>
```

## 4. 快速上手

整个集成过程分四步：定义工作流 -> 加载 AgentCard -> 实现 ControlPoint -> 执行。

完整可运行源码：[HostQuickStart.java](../../samples/src/main/java/dev/openan/workflow/engine/examples/demo/HostQuickStart.java)。
该源码参与编译，远端任务→本地汇总流程由 HostQuickStartTest 验证。
可复制到集成方工程，或在 IDEA 的 samples 模块运行，传入注册中心 URL、目标 AgentCard 名称、凭证路径。
下列片段解释同一套 API；AgentCard 加载方式二选一，Task-T 内容生成需要由业务实现。
非空业务条件必须实现 onRoute 策略；这里使用无条件边，不依赖默认选路。

### 4.1 定义工作流

```java
Workflow workflow = Workflow.builder()
        .name("服务分析")
        .steps(List.of(
                WorkflowStep.builder()
                        .name("analyze")
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Dispatched Agent")
                                        .skill("analysis")
                                        .description("分析当前请求")
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
                        .stepType(StepType.SELF_LOOP)   // 宿主智能体本地步骤，不发送 A2A-T
                        .subtasks(List.of(
                                Task.builder()
                                        .agent("Host Agent")
                                        .skill("aggregate")
                                        .description("汇总结果")
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

### 4.2 加载 AgentCard

```java
// 方式一：从 JSON 文件加载
ObjectMapper mapper = new ObjectMapper()
                .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
        new File("agentcard/my_agent.json"), AgentCard.class);

// 方式二：从注册中心拉取
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
ObjectMapper cardMapper = new ObjectMapper().registerModule(new AgentCardJacksonModule());
List<AgentCard> cards = registry.fetchAgentCards().stream()
        .map(raw -> cardMapper.convertValue(raw, AgentCard.class)).toList();
```



RegistryClient 默认设置覆盖正文读取的 30 秒截止时间；可使用
`new RegistryClient(url, true, Duration.ofSeconds(15))` 设置正值预算。编排中心 query token 日志仅显示 `<anonymous>`，
集成方也不要自行打印带凭据的原始 URL。

### 4.3 实现 ControlPoint

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

### 4.4 执行

```java
CompletableFuture<ExecutionResult> execution = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(callbacks)
        .runtimeIntent("分析服务请求")
        .lang("zh")
        .credentialsConfigPath("credentials.json")
        .sslVerify(true)
        .onFinish((r, history) -> {
            System.out.println("执行结果: " + r.isSuccess());
        })
        .execute();
try {
    ExecutionResult result = execution.get(10, TimeUnit.MINUTES);
    System.out.println(result.getStepOutputs());
} finally {
    if (!execution.isDone()) execution.cancel(true);
}
```

必填项：`psop`、`controlPoint`。其余配置项都有默认值。

## 5. 配置

### 5.1 .env 文件

引擎不读取 A2A-T .env，也不创建 LLM client。宿主智能体的业务回调需要 A2A-T 时，由宿主使用自有环境文件初始化 A2ATClient/A2ATServer，配置 provider/model/key/base URL 和 A2AT_LANGUAGE。样例中的 a2atEnvPath 不是引擎 builder 参数。被调度智能体凭据解密不与
LLM 配置耦合：内置凭据模式通过 WorkflowEngineClientConfig.builder().credentialEncryptionKey (key) 显式提供密钥， 再将配置好的
engineClient 传给 ExecutePsop。自定义 AuthProvider 自行管理 token 和配置。 测试使用当前 SDK SPI 的离线
provider，不覆盖模板，也不是生产失败兜底。

### 5.2 凭证配置文件

需要认证的智能体，提供 JSON 凭证文件：

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

生成密钥后由宿主安全保存，并显式传给 credentialEncryptionKey；下列环境变量也可由宿主读取：

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

实例隔离加密可调用 `CredentialCrypto.encrypt(plaintext, keyHex)`；显式 key 优先且不会修改 JVM 全局属性。不要记录明文或密钥。

**加密密码**

先执行 `mvn -pl workflow-engine -am package`，以下命令在仓库根目录运行，仅需 SDK jar 和 JDK。
`set` 是 Windows cmd 语法，PowerShell 应使用 `$env:A2AT_CRED_KEY='...'`。
以下参数仅用于演示；命令行口令/密钥可能进入终端历史和进程参数。生产应由集成方安全读取密钥并调用 Java 加密 API。

```bash
# 方式一：先设置环境变量
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"

# 方式二：密钥作为第二个参数
java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

输出：

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

将输出结果填入凭证 JSON 的 `value` 字段。

**更换密钥**

1. 生成新密钥：`openssl rand -hex 32`
2. 更新集成方密钥存储及显式 `credentialEncryptionKey`，或 OS/JVM 的 `A2AT_CRED_KEY`；引擎不自动加载 `.env`
3. 用新密钥重新加密所有密码：`java -cp workflow-engine/target/workflow-engine-1.0.0.jar dev.openan.workflow.engine.client.CredentialCrypto "明文密码" 新密钥`
4. 将新的 `enc:...` 结果更新到凭证 JSON 文件

> `.env` 文件不应提交到版本库，建议加入 `.gitignore`。

### 5.3 自定义认证（AuthProvider）

当令牌由集成方或外部认证服务获取，或使用非标准认证方式时，实现 `AuthProvider` 接口。接口只有一个方法：

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
        .sslVerify(true)
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
- `securitySchemes` 表示智能体支持的认证方式；`securityRequirements` 表示当前对接强制要求的认证方式。
  `securityRequirements` 为空时不启用内置凭证认证，但 `AuthProvider` 仍会被调用
- 只配置 `AuthProvider` 时，它可以作为唯一认证来源，即使 `securityRequirements` 非空
- 如果同时配了凭证文件和 `AuthProvider`，两者分别生成 Header 后合并；同名不同值会 fail-fast
- 认证失败时（如 token 获取异常），抛出的异常会传播到 `send()` 方法，请求会被拦截，不会发出

## 6. AgentCard 定义

AgentCard 通过 `capabilities.extensions` 声明扩展点：

```json
{
  "name": "Dispatched Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "结构化任务提示",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Negotiation-T/v1",
        "description": "协商文本交换",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "授权操作",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "通知订阅",
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

`securitySchemes` 与 `securityRequirements` 都是可选字段。前者表示智能体支持的认证方式，后者表示当前对接强制要求的认证方式；
`securityRequirements: []` 表示不启用内置凭证认证。

## 7. A2A-T 扩展能力

只有远端 `INPUT_REQUIRED` 携带有效 Negotiation-T Propose 才进入 `onNegotiation`。 终态不会重启协商，普通 INPUT_REQUIRED
明确报告不支持的交互。 宿主自行校验、理解 Propose，并用自己的 A2A-T client 生成最终 Accept/Reject/Abort。 通过
`A2atMessages.contextOf(request.received())` 取得收到的上下文； 结束回复保持相同 id、round、maxRounds，最后允许的一轮仍可回答，不自行
nextRound 或返回新 Propose。

返回 `new NegotiationReply.Send(content)` 发送最终内容； 返回 `new NegotiationReply.Stop(code, reason)` 只在本地停止，不生成
Abort。 同一任务／会话／轮次的重复等待事件不会重复回调、重复提交；未变化状态通过 getTask 观察。
`maxNegotiationExchanges` 默认 3，是独立于 SDK context.maxRounds 的本地交互资源预算。 超时、预算耗尽、回调缺失均明确失败，不默认
Accept，也不自动生成 Abort。 Accept/Reject 的 SUBMITTED/WORKING ACK 仍需等待任务结果，不重发原命令。 业务发送 Abort 后，即使远端用
COMPLETED 确认，也不能判为任务成功。

```java
CompletableFuture<SendMessageResult> sendAuthorization(String agentName, MessageContent content);
NotificationSubscription openNotification(String agentName, MessageContent content,
    BiConsumer<NotificationSubscription, ReceivedMessage> listener);
```

宿主智能体生成最终 Authorization-T/Notification-T 内容后调用上述接口；使用三类独立 transport/runtime/context。订阅监听器收到 handle 与完整 ReceivedMessage，在宿主定义的终态事件上关闭。handle.acknowledgement() 和 completion() 分别表示 ACK 和真实流退出，两者都不是工作流前提。

## 8. HTTPS 配置

```java
// 仅用于受控的本地诊断：跳过证书链校验，但仍校验主机名
.sslVerify(false)

// 生产环境：启用验证 + 自定义 CA 证书
.sslVerify(true)
.caCertsPath("/path/to/ca-certs.pem")

// 可选：mTLS 与 CRL。私钥支持 PKCS#8 PEM/DER；加密私钥需提供密码
.clientCertPath("/path/to/client-cert.pem")
.clientKeyPath("/path/to/client-key.pem")
.clientKeyPassword("change-me")
.crlPath("/path/to/revocations.crl")
```

HTTP/JSON-RPC 的 TLS 策略只作用于当前客户端，不修改 JVM 全局主机名校验设置；关闭证书链校验时仍会加载 mTLS 客户端身份。生产环境应保持
`sslVerify(true)`；自签证书通过 `caCertsPath` 建立信任。默认 gRPC runtime 在
`sslVerify(false)` 时使用 plaintext，因此不能同时配置 mTLS 或 `crlPath`，这些组合会 fail-fast。

## 9. 日志

将 `PROTOCOL` logger 设为 DEBUG，可查看实际传输边界的观测记录。 HTTP/JSON-RPC 记录 A2A SDK 处理后的实际序列化正文和应用头；
A2A-Version 只在真实请求有该头时出现，不为日志展示补造 Header。 gRPC 记录实际 metadata 和 protobuf 的 JSON 展示，不伪装成
HTTP JSON 报文。 JDK 自动网络头、HTTP/2 帧、TLS 密文及服务端字节不在此观测范围。

`MODEL_PREVIEW` 只是高层预览，默认关闭，不是协议证据。

```properties
logger.protocol.name=PROTOCOL
logger.protocol.level=DEBUG
logger.protocol.additivity=true
WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY=true
WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS=100000
```

DEBUG 开启时正文观测默认开启；敏感部署可显式禁用。JVM 同名属性优先于环境变量。 认证头、Cookie、Token
及识别出的口令字段强制脱敏，不提供关闭脱敏的开关。 这是字段级脱敏，不能自动识别所有个人信息和业务敏感内容，生产应另定日志策略。
缓冲有界：原始收集器按该数值限制字节，输出文本按字符限制； 超限 SSE 帧整帧丢弃至下个分隔符并标记
dropped-capacity，禁用、截断、中断也有明确标记。 UTF-8 分片先组装再解码，日志观察失败不改变报文投递；文件引用不会为打印日志而下载。
requestId 关联单次调用；工作流调用还带 executionId/logicalTaskId/attempt、 agent/contextId/channel，已知远端任务时附
remoteTaskId，均为本地日志字段，不污染协议 metadata。

### 验证协议与协商日志

使用一个被调度智能体返回“`INPUT_REQUIRED` + 有效 Negotiation-T Propose”的受控测试。预期序列为：请求 → Propose → `onNegotiation` → 最终 Send/Stop → 任务终态。`PROTOCOL` 日志应显示实际观测到的请求／响应边界与关联字段，且不泄露凭据。AgentCard 声明 Negotiation-T 能力本身不会强制发起协商。

## 10. 事件回调

订阅执行事件实现实时监控：

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case EventType.STEP_START -> System.out.println("步骤开始: " + data.get("step"));
            case EventType.TASK_STATUS_CHANGED -> System.out.println(
                    data.get("agent") + " 状态: " + data.get("state"));
            case EventType.NEGOTIATION_REQUEST -> System.out.println(
                    "协商请求来自 " + data.get("agent"));
            case EventType.COMPLETE -> System.out.println("工作流执行完成");
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

常用事件类型：`START`、`STEP_START`、`TASK_REQUEST`、`TASK_RESPONSE`、
`TASK_STATUS_CHANGED`、`STEP_COMPLETE`、`NEGOTIATION_REQUEST`、
`NEGOTIATION_RESOLVED`、`NEGOTIATION_FAILED`、`ROUTE_DECISION`、
`WORKFLOW_COMPLETE`、`COMPLETE`、`ERROR`、`CLOSE`。

## 11. 从编排中心加载工作流

```java
// 按意图搜索
List<WorkflowSearchResult> results = LoadPsop.search(
                "https://orchestration.example.com", "分析服务请求", 5, null, true);

// 按 ID 加载完整工作流
Workflow workflow = LoadPsop.load(
        "https://orchestration.example.com", results.get(0).getWorkflowId(), null, true);
```

## 12. 自定义扩展

直接构造最终 MessageContent(parts, metadata, extensions)，由宿主管理扩展内容的生成／校验。无需注册引擎 handler 或 SDK
实例。A2atMessages.from 是 A2A-T metadata 复制辅助；非 A2A-T 扩展也可直接提供 metadata 和激活 URI。引擎不会仅因 AgentCard
声明而自动生成内容。

## 13. 外部端点与启动责任

### 13.1 编排中心 HTTPS 联调

`LoadPsop.search/load` 显式接收 `sslVerify` 参数。生产环境保持 `true`。受控开发环境可传入 `false`，仅对当前连接跳过证书链和主机名校验，并打印 `[Registry] INSECURE_TLS` 警告；不修改 JVM 全局 TLS 默认值。

“免配本地证书”不表示 HTTPS 服务端不需要证书，也不能绕过要求客户端证书的 mTLS。
该非验证模式不能确认服务端身份，存在中间人风险，仅用于受控联调。
生产保持 `true`，给服务端配置匹配实际 URL 的 SAN，并让运行 JVM 信任其 CA；
被调度智能体的 `caCertsPath` 不会自动传给 LoadPsop。
LoadPsop 不修改 JVM 全局 SSLContext、默认 SocketFactory 或 HostnameVerifier；
被调度智能体 HTTP/JSON-RPC 的 `false` 仍仅跳过证书链校验。

### 13.2 被调度智能体端点

引擎只消费 AgentCard，不启动被调度智能体服务。宿主智能体负责 AgentCard 发现、端点可达性校验与开发测试资源。使用外部管理的被调度智能体时，不得在相同端点上启动本地测试服务。生产 AgentCard 和凭据应保存在仓库外部。

### 13.3 Demo 启动前任务清理

Demo 在打开独立协议通道和启动工作流之前，先查询每个被调度智能体中处于 `SUBMITTED`、`WORKING`、
`INPUT_REQUIRED`、`AUTH_REQUIRED` 状态的任务，再通过标准 A2A 任务接口取消当前认证身份可见的全部结果。
查询会跟随 `nextPageToken`，对扫描过程中发生状态变化的任务去重，并正确处理“查询后、取消前任务已进入终态”的竞态。
查询和取消使用独立的短生命周期认证传输，不复用工作流、授权或通知的生命周期。

清理默认开启并采用 fail-fast，避免遗留任务静默累积后触发容量错误。可通过
`A2A_TASK_CLEANUP_ENABLED`、`A2A_TASK_CLEANUP_FAIL_FAST`、`A2A_TASK_CLEANUP_PAGE_SIZE`（1–100）和
`A2A_TASK_CLEANUP_MAX_TASKS` 配置。任务查询受认证身份权限约束；若多个实例共用同一身份，Demo 可能取消其他实例创建的活跃任务。
应使用隔离身份，或在提供等价的任务归属清理策略后关闭该功能。

## 14. A2A 错误与任务失败

任务创建前和创建后的失败必须分开处理。请求在任务创建前被拒绝时，返回非 2xx HTTP 状态和标准
A2A `google.rpc.Status` JSON 错误信封：

```json
{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"缺少必填参数","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"INVALID_PARAMS","domain":"a2a-protocol.org","metadata":{"field":"port"}}]}}
```

`RemoteA2AErrorException` 保留实际观察到的 HTTP 状态、信封 code/status/message、类型化 details、
ErrorInfo reason/domain 及安全响应头；`findIn(Throwable)` 也会投影 A2A Java SDK 的类型化错误。
直连传输检查普通响应，并防御性识别被 SDK 作为 SSE data 暴露的建流前顶层错误信封。
Task、Message 或 Artifact 内嵌的 `error` 对象仍属于业务内容，不会被误判为协议错误。

若 SSE 调用未产生任何 A2A 事件便结束，传输会立即失败，不再等待工作流发送超时。底层 SDK 不暴露空流响应的 HTTP
状态，因此该场景报告为传输/协议失败，不会虚构 HTTP 错误码。

工作流 history 和 TASK_RESPONSE 使用 `a2a.invalid_params` 等稳定错误码；未知 HTTP 错误回退为
`a2a.http.<status>`。`errorDetails` 保留协议事实及实际取得的 `retryAfter`。这类错误不生成成功输出、
不触发 onNegotiation，也不自动重试。

任务创建成功后的业务执行失败不是 HTTP 协议错误。被调度智能体返回 HTTP 200，并通过 Task 或
StatusUpdate 的 `TASK_STATE_FAILED` 表示失败；TaskStatus 消息、任务 metadata 和 artifact 携带失败证据。
引擎将工作流任务标记为失败，把证据保留在 `receivedMessages`，但不解释扩展协议的业务结果 schema。
独立授权和通知订阅的失败仍按各自生命周期处理，不决定工作流结果。

对于 `ALL_SUCCESS` 步骤，一个任务失败不会追溯取消已下发的并行任务；引擎在另一任务返回或超时后报告 `success=false`。后续步骤不执行，成功的并行结果保留在 history，不伪造整体成功结果。引擎不自动重试、排队或部分成功汇总；`ANY_SUCCESS` 节点仍遵循声明的任一成功语义。

宿主智能体通过 TASK_RESPONSE 事件及时观察任务失败，或在 `onFinish(result, events)`
检查 `result.history`；onTask 是发送前准备消息的回调，不是失败重试回调。
引擎节点事件带 executionId；TASK_RESPONSE 和 history 还带逻辑 taskId。
宿主智能体决定如何向调用方暴露失败，但不得把失败执行转换为成功 artifact。常驻宿主以独立生命周期管理通知订阅。

**日志职责**：PROTOCOL 记录已观察到的请求/响应，按 requestId 关联并发报文；
A2A_ERROR 记录协议拒绝，TASK_FAILED 记录执行 ID、节点、逻辑任务 ID、
智能体、错误码和原因，WORKFLOW_STOPPED 列出未执行节点。示例 TASK_RESPONSE 日志同时包含
contextId 与 executionId，供跨层定位。已识别的 A2A 错误使用 WARN 摘要，
未知异常保留 ERROR 堆栈；错误详情中的已知凭据及 Bearer/Basic 值脱敏。
日志开关、pretty 展示和观察回调不会改变任务结果。

## 15. 接口一览

| 接口/类                                                | 用途                                                          |
|--------------------------------------------------------|---------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | 工作流执行入口                                                |
| `ControlPoint` / `DefaultControlPoint`                 | 业务决策实现（onTask、onSelfTask、onRoute、onNegotiation 等） |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | 工作流发送（sendMessage、认证、扩展）                         |
| `ExtensionSender` / `DefaultExtensionSender`           | 独立 Authorization-T 操作与 Notification-T 长连接订阅         |
| `A2ATransport`                                         | 共享通信层（A2A Java 客户端 runtime、认证、SSE 消费）         |
| `WorkflowEngineClientConfig`                           | 配置（SSL、认证、A2A-T、协商轮数、自定义 Handler）            |
| `AuthProvider`                                         | 自定义认证                                                    |
| `EventCallback` / `EventType`                          | 事件回调                                                      |
| `LoadPsop` / `RegistryClient`                          | 工作流加载 / AgentCard 获取                                   |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | 工作流定义                                                    |
| `ExecutionResult`                                      | 执行结果                                                      |
| `SendMessageResult` / `TaskResult`                     | 消息/任务响应                                                 |
