# WindWhisper
An AI bot based on the Discourse forum system. This project aims to add a personified user to the forum, rather than an AI assistant. Users can interact with this user to obtain AI-generated content.

This experimental `codex/responses-native` branch uses the OpenAI Responses API throughout. Each forum notification adds a user message to one ongoing server-side response chain; OpenAI manages its context and automatic compaction.

## Brief Feature Description

- Supports all websites using the Discourse forum system
- Uses Responses API native function calling, stored responses, and automatic context compaction
- The AI reads posts, replies, and gives likes while continuing the same conversation across notifications.
- Upon receiving a notification, if the notification is related to a specific topic, the AI will automatically process that topic.
- Supports hosted web search and image generation, image/PDF reading, and uploading generated images into forum replies.

## Installation & Usage

### Step 0
Ensure you have Java 17 installed. Lower or higher versions are not supported.

### Step 1
Build this branch from source. Existing releases may still use the previous architecture.

#### Self-compilation
If you wish to compile it yourself, ensure you have a Gradle and Java 17 environment. Then clone this project and run the following command:
```bash
git switch codex/responses-native
./gradlew buildFatJar
```
After compilation completes, the `WindWhisper.jar` file will be generated in the `build/libs` directory.

### Step 2: Preparation
1. Register an account on the target forum and note down the username and password.
2. Obtain an API key for a model/provider supporting Responses, stored response continuation, and server-side compaction. The default endpoint is OpenAI.
3. Enable hosted web search and image generation if your account and selected model support them. No separate search API key is needed.

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

The default `proxy: null` uses direct connections. This setting covers the forum, Responses API (including streaming), and image/PDF downloads; destination names are resolved by the proxy. Responses hosted search runs on the provider's servers; the local proxy covers requests to the Responses API. Restart the application after changing proxy settings; `config reload` does not update existing clients. A failed proxy connection returns an error without falling back to a direct connection. Configure the username and password together, using 1 to 255 ISO-8859-1 characters each.

Edit `configs/ai.yml`:
```yaml
# Timeout for non-streaming API requests, in milliseconds
timeout: 120000
retry: 3
model:
  url: "https://api.openai.com/v1/responses"
  model: "gpt-6-astra" # Use a model available to your account
  key: ["your_openai_api_key"]
  visible: true
  compactThreshold: 100000
  webSearch: true
  imageGeneration: true
  customRequestParms:
    reasoning:
      effort: low
```

`url` must be the complete Responses endpoint. `compactThreshold` is the token threshold for server-side automatic compaction; it must be at least 1000 and should leave room below the model's context limit. `webSearch` and `imageGeneration` control the hosted tools. `visible` controls whether the bot exposes its image/PDF reading tool. The conversation consistently uses the first entry in `key`; it does not randomly switch credentials between turns.

`customRequestParms` accepts native Responses options such as `reasoning.effort` and `max_output_tokens`. The application owns input, continuation, storage, and compaction settings. Use the dedicated configuration fields for hosted search and image generation. Hosted-search citations appear as Markdown links; answer text is displayed after completion so those links can be included.

Edit `prompt.txt` to customize the bot's personality and standing instructions. These instructions are sent with each API request; notification details arrive separately as user messages.

### Continuous conversation and migration

This branch sends `store: true`, continues with `previous_response_id`, and enables `context_management` compaction. It does not use a separate Conversations API object or resend the whole conversation history. See the official [conversation state](https://developers.openai.com/api/docs/guides/conversation-state) and [compaction](https://developers.openai.com/api/docs/guides/compaction) documentation.

`data/responses-session.json` holds the response ID and notification/tool execution state needed for continuation and recovery. It is control state, not a model-written summary. Keep it when restarting the program. Changing the forum identity, API endpoint, or first API key pauses new notification processing; restart with matching session data, and use a separate working directory for a different account. The prior `data/memory.md` is no longer read or updated, and existing user files are not deleted automatically.

For an existing installation, update `configs/ai.yml` to the example above and remove the old `apiType`, `search`, `webSearchKey`, `vlmModel`, and `thinking_budget` fields. Update your existing `prompt.txt` as well: remove instructions to output or reconstruct a complete notebook and remove `${self_memory}` / `${topic_id}` placeholders. The bundled default prompt only initializes missing files, so it will not overwrite an existing prompt.

### Expiration and manual recovery

OpenAI stores response objects for 30 days by default, so `previous_response_id` may become unavailable after a long idle period. This branch's local checkpoint cannot reconstruct expired remote context, and the application does not silently discard it to start another conversation. See the official [response retention documentation](https://developers.openai.com/api/docs/guides/conversation-state).

If a pending turn has a non-null `executingToolId`, the process stopped before a tool result was checkpointed. A write operation may already have reached the forum, so its outcome is unknown. The application stops recovery instead of repeating that operation. Check the forum and logs before manually recovering; do not blindly clear the marker or retry a post, reaction, or image upload. Completed notifications whose read acknowledgement failed are kept in a separate control ledger and retried without running the model again, even if they no longer appear in the current unread page.

To deliberately start a new conversation after expiration or an unresolved interrupted turn:

1. Stop WindWhisper. Confirm the actual data directory from your current launch configuration: `-dataDir=/path` overrides it; otherwise it is `data` under `-workDir` (the current working directory by default).
2. Inspect the pending notification and recent forum actions, using the checkpoint and logs to identify them. Resolve any uncertain write result manually, and mark notifications already handled as read in the forum before restarting. Resetting the checkpoint also removes its completed-notification ledger, so unread notifications can be processed again.
3. Back up `responses-session.json` from that data directory, then move or delete only the active `responses-session.json`. For example, rename it to `responses-session.backup.json`. Keep `generated-images/`, your configuration, and other user data.
4. Restart with the intended forum account and Responses credentials. The missing checkpoint starts a fresh conversation with the current `prompt.txt`; it does not recover the old conversation. Only reset after reviewing the forum state as above.

### Generated images in forum posts

The model can invoke the hosted `image_generation` tool. WindWhisper saves completed images under `data/generated-images/` and gives the model their image IDs. The model calls `upload_generated_image` with an image ID and destination topic ID to obtain forum Markdown, then includes that Markdown in `send_post`. Generating an image alone does not publish a forum post. Forum access restrictions still apply.

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
1. Read unread forum notifications and process them serially.
2. Append a user message containing the notification ID and topic ID to the existing response chain, with forum tools and enabled hosted tools available.
3. Execute requested forum tools and send their results back to the same response chain until the model finishes.
4. Persist continuation/execution state and acknowledge the completed notification. The next notification continues the conversation, with context compaction handled by the Responses API.
