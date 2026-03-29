# Remodex Android

Android client for [Remodex](https://github.com/Emanuele-web04/remodex), with compatible bridge and relay updates for a local-first, self-hostable setup.

This project is derived from the upstream Remodex work by Emanuele Di Pietro. The public repository here focuses on the Android client plus the bridge and relay pieces needed to use it from source. It does not include the upstream iOS source tree, but it is intended to stay compatible with the upstream iOS app and protocol where that behavior is documented and preserved in the shared bridge/relay code.

![Remodex Android chat](assets/remodex-android-chat.jpg)

## Current Status

Remodex Android is an early but usable self-hostable Android client. The current public snapshot supports:

- QR pairing from Android
- trusted reconnect
- thread list and thread detail
- text send
- stop / continue controls
- structured timeline rendering
- image send and basic history image handling
- local-first self-hosting with the included bridge and relay code

This repo is maintained on a best-effort basis. Expect rough edges, and expect the public roadmap to move slower than the private build that produced it.

## What This Repo Includes

- `app/`: the Android client
- `phodex-bridge/`: the local bridge that runs next to Codex on your Mac
- `relay/`: the public relay process used for pairing and trusted reconnect
- `Docs/`: architecture, self-hosting, project history, and durable decisions

What this repo does not include:

- the upstream iOS source tree
- any hosted production relay default
- any private build defaults, credentials, or deployment secrets

## Relationship To Upstream Remodex

- Upstream project: [Emanuele-web04/remodex](https://github.com/Emanuele-web04/remodex)
- Upstream focus: iPhone client plus shared bridge/relay stack
- This repo's focus: Android client plus the compatible bridge/relay changes required to use it

If you want the upstream iOS app or the original repository history, use the upstream project directly.

## Self-Hosting Model

This repo is meant to be self-hostable and local-first:

- Codex still runs on your own Mac
- the bridge still runs on your own Mac
- the relay is only a transport layer
- the Android app pairs once with a QR code, then reuses trusted reconnect

You should assume that you will run one of these setups:

1. local testing with `./run-local-remodex.sh`
2. your own relay endpoint passed through `REMODEX_RELAY`

Start with:

```sh
git clone https://github.com/your-org/remodex-android.git
cd remodex-android
./run-local-remodex.sh
```

Then build and install the Android app from `app/`.

For the full setup guide, read [Docs/self-hosting.md](Docs/self-hosting.md) and [SELF_HOSTING_MODEL.md](SELF_HOSTING_MODEL.md).

## Screenshots

![Onboarding](assets/remodex-android-onboard.jpg)
![Foldable layout](assets/remodex-android-fold.jpg)
![Settings](assets/remodex-android-settings.jpg)

## Development Notes

- Android stack: Kotlin + Jetpack Compose + Material 3
- Bridge package/CLI name remains `remodex` in this repo for compatibility
- The included bridge and relay code are part of the Android source distribution because they are needed for self-hostable use

## Maintenance

This repository is lightly maintained.

- bug reports are fine
- small documentation fixes are welcome
- small focused fixes may be considered
- large feature PRs, rewrites, or scope expansion are unlikely to be accepted

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening anything non-trivial.

## License

This repository uses the [ISC License](LICENSE).

Because this work is derived in part from the upstream Remodex project, keep the upstream attribution and license notice intact when redistributing modified versions.
