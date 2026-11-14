/**
 * cloudshell-mcp-worker.js
 * True Serverless Dual-Power MCP Gateway (Direct Cloudflare Edge + Optional PC Tunnel)
 *
 * Capabilities:
 * 1. ZERO-PC DIRECT PHONE MODE (via Durable Object `DeviceBridge`):
 *    - Phone connects to `/__device_connect` over HTTPS stream from anywhere (Wi-Fi/4G/5G).
 *    - Cloudflare Edge holds the stream in-memory across the world.
 *    - Phone POSTs responses to `/__device_respond?id=<id>`.
 *    - Agents / Gemini talk to `/mcp` directly without needing any PC or local bridge!
 * 2. PARAMETER NORMALIZATION:
 *    - `open_app`: maps friendly names ("whatsapp", "youtube", etc.) to package names.
 *    - `tap_element`: normalizes `ref` strings into integer `index` or `x, y` coordinates.
 *    - `input_text`: maps `value` to `text`.
 * 3. OPTIONAL PC BRIDGE FALLBACK:
 *    - If PC bridge is online via `TUNNEL_STORE`, merges BrowserOS Neo desktop tools.
 */

const APP_PACKAGE_MAP = {
  "whatsapp": "com.whatsapp",
  "youtube": "com.google.android.youtube",
  "chrome": "com.android.chrome",
  "gmail": "com.google.android.gm",
  "settings": "com.android.settings",
  "messages": "com.google.android.apps.messaging",
  "camera": "com.google.android.GoogleCamera",
  "maps": "com.google.android.apps.maps",
  "photos": "com.google.android.apps.photos",
  "telegram": "org.telegram.messenger",
  "instagram": "com.instagram.android"
};

const DROID_TOOLS = [
  {
    name: "phone_connection_status",
    description: "Check if the Android phone (DroidOS Neo) is connected directly via Cloudflare Edge or local network.",
    inputSchema: { type: "object", properties: {} }
  },
  {
    name: "take_snapshot",
    description: "[Phone] Capture an accessibility snapshot of the current Android screen with indexed UI elements.",
    inputSchema: { type: "object", properties: {} }
  },
  {
    name: "tap_element",
    description: "[Phone] Tap a UI element on the Android screen by reference handle ('35', 'e35'), element index, or (x, y) coordinates.",
    inputSchema: {
      type: "object",
      properties: {
        index: { type: "integer", description: "Element index from snapshot" },
        ref: { type: "string", description: "Element reference or index (e.g. '35', 'e35', '540,1200')" },
        x: { type: "integer", description: "X coordinate" },
        y: { type: "integer", description: "Y coordinate" }
      }
    }
  },
  {
    name: "input_text",
    description: "[Phone] Enter text into the currently focused or targeted input field on Android.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "The text to type" },
        value: { type: "string", description: "Alternative key for text" },
        index: { type: "integer", description: "Element index of input field" },
        ref: { type: "string", description: "Element reference or index" }
      },
      required: []
    }
  },
  {
    name: "scroll_screen",
    description: "[Phone] Scroll the Android screen.",
    inputSchema: {
      type: "object",
      properties: {
        direction: { type: "string", enum: ["down", "up", "left", "right"], default: "down" }
      },
      required: ["direction"]
    }
  },
  {
    name: "open_app",
    description: "[Phone] Launch an Android application by friendly name (e.g. 'whatsapp', 'youtube') or package name.",
    inputSchema: {
      type: "object",
      properties: {
        app_name: { type: "string", description: "Friendly app name or package name" },
        package_name: { type: "string", description: "Android package name" }
      }
    }
  },
  {
    name: "press_key",
    description: "[Phone] Trigger standard Android hardware navigation keys ('back', 'home', 'recents').",
    inputSchema: {
      type: "object",
      properties: {
        key: { type: "string", enum: ["back", "home", "recents"], description: "Key name" }
      },
      required: ["key"]
    }
  },
  {
    name: "run_macro",
    description: "[Phone] Execute a list of sequential actions in a single batch on the Android device for ultra-fast performance.",
    inputSchema: {
      type: "object",
      properties: {
        actions: {
          type: "array",
          description: "List of action objects with 'action' and parameters",
          items: { type: "object" }
        }
      },
      required: ["actions"]
    }
  }
];

function normalizeDroidParams(name, args = {}) {
  const norm = { ...args };
  if (name === "open_app") {
    let pkg = norm.package_name || norm.app_name || norm.package || norm.name || "";
    const lower = pkg.toLowerCase().trim();
    if (APP_PACKAGE_MAP[lower]) pkg = APP_PACKAGE_MAP[lower];
    norm.package_name = pkg;
    norm.app_name = pkg;
  } else if (name === "tap_element") {
    const rawRef = norm.ref !== undefined ? String(norm.ref) : (norm.index !== undefined ? String(norm.index) : "");
    if (rawRef.includes(",")) {
      const parts = rawRef.split(",");
      const px = parseInt(parts[0].trim(), 10);
      const py = parseInt(parts[1].trim(), 10);
      if (!isNaN(px) && !isNaN(py)) { norm.x = px; norm.y = py; }
    } else {
      const cleanRef = rawRef.replace(/[\[\]e#]/gi, "").trim();
      const idx = parseInt(cleanRef, 10);
      if (!isNaN(idx)) { norm.index = idx; norm.ref = String(idx); }
    }
  } else if (name === "input_text") {
    if (!norm.text && norm.value) norm.text = norm.value;
    if (norm.ref && norm.index === undefined) {
      const cleanRef = String(norm.ref).replace(/[\[\]e#]/gi, "").trim();
      const idx = parseInt(cleanRef, 10);
      if (!isNaN(idx)) norm.index = idx;
    }
  } else if (name === "press_key") {
    if (norm.key) norm.key = norm.key.toLowerCase().trim();
  }
  return norm;
}

export class DeviceBridge {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.pending = new Map();
    this.deviceWriter = null;
    this.deviceConnectedAt = null;
    this.nextId = 1;
  }

  corsHeaders() {
    return {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS, HEAD",
      "Access-Control-Allow-Headers": "*",
      "Access-Control-Expose-Headers": "*"
    };
  }

  jsonResponse(status, body) {
    return new Response(JSON.stringify(body), {
      status,
      headers: {
        "Content-Type": "application/json; charset=UTF-8",
        ...this.corsHeaders()
      }
    });
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: this.corsHeaders() });
    }

    // 1. Phone Persistent Streaming Endpoint
    if (url.pathname === "/__device_connect") {
      if (request.method !== "GET") return new Response("Use GET", { status: 405 });

      if (this.deviceWriter) {
        try { await this.deviceWriter.close(); } catch (_) {}
        this.deviceWriter = null;
      }

      const { readable, writable } = new TransformStream();
      this.deviceWriter = writable.getWriter();
      this.deviceConnectedAt = new Date().toISOString();

      return new Response(readable, {
        status: 200,
        headers: {
          "Content-Type": "application/x-ndjson; charset=utf-8",
          "Cache-Control": "no-cache, no-store, no-transform",
          "Transfer-Encoding": "chunked",
          "X-Accel-Buffering": "no",
          "Connection": "keep-alive",
          ...this.corsHeaders()
        }
      });
    }

    // 2. Phone Response Submission Endpoint
    if (url.pathname === "/__device_respond") {
      if (request.method !== "POST") return new Response("Use POST", { status: 405 });
      const id = url.searchParams.get("id");
      const body = await request.text();

      const pendingReq = this.pending.get(id);
      if (pendingReq) {
        clearTimeout(pendingReq.timer);
        this.pending.delete(id);
        pendingReq.resolve(body);
      }
      return new Response("OK", { status: 200, headers: this.corsHeaders() });
    }

    // 3. Status Endpoint
    if (url.pathname === "/__status" || url.pathname === "/status") {
      let pcTunnel = null;
      if (this.env.TUNNEL_STORE) {
        try { pcTunnel = await this.env.TUNNEL_STORE.get("TUNNEL_URL"); } catch (_) {}
      }

      return this.jsonResponse(200, {
        status: "ready",
        server: "DualPower.CloudServerless",
        directPhoneConnected: !!this.deviceWriter,
        phoneConnectedAt: this.deviceConnectedAt,
        pcBridgeTunnel: pcTunnel || "OFFLINE (Not Needed for Direct Phone Mode)",
        pendingRequests: this.pending.size,
        timestamp: new Date().toISOString()
      });
    }

    // 4. Main MCP Protocol Endpoint (/mcp or /)
    if (url.pathname === "/mcp" || url.pathname === "/") {
      if (request.method === "GET" || request.method === "HEAD") {
        return this.jsonResponse(200, {
          status: "ready",
          server: "DualPower.CloudServerless",
          version: "1.0.0",
          protocolVersion: "2024-11-05"
        });
      }

      if (request.method !== "POST") return new Response("Use POST", { status: 405 });

      let body;
      try {
        body = await request.json();
      } catch (err) {
        return this.jsonResponse(400, {
          jsonrpc: "2.0",
          id: null,
          error: { code: -32700, message: "Parse error: Invalid JSON" }
        });
      }

      const rpcId = body.id !== undefined ? body.id : 1;
      const method = body.method || "";
      const params = body.params || {};

      // Handle MCP Lifecycle Directly on Edge
      if (method === "initialize") {
        return this.jsonResponse(200, {
          jsonrpc: "2.0",
          id: rpcId,
          result: {
            protocolVersion: "2024-11-05",
            capabilities: {
              prompts: {},
              resources: {},
              tools: { listChanged: true }
            },
            serverInfo: {
              name: "DualPower.Hub",
              title: "DualPower Hub (Cloud Serverless)",
              version: "2.0.0"
            },
            instructions: "Direct Cloudflare Edge Bridge: Android phone (DroidOS Neo) control with Zero PC required."
          }
        });
      }

      if (method === "notifications/initialized") {
        return new Response(null, { status: 202, headers: this.corsHeaders() });
      }

      if (method === "tools/list") {
        let tools = [...DROID_TOOLS];
        // If PC tunnel is registered in KV, optionally fetch and append desktop BrowserOS tools
        if (this.env.TUNNEL_STORE) {
          try {
            const pcTunnel = await this.env.TUNNEL_STORE.get("TUNNEL_URL");
            if (pcTunnel) {
              const bResp = await fetch(`${pcTunnel}/mcp`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ jsonrpc: "2.0", id: "probe-b", method: "tools/list", params: {} })
              });
              if (bResp.ok) {
                const bData = await bResp.json();
                const bTools = (bData.result && bData.result.tools) || [];
                const bOnly = bTools.filter(t => !tools.some(dt => dt.name === t.name));
                tools.push(...bOnly);
              }
            }
          } catch (_) {}
        }

        return this.jsonResponse(200, {
          jsonrpc: "2.0",
          id: rpcId,
          result: { tools }
        });
      }

      if (method === "tools/call") {
        const toolName = params.name || "";
        const toolArgs = params.arguments || {};

        // Status tool
        if (toolName === "phone_connection_status") {
          const isDirect = !!this.deviceWriter;
          let msg = "";
          if (isDirect) {
            msg = "DroidOS Neo is ONLINE and directly connected to Cloudflare Edge (True Serverless Mode — Zero PC Needed).";
          } else {
            msg = "DroidOS Neo is OFFLINE. Please open DroidOS Neo on your phone and ensure the 'Direct Cloudflare Mode' toggle is ON.";
          }
          return this.jsonResponse(200, {
            jsonrpc: "2.0",
            id: rpcId,
            result: {
              content: [{ type: "text", text: msg }],
              isError: !isDirect
            }
          });
        }

        // Phone tools
        const isDroidTool = DROID_TOOLS.some(t => t.name === toolName);
        if (isDroidTool) {
          if (!this.deviceWriter) {
            return this.jsonResponse(200, {
              jsonrpc: "2.0",
              id: rpcId,
              result: {
                content: [{
                  type: "text",
                  text: "DroidOS Neo is currently disconnected from Cloudflare. Open DroidOS Neo on your phone and toggle Direct Cloudflare Mode ON."
                }],
                isError: true
              }
            });
          }

          const normArgs = normalizeDroidParams(toolName, toolArgs);
          const reqId = String(this.nextId++);
          const phonePayload = {
            jsonrpc: "2.0",
            id: reqId,
            method: "tools/call",
            params: {
              name: toolName,
              arguments: normArgs
            }
          };

          try {
            await this.deviceWriter.write(
              new TextEncoder().encode(JSON.stringify(phonePayload) + "\n")
            );
          } catch (e) {
            this.deviceWriter = null;
            return this.jsonResponse(200, {
              jsonrpc: "2.0",
              id: rpcId,
              result: {
                content: [{ type: "text", text: `Connection to phone lost: ${e.message}. Reconnecting...` }],
                isError: true
              }
            });
          }

          // Await device response (up to 30 seconds)
          try {
            const rawResponse = await new Promise((resolve, reject) => {
              const timer = setTimeout(() => {
                this.pending.delete(reqId);
                reject(new Error("Phone response timed out after 30 seconds."));
              }, 30000);
              this.pending.set(reqId, { resolve, reject, timer });
            });

            const parsedRes = JSON.parse(rawResponse);
            return this.jsonResponse(200, {
              jsonrpc: "2.0",
              id: rpcId,
              result: parsedRes.result || { content: [{ type: "text", text: rawResponse }] },
              error: parsedRes.error
            });
          } catch (err) {
            return this.jsonResponse(200, {
              jsonrpc: "2.0",
              id: rpcId,
              result: {
                content: [{ type: "text", text: `Error from phone: ${err.message}` }],
                isError: true
              }
            });
          }
        }

        // Desktop browser tool fallback (if PC tunnel active)
        if (this.env.TUNNEL_STORE) {
          try {
            const pcTunnel = await this.env.TUNNEL_STORE.get("TUNNEL_URL");
            if (pcTunnel) {
              const fwd = await fetch(`${pcTunnel}/mcp`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
              });
              const fwdData = await fwd.json();
              return this.jsonResponse(200, fwdData);
            }
          } catch (_) {}
        }

        return this.jsonResponse(200, {
          jsonrpc: "2.0",
          id: rpcId,
          result: {
            content: [{ type: "text", text: `Unknown tool: ${toolName}. For desktop tools, start local bridge on PC.` }],
            isError: true
          }
        });
      }

      return this.jsonResponse(200, {
        jsonrpc: "2.0",
        id: rpcId,
        error: { code: -32601, message: `Method not found: ${method}` }
      });
    }

    return new Response("Not found", { status: 404, headers: this.corsHeaders() });
  }
}

export default {
  async fetch(request, env) {
    const id = env.DEVICE_BRIDGE.idFromName("singleton");
    const obj = env.DEVICE_BRIDGE.get(id);
    return obj.fetch(request);
  }
};
