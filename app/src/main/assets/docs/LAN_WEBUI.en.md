# LAN WebUI access

> Open the **native dsh WebUI running on your phone** from a desktop browser on the same
> network — the same UI, not a reimplementation.

## 1. Why upstream doesn't support it

The dsh engine listens on `127.0.0.1` only. That is upstream's security design (the source
refuses `0.0.0.0`: "would expose remote code execution to the network"). Even if it bound to all
interfaces, a browser hitting `http://192.168.x.x` would run into four blockers:

| # | Blocker | Effect |
|---|---|---|
| ① | `crypto.randomUUID` is secure-context only | page init throws |
| ② | front-end `isLoopbackHostname(location.hostname)` is false | connection treated as non-loopback; settings persistence degrades |
| ③ | `/api` Origin / Host same-origin checks | requests rejected |
| ④ | WebSocket upgrades must be proxied | live conversation stream never arrives |

## 2. Solution: an app-level reverse proxy (port 3090)

The app ships a node reverse proxy (`assets/tools/lan-web-proxy.mjs`) that listens on its own
port **3090**, forwards to `127.0.0.1:3080`, and patches responses **in memory**:

- injects a `crypto.randomUUID` polyfill;
- rewrites the `isLoopbackHostname(...)` call site to a truthy value;
- normalizes upstream `Host` / `Origin` to `127.0.0.1:3080`;
- forwards WebSocket upgrades (the conversation stream depends on it);
- handles `SameSite=Strict` cookies and chunked encoding.

**Principles**: no engine files on disk are modified; patches live only in proxy responses; a
proxy failure never propagates into the engine.

## 3. How to use

1. Put the phone and the computer on the same network (same Wi-Fi, or connect the computer to
   the phone's hotspot);
2. On the phone: **Settings → Extensions → LAN WebUI access**, read the notice, confirm;
3. A dialog shows:
   - a **6-digit code** (large text, tap to copy; regenerated every time you enable it);
   - the address `http://<phone-IP>:3090` (also tap-to-copy);
4. Open that address in a desktop browser and enter the code.

> 🔐 The code is shown **once**, when you enable it (it is not kept in the settings row
> afterwards). Forget it and just toggle off/on to generate a new one.

## 4. Persistent reminder & turning it off

Once enabled, the proxy keeps listening — easy to forget. So a **persistent notification** appears
in the status bar as soon as the proxy is verified:

- Title: "LAN WebUI is on";
- Text: `http://<phone-IP>:3090 · tap Stop to turn it off`;
- Action: **Stop**.

Three ways to turn it off, all equivalent:

| Entry | Behavior |
|---|---|
| **Stop** in the notification | clears the preference → kills the proxy process → removes the notification |
| the settings toggle | same |
| **Exit** in the engine notification | exit also stops the proxy and removes the notification |

Stopping uses a **same-uid kill**: the app owns the node process it forked, so no Shizuku or root
is required — which means it also works in **Normal** mode (older builds could leave an orphan
process there).

## 5. Security notes

- Only enable on networks you trust; never expose port 3090 to the internet;
- behind the proxy sits an agent with full file and command access — the code is the only gate,
  don't share screenshots of it;
- When done, tap **Stop** or exit the engine (which stops the proxy too);
- The proxy does **not** listen on 3080 (engine WebUI) or 3083 (capability bridge) — only 3090.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Browser can't connect | same subnet? `ping <phone-IP>`; is 3090 listening (the proxy needs ~2 s to start)? |
| "Wrong code" | codes are case-sensitive; toggling off/on creates a new one; clear cached cookies |
| Blank page | browser/WebView must be Chrome 85+; check `$DSH_HOME/lan-proxy.log` |
| Still reachable after turning off | an orphan from an older build; update to the build with same-uid kill |
| No persistent notification | notification permission (Android 13+); the proxy still works without it |

## 7. Related files

| File | Role |
|---|---|
| `app/src/main/assets/tools/lan-web-proxy.mjs` | the node reverse proxy (deployed to `dsh-home/tools/`) |
| `app/src/main/java/app/dsh/mobile/engine/LanWebProxy.kt` | start/stop, auth, persistent notification, process cleanup |
| `app/src/main/java/app/dsh/mobile/LanWebOffReceiver.kt` | broadcast receiver behind the Stop button |
| `app/src/main/java/app/dsh/mobile/LanAccessDialog.kt` | dialog that shows the code and address |
