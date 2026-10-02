# GameSpace

chaldeaprjkt's GameSpace is an alternative to the Google's proprietary implementation of the Game Dashboard with a goals of providing basic user-interface for the [Android Game Mode API](https://developer.android.com/games/gamemode/gamemode-api).

This fork builds GameSpace as a **regular, user-installed APK** for stock LineageOS 23 (Android 16). Instead of ROM patches, it ships as an **Xposed module** (legacy API 82, tested target: Vector/LSPosed) and uses **root** for a few shell-only operations.

## Requirements

- LineageOS 23.x (Android 16)
- Root (Magisk)
- An Xposed framework implementing API 82 (Vector / LSPosed)

## Install

1. Build: `./gradlew :app:assembleDebug` (JDK 17–21). The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. In your Xposed manager, enable **Game Space** with the scopes **System Framework** and **System UI**, then reboot.
4. Open Game Space. Work through the **Setup** section: allow root, overlay permission, notification access (danmaku), DND access, and the call/contacts permissions. The section disappears once everything is ready.
5. Add games. Launching a listed game shows the game bar.

## How it fits together

| Formerly (ROM patch) | Now |
|---|---|
| `GameSpaceService` / `GameStateDispatcher` (system_server) start and stop `SessionService` | `xposed/system/SessionDispatcher`, which hooks app focus, task removal and keyguard |
| `GamePackageHandler` auto-adds installed games | `xposed/system/SystemServerHooks` package receiver |
| `DisplayPolicy` gaming gesture lock | Hook on `DisplayPolicy#requestTransientBars` + SystemUI `EdgeBackGestureHandler#isWithinInsets` |
| Platform signature for game mode, FPS counter, brightness, settings writes | `ISystemBridge` binder served from system_server (caller uid checked) |
| AxionOS `AxPlatformService` for gamebar tiles | `IPlatformBridge` served from SystemUI, which drives SystemUI's own QS tiles |
| Game list in `Settings.System` | App storage, served to the hooks through `GameSpaceProvider` |

Without the hooks the app still opens, but sessions don't start automatically and privileged features fall back to root (`settings`, `cmd game`, `device_config`) or turn themselves off.

Not ported: the key mapper (`BuildFlags.MAPPER_ENABLED`), plus the AxionOS-only touch boost, pulse bass haptics and bypass charging.

## License

This work is licensed under [Apache 2.0 License](LICENSE.md).
