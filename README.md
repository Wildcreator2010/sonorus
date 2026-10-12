# Sonorus

一个跑在自己手机上的 AI 客户端：把 API Key 交给你自己选的模型服务商，然后在本机聊天、生成、导出。没有中转服务器，没有账号体系，也没有任何人替你转发请求。

## 能做什么

- **三种协议，一个客户端**：OpenAI 兼容 / Anthropic Messages / Gemini Generative Language。添加提供商时选好协议，端点和密钥格式由适配层处理，你不用关心 `reasoning_effort` 和 `thinking.budget_tokens` 的差别。
- **思考等级**：关闭 / 低 / 中 / 高四档，全局默认 + 单会话覆盖。三套协议的映射方式不同（`reasoning_effort`、`thinking.budget_tokens`、`thinkingBudget`）；Anthropic 要求预算严格小于 `max_tokens`，塞不下时整块省略 `thinking` 字段，宁可退化成普通请求也不发会被 400 的非法值。
- **多模态**：一条消息最多 4 张图，发送前压到长边 1024；透明图保持 PNG，其余按 JPEG 85 编码。
- **回答可以反悔**：重新生成、按选中原文批注修改、多版本切换、编辑已发送的提示词并重发。所有版本存在同一条消息里。
- **本地工具**：计算器与时间，模型可以自己调用（按协议能力开关）。
- **离开页面不断流**：回答在后台继续写完，切回来还是实时的进度。没有任何消息的空会话在你回到列表时自动清掉。
- **导出与分享**：Markdown / JSON，复制到剪贴板、分享、或存成文件。
- **请求成功率统计**：纯本地记录，只记协议、模型、耗时和失败摘要，不记 prompt 也不记响应正文。

## 数据与安全

- API Key 存在 **Keystore 加密的 EncryptedSharedPreferences**（`sonorus_secrets.xml`）里：不进 Room 数据库、不写日志、不进 UI 状态，并被显式排除在云备份和设备迁移之外——Keystore 主密钥本就无法导出，跨设备恢复注定失败。
- 会话与消息在 Room 数据库 `sonorus.db`，**明文存储**，因此它不参与云备份；换机时靠系统设备迁移（规则里显式允许）或用应用内「导出会话」自己搬。
- 全局偏好（默认提供商+模型、上下文长度、主题、字号等）走 DataStore，**会**随云备份走，里面没有任何密钥。
- release 构建禁止明文 HTTP（debug 变体单独放开，方便连局域网自建网关）、OkHttp 不跟随重定向（防止密钥被 302 带到别的主机）、R8 剥离日志。
- 应用内更新只认 GitHub Releases，且**发布说明里必须带 sha256**，校验不过直接拒装，不会降级成"不校验也装"。

## 构建

需要 JDK 17 与 Android SDK（`compileSdk` / `targetSdk` 均为 34）。

在项目根目录建 `local.properties`：

```properties
sdk.dir=<你的 Android SDK 路径>

# 可选：release 签名。缺省时 release 自动回退 debug 签名，仅供本地自测，绝不可对外分发
RELEASE_STORE_FILE=<p12/jks 路径>
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

然后：

```
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（有凭据就用你的签名）
./gradlew testDebugUnitTest  # JVM 单元测试，无需设备
```

`gradle/wrapper` 里的 `distributionUrl` 指向腾讯镜像；官方源在你的网络环境下不通时，可自行改回 `services.gradle.org`。

## 目录分层

```
data/     local(Room) / remote(协议适配 + SSE) / prefs(DataStore) / security(密钥) / repository
domain/   agent(执行器与工具) / model / repository(接口)
ui/       Compose 页面 + ViewModel
di/       Hilt 装配
```

新增一种协议 = 加一个 `ProviderProtocol` 枚举值 + 实现 `ProtocolAdapter` + 在 `AppModule` 里 `@IntoMap` 注册。
新增一个工具 = 实现 `AgentTool` 并注册进 `ToolRegistry`。

## 版本要求

Android 8.0（minSdk 26）及以上。当前 `0.1.0`。

## 说明

代码里大量注释写的是"为什么这样绕"而不是"这行干什么"——因为踩过的坑（Anthropic 要求 messages 首条必须是 user、`budget_tokens` 必须严格小于 `max_tokens`、Room 实体里的枚举是 String）都不显眼，值得留字为证。
