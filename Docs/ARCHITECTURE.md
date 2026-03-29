# Architecture

## Core Direction

Keep the host-side system mostly intact and provide a native Android client on top of the shared Remodex bridge and relay model.

This repository is Android-focused. It does not vendor the upstream iOS app source tree, but it preserves the shared bridge/relay contract needed to remain compatible with upstream Remodex behavior where documented.

## System Boundary

```text
Android app
  <-> secure transport over relay
relay/
  <-> phodex-bridge/
phodex-bridge/
  <-> Codex runtime on the Mac
```

## Reused Areas

The public Android repo intentionally reuses these shared areas:

- bridge CLI and runtime
- relay pairing and trusted-session resolve flow
- QR payload shape
- secure handshake and encrypted transport contract
- JSON-RPC methods used for thread and turn control

## Android-Specific Areas

The Android client owns:

- app shell and navigation
- local secure storage
- reconnect state handling on Android
- QR scanning and Android permissions
- thread list, timeline, and composer UI

## Android Baseline

- Kotlin + Jetpack Compose + Material 3
- Gradle Kotlin DSL with checked-in wrapper
- single `:app` module
- namespace and application id: `dev.remodex.android`

## Design Constraints

- local-first by default
- self-host friendly
- avoid private hosted assumptions
- preserve compatibility unless a change is explicitly documented
