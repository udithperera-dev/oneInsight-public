# OneInsight

Inspect Android app network traffic directly inside Android Studio or IntelliJ IDEA.

OneInsight uses Android App Inspection and in-process ART hooks—the same underlying mechanism used by Android Studio's Network Inspector. It does not require a VPN, HTTP proxy, MITM certificate, or changes to your app's source code.

## Features

- Inspect requests and responses from debuggable Android apps
- Capture supported OkHttp, HttpURLConnection, and gRPC traffic
- View traffic without installing certificates or changing network security settings
- Work with TLS certificate pinning because capture happens inside the app process
- Run in Android Studio or IntelliJ IDEA with the Android plugin installed

## Getting started

After installing OneInsight, restart the IDE and open an Android project. No SDK dependency or application code changes are required.

1. Run a **debuggable** Android app on an emulator or USB-connected device.
2. Open **View → Tool Windows → OneInsight**.
3. Click **Refresh Processes** to find apps available through Android App Inspection.
4. Select your app process and click **Attach**.
5. Use the app normally. Supported network requests and responses will appear in the OneInsight tool window.

If your app does not appear, confirm that the device is connected, the app is debuggable or profileable, and Android Studio can see the process through App Inspection.

## Build

```bash
./gradlew :studio-plugin:buildPlugin
```

Install from disk in Android Studio / IntelliJ IDEA (Android plugin required):

1. Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. Choose `studio-plugin/build/distributions/OneInsight-<version>.zip`
3. Restart the IDE
4. Open the **OneInsight** tool window

## Requirements

- Android Studio or IntelliJ IDEA with the Android plugin installed
- IDE platform build `261.*`
- A debuggable or profileable Android app visible to Android App Inspection
- An emulator or USB-connected Android device

## Limitations

- Raw sockets and unsupported networking engines are not captured.
- WebSocket message frames are not exposed by the built-in Android network inspection agent.
- Availability depends on Android App Inspection support for the selected app process and networking library.

## Optional SDK mode

The `capture-sdk` / `capture-noop` modules remain available for apps where explicit WebSocket frame capture is required. App Inspection mode is the default IDE plugin path.

## Privacy

Captured traffic is inspected locally in your IDE. OneInsight does not require a proxy or send app traffic through an external service.

## Support

- Developer: Udith Perera
- Website: [akurupela.com](https://akurupela.com)
- GitHub: [@udihperera-dev](https://github.com/udihperera-dev)
