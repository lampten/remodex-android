# Self-Hosting Remodex Android

This guide explains how to run Remodex Android with infrastructure you control.

It supports two main flows:

1. local testing with the included launcher
2. a self-hosted relay that the bridge connects to explicitly

## What Self-Hosting Means Here

- Codex runs on your Mac
- the bridge runs on your Mac
- the Android app is the paired client
- the relay is only a transport layer

After the secure pairing handshake completes, the relay should not see plaintext application content.

## Option 1: Local Testing

This is the easiest way to get started.

### What You Need

- a Mac with Codex CLI installed
- Android Studio or another way to build/install the Android app
- an Android device on the same reachable network path

### Start The Local Stack

```sh
git clone https://github.com/your-org/remodex-android.git
cd remodex-android
./run-local-remodex.sh
```

This starts:

- a local relay
- the bridge
- a terminal QR code for pairing

Then:

1. Build and install the Android app
2. Open the app
3. Scan the QR code from inside the app
4. Start a thread and send a message

### If The Device Cannot Reach The Default Hostname

Pass a hostname that your Android device can actually reach:

```sh
./run-local-remodex.sh --hostname your-mac.local
```

### Health Check

```sh
curl http://127.0.0.1:9000/health
```

Expected response:

```json
{"ok":true}
```

## Option 2: Self-Hosted Relay

Use this when you want the bridge to connect through a relay you run yourself.

### What Runs Where

On your relay host:

- the relay

On your Mac:

- the bridge
- Codex CLI / app-server

On your Android device:

- the Remodex Android app

### Start The Relay

```sh
git clone https://github.com/your-org/remodex-android.git
cd remodex-android/relay
npm install
npm start
```

### Verify The Relay

```sh
curl http://127.0.0.1:9000/health
```

### Put A Reverse Proxy In Front

Expose the relay through your own `wss://` endpoint and forward `/relay/...` traffic to the Node relay process.

### Point The Bridge At Your Relay

Installed bridge:

```sh
REMODEX_RELAY="wss://relay.example.com/relay" remodex up
```

From source:

```sh
cd phodex-bridge
npm install
REMODEX_RELAY="wss://relay.example.com/relay" npm start
```

Then pair from the Android app with the QR code.

## Important Notes

- There is no built-in hosted relay default in this public repo
- keep your real deployment hostnames and credentials out of Git
- the bridge package/CLI name remains `remodex`
- macOS remains the primary host-side background-service target

## Troubleshooting

If pairing fails:

- check that the relay is reachable from the Android device
- check that WebSocket upgrades are forwarded correctly
- check that the bridge is using the expected `REMODEX_RELAY`
- use `wss://` for internet-facing deployments
