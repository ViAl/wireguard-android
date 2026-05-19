# Third-Party Notices — OlcRTC Module

This module includes pre-built binary artifacts from the following third-party projects.
The source code for these artifacts is available under their respective licenses.

## Artifacts

### olcrtc-classes.jar (Go gomobile bindings)

- **Upstream:** OlcRTC Go client
- **Source:** https://github.com/openlibrecommunity/olcrtc
- **Commit SHA:** `028e94d4ce4e3772f826937c61e5465c4ced5755`
- **SHA256 (arm64-v8a + x86_64):** `77c5ecf2f1532eb2a52f733bd1d47beb830f596317dde8b6e3f0eefb98a8a23f`
- **License:** MIT

### libgojni.so (Go runtime JNI bridge)

- **Built by:** gomobile bind
- **Commit SHA:** `028e94d4ce4e3772f826937c61e5465c4ced5755` (same as olcrtc-classes.jar)
- **SHA256 (arm64-v8a):** `561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9`
- **SHA256 (x86_64):** `561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9`
- **License:** Go's BSD-style license (https://golang.org/LICENSE)

### libhev-socks5-tunnel.so (tun2socks)

- **Upstream:** hev-socks5-tunnel
- **Source:** https://github.com/heiher/hev-socks5-tunnel
- **Version:** v2.15.0
- **Commit SHA:** `00c7eb9ad7972a21fa96c6f41e51e6acb2e7daa0`
- **SHA256 (arm64-v8a):** `c2b14023abe53863a04a82cf836d147ff8eeaf2563ca507a025d3f3e1a991772`
- **SHA256 (x86_64):** `ea11700dc262b0a81e45f874cb7a2416d41d33bb5fb49c8d636208261d1867a0`
- **License:** Apache 2.0

### libolcrtc_tun2socks.so (Custom JNI bridge)

- **Built from:** `olcrtc/src/main/jni/olcrtc_tun2socks_jni.c`
- **Built by:** ndkBuild (part of the regular Gradle build)
- **Not pre-committed** — built from source during each build.

## ABI Policy

Only **arm64-v8a** and **x86_64** are supported.
- armeabi-v7a and x86 are excluded via `build.gradle.kts` `ndk { abiFilters }`.

## Rebuild & Verify

See `scripts/olcrtc-rebuild-verify.sh` for a script that:
1. Rebuilds all Go-based artifacts from source
2. Computes SHA256 checksums
3. Compares against the values above
