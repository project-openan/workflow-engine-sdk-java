# A2A-T 工作流执行引擎 - 架构设计

> A2A-T 工作流执行引擎的架构设计与设计原理。
> 本文档描述 v1.0 发布版本。面向集成或扩展 SDK 的工程师，
> 不是某个 bug 修复的历史记录。

---

## 1. 概述

A2A-T 工作流执行引擎让宿主智能体通过 A2A 协议和 A2A-T 电信扩展执行多步骤工作流。
工作流是一个有向无环图（DAG），每个步骤向远程智能体下发一个或多个任务，并路由到下一步。
SDK 负责协议机制（消息发送、流式传输、认证、Task-T 提示词生成、Negotiation-T 自动协商循环），
暴露少量决策接口由宿主实现业务逻辑。

核心设计原则：**SDK 负责协议机制，宿主负责业务决策**。

| SDK 负责（协议机制）                                      | 宿主负责（业务决策）           |
|----------------------------------------------------------|-------------------------------|
| A2A 消息发送、流式传输、SSE 规范化                        | 是否发送任务、何时发送         |
| 智能体认证（Bearer、自定义 Header）                       | 凭证配置                       |
| A2A-T 扩展（Task-T、Negotiation-T、Authorization-T、Notification-T） | 授权审批、通知处理             |
| DAG 遍历、上下文组装、状态管理                            | 分支路由决策                   |
| 事件发射                                                  | 事件处理                       |

---

## 2. 分层架构

SDK 分为四层，每层构建在下一层之上，单一职责，入口清晰。

```
Layer 2 - 编排层       ExecutePsop
   |     生命周期、事件流、取消、onFinish 持久化
Layer 1 - 遍历层       WorkflowExecutor
   |     DAG 遍历、并行下发、上下文组装、路由
Layer 0 - 通信层       A2ATransport + 两个门面
   |     WorkflowEngineClient（工作流发送）| ExtensionSender（一次性前置下发）
基础层 - 决策          ControlPoint / ExtensionCallback
                         用户实现的业务决策
```

### 2.1 Layer 0 - 通信层

**`A2ATransport`** 是共享通信层，只负责一件事：把字节发到远程智能体再收回来。
它拥有 A2A SDK 客户端运行时、认证管理器和拦截器、智能体卡片映射、流式响应消费者。
暴露两个发送原语：`send`（收集并返回）和 `sendNotificationStream`（长连接 SSE），
以及将原始 SDK 事件流转换为文本、任务状态和元数据的静态提取器。

**两个门面构建在 transport 之上，各司其职：**

- **`WorkflowEngineClient`** — 工作流执行发送路径。拥有 Task-T 提示词生成（发送前）、
  Negotiation-T 自动循环（接收后）、全局 `EventCallback`、`ControlPoint` / `ExtensionCallback` 装配。
  这是执行器在工作流执行期间调用的门面。
- **`ExtensionSender`** — 一次性前置下发。在工作流启动前向智能体发送 Authorization-T 和 Notification-T 消息。
  绕过 Task-T 生成和协商循环，不通过全局回调发射事件 — 返回的结果就是回调。

#### 为什么共享 transport + 两个门面？

工作流发送路径和一次性前置下发路径都需要相同的通信层机制：HTTP 客户端、TLS 配置、
认证拦截器、智能体卡片解析、SSE 解析。把这些机制放在任一门面上要么 (a) 强制只想做前置下发的
调用方持有完整工作流门面，要么 (b) 在两个类中重复通信代码。共享 transport + 两个门面的设计
避免了这两个问题。

### 2.2 Layer 1 - 遍历层

**`WorkflowExecutor`** 遍历 DAG。在每个步骤组装上游上下文（`ContextBuilder`），
并发下发子任务，应用步骤成功策略，确定下一步。所有决策委托给 `ControlPoint`，
所有发送委托给 `WorkflowEngineClient`。

步骤下发规则：
- 前驱步骤全部完成的步骤被收集并并行下发
- 同一层的步骤并发执行
- `ALL_SUCCESS` — 所有子任务必须成功
- `ANY_SUCCESS` — 第一个成功的子任务胜出，其余取消
- `SELF_LOOP` — 任务由 `onSelfTask` 本地处理，不发送 A2A-T 消息

### 2.3 Layer 2 - 编排层

**`ExecutePsop`** 是高层运行器。包装执行器，提供生命周期管理（启动/完成/错误/关闭）、
事件序列化、客户端断连取消、`onFinish` 持久化钩子。大多数集成使用这一层。

---

## 3. 决策接口

SDK 暴露两个用户实现的接口，按职责拆分。

### 3.1 ControlPoint — 流程决策

驱动工作流前进。每个方法由执行器或自动协商循环调用，做恰好一个决策：

| 方法             | 调用方       | 决策                                       |
|------------------|-------------|-------------------------------------------|
| `onTask`         | 执行器       | 向智能体发送任务（调用 `sendMessage`）       |
| `onSelfTask`     | 执行器       | 本地处理自环任务（不走 A2A-T）              |
| `onRoute`        | 执行器       | 在条件步骤选择分支                          |
| `onNegotiation`  | 客户端自动循环 | 在 INPUT_REQUIRED 时提供澄清文本            |

### 3.2 ExtensionCallback — 响应式钩子

响应智能体推送的 A2A-T 数据。与流程决策不同：它们响应对端发起的扩展流量，不驱动工作流前进。

| 方法              | 触发时机                                  | 决策           |
|-------------------|------------------------------------------|---------------|
| `onAuthorization` | 智能体在任务响应中推送 Authorization-T 请求 | 批准或拒绝     |
| `onNotification`  | 智能体在任务响应中推送 Notification-T 载荷 | 处理通知       |

#### 为什么拆分 ControlPoint 和 ExtensionCallback？

流程决策和响应式钩子有不同的调用时机和职责。`onTask` 和 `onRoute` 由执行器在 DAG 遍历时调用；
`onAuthorization` 和 `onNotification` 由扩展处理器响应智能体推送的数据时调用。
分开接口意味着只关心路由的宿主不需要实现授权钩子，反之亦然。

---

## 4. A2A-T 扩展模型

支持四个 A2A-T 扩展，按生命周期分为两组。

### 4.1 工作流内扩展

参与每次 `sendMessage` 生命周期，通过扩展处理器链（`ExtensionRegistry` 自动注册）：

- **Task-T** — 发送时，调用 A2A-T SDK 从自然语言消息生成结构化任务提示词，注入消息 metadata。
  协商后续和调用方预设提示词时跳过。接收时：透传。
- **Negotiation-T** — 接收时，当智能体返回 `INPUT_REQUIRED` 并声明该扩展，
  提取协商上下文和消息。这驱动自动循环：引擎调用 `ControlPoint.onNegotiation` 获取澄清，
  重发后续消息，重复直到达到配置的轮次上限。

### 4.2 前置下发扩展

一次性发送，在工作流启动前通过 `ExtensionSender` 完成：

- **Authorization-T** — 发送授权前置请求。提示词值由 A2A-T SDK 生成；
  SDK 不可用时回退到原始自然语言输入。
- **Notification-T** — 建立结果订阅。打开长连接 SSE 流，后续抢通结果通过该流返回。

订阅结果（如后续推送的抢通结果）通过 `sendNotification` 响应流返回，
不通过 `onNotification`。该钩子仅在智能体在 `sendMessage` 任务响应中主动包含
Notification-T 载荷时触发。

### 4.3 扩展处理器链

```
sendMessage(agent, message)
  -> before_send:  Task-T 生成提示词，注入 metadata
  -> transport.send（Task-T metadata 在线上传输）
  -> after_receive: Negotiation-T 提取上下文（驱动自动循环）
  -> auto_negotiate 循环（如果 INPUT_REQUIRED）
```

`ExtensionRegistry.getHandlersForExtensions` 将智能体声明的扩展 URI 与处理器关键字
（不区分大小写）匹配，返回该智能体的处理器链。Authorization-T / Notification-T 处理器类
保留给需要内联处理智能体推送数据的调用方，但不自动注册 — 这是前置下发的关注点。

---

## 5. 条件路由

步骤的 `next` 列表持有 `JumpCondition(step, condition)` 条目。路由规则：

- **无 `next`** — 终端步骤，完成该分支。
- **所有条件为空** — 无条件扇出：并行下发每个非终端下一步。
- **有条件** — 条件路由：调用 `ControlPoint.onRoute`，返回单个 `RouteDecision.nextStep`。
  引擎强制要求返回的步骤在声明的条件中；无效步骤以警告结束工作流。

这使得条件分支是 N 选 1 选择，无条件扇出是自动并行下发。

---

## 6. 事件模型

事件通过可选的 `EventCallback` 以稳定字符串类型（`EventType`）发射，按来源分组：

- **运行器生命周期** — `start`、`complete`、`close`
- **步骤/任务执行** — `step_start`、`step_complete`、`task_request`、`task_response`、
  `task_status_changed`、`route_decision`、`workflow_complete`
- **智能体流量** — `agent_request`、`agent_response`、`agent_status_update`、
  `agent_artifact_update`、`agent_message_event`
- **A2A-T 扩展** — `negotiation_request`、`negotiation_resolved`、`negotiation_failed`、
  `authorization_request`、`authorization_resolved`、`notification`
- **失败** — `error`，由执行器在步骤失败时和运行器在最终失败时发射

---

## 7. 交互序列

### 7.1 带协商的工作流执行

```
宿主          执行器           EngineClient       智能体
 |  run(workflow)|                |                 |
 |-------------->|                |                 |
 |              | onTask(req)     |                 |
 |              |--------------->|                 |
 |              |                | before_send:Task-T|
 |              |                |---------------->|
 |              |                |   send message  |
 |              |                |<----------------|
 |              |                | after_receive:  |
 |              |                |  Negotiation-T  |
 |              |                | (INPUT_REQUIRED)|
 |              |<---------------| negotiation result|
 |              | onNegotiation  |                 |
 |<-------------|  (宿主提供澄清) |                 |
 |------------->|                |                 |
 |              |--------------->| follow-up send  |
 |              |                |---------------->|
 |              |                |<----------------|
 |              |<---------------| final result    |
 | ExecutionResult|             |                 |
 |<-------------|                |                 |
```

### 7.2 前置下发授权

```
宿主                ExtensionSender         Transport        智能体
 | sendAuthorization|                        |               |
 |------------------>|                        |               |
 |                   | generate prompt (SDK) |               |
 |                   | send(instruction,auth)|               |
 |                   |----------------------->|               |
 |                   |                        |-------------->|
 |                   |                        |<--------------|
 |                   |<-----------------------| auth result   |
 |<------------------|                        |               |
```

### 7.3 Notification 订阅

```
宿主           ExtensionSender         Transport             智能体
 | sendNotification|                     |                      |
 |--------------->|                     |                      |
 |                | sendNotificationStream|                     |
 |                |--------------------->|                      |
 |                |                     | open long-lived SSE  |
 |                |                     |---------------------->|
 |                |                     |<--- ack (working) ----|
 |                |<--------------------| first event -> future |
 |<---------------|                     |                      |
                                                (后续结果通过同一连接流回)
```

---

## 9. 依赖

**本 SDK：** `org.a2aproject.sdk:a2a-java-sdk-client`（A2A 协议）、
`net.openan.a2at.sdk:a2a-t-client`（A2A-T 扩展）、Jackson、SLF4J、Lombok。

SDK 是独立的：不依赖编排中心。

---

## 10. 设计决策总结

1. **共享 transport，两个门面** — 通信层机制在 `A2ATransport` 上写一次；
   `WorkflowEngineClient` 和 `ExtensionSender` 各自拥有一个编排职责，委托通信工作。
   避免强制门面耦合和通信代码重复。

2. **ControlPoint / ExtensionCallback 拆分** — 流程决策和响应式钩子有不同的调用时机和职责；
   分开保持每个接口的内聚性，宿主只实现需要的部分。

3. **工作流内扩展 vs 前置下发扩展** — Task-T 和 Negotiation-T 是 `sendMessage` 链的一部分；
   Authorization-T 和 Notification-T 是工作流启动前的一次性发送。
   注册表只自动注册工作流内的一对。

4. **自动协商循环** — 引擎拥有重发循环，宿主只提供澄清文本（`onNegotiation`），
   不需要关心重发的协议机制。

5. **条件路由语义** — 空条件意味着扇出（并行），条件分支意味着通过 `onRoute` 做 N 选 1。
   保持路由模型可预测。