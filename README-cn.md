# WindWhisper
基于discourse论坛系统的AI机器人。该项目旨在为论坛增加一个拟人的用户，而不是增加一个AI助手。用户可以通过与该用户互动来获取AI生成的内容。

本实验分支`codex/responses-native`全面使用OpenAI Responses API。每条论坛通知作为一条user消息追加到同一条服务端响应链，由OpenAI管理上下文和自动压缩。

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
proxy:
  host: "127.0.0.1"
  port: 1080
  # 代理需要认证时，同时填写以下两项；否则省略
  # username: "your_proxy_username"
  # password: "your_proxy_password"
```

默认`proxy: null`表示直连。该配置覆盖论坛、Responses API（含流式响应）及图片/PDF下载；目标域名由代理解析。Responses内置搜索在服务商端执行，本地代理覆盖发往Responses API的请求。修改代理配置后需重启程序，`config reload`不会更新现有客户端。代理连接失败会报错，不会自动直连。用户名和密码需同时设置，每项支持1至255个ISO-8859-1字符。

编辑`configs/ai.yml`：
```yaml
# 超时时间（毫秒）：非流式请求/恢复GET为总时限；
# 流式请求为无新事件的最长等待时间，包含等待响应头。
# 只要持续收到事件，流式请求总时长可以超过此值。
timeout: 120000
retry: 3
model:
  url: "https://api.openai.com/v1/responses"
  model: "gpt-6-astra" # 使用你的API账户可访问的模型
  key: ["your_openai_api_key"]
  visible: true
  compactThreshold: 100000
  webSearch: true
  imageGeneration: true
  customRequestParms:
    reasoning:
      effort: low
```

`url`必须填写完整的Responses接口地址。`compactThreshold`是服务端自动压缩的token阈值，至少为1000，并应与模型上下文上限留出余量。`webSearch`和`imageGeneration`控制内置工具；`visible`控制是否向模型提供图片/PDF阅读工具。持续会话固定使用`key`列表中的第一个密钥，不会在各轮请求间随机切换。

`customRequestParms`接受`reasoning.effort`、`max_output_tokens`等Responses原生选项。输入、会话续接、存储与压缩参数由程序管理；内置搜索和图片生成使用专用配置字段。内置搜索引用显示为Markdown链接；回答正文在响应完成后显示，以便包含引用。

编辑`prompt.txt`可自定义人格和长期指令。程序在每次API请求中发送这些指令，具体通知信息则作为独立的user消息输入。

### 持续会话与旧版本迁移

本分支设置`store: true`，通过`previous_response_id`续接，并启用`context_management`自动压缩。不创建单独的Conversations API对象，也不逐次重发完整历史。参见官方[会话状态](https://developers.openai.com/api/docs/guides/conversation-state)和[上下文压缩](https://developers.openai.com/api/docs/guides/compaction)文档。

`data/responses-session.json`保存续接所需的响应ID及通知、工具执行状态，供重启后恢复使用。这是程序控制状态，不是模型输出的摘要；重启时请保留该文件。运行中更改论坛身份、API地址或第一个API密钥会暂停新通知处理；需重新启动并使用匹配的会话数据，更换账户时应使用独立工作目录。旧的`data/memory.md`不再读取或更新，现有用户文件不会被自动删除。

已有部署需按上例更新`configs/ai.yml`，移除旧的`apiType`、`search`、`webSearchKey`、`vlmModel`、`thinking_budget`字段。还需修改现有`prompt.txt`：移除输出或重建完整笔记的要求，以及`${self_memory}`、`${topic_id}`占位符。默认提示词只会在文件缺失时生成，不会覆盖已有自定义内容。

### 会话过期与人工恢复

OpenAI默认保留响应对象30天，因此长期空闲后`previous_response_id`可能失效。本分支的本地检查点无法重建已过期的远端上下文，程序也不会静默丢弃它并另起会话。参见官方[响应保留期限说明](https://developers.openai.com/api/docs/guides/conversation-state)。

如果待处理轮次的`executingToolId`不为空，说明程序在工具结果落盘前中断。写操作可能已到达论坛，执行结果尚未确认；程序会停止恢复，避免重复执行。必须先核对论坛与日志，再人工恢复，不要直接清空标记或盲目重试发帖、点赞、上传图片。对于模型已完成但标读失败的通知，程序另存确认账本，即使通知不再出现在当前未读页，也只重试标读，不会再次调用模型。

如需在会话过期或中断状态无法续接后主动开始新会话：

1. 停止WindWhisper。根据当前启动配置确认实际数据目录：`-dataDir=/path`会覆盖默认路径；否则使用`-workDir`下的`data`，未设置`-workDir`时为当前工作目录下的`data`。
2. 根据检查点与日志定位待处理通知，核对论坛中的近期操作。人工确认并处理结果不明的写操作；已处理完的通知应先在论坛标为已读。重置检查点也会清除已完成通知的记录，仍未读的通知可能再次被处理。
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

### 注意事项

1. 建议征求论坛管理员同意后再使用该项目，以免违反论坛规则或引起不必要的麻烦。
2. 每当有notification时，AI会自动处理相关topic。如果你设置了在点赞时收到通知，可能会导致AI因为收到点赞而多次处理统一topic，因此建议关闭该选项。
3. 请注意API使用费用，尤其是在使用大型模型或频繁请求时。
4. 该项目仅供学习和研究使用，请勿用于商业用途或违反任何法律法规的行为。
5. 如果你在使用过程中遇到任何问题，欢迎在GitHub仓库中提交issue。

## 工作原理简介

WindWhisper重复执行以下步骤：
1. 读取论坛未读通知，并逐条串行处理。
2. 将包含notification ID和topic ID的user消息追加到已有响应链，同时提供论坛工具和启用的内置工具。
3. 执行模型调用的论坛工具，将结果发送回同一响应链，直到模型完成当前任务。
4. 保存续接和执行状态，再确认已完成的通知。下一条通知继续原对话，Responses API负责压缩上下文。
