# DroidOS Neo (Serverless Android MCP)

Control your Android smartphone directly from Gemini (or any MCP client) over Cloudflare Workers — **no PC or root required**.

## How It Works

1. The Android app runs an Accessibility Service (`DroidOSAccessibilityService`) and opens an outbound HTTPS stream (`GET /__device_connect`) to your Cloudflare Worker.
2. The Cloudflare Worker uses a SQLite-backed Durable Object (`DeviceBridge`) to hold the stream in memory and expose a standard MCP endpoint (`POST /mcp`).
3. When Gemini calls a tool (`take_snapshot`, `tap_element`, `input_text`, `scroll_screen`, `open_app`, `press_key`), the Worker pushes the command down the stream and waits for the phone's `POST /__device_respond` callback.

## Setup Guide

### 1. Deploy the Cloudflare Worker (Free Plan)

```bash
cd cloudflare-worker
npx wrangler login
npx wrangler deploy
```

Copy your deployed Worker URL (e.g. `https://droidos-mcp.<your-subdomain>.workers.dev`).

> Note: `wrangler.toml` uses `new_sqlite_classes = ["DeviceBridge"]`, which is supported on the Cloudflare Workers Free plan.

### 2. Build & Run the Android App

1. Open the project in Android Studio (or run `./gradlew assembleDebug`) and install the APK on your phone.
2. Open **DroidOS Neo** on your phone:
   - Grant **Accessibility Service** permission when prompted.
   - Enter your deployed Cloudflare Worker URL (e.g. `https://droidos-mcp.<your-subdomain>.workers.dev`) in the Worker URL field.
   - Toggle **Direct Cloudflare Mode** ON. State and URL are automatically saved.

### 3. Connect to Gemini

1. Open **Gemini Settings → Integrations / MCPs → Connect to an MCP server**.
2. Enter `https://<your-worker>.workers.dev/mcp`.
3. Start a new chat and ask Gemini to check `phone_connection_status` or `take_snapshot`.
