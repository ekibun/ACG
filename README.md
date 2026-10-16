This is a Kotlin Multiplatform project targeting Android and Desktop (JVM).

* [/platform](./platform/src) holds the platform layer — the JNI bindings, the WebView contract and
  its two implementations, the player views and the IO primitives.
  - [commonMain](./platform/src/commonMain/kotlin) is platform-independent.
  - [androidMain](./platform/src/androidMain/kotlin) and [jvmMain](./platform/src/jvmMain/kotlin) hold the
    platform-specific implementations of the `expect` declarations in `commonMain`.
* [/shared](./shared/src) holds the shared business layer.
  - [commonMain](./shared/src/commonMain/kotlin) is the only production source set.
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

Working in this repository? Start with [docs/architecture.md](./docs/architecture.md) — the map of how
the modules, the platform layer and the native libraries fit together — then read [AGENTS.md](./AGENTS.md),
the project's binding contract for how to build, test, and what not to touch.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
