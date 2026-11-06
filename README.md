# WindWhisper
An AI bot based on the Discourse forum system. This project aims to add a personified user to the forum, rather than an AI assistant. Users can interact with this user to obtain AI-generated content.

This experimental `codex/responses-native` branch uses the OpenAI Responses API throughout. Each poll adds one user message containing a batch of forum notifications to an ongoing server-side response chain; OpenAI manages its context and automatic compaction.

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
forumWriteTimeoutMillis: 120000
proxy:
  host: "127.0.0.1"
  port: 1080
  # Set both fields if the proxy requires authentication; otherwise omit them
  # username: "your_proxy_username"
  # password: "your_proxy_password"
```

The default `proxy: null` uses direct connections. This setting covers the forum, Responses API (including streaming), and image/PDF downloads; destination names are resolved by the proxy. Responses hosted search runs on the provider's servers; the local proxy covers requests to the Responses API. Restart the application after changing proxy settings; `config reload` does not update existing clients. A failed proxy connection returns an error without falling back to a direct connection. Configure the username and password together, using 1 to 255 ISO-8859-1 characters each.

`forumWriteTimeoutMillis` sets the total timeout for forum writes, including image upload, forum processing, and receiving the response. It defaults to 120000 milliseconds and requires a restart after changes. It does not change model, forum-read, or media-download timeouts. Timed-out or disconnected writes are not automatically replayed.

Edit `configs/ai.yml`:
```yaml
# Timeout in milliseconds: total time for non-streaming requests/recovery GETs;
# maximum time without a new event for streams, including waiting for response headers.
# Active streams may run longer as long as events keep arriving.
timeout: 120000
notificationPollIntervalSeconds: 60
model:
  url: "https://api.openai.com/v1/responses"
  model: "gpt-6-astra" # Use a model available to your account
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

`notificationPollIntervalSeconds` controls the wait before the first notification poll and after each notification-processing round. It defaults to 5 seconds when omitted; the example above sets it to 60 seconds. `config reload ai.yml` applies changes when the next wait is scheduled; it does not interrupt an already scheduled wait.

`url` must be the complete Responses endpoint. `compactThreshold` is the token threshold for server-side automatic compaction; it must be at least 1000 and should leave room below the model's context limit. `webSearch` and `imageGeneration` control the hosted tools. `visible` controls whether the bot exposes its image/PDF reading tool. The conversation consistently uses the first entry in `key`; it does not randomly switch credentials between turns.

`imageGenerationModel` selects the hosted image model independently of the conversation model. The default is `gpt-image-2.6-sunburst`, which supports the `complexity` parameter used by current conversation models; relying on the API default can select an older image model that rejects this parameter.

`customRequestParms` accepts native Responses options such as `reasoning.effort` and `max_output_tokens`. The application owns input, continuation, storage, and compaction settings. Use the dedicated configuration fields for hosted search and image generation. Hosted-search citations appear as Markdown links; answer text is displayed after completion so those links can be included.

Edit `prompt.txt` to customize the bot's personality and standing instructions. These instructions are sent with each API request; each poll's notification batch arrives separately in one user message.

Routine request start, response creation/completion, notification completion, and read acknowledgements use `FINE` logging and are hidden at the default `INFO` level. Use `logger level FINE` in the console for diagnostics. Long waits, errors, and notification summaries remain visible. A successful response containing a `compaction` item produces an `INFO` entry, `Responses API context compaction result received`, without encrypted contents or guessed before/after token counts. Recovery GETs may confirm the same response's compaction result again.

### Continuous conversation and migration

This branch sends `store: true`, continues with `previous_response_id`, and enables `context_management` compaction. It does not use a separate Conversations API object or resend the whole conversation history. See the official [conversation state](https://developers.openai.com/api/docs/guides/conversation-state) and [compaction](https://developers.openai.com/api/docs/guides/compaction) documentation.

`data/responses-session.json` holds the response ID and notification/tool execution state needed for continuation and recovery. It is control state, not a model-written summary. Keep it when restarting the program. Changing the forum identity, API endpoint, or first API key stops the notification loop with an error; restore the matching configuration and restart. Use a separate working directory for a different account. The prior `data/memory.md` is no longer read or updated, and existing user files are not deleted automatically.

Each poll collects all unprocessed notifications, deduplicates them by notification ID, and lists them oldest first in one user message. Multiple notifications for the same topic retain their separate IDs so the model can handle that topic together. Only one new batch is submitted per poll, after any pending batch has been recovered and outstanding read acknowledgements have been retried.

Local function-tool failures are returned to the model as `function_call_output` with the exception type, original message, and underlying causes, and the current turn continues. HTTP errors retain their status and response body, including details such as `wait_seconds` in a 429 response. A timed-out or disconnected write reports `outcome: unknown` instead of assuming the forum did not execute it. The application does not automatically replay tool requests; the model can use the error to decide its next calls. Successful and failed tool results are checkpointed so recorded calls are not executed again during recovery.

If the Responses API request, response protocol, or checkpoint handling fails, the application discards the batch and attempts to mark all of its notifications as read. Model requests are not automatically retried, and the batch is not rerun at the next five-second poll. Forum actions already performed are not rolled back. A read-acknowledgement failure remains in the ledger for retry, even if the notification is no longer on the current unread page. Each ID is acknowledged at most once per poll; a failed acknowledgement does not block other acknowledgements or new notification batches, and the old notification is never sent back to the model. Stopping or cancelling the application instead preserves the pending batch for recovery. The checkpoint tracks these delivery decisions separately from the server's conversation context.

For an existing installation, update `configs/ai.yml` to the example above and remove its old `retry`, `apiType`, `search`, `webSearchKey`, `vlmModel`, and `thinking_budget` fields. The forum-login `retry` setting in `config.json` remains supported. Update your existing `prompt.txt` as well: remove instructions to output or reconstruct a complete notebook and remove `${self_memory}` / `${topic_id}` placeholders. Revise single-notification instructions to handle a batch of notifications together. The bundled default prompt only initializes missing files, so it will not overwrite an existing prompt.

### Expiration and manual recovery

OpenAI stores response objects for 30 days by default, so `previous_response_id` may become unavailable after a long idle period. This branch's local checkpoint cannot reconstruct expired remote context, and the application does not silently discard it to start another conversation. See the official [response retention documentation](https://developers.openai.com/api/docs/guides/conversation-state).

If a pending turn has a non-null `executingToolId`, the process stopped before a tool result was checkpointed. A write operation may already have reached the forum, so its outcome is unknown. Recovery treats this as a failed batch and discards it without repeating the operation. Check the forum and logs before taking any manual action; do not blindly clear the marker or retry a post, reaction, or image upload. Expired server context cannot be repaired by discarding a batch; review and reset the conversation as described below before processing further notifications.

To deliberately start a new conversation after expiration or an unresolved interrupted turn:

1. Stop WindWhisper. Confirm the actual data directory from your current launch configuration: `-dataDir=/path` overrides it; otherwise it is `data` under `-workDir` (the current working directory by default).
2. Inspect recent batches and forum actions using the logs and any pending checkpoint. Resolve uncertain write results manually, and mark notifications already handled as read in the forum before restarting. Resetting the checkpoint also removes its completed-notification ledger, so unread notifications can be processed again.
3. Back up `responses-session.json` from that data directory, then move or delete only the active `responses-session.json`. For example, rename it to `responses-session.backup.json`. Keep `generated-images/`, your configuration, and other user data.
4. Restart with the intended forum account and Responses credentials. The missing checkpoint starts a fresh conversation with the current `prompt.txt`; it does not recover the old conversation. Only reset after reviewing the forum state as above.

### Generated images in forum posts

The model can invoke the hosted `image_generation` tool. WindWhisper saves completed images under `data/generated-images/` and gives the model their image IDs. The model calls `upload_generated_image` with an image ID and destination topic ID to obtain forum Markdown, then includes that Markdown in `send_post`. Generating an image alone does not publish a forum post. Forum access restrictions still apply.

### Update the bot's avatar and biography

`get_my_profile` reads the current account's biography, avatar, and avatar permissions. `update_my_bio` accepts `bio` and replaces the full biography; an empty string clears it. `update_my_avatar` accepts a saved generated `imageId`, uploads the image, and selects it as the account's avatar. The account is fixed by the login configuration; these tools accept no target username or user ID. Forum errors return to the model without automatic write retries. These tools do not change the bot's persona instructions.

### Generate speech with a saved voice

Add a top-level `speech` section to `configs/ai.yml`. First create a voice once using OpenAI's [custom voice API](https://developers.openai.com/api/docs/guides/custom-voices), retaining its ID and original description. Prompt-created voices require GPT-Live.

```yaml
speech:
  enabled: true
  url: wss://api.openai.com/v1/live/sessions
  model: gpt-live-1
  voiceId: voice_your_saved_id
  voicePrompt: "The original description used to create your voice"
  timeoutMillis: 120000
```

Speech inherits the first Responses key only for the same provider; set `speech.key` explicitly for a different provider. Requests use the same configured SOCKS5 proxy. `config reload ai.yml` applies speech settings to the next turn's tools. Speech is disabled by default.

The `generate_speech` tool accepts `text` (1–500 characters), `speed` (0.5–2.0, default 1), and `style` (for example, happy, calm, or gentle). The saved voice remains fixed across calls. Speed is approximate speaking-pace guidance, not an exact playback multiplier.

GPT-Live is a continuous conversation API without a per-clip audio completion event. This tool checks the transcript and trailing audio silence to collect a clip; this is a heuristic, not a native TTS completion guarantee. Text mismatches, timeouts, and service errors return tool errors without automatic retry or incomplete uploads. Completed WAV files are saved under `data/generated-audio/` before being uploaded. The returned Markdown labels the audio as AI-generated and can be included in `send_post.content`; generating and uploading speech does not itself publish a post.

### Step 4: Running
Run the following command again to start WindWhisper:
```bash
java -jar WindWhisper.jar
```
The program should connect to the forum and start working. You can see log output in the console.

### Console commands

```text
send Please check the latest replies in topic 9950
context
```

`send <message>` appends a user message to the current Responses conversation with the same tools and persona used for notifications. The command returns immediately. Messages wait for the current task to finish and results appear in the logs. The queue holds up to 32 messages; messages that have not started are not retained across restarts, while started turns use the existing checkpoint recovery. Sending a console message does not acknowledge forum notifications.

`context` shows the model, pending turn, queued messages, continuation response, and automatic compaction threshold. It uses the Responses input-token-count endpoint to measure the saved context, including instructions, tool definitions, and pending input, without generating an answer or executing tools. While the model is working, the count excludes output still being generated. The compaction threshold is not the model's maximum context window. Both commands are also available through the existing web console.

### Notes

1. It is recommended to obtain consent from the forum administrator before using this project to avoid violating forum rules or causing unnecessary trouble.
2. Whenever there is a notification, the AI will automatically process the related topic. If you have notifications enabled for likes, it might cause the AI to process the same topic multiple times due to receiving likes. Therefore, it is suggested to disable that option.
3. Please be mindful of API usage costs, especially when using large models or making frequent requests.
4. This project is for learning and research purposes only. Do not use it for commercial purposes or any actions that violate laws and regulations.
5. If you encounter any issues during use, feel free to submit an issue on the GitHub repository.

## Brief Working Principle Introduction

WindWhisper repeats the following steps:
1. Retry outstanding read acknowledgements and recover a batch interrupted by shutdown, if any.
2. Collect unprocessed notifications oldest first and append one user message listing all notification IDs and topic IDs to the existing response chain, with forum tools and enabled hosted tools available.
3. Execute requested forum tools and send their results back to the same response chain until the model finishes or the batch fails.
4. Persist continuation and delivery state, then mark the batch's notifications as read. A failed batch is discarded rather than replayed. The next batch continues the conversation, with context compaction handled by the Responses API.
