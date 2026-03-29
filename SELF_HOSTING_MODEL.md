# Public Repo And Self-Hosting

This repository is the self-hostable Android-focused source distribution of Remodex Android.

It is designed so you can inspect the transport, build the Android app, run the bridge yourself, and point everything at a relay you control.

## What The Public Repo Includes

- the Android app source
- the bridge that runs next to Codex on your Mac
- the relay code
- self-hosting documentation

## What The Public Repo Does Not Include

- a private hosted relay URL
- deployment secrets
- private build defaults
- the upstream iOS source tree

If you clone this repo, assume you are bringing your own infrastructure choices.

## Intended Usage Model

- Codex runs on your Mac
- the bridge runs on your Mac
- the Android app is the paired mobile client
- the relay only forwards pairing, trusted reconnect, and encrypted transport traffic

The first QR scan bootstraps trust. Later reconnects should reuse that trust over the same relay path.

## Supported Setup Paths

1. local testing with `./run-local-remodex.sh`
2. a self-hosted relay passed through `REMODEX_RELAY`

For step-by-step setup, read [Docs/self-hosting.md](Docs/self-hosting.md).

## Why The Repo Stays Generic

The source tree stays generic on purpose:

- no private hosted dependency is baked in
- the bridge and relay can be inspected directly
- users can run the project locally
- users can self-host their own relay

## What To Keep Private

If you fork or deploy this project yourself, keep these things out of your public repo:

- deployed hostnames
- VPS IP addresses
- credentials
- private build overrides
- private environment defaults

## Short Version

This repo should be treated as the self-hostable Android version of the Remodex stack:

- build the Android app yourself
- run the bridge yourself
- run your own relay or use the local launcher
- pair once with QR
- reuse trusted reconnect after that
