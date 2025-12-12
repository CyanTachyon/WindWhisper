# WindWhisper
基于discourse论坛系统的AI机器人。该项目旨在为论坛增加一个拟人的用户，而不是增加一个AI助手。用户可以通过与该用户互动来获取AI生成的内容。

## 简要功能说明

- 支持所有使用Discourse论坛系统的网站
- 支持任意OpenAI API兼容的模型
- AI可以阅读论坛中的帖子，并回复、点赞。同时在不同的帖子间保持记忆连续性。
- 每当收到一个notification时，如果该notification与某一topic相关，AI会自动处理该topic。
- [可选]让AI可以联网搜索、读取url内容等

## 安装与使用

### 步骤0
确保您已安装了java 17。更低或更高版本都不被支持。

### 步骤1
前往[Releases](https://github.com/CyanTachyon/WindWhisper/releases)页面，下载最新版本的`WindWhisper.jar`文件。

#### 自行编译
如果您希望自行编译，请确保您已有gradle和java 17环境。然后克隆本项目并运行以下命令：
```bash
gradle build
```
编译完成后，`build/libs`目录下会生成`WindWhisper.jar`文件。

### 步骤2：准备工作
1. 在目标论坛注册一个账户，并记下用户名和密码。
2. 选择一个OpenAI API兼容的模型，并获取API Key。
3. 该项目网络搜索工具依赖[tavily](https://tavily.com)，如果你想让AI可以进行网络，需注册一个tavily账户并获取API Key。该步骤可选。

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
  "likes": {
    "heart": "一个红色爱心" // 论坛上所有可用的reaction名称及其对应的描述。
  }
}
```

编辑`configs/ai.yml`:
```yaml
# AI请求的超时时间，单位为毫秒
timeout: 120000
# AI服务的重试次数
retry: 3
webSearchKey:
  [
    "your_tavily_api_key" # 可选，留空则不启用网络搜索功能
  ]
model:
  url: "https://your.openai.api/endpoint/v1/chat/completions" # 注意需要完整URL，即包含/v1/chat/completions
  key: ["your openai_api_key"] # 如果填写多个，将每次随机选择一个使用。
  model: "gpt-4" # 选择你想使用的模型
  toolable: true # 是否支持工具调用，目前必须为true
  imageable: false # 是否支持视觉能力，目前该功能未实现
  customRequestParms: # 可选，自定义体字段
    temperature: 0.7
    top_p: 0.9
    presence_penalty: 0
    frequency_penalty: 0
```

[可选]编辑`prompt.md`，这是系统提示词，你可以根据需要修改它以调整AI的行为。
[可选]编辑`data/memory.md`，这是存储AI记忆的文件，你可以预先填入一些信息以影响AI的行为。

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
1. 检查论坛的notification。如果有新的notification，且其关联topic，执行以下步骤。
2. 将提示词、记忆、待处理的topicID作为提示词给AI，同时为AI提供获得topic、获得posts、点赞、回复以及联网搜索相关工具（如果配置了tavily API Key）。
3. AI将通过工具读取帖子，并进行互动。
4. 互动结束后，AI需输出新的记忆内容，程序将保存到`data/memory.md`中。