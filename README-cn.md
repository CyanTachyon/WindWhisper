# WindWhisper
基于discourse论坛系统的AI机器人。该项目旨在为论坛增加一个拟人的用户，而不是增加一个AI助手。用户可以通过与该用户互动来获取AI生成的内容。

本实验分支`codex/responses-native`全面使用OpenAI Responses API。每轮将一批论坛通知集中作为一条user消息追加到同一条服务端响应链，由OpenAI管理上下文和自动压缩。

## 简要功能说明

- 支持所有使用Discourse论坛系统的网站
- 使用Responses API原生函数调用、服务端响应续接和自动上下文压缩
- AI可以阅读论坛中的帖子，并回复、点赞；不同通知之间延续同一个对话。
- 每当收到一个notification时，如果该notification与某一topic相关，AI会自动处理该topic。
- 支持内置联网搜索、图片生成、图片/PDF阅读，以及将生成的图片上传到论坛回复。

## 安装与使用

### 步骤0
确保您已安装了java 17。更低或更高版本都不被支持。

### 步骤1
请从当前分支源码编译；现有Releases可能仍使用旧架构。

#### 自行编译
如果您希望自行编译，请确保您已有gradle和java 17环境。然后克隆本项目并运行以下命令：
```bash
git switch codex/responses-native
./gradlew buildFatJar
```
编译完成后，`build/libs`目录下会生成`WindWhisper.jar`文件。

### 步骤2：准备工作
1. 在目标论坛注册一个账户，并记下用户名和密码。
2. 获取支持Responses、服务端响应续接和自动压缩的模型及服务商API Key；默认使用OpenAI接口。
3. 按账户与模型的支持情况启用内置联网搜索和图片生成，无需单独的搜索API Key。

### 步骤3：初始化
使用以下命令启动WindWhisper：
```bash
java -jar WindWhisper.jar
```
首次执行，程序应当会立刻退出，并在当前目录生成相关文件。

编辑`config.json`:
```json5
{
  "url": "your_forum_url",
  "username": "your_forum_username",
  "password": "your_forum_password",
  "retry": 3, // 登陆论坛失败时的重试次数
  "defaultHeaders": {
    "KEY_NAME": "KEY_VALUE" // 请求论坛时额外的请求头。可选
  },
  "reactions": {
    "heart": "一个红色爱心" // 论坛上所有可用的reaction名称及其对应的描述。
  },
  "webServer": { // 可选，启用后可通过web界面查看日志、设置黑名单等
    "enabled": false, // 是否启用web服务器，默认false
    "port": 8080, // web服务器监听端口，默认8080
    "host": "0.0.0.0", // web服务器监听地址，默认0.0.0.0
    "rootPath": "/" // web服务器根路径，默认/
  }
}
```

如需让所有对外请求使用SOCKS5代理，编辑`configs/network.yml`：

```yaml
forumWriteTimeoutMillis: 120000
proxy:
  host: "127.0.0.1"
  port: 1080
  # 代理需要认证时，同时填写以下两项；否则省略
  # username: "your_proxy_username"
  # password: "your_proxy_password"
```

默认`proxy: null`表示直连。该配置覆盖论坛、Responses API（含流式响应）及图片/PDF下载；目标域名由代理解析。Responses内置搜索在服务商端执行，本地代理覆盖发往Responses API的请求。修改代理配置后需重启程序，`config reload`不会更新现有客户端。代理连接失败会报错，不会自动直连。用户名和密码需同时设置，每项支持1至255个ISO-8859-1字符。

`forumWriteTimeoutMillis`设置论坛写请求的总超时，默认120000毫秒，包含图片上传、论坛处理和接收响应；修改后需重启。它不改变模型请求、论坛读取或媒体下载的超时。写请求超时或断连后不会自动重发，避免重复上传、发帖或切换点赞状态。

编辑`configs/ai.yml`：
```yaml
# 超时时间（毫秒）：非流式请求/恢复GET为总时限；
# 流式请求为无新事件的最长等待时间，包含等待响应头。
# 只要持续收到事件，流式请求总时长可以超过此值。
timeout: 120000
model:
  url: "https://api.openai.com/v1/responses"
  model: "gpt-6-astra" # 使用你的API账户可访问的模型
  key: ["your_openai_api_key"]
  visible: true
  compactThreshold: 100000
  webSearch: true
  imageGeneration: true
  imageGenerationModel: gpt-image-2.6-sunburst
  customRequestParms:
    reasoning:
      effort: low
```

`url`必须填写完整的Responses接口地址。`compactThreshold`是服务端自动压缩的token阈值，至少为1000，并应与模型上下文上限留出余量。`webSearch`和`imageGeneration`控制内置工具；`visible`控制是否向模型提供图片/PDF阅读工具。持续会话固定使用`key`列表中的第一个密钥，不会在各轮请求间随机切换。

`imageGenerationModel`单独指定内置图片工具的模型，默认使用`gpt-image-2.6-sunburst`，支持当前主模型传入的`complexity`参数；依赖API默认值可能选中不支持该参数的旧图片模型。

`customRequestParms`接受`reasoning.effort`、`max_output_tokens`等Responses原生选项。输入、会话续接、存储与压缩参数由程序管理；内置搜索和图片生成使用专用配置字段。内置搜索引用显示为Markdown链接；回答正文在响应完成后显示，以便包含引用。

编辑`prompt.txt`可自定义人格和长期指令。程序在每次API请求中发送这些指令，每轮的一批通知则集中作为一条独立的user消息输入。

常规请求开始、响应创建/完成、通知完成和标读详情使用`FINE`日志，默认`INFO`不显示；排查时可在控制台执行`logger level FINE`。长时间等待、异常提示和通知概览仍会显示。收到成功响应中的`compaction`项时，会以`INFO`记录`Responses API context compaction result received`，不打印加密压缩内容，也不推测压缩前后的token数量；恢复GET可能再次确认同一响应中的压缩结果。

### 持续会话与旧版本迁移

本分支设置`store: true`，通过`previous_response_id`续接，并启用`context_management`自动压缩。不创建单独的Conversations API对象，也不逐次重发完整历史。参见官方[会话状态](https://developers.openai.com/api/docs/guides/conversation-state)和[上下文压缩](https://developers.openai.com/api/docs/guides/compaction)文档。

`data/responses-session.json`保存续接所需的响应ID及通知、工具执行状态，供重启后恢复使用。这是程序控制状态，不是模型输出的摘要；重启时请保留该文件。运行中更改论坛身份、API地址或第一个API密钥会报错并结束通知循环；需要恢复匹配的配置后重启，更换账户时应使用独立工作目录。旧的`data/memory.md`不再读取或更新，现有用户文件不会被自动删除。

每轮轮询收集所有尚未处理的通知，按notification ID去重，再从旧到新列在同一条user消息中。同一话题的多个通知仍保留各自ID，供模型统一处理。程序先补充标读，再恢复尚未完成的批次；每轮最多提交一条新的批量消息。

批次失败时，程序立即丢弃整批并尝试将所有通知标为已读；模型请求不自动重试，也不会在下一次五秒轮询时重新执行该批次。已经发生的论坛操作不会回滚。标读失败的ID保留在账本中重试，即使通知已不在当前未读页，也不会再次调用模型。每个ID每轮最多尝试标读一次；某条标读失败不会阻塞其他通知标读或新批次处理，旧通知也不会重新发给模型。停止或取消程序则保留待处理批次，供下次恢复。检查点分别记录这些通知处理状态与服务端会话的续接信息。

已有部署需按上例更新`configs/ai.yml`，移除其中旧的`retry`、`apiType`、`search`、`webSearchKey`、`vlmModel`、`thinking_budget`字段；`config.json`中的论坛登录`retry`选项仍然保留。还需修改现有`prompt.txt`：移除输出或重建完整笔记的要求，以及`${self_memory}`、`${topic_id}`占位符，并将只处理单条通知的指令改成统一处理一批通知。默认提示词只会在文件缺失时生成，不会覆盖已有自定义内容。

### 会话过期与人工恢复

OpenAI默认保留响应对象30天，因此长期空闲后`previous_response_id`可能失效。本分支的本地检查点无法重建已过期的远端上下文，程序也不会静默丢弃它并另起会话。参见官方[响应保留期限说明](https://developers.openai.com/api/docs/guides/conversation-state)。

如果待处理轮次的`executingToolId`不为空，说明程序在工具结果落盘前中断。写操作可能已到达论坛，执行结果尚未确认；恢复时会将该批次按失败丢弃，不重复执行该操作。采取人工操作前应核对论坛与日志，不要直接清空标记或盲目重试发帖、点赞、上传图片。丢弃批次无法修复已过期的服务端上下文；继续处理后续通知前，应按下面的步骤核对并重置会话。

如需在会话过期或中断状态无法续接后主动开始新会话：

1. 停止WindWhisper。根据当前启动配置确认实际数据目录：`-dataDir=/path`会覆盖默认路径；否则使用`-workDir`下的`data`，未设置`-workDir`时为当前工作目录下的`data`。
2. 根据日志和仍待处理的检查点定位近期批次，核对论坛中的操作。人工确认并处理结果不明的写操作；已处理完的通知应先在论坛标为已读。重置检查点也会清除已完成通知的记录，仍未读的通知可能再次被处理。
3. 备份该目录中的`responses-session.json`，随后仅移走或删除生效的`responses-session.json`，例如重命名为`responses-session.backup.json`。保留`generated-images/`、配置和其他用户数据。
4. 确认论坛账户与Responses密钥后重新启动。检查点缺失时，程序使用当前`prompt.txt`开始新会话，不会恢复旧会话；重置前务必完成上述论坛状态核对。

### 在论坛中发送生成的图片

模型可以调用内置`image_generation`工具。程序将完整生成图片保存到`data/generated-images/`，并向模型提供image ID。模型通过`upload_generated_image`传入image ID和目标topic ID获得论坛Markdown，再将其放入`send_post`的内容。生成图片本身不会自动发帖，原有论坛访问限制继续生效。

### 步骤4：运行
再次运行以下命令启动WindWhisper：
```bash
java -jar WindWhisper.jar
```
程序应当会连接论坛并开始工作。你可以在控制台看到日志输出。

### 控制台命令

```text
send 请查看话题9950的最新回复
context
```

`send <消息>`将消息作为一条user消息送入当前Responses会话，使用与通知相同的工具和人格提示词。命令立即返回；若模型正在工作，消息会排队等待当前任务结束，处理结果显示在日志中。队列最多容纳32条消息；尚未开始的排队消息不会跨重启保留，已开始的轮次使用现有检查点恢复。发送命令本身不会标读论坛通知。

`context`显示当前模型、待处理轮次、排队消息、续接响应和自动压缩阈值，并调用Responses输入token计数接口查询当前已保存上下文的实际大小，包括提示词、工具定义和待发送内容。查询不会生成回答或执行工具。模型正在工作时，计数不包含仍在生成的输出；自动压缩阈值不等同于模型最大窗口。两个命令也可以通过现有Web控制台使用。

### 注意事项

1. 建议征求论坛管理员同意后再使用该项目，以免违反论坛规则或引起不必要的麻烦。
2. 每当有notification时，AI会自动处理相关topic。如果你设置了在点赞时收到通知，可能会导致AI因为收到点赞而多次处理统一topic，因此建议关闭该选项。
3. 请注意API使用费用，尤其是在使用大型模型或频繁请求时。
4. 该项目仅供学习和研究使用，请勿用于商业用途或违反任何法律法规的行为。
5. 如果你在使用过程中遇到任何问题，欢迎在GitHub仓库中提交issue。

## 工作原理简介

WindWhisper重复执行以下步骤：
1. 补做尚未成功的标读，并恢复因停机中断的批次（如有）。
2. 收集尚未处理的通知，从旧到新列出所有notification ID和topic ID，集中作为一条user消息追加到已有响应链，同时提供论坛工具和启用的内置工具。
3. 执行模型调用的论坛工具，将结果发送回同一响应链，直到模型完成或批次失败。
4. 保存续接和通知处理状态，再将整批通知标为已读；失败批次直接丢弃，不重新执行。下一批通知继续原对话，Responses API负责压缩上下文。
