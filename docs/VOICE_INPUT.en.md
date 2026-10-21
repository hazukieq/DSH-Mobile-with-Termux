# Voice input

> Tap the microphone on the floating ball, speak a sentence, edit the recognized text if needed,
> and send it to the current conversation with one tap — even while the app is in the background.
>
> Fork note (v1.6.x): the overlay microphone was removed from this fork (the recording panel is
> owned by the accessibility service and was coupled to the overlay window lifecycle). The voice
> panel and ASR pipeline remain in the code base; the overlay simply has no mic entry right now.

## 1. Entry point and form

- **Entry**: the mic button on the floating status ball, owned by the accessibility service — it
  does not depend on the main activity being alive.
- **Panel**: an editable input panel that slides up from the bottom
  (`TYPE_ACCESSIBILITY_OVERLAY`, hosted by the accessibility service).
- **Interaction**: recognized text streams into the box; tap the box to edit; **Send**, **Retry**,
  **Close**.

## 2. Recognition channels (highest priority first)

| Priority | Channel | Notes |
|---|---|---|
| ① | **System recognizer** | the device's `SpeechRecognizer` (zero-config, usually offline, good quality) |
| ② | **Cloud recognizer** | when no system service exists, an OpenAI-compatible API configured in settings (quasi-streaming: split on pauses, upload segments, append while recording) |

If neither is available the panel says so explicitly instead of "nothing happened".

## 3. Send pipeline (why nothing is duplicated or lost)

Sending goes **through the WebUI's own input box** (reusing its attachment/@-mention logic):

1. write with the native setter and dispatch an `input` event so React's controlled component sees it;
2. if the editor mishandles synthetic paste, read the box back and repair with `execCommand`;
3. submit with the send key;
4. several de-dup guards:
   - the panel's `consumed` guard (one touch triggers once);
   - content + 1.5 s window de-dup (final line of defense);
   - collapsing "the same sentence twice" (IME late-commit fallback).

**Why it works in the background**: the chain is driven entirely by Android-side timing — no
`requestAnimationFrame`, no page-level JS timers — while the main WebView keeps `resumeTimers` so
streamed replies still render.

## 4. Panel lifecycle and "parking"

The panel is a separate accessibility overlay window; adding/removing windows can produce a 1–2
frame compositor artifact on some devices. So:

- **Close / Retry**: animate "slide down + fade out", then remove the window;
- **Send**: the window is **not destroyed** but *parked* — `alpha=0` plus
  `NOT_TOUCHABLE | NOT_FOCUSABLE` flags — and reused by the next session (`unpark` restores it).

This avoids window churn at send time and avoids a translucent panel hovering over a scrolling page.

> ⚠️ Known issue: on some devices a 1–2 frame ghost can still appear at the moment of sending.
> The app already tried four timings (immediate removal / deferred removal / alpha-zero / parking);
> impact is minimized.

## 5. State and floating ball

- Listening: the dot turns red and the status line shows "Listening";
- Recognized but not sent: the listening state is **kept** until you send/close (so the overlay does
  not collapse the instant text appears);
- Panel hidden (any path): stop recognition, release the mic, return to idle;
- Watchdog: while listening, every 1.5 s it checks that the panel still exists; if the system
  removed it, recording stops and the mic is released immediately.

## 6. Permissions and dependencies

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | microphone (requested on first use) |
| Accessibility service | hosts the floating ball and the panel (enable manually in system settings) |
| Notification permission (Android 13+) | optional, for notification features |

The offline recognizer plugin [dsh-asr-service](https://github.com/Soodok/dsh-asr-service) is
optional; cloud recognition is configured with an OpenAI-compatible endpoint and key.

## 7. Troubleshooting

| Symptom | Check |
|---|---|
| Tapping the mic does nothing | mic permission granted? a recognizer service or cloud API configured? |
| Text never reaches the input box | is the main activity alive? (if the app was reclaimed, the overlay says "open the app first") |
| The same sentence sent twice | several de-dup layers exist; if it still happens, share the log (`send tapped` + de-dup result) |
| Mic indicator stays on | hiding the panel releases it; if it persists, reopen voice or restart the engine |
