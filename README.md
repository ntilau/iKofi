# iKofi

SSH/SFTP server with shell access, rsync and scp for Android devices.

Built on [Dropbear](https://matt.ucc.asn.au/dropbear/dropbear.html),
[rsync](https://rsync.samba.org/), and
[OpenSSH sftp-server](https://github.com/openssh/openssh-portable).

- Dropbear 2026.91
- rsync 3.4.2
- sftp-server from OpenSSH 10.3p1

> **Note**: rsync operates over SSH only — no rsync daemon is started.

## Build

Requires JDK 21, Android SDK, NDK 30, and CMake 4.1.2.

```bash
# Install all dependencies and build
./setup.sh
./gradlew assembleDebug

# Or manually:
export ANDROID_HOME=$HOME/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
sdkmanager "platforms;android-36" "ndk;30.0.14904198" "cmake;4.1.2"
./gradlew assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/iKofi-*-debug.apk
```

## Device support

- **Android**: Requires 11 (API 30), tested up to 16 (API 36)
- **Android/Google TV**: Supported on TV emulator 13/14

## Permissions

- `POST_NOTIFICATIONS` — foreground service notification
- `FOREGROUND_SERVICE` — persistent background SSH service
- `RECEIVE_BOOT_COMPLETED` — auto-start on boot (optional)
- `MANAGE_EXTERNAL_STORAGE` — full filesystem access for rsync/sftp (must be manually granted)

## UI Languages

Chinese, English, French, German, Tamil.

## License

GNU General Public License v3.0
