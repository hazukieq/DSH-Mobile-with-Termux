# Android runtime choices

This project packages DeepSeek Harness with a Termux userland inside `com.termux`.
It runs native Android/bionic programs; it does not start a proot Linux distribution.
Additional tools come from Termux `pkg`/`apt`; there is no one-tap environment extension store.
The engine layer is built and validated locally; this repository contains no CI workflows.

Manual Termux installs, community bootstrap scripts, and proot distributions are alternative delivery
approaches. Their current compatibility, setup cost and package sizes are not maintained here.
This document makes no benchmark or compatibility claims about other projects.

For this implementation, see [terminal and runtime](TERMINAL_AND_LINUX.en.md),
[engine build](ENGINE-BUILD.md), [accessibility scope](A11Y-SCOPE.en.md),
and the agent capability inventory in `assets/agents/DSH-MOBILE.md`.
AgentBridge dispatch lives in `bridge/Router.kt`; it is the source of truth for routes.
