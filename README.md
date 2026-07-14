# iKofi

**SSH/SFTP server with shell access, rsync, and scp for Android devices.**

iKofi bundles a full SSH server (Dropbear), SFTP server (from OpenSSH), rsync, and scp
into a single Android app.  It runs as a foreground service for persistent background
operation and is controllable via the app UI, Quick Settings tile, or adb intents.

```
┌─────────────────────────────────────────────────────────┐
│ Java/Android Layer                                      │
│ App → MainActivity → MainFragment → SshdService         │
│                      SettingsFragment → SettingsViewModel │
│ ServiceViewModel polls dropbear.err → updates UI         │
├─────────────────────────┬───────────────────────────────┤
│ JNI Bridge (jni-dropbear.c)                              │
│ - forks dropbear_main() as child process                 │
│ - auth hooks (password file, pubkey, single-use)        │
│ - exec mapping: scp→libscp.so, rsync→librsync.so,       │
│   sftp-server→libsftp-server.so                          │
│ - waitpid() watchdog with auto-restart logic            │
├──────────┬───────────────┬──────────────────────────────┤
│ Dropbear │ OpenSSH       │ rsync                        │
│ (sshd)   │ (sftp-server) │                              │
│ (scp)    │               │                              │
├──────────┴───────────────┴──────────────────────────────┤
│ libtomcrypt · libtommath · libcrypto (internal)          │
│ zlib (internal) · popt                                   │
└──────────────────────────────────────────────────────────┘
```

## Features

- **SSH server** — based on [Dropbear 2026.91](https://matt.ucc.asn.au/dropbear/dropbear.html)
- **SFTP** — [OpenSSH 10.3p1](https://github.com/openssh/openssh-portable) sftp-server
- **rsync** — [rsync 3.4.2](https://rsync.samba.org/) (SSH transport only, no daemon mode)
- **scp** — via Dropbear's built-in scp support
- **Authentication methods** (all toggleable):
  - Public key (`~/.ssh/authorized_keys`)
  - Fixed username/password (SHA-512 hashed, stored in encrypted preferences)
  - Single-use auto-generated passwords (8 chars, shown in the log)
- **Shell access** — configurable shell path (default `/system/bin/sh`), can be disabled
- **Foreground service** — persistent background operation with notification
- **Boot autostart** — optional start on device boot
- **Quick Settings tile** — one-tap toggle from the notification shade
- **Intent-based control** — start/stop via `adb shell am start -a com.ikofi.sshd.fg.{START|STOP}`
- **Android/Google TV support** — leanback UI with dedicated button layout
- **Custom env vars** — set environment variables for SSH sessions
- **Port configuration** — default 2222, configurable (1024–32768)
- **Additional Dropbear options** — pass custom command-line flags
- **Theme support** — follow device theme, force light, or force dark
- **Home directory** — defaults to external storage (`/sdcard`), configurable

> **Note**: rsync operates over SSH only — no rsync daemon is started.

## Requirements

- **Android**: 11 (API 30) minimum, tested up to 16 (API 36)
- **Android/Google TV**: Supported on TV emulator 13/14
- **Build host**: JDK 21, Android SDK, NDK 30.0.14904198, CMake 4.1.2

## Quick start

### Install from source

```bash
# Install dependencies
./setup.sh

# Or using make
make setup

# Build debug APK (arm64-v8a)
./gradlew assembleDebug
# or
make

# Install on device
adb install -r app/build/outputs/apk/debug/iKofi-*-debug.apk
# or
make install
```

### After installing

1. Open the app and tap **Start**
2. The server starts on port **2222** by default
3. Connect from another device:
   ```bash
   ssh -p 2222 user@<device-ip>
   ```
4. Single-use passwords appear in the app's log view

### Testing via adb forward

```bash
# Forward host port 2223 → device port 2222
adb forward tcp:2223 tcp:2222

# Connect through the forward
ssh -p 2223 localhost
```

Or use `make adb-forward`.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Network access for SSH connections |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `FOREGROUND_SERVICE` | Persistent background SSH service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required for special-use foreground services |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot (optional) |
| `MANAGE_EXTERNAL_STORAGE` | Full filesystem access for rsync/sftp (must be manually granted) |

**Important**: `MANAGE_EXTERNAL_STORAGE` must be granted manually via
**Settings → Apps → iKofi → App info → Manage all files**.  Without it, SFTP and rsync
are limited to the app's private directory.

## Settings

Access via the **gear icon** in the app.

### Startup
- **Start on device boot** — auto-start after reboot
- **Start on app opening** — auto-start when the app launches
- **Allow start/stop by Intent** — respond to adb or external app intents
- **Run in foreground** — keep the service alive in the background (with notification)
- **Keep running after app exit** — don't stop when swiping the app away

### Connections
- **Port** — listen port (default: 2222, range: 1024–32768)
- **Extra command-line options** — pass additional flags to the Dropbear daemon
- **Environment variables** — set for SSH sessions (e.g., `TERM=xterm-256color`)

### Paths & Auth
- **Shell access** — enable/disable remote shell
- **Home directory** — SSH home path (default: `/sdcard`)
- **Shell** — remote shell binary (default: `/system/bin/sh`)
- **Single-use passwords** — generate random 8-char passwords on each start
- **Public key login** — use `~/.ssh/authorized_keys`
- **Username / Password** — fixed credentials (SHA-512 hashed, encrypted storage)

### UI
- **Theme** — follow system, force light, or force dark

## Build reference

### Make

```bash
make              # Debug build (default)
make release      # Release build (requires signing key)
make install      # Build + install on device
make test         # Run instrumentation tests
make setup        # Install all build dependencies
make clean        # Remove build output
make lint         # Run Android lint
make check        # Verify build environment
make env          # Show toolchain versions
make version      # Show app version
make apk-path     # Show path to latest APK
make adb-forward  # Forward port 2223 → 2222
```

### Gradle

```bash
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease

# Tests
./gradlew connectedDebugAndroidTest

# Single test
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.ikofi.sshd.UserPasswordTest

# Single test method
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.ikofi.sshd.UserPasswordTest#pw
```

### Release signing

Create a `gradle.properties` entry pointing to an external properties file:

```properties
iKofi.properties=$HOME/.config/iKofi/signing.properties
```

The properties file should contain:

```properties
sign.storeFile=$HOME/keystore.jks
sign.storePassword=MyStorePassword
sign.keyAlias=MyKeyAlias
sign.keyPassword=MyKeyPassword
```

Signing is gracefully skipped when the file is absent (unsigned APK).

## Intent-based control

```bash
# Start the SSH service
adb shell am start -a com.ikofi.sshd.fg.START

# Stop the SSH service
adb shell am start -a com.ikofi.sshd.fg.STOP
```

Must be enabled in **Settings → Allow start/stop by Intent**.

## How it works

### Architecture

The native Dropbear SSH server runs as a **forked child process** of the Android service
(`SshdService`).  The Java layer manages process lifecycle (fork, kill, watchdog restart),
while a JNI bridge (`jni-dropbear.c`) handles authentication callbacks and executable
path remapping.

### Watchdog restart logic

After forking the native process, a background thread calls `waitpid()` to block until the
child exits:

- First start → restart once regardless of uptime
- Subsequent exits → restart if the process ran for ≥ 10 seconds
- Two crashes within 10 seconds → stop (crash-loop detection)

### Authentication

Three authentication methods are available, all configurable in Settings:

1. **Public key** — reads `authorized_keys` from the dropbear config directory
2. **Fixed user/password** — SHA-512 hashed, stored in `EncryptedSharedPreferences`
3. **Single-use passwords** — auto-generated 8-char passwords, displayed in the app log

At least one method must be enabled before the service starts.

### Executable mapping

The JNI bridge intercepts `exec()` calls for `scp`, `rsync`, and `sftp-server` and
redirects them to the bundled `.so` libraries via `ikofi_exe_to_lib()`.  This is why
`useLegacyPackaging = true` is required — the native libs must be extracted to the
filesystem for `execv()` to work.

## Project structure

| Path | Purpose |
|---|---|
| `app/src/main/java/com/ikofi/sshd/` | Android layer |
| `SshdService.java` | Foreground service; native process lifecycle management |
| `MainActivity.java` | Entry point; handles Intent-based start/stop |
| `MainFragment.java` | Primary UI with start/stop button, log viewer, address display |
| `ServiceViewModel.java` | Polls dropbear.err log via background thread; exposes LiveData |
| `SshdSettings.java` | Config file management (host keys, authorized_keys, password hashing) |
| `SshdTileService.java` | Quick Settings tile |
| `BootReceiver.java` | BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver |
| `App.java` | Application subclass; initializes night mode |
| `StartMode.java` | Enum: ByUser, ByIntent, OnBoot |
| `settings/Prefs.java` | Central SharedPreferences accessor |
| `settings/SettingsFragment.java` | Settings UI |
| `settings/UserPassStorage.java` | EncryptedSharedPreferences wrapper for passwords |
| `app/src/main/cpp/` | Native C layer |
| `jni-dropbear.c` / `.h` | JNI bridge; auth hooks; exec mapping |
| `CMakeLists.txt` | Builds 4 binary targets from 722 source files |
| `dropbear/` | Vendored Dropbear + libtomcrypt + libtommath |
| `openssh/` | Vendored OpenSSH sftp-server |
| `rsync/` | Vendored rsync + internal zlib + popt |
| `prebuild-dropbear.sh` | Regenerates dropbear options guard header |
| `prebuild-rsyn.sh` | Generates rsync headers from markdown |

## UI Languages

Chinese (`zh_CN`), English (`en`), French (`fr`), German (`de`), Tamil (`ta`).

## License

[GNU General Public License v3.0](LICENSE)
