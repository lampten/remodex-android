# Contributing to Remodex Android

This is a personal project, published mainly so other people can use it or fork it.

I will probably not maintain it much, and I will probably not accept most PRs. If you want to move quickly or change direction, fork it.

If you still open a PR:

- keep it small
- explain the problem clearly
- include screenshots if UI changes
- explain compatibility impact if bridge or relay behavior changes

Useful checks:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
cd phodex-bridge && npm test
cd ../relay && npm test
```
