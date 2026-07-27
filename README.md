# OneInsight

Local Android network inspector for Android Studio and IntelliJ IDEA, using the same App Inspection agent and ART-hook mechanism as Android Studio's built-in Network Inspector.

```text
OneInsight tool window
  → Android App Inspection transport
  → network-inspector.jar injected into a debuggable app process
  → ART hooks for OkHttp / HttpURLConnection / gRPC
  → request and response events streamed back to OneInsight
```

There is no VPN, HTTP proxy, MITM certificate, or app source dependency.

## Install

```bash
./gradlew :studio-plugin:buildPlugin
```

For an obfuscated release artifact:

```bash
./gradlew :studio-plugin:buildObfuscatedPlugin
```

Then in Android Studio or IntelliJ IDEA with the Android plugin installed:

1. Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. Choose `studio-plugin/build/distributions/OneInsight-<version>-obfuscated.zip`
3. Restart the IDE
4. Open the **OneInsight** tool window

## Capture workflow

1. Run a **debuggable** Android app on an emulator or USB device.
2. Open OneInsight and click **Refresh Processes**.
3. Select the app process and click **Attach**.
4. Use the app. Supported requests appear in OneInsight.

The plugin deploys the Android plugin's bundled
`plugins/android/resources/app-inspection/network-inspector.jar` into the selected process and consumes its protobuf event stream.

## Limits

- The process must be visible to Android App Inspection (normally a debuggable/profileable build).
- The Android App Inspection agent supports OkHttp, HttpURLConnection, and supported gRPC clients.
- Raw sockets and unsupported networking engines are not visible.
- TLS certificate pinning does not interfere because capture happens inside the app after TLS.
- The built-in network agent captures HTTP calls; it does not expose WebSocket message frames.
- This relies on internal Android App Inspection APIs and is pinned to IDE platform build `261.*`.

## Optional SDK mode

The `capture-sdk` / `capture-noop` modules remain available for apps where explicit WebSocket frame capture is required. App Inspection mode is the default IDE plugin path.

## Build / JDK

Uses Android Studio’s bundled JBR (JDK 21) via `org.gradle.java.home` in `gradle.properties`.

The obfuscated build uses ProGuard and requires a full local JDK installation
containing `jmods` (the Android Studio JBR omits them). Mapping files are written
to `studio-plugin/build/obfuscated/` and must be retained privately for decoding
production stack traces.
