# ADR-001: Android App Stack And Module Layout

- status: accepted
- date: 2026-03-26

## Context

The Android client needed a real native baseline before pairing, reconnect, and conversation features could become usable.

## Decision

- Use Kotlin, Jetpack Compose, and Material 3 for the Android client
- Use Gradle Kotlin DSL and a version catalog
- Start with a single `:app` module
- Keep the internal source layout feature-oriented:
  - `app/`
  - `feature/pairing`
  - `feature/threads`
  - `feature/settings`
  - `feature/runtime`
  - `model/`
  - `ui/`
- Use `dev.remodex.android` as the initial namespace and application id

## Consequences

- The Android client can move quickly without speculative module splits
- Refactoring can happen later if real seams emerge
- Local verification depends on JDK 17 and Android SDK tooling
