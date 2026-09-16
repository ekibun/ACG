This is a Kotlin Multiplatform project targeting Android and Desktop (JVM).

* [/shared](./shared/src) holds everything shared by the two applications.
  - [commonMain](./shared/src/commonMain/kotlin) is platform-independent.
  - [androidMain](./shared/src/androidMain/kotlin) and [jvmMain](./shared/src/jvmMain/kotlin) hold the
    platform-specific implementations of the `expect` declarations in `commonMain`.
* [/androidApp](./androidApp) is the Android application module.
* [/desktopApp](./desktopApp) is the desktop (JVM) application module.
* [/cxx](./cxx) contains the native libraries — a WebView2 host, a QuickJS bridge and FFmpeg.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app: `./gradlew :desktopApp:run`

### Building the native libraries

The desktop build compiles them automatically (`:desktopApp:buildJni`).
For prerequisites and manual rebuilds, see [cxx/AGENTS.md](./cxx/AGENTS.md).

---

Working in this repository? Read [AGENTS.md](./AGENTS.md) first — it is the project's binding contract
for how to build, test, and what not to touch.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
