# Notifications and background behavior

> DSH Mobile posts notifications in three situations: agent task status, the AI operating the
> device for a long sequence, and LAN WebUI being enabled. "Exit" also truly ends the UI and
> releases memory. This document covers where each notification comes from, how often it appears,
> and how to turn it off.

## 1. Notification channels

| Channel | Name | Importance | Purpose |
|---|---|---|---|
| `engine` | Engine status | LOW (persistent) | foreground service: ready/starting; carries the **Exit** action |
| `agent_notify` | Agent notifications | DEFAULT | task-complete pushes; progress for long accessibility sequences |
| `lan_web` | LAN WebUI | LOW | persistent "is on" reminder with a **Stop** action |

Android 13+ requires notification permission. Without it:
- the foreground service still runs (its notification is just invisible);
- the LAN proxy **keeps working** but no reminder appears;
- agent notifications (`/notify`) degrade to the foreground service's status line.

## 2. Long accessibility-sequence notification

**Goal**: while the AI drives the UI (tap / type / swipe / launch), you can confirm it is really
working instead of wondering why the screen keeps changing.

Rules (`AgentBridge.noteA11yOperation`):

- only **action** endpoints count: `/tap`, `/tap-id`, `/gesture`, `/key`, `/input`,
  `/scroll-find`, `/scroll-center`, `/scroll-edge`, `/launch`, `/launch-intent`,
  `/vision/tap`, `/vision/drag`, `/loop/run`; screen reads don't (otherwise every glance counts);
- appears from the **3rd consecutive step**, then **updates the same notification in place**
  (fixed id, `setOnlyAlertOnce` so it doesn't ring repeatedly);
- example body: "Step 7 · tapping";
- 30 s without a new action starts a new sequence; **auto-dismissed 10 s after the last step**;
- `/loop/run` is a whole sequence, so **one call already qualifies** (its inner steps don't traverse
  HTTP, otherwise the threshold would never be reached);
- tapping the notification returns to the app.

## 3. Task-complete notifications

The agent can call the local bridge `POST /notify` (or the `notify` command):
`{"title": "...", "body": "..."}` — used for "done" / "needs your confirmation" style reminders.
Without notification permission it degrades to the foreground service status line rather than
disappearing silently.

## 4. LAN-enabled reminder

See [LAN_WEBUI.en.md](LAN_WEBUI.en.md): once the proxy is verified, a persistent notification with
a **Stop** action appears. Stopping (notification button / settings toggle / engine exit) removes
it in the same breath.

## 5. What "Exit" actually does

**Exit** in the foreground-service notification is an *explicit user exit*, strictly distinguished
from "engine crashed / reclaimed by the system":

1. stop the engine (graceful, up to 3 s);
2. stop **independent child processes**: the LAN proxy (node, port 3090) and the local inference
   service (llama-server);
3. finish the main task (`finishAndRemoveTask`) → `onDestroy` → **destroy the WebView**;
4. tear down the floating overlay, periodic refreshes and timers;
5. remove the foreground-service notification.

Step 3 is the memory win: the WebView plus its renderer process typically hold 300–400 MB. Stopping
only the engine leaves that memory parked in the background.

**No false triggers**: only an explicit exit closes the UI; a passive stop (system reclaim, service
teardown) does not. Reopening the app after an exit shows the normal "Start engine" state — it does
not instantly close.

## 6. Floating-ball status (not a system notification)

The floating ball also shows state: idle / working / listening / done, expandable to the latest
action. It lives in the accessibility overlay, doesn't occupy the notification shade, and never
spams while the AI works.

## 7. FAQ

| Symptom | Cause / fix |
|---|---|
| No notifications at all | check notification permission (Android 13+) and per-channel settings / Do Not Disturb |
| No notification while the AI works | fewer than 3 steps; screen reads don't count; `/loop/run` qualifies immediately |
| Notification never disappears | it auto-dismisses 10 s after the last action; persistent ones (engine/LAN) are meant to stay |
| Processes remain after exit | update to a build with same-uid kill; this build stops the LAN proxy and local inference too |
