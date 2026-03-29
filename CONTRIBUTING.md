# Contributing to Remodex Android

This project is published for public use, but it is maintained on a best-effort basis.

I am not treating this as a fast-moving community project. If you open an issue or PR, there is a real chance that I defer it, close it, or simply do not get to it quickly.

## What Is Most Likely To Be Accepted

- small bug fixes
- focused reliability improvements
- documentation fixes
- small setup or self-hosting improvements

## What Is Least Likely To Be Accepted

- large feature PRs
- broad rewrites or refactors
- scope expansion that changes the product direction
- work that moves the project away from local-first, self-hostable use

## Before Opening A PR

- Open an issue first for anything non-trivial.
- Describe the problem clearly before proposing a fix.
- Keep the change narrow.
- If UI behavior changes, include screenshots or a short recording.
- If bridge or relay behavior changes, explain compatibility impact with upstream Remodex.

## Development Expectations

- Keep the Android client, bridge, and relay compatible unless there is a documented reason to break behavior.
- Do not introduce hosted defaults, private relay assumptions, or secret-dependent setup into source control.
- Prefer small, understandable changes over ambitious cleanup passes.

## Local Verification

Before sending a PR, run the narrowest useful checks for the files you changed.

Common checks:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
cd phodex-bridge && npm test
cd ../relay && npm test
```

## Maintenance Reality

Opening an issue or PR does not create an obligation on my side. The main goal of this repo is to make the Android client and compatible local-first stack available to other people, not to promise a high-throughput contribution workflow.
