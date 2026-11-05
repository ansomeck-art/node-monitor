# Android checks

`cn/nodemon/app` contains JVM checks for address restrictions, GPU sample freshness and alert continuity. These use the SDK `android.jar` for compilation; they do not simulate a full Android device.

`instrumentation` contains device-side integration and visual checks. Run against an already booted Android 8.0+ emulator:

```powershell
.\scripts\test-android.ps1 -JavaHome 'JDK 17 directory' -AndroidSdk 'SDK directory' -Serial emulator-5580
```

The script builds the isolated `cn.nodemon.serverdeck.test` app without the native Tailscale library, plus a same-key instrumentation APK. It refuses to run on a physical device. It temporarily grants widget binding permission to this test package, then revokes it. The test process starts its own loopback-only HTTP fixture using `demo: true` snapshots labelled “演示 / 模拟”; it never connects to a real server or uses production host credentials.

Checks include:

- Encrypted token read and a real `MonitorClient.fetch` through HTTP.
- SQLite sample deduplication, historical queries, cache ordering, alert duration / recovery / offline criteria, missing GPUs, changed thresholds and history gaps.
- Six real app-widget provider registrations, widget host binding and per-widget host selection, dashboard mute / unmute / refresh, and invalid widget ID rejection. Temporary widget host IDs are deleted afterward.
- Current-profile discovery plus a real Launcher3 pin confirmation: the test clicks the system Add button, verifies the success callback's new ID and host binding, and captures the actual home-screen widget. It also withholds confirmation past the timeout to verify that an unconfirmed request never reports success, then accepts the late callback. An unsupported-launcher gallery state is checked separately without claiming actual Xiaomi hardware coverage.
- A real foreground widget service while the Activity is in the background: two wall-clock refresh cycles update an actual registered widget, with a persistent foreground notification. The checks also cover shared polling ownership, foreground coexistence, notification stop, switch stop and stopping after the final widget is removed. Results are recorded in `widget-live-results.json`; this is an emulator test, not an OEM power-management guarantee.
- Native rendering of all six app pages in both themes, eight widget visual states in both themes, and numeric animation frames. Widget screenshots use the actual `RemoteViews.apply` layout and Android drawing, rather than a separate web mockup.
- The full native widget gallery is captured in both themes, including its actual widget previews. The isolated fixture app is reinstalled for each run so launcher-owned widgets from prior test runs cannot affect counts; other applications and launcher data are preserved.

Results are written to `build/qa/android-instrumentation.txt`; PNGs are pulled to `build/qa/qa/`. These images verify layout with simulated data. They do not establish physical phone launcher behavior, lock-screen widget availability, battery consumption, background scheduling reliability, or native Tailscale operation. Those require the corresponding phone and server tests.

`-SkipApplicationBuild` is useful only when the test APK and the application `classes.jar` were built from the same current sources. Rebuild after changing production code or regression assertions.
