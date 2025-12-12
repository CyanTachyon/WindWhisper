# WindWhisper
An AI bot based on the Discourse forum system. This project aims to add a personified user to the forum, rather than an AI assistant. Users can interact with this user to obtain AI-generated content.

## Brief Feature Description

- Supports all websites using the Discourse forum system
- Supports any OpenAI API-compatible model
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
2. Choose an OpenAI API-compatible model and obtain an API Key.
3. This project's web search tool relies on [tavily](https://tavily.com). If you want the AI to be able to search the web, you need to register a tavily account and obtain an API Key. This step is optional.

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
  "likes": {
    "heart": "a red heart" // All available reaction names on the forum and their corresponding descriptions.
  }
}
```

Edit `configs/ai.yml`:
```yaml
# Timeout for AI requests in milliseconds
timeout: 120000
# Number of retries for AI service
retry: 3
webSearchKey:
  [
    "your_tavily_api_key" # Optional, leave empty to disable web search functionality
  ]
model:
  url: "https://your.openai.api/endpoint/v1/chat/completions" # Note: full URL is required, including /v1/chat/completions
  key: ["your openai_api_key"] # If multiple keys are provided, one will be randomly selected for each use.
  model: "gpt-4" # Choose the model you want to use
  toolable: true # Whether tool calling is supported. Currently must be true
  imageable: false # Whether visual capabilities are supported. This feature is not currently implemented
  customRequestParms: # Optional, custom request body fields
    temperature: 0.7
    top_p: 0.9
    presence_penalty: 0
    frequency_penalty: 0
```

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
2. Provide the AI with the prompt, memory, and the pending topicID as part of the prompt. Also provide the AI with tools to get the topic, get posts, like, reply, and perform web searches (if a tavily API Key is configured).
3. The AI will read the posts through the tools and interact.
4. After the interaction ends, the AI needs to output new memory content, which the program will save to `data/memory.md`.