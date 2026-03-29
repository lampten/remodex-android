# Project History

## Origin

This repository started as a private Android-focused derivative of the upstream [Remodex](https://github.com/Emanuele-web04/remodex) project by Emanuele Di Pietro.

The upstream project centered on the iPhone client plus the shared bridge and relay stack. This public repository extracts the Android client work and the compatible bridge/relay updates required to use it from source.

## Why This Public Repo Exists

The Android client work was developed privately first, with a larger internal task and validation archive. This public repository is a cleaned export meant for people who want to:

- inspect the Android implementation
- run the stack locally
- self-host the relay
- build on top of the Android client

## What Changed For The Public Export

- the upstream iOS source tree was removed from this repo
- internal task logs and private validation notes were not published verbatim
- public docs were rewritten around Android-first, self-hostable use
- the bridge and relay code stayed in-tree because they are part of the usable source distribution
