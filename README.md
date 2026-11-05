# WindWhisper
An AI bot based on the Discourse forum system. This project aims to add a personified user to the forum, rather than an AI assistant. Users can interact with this user to obtain AI-generated content.

## Brief Feature Description

- Supports all websites using the Discourse forum system
- Supports models with native function calling through OpenAI Responses API or Chat Completions
- The AI can read posts on the forum, reply, and give likes. It maintains memory continuity across different posts.
- Upon receiving a notification, if the notification is related to a specific topic, the AI will automatically process that topic.
- [Optional]Enables the AI to perform web searches, read URL content, etc.

## Installation & Usage

### Step 0
Ensure you have Java 17 installed. Lower or higher versions are not supported.

### Step 1
Go to the [Releases](https://github.com/CyanTachyon/WindWhisper/releases) page and download the latest version of the `WindWhisper.jar` file.

#### Self-compilation
If you wish to compile it yourself, ensure you have a Gradle and Java 17 environment. Then clone this project and run the following command:
```bash
gradle build
```
After compilation completes, the `WindWhisper.jar` file will be generated in the `build/libs` directory.

### Step 2: Preparation
1. Register an account on the target forum and note down the username and password.
2. Choose an OpenAI API-compatible model and provider that support native function calling, then obtain an API Key.
3. Choose a web search provider if needed. Tavily search requires a [Tavily](https://tavily.com) API Key; Responses hosted search uses your model provider and needs no Tavily key. Search can also be disabled.

### Step 3: Initialization
Start WindWhisper using the following command:
```bash
java -jar WindWhisper.jar
```
On the first run, the program should exit immediately and generate related files in the current directory.

Edit `config.json`:
```json5
{
  "url": "your_forum_url",
  "username": "your_forum_username",
  "password": "your_forum_password",
  "retry": 3, // Number of retries on failed forum login attempts
  "defaultHeaders": {
    "KEY_NAME": "KEY_VALUE" // Additional headers for forum requests. Optional
  },
  "reactions": {
    "heart": "a red heart" // All available reaction names on the forum and their corresponding descriptions.
  },
  "webServer": { // Optional, enables a web interface to view logs, set blacklists, etc.
    "enabled": false, // Whether to enable the web server, default is false
    "port": 8080, // Web server listening port, default is 8080
    "host": "0.0.0.0", // Web server listening address, default is 0.0.0.0
    "rootPath": "/" // Web server root path, default is /
  }
}
```

To route all outgoing requests through a SOCKS5 proxy, edit `configs/network.yml`:

```yaml
proxy:
  host: "127.0.0.1"
  port: 1080
  # Set both fields if the proxy requires authentication; otherwise omit them
  # username: "your_proxy_username"
  # password: "your_proxy_password"
```

The default `proxy: null` uses direct connections. This setting covers the forum, model APIs (including streaming), Tavily, and image/PDF/video downloads; destination names are resolved by the proxy. Responses hosted search runs on the provider's servers; the local proxy covers requests to the Responses API. Restart the application after changing proxy settings; `config reload` does not update existing clients. A failed proxy connection returns an error without falling back to a direct connection. Configure the username and password together, using 1 to 255 ISO-8859-1 characters each.

Edit `configs/ai.yml`:
```yaml
# Timeout for AI requests in milliseconds
timeout: 120000
# Number of retries for AI service
retry: 3
webSearchKey:
  [
    "your_tavily_api_key" # Optional; use webSearchKey: [] to disable Tavily tools
  ]
model:
  apiType: chat_completions # Optional; this is the default for existing configurations
  search: tavily # tavily, responses, or none
  url: "https://your.openai.api/endpoint/v1/chat/completions" # Note: full URL is required, including /v1/chat/completions
  key: ["your openai_api_key"] # If multiple keys are provided, one will be randomly selected for each use.
  model: "gpt-4" # Choose the model you want to use
  visible: false # Whether the model supports image inputs
  customRequestParms: # Optional, custom request body fields
    temperature: 0.7
    top_p: 0.9
    presence_penalty: 0
    frequency_penalty: 0
```

To use OpenAI Responses API, replace the `model` section with:

```yaml
model:
  apiType: responses
  search: responses
  url: "https://api.openai.com/v1/responses"
  key: ["your_openai_api_key"]
  model: "gpt-6-astra" # Use a model available to your API account
  visible: true
  customRequestParms:
    reasoning:
      effort: low
```

Choose the protocol separately for each model: the optional `vlmModel` section accepts the same fields and must have `visible: true`. Existing configurations without `apiType` continue to use Chat Completions. Always supply the complete URL for the selected protocol; changing `apiType` does not rewrite the URL.

Select web search with `model.search`:

| Value | Behavior | Requirements |
| --- | --- | --- |
| `tavily` | Registers the Tavily search, extract, and crawl tools. | At least one key in the existing top-level `webSearchKey` list. Works with either API protocol. |
| `responses` | Enables the Responses API's hosted web search, without registering Tavily tools. | `apiType: responses` and a model/provider supporting hosted web search. No Tavily key needed. |
| `none` | Disables Tavily search, extract, and crawl tools, and hosted web search. | None. |

Omitting `model.search` preserves Tavily tools for the main model; an empty `webSearchKey: []` disables those tools. Select hosted search through this field, not `customRequestParms.tools`. Hosted search citations are rendered as Markdown links. When hosted search is enabled, answer text is displayed after the response completes so citations can be included; reasoning summaries still stream when provided by the API.

Chat Completions remains supported by OpenAI. GPT-6 Astra's tool calling requires Responses, which is needed for this bot's forum tools. See the official [Responses migration guide](https://developers.openai.com/api/docs/guides/migrate-to-responses) and [GPT-6 guide](https://developers.openai.com/api/docs/guides/latest-model).

`customRequestParms` supplies native request body fields for the selected API. For Responses, use `max_output_tokens` for an output limit and `reasoning.effort` for reasoning effort; do not copy Chat Completions fields such as `max_tokens`, `presence_penalty`, `frequency_penalty`, `stop`, or `thinking_budget`. Omit `temperature` and `top_p` for GPT-6 reasoning. Responses requests default to `store: false`; WindWhisper replays output items and tool results locally between turns, without `previous_response_id`.

[Optional]Edit `prompt.md`. This is the system prompt. You can modify it as needed to adjust the AI's behavior.
[Optional]Edit `data/memory.md`. This file stores the AI's memory. You can pre-fill it with some information to influence the AI's behavior.

### Step 4: Running
Run the following command again to start WindWhisper:
```bash
java -jar WindWhisper.jar
```
The program should connect to the forum and start working. You can see log output in the console.

### Notes

1. It is recommended to obtain consent from the forum administrator before using this project to avoid violating forum rules or causing unnecessary trouble.
2. Whenever there is a notification, the AI will automatically process the related topic. If you have notifications enabled for likes, it might cause the AI to process the same topic multiple times due to receiving likes. Therefore, it is suggested to disable that option.
3. Please be mindful of API usage costs, especially when using large models or making frequent requests.
4. This project is for learning and research purposes only. Do not use it for commercial purposes or any actions that violate laws and regulations.
5. If you encounter any issues during use, feel free to submit an issue on the GitHub repository.

## Brief Working Principle Introduction

WindWhisper repeats the following steps:
1. Check the forum's notifications. If there is a new notification and it is associated with a topic, execute the following steps.
2. Provide the AI with the prompt, memory, and the pending topicID as part of the prompt. Also provide tools to get the topic, get posts, like, reply, and the web search provider selected by `model.search`.
3. The AI will read the posts through the tools and interact.
4. After the interaction ends, the AI needs to output new memory content, which the program will save to `data/memory.md`.
