# App case: Bilibili (哔哩哔哩) player page — automation notes

- App: `tv.danmaku.bili` 9.17.0 (`versionCode` 9170300)
- Device used for calibration: vivo V2520A, 1080×2376 @480dpi, Android 17
- Written after the 2026-11-07 session that took 40+ steps for a 5-step task
  (`~/Downloads/qq/issue-bili-player-automation-2026-11-07.md`). Everything below is measured, not
  guessed; unverified items are marked 【推测】.

## 1. The core problem: the player UI is self-drawn

| Fact | Evidence | Status |
|---|---|---|
| Player controls are **not in the a11y tree** (`scr dump` returns 0 player nodes; 51–60 nodes are page/recommend cards only) | repeated dumps | 【已证实】 |
| The control bar **auto-hides** (visible window ≈ 2–4s) | capture only succeeds when taken right after a tap | 【已证实】 |
| The docked/mini player state does expose real nodes (`返回 / 首页 / 继续播放 / 更多操作`) | dump in docked state | 【已证实】 |
| The play-settings entry is the **⋮** at the player's top-right; the bottom 分享 button opens the share sheet instead | clicked both, compared panels | 【已证实】 |
| Seek bar geometry: `y ≈ 652`, track `x ≈ 176–510`; `x < 176` is inert; tapping jumps to that position | x=185→~0:05, x=500→~3:05, x=178 flaky | 【已证实】 |
| Position can be cross-checked objectively via `api.bilibili.com/x/v2/history` → `progress` (seconds) | measured `progress=57`, `duration=196` | 【已证实】 |

## 2. Correct chain (5 steps, not 40)

Everything goes through **one** `/act` transaction; the screenshot for the transient control bar is a
`{"shot":true}` step inside the same transaction (v2.9.93+).

### 2.1 Capture the control bar

```bash
curl -s -X POST -H "X-DSH-Token: $TOK" -H 'Content-Type: application/json' \
  -d '{"steps":[{"tapXY":[540,420]},{"sleepMs":450},{"shot":true}]}' \
  http://127.0.0.1:3083/act
# -> steps[2].shot = /data/user/0/com.termux/files/screens/step-N.png
```

Do **not** use `scr tap` + `scr shot` as two calls: they miss the window, and the control bar also
needs ~400–500ms to animate in after the tap (measured: 250ms captures were mid-animation).
Latency measured on 2.9.94: `/tap` noShot 30–73ms, `/tap` with receipt 0.5–0.8s, `/act` (tap +
sleep 450 + shot) ~0.55s. The 2.6s `scr tap` quoted in the original issue was pre-2.9.93, when the
receipt screenshot used the legacy display+hide path.

### 2.2 Open play settings (loop / speed / autoplay / timer / background audio / subtitles)

```bash
-d '{"steps":[{"tapXY":[540,420]},{"sleepMs":250},{"tapXY":[1020,198]},{"sleepMs":700},{"shot":true}]}'
```

Panel items: 循环播放 → 单集循环 / 列表循环 / 不循环; 倍速; 自动连播; 定时关闭; 后台听视频; 字幕.
The selected item's a11y flags contain `v` (checked).

### 2.3 Seek to the start / near the end

```bash
-d '{"steps":[{"tapXY":[540,420]},{"sleepMs":250},{"tapXY":[178,652]},{"sleepMs":400},{"shot":true}]}'
```

## 3. Verification

- Visual: the capture in §2.1 contains the timestamp (`01:30` etc.) — judge by OCR/known pixels in the
  control-bar band, not by "looks unchanged".
- Objective: `GET https://api.bilibili.com/x/v2/history?pn=1&ps=10` with the logged-in cookies
  (`$DSH_HOME/local/share/bili/cookies.txt`) → `data[].progress` in seconds.
- Loop check: seek to ~`duration-8s`, wait for natural end; the title must NOT advance to the next
  episode of the collection and playback restarts at `00:0x`.

## 4. Known traps

- `scr find` scrolling on this page can hit the "不感兴趣" menu — avoid scroll-to-find here.
- Danmaku off ⇒ the danmaku input row disappears from the a11y tree (useful as a state signal).
- The bottom 分享 sheet is not the play-settings panel; do not judge "settings opened" by any panel
  appearing.
- `/ocr` may answer `cached:true` from a previous frame; compare `capturedAt` before using it for a
  timing conclusion.
- The mini/docked bar state is the cheapest place to read geometry, because its buttons are real
  nodes.
- Step screenshots are retained 12 deep (`KEEP_STEP_SHOTS`); a 5-shot burst keeps all five, so OCR
  each path afterwards instead of assuming the files are gone.

## 5. Evidence from the session

- Play settings with 单集循环 selected: `screens/shot-1794050779082.png`
- Control bar captured (paused 01:30): `screens/shot-1794051369234.png`
- Control bar captured (playing 01:40): `screens/shot-1794051402068.png`
- Missed captures (old `scr tap` + `scr shot` timing): `screens/shot-1794051340020.png`,
  `screens/shot-1794051379849.png`
