#!/usr/bin/env bash
# olcrtc-rebuild-verify.sh
# Rebuild OlcRTC Go artifacts from source, compute SHA256 checksums,
# and compare against THIRD_PARTY_NOTICES.md.
#
# Prerequisites:
#   - gomobile installed and in PATH
#   - OLCRTC_REPO pointing to the OlcRTC Go source checkout
#   - Go toolchain
#
# Usage:
#   ./scripts/olcrtc-rebuild-verify.sh [--verify-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OLCRTC_MODULE="$REPO_ROOT/olcrtc"
THIRD_PARTY="$OLCRTC_MODULE/THIRD_PARTY_NOTICES.md"

# Expected checksums (must match THIRD_PARTY_NOTICES.md)
declare -A EXPECTED
EXPECTED["jniLibs/arm64-v8a/libgojni.so"]="561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9"
EXPECTED["jniLibs/arm64-v8a/libhev-socks5-tunnel.so"]="c2b14023abe53863a04a82cf836d147ff8eeaf2563ca507a025d3f3e1a991772"
EXPECTED["jniLibs/x86_64/libgojni.so"]="561ad9beef951ebeff3373c35c9b5cfaeda8c5ec6f9f354030b1ab1931fe22d9"
EXPECTED["jniLibs/x86_64/libhev-socks5-tunnel.so"]="ea11700dc262b0a81e45f874cb7a2416d41d33bb5fb49c8d636208261d1867a0"
EXPECTED["libs/olcrtc-classes.jar"]="77c5ecf2f1532eb2a52f733bd1d47beb830f596317dde8b6e3f0eefb98a8a23f"

verify_checksums() {
    echo "=== Verifying binary checksums ==="
    local all_ok=true

    for rel_path in "${!EXPECTED[@]}"; do
        local full_path="$OLCRTC_MODULE/src/main/$rel_path"
        if [ ! -f "$full_path" ]; then
            echo "MISSING: $rel_path"
            all_ok=false
            continue
        fi
        local actual
        actual=$(sha256sum "$full_path" | cut -d' ' -f1)
        local expected="${EXPECTED[$rel_path]}"
        if [ "$actual" = "$expected" ]; then
            echo "OK: $rel_path"
        else
            echo "MISMATCH: $rel_path"
            echo "  expected: $expected"
            echo "  actual:   $actual"
            all_ok=false
        fi
    done

    if $all_ok; then
        echo ""
        echo "✓ All checksums match."
        return 0
    else
        echo ""
        echo "✗ Checksum verification FAILED."
        echo "  Update THIRD_PARTY_NOTICES.md or rebuild."
        return 1
    fi
}

if [ "${1:-}" = "--verify-only" ]; then
    verify_checksums
    exit $?
fi

# Build
if [ -z "${OLCRTC_REPO:-}" ]; then
    echo "ERROR: OLCRTC_REPO not set. Point it to the OlcRTC Go source checkout."
    exit 1
fi

if ! command -v gomobile &> /dev/null; then
    echo "ERROR: gomobile not found in PATH."
    echo "Install: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init"
    exit 1
fi

echo "=== Rebuilding olcrtc.aar from $OLCRTC_REPO ==="
cd "$OLCRTC_REPO"
gomobile bind \
    -target=android \
    -androidapi 21 \
    -ldflags "-s -w -checklinkname=0" \
    -o "$OLCRTC_MODULE/src/main/libs/olcrtc.aar" \
    ./mobile

echo "AAR built: $OLCRTC_MODULE/src/main/libs/olcrtc.aar"

# Now verify
verify_checksums
echo ""
echo "Done. If checksums changed, update THIRD_PARTY_NOTICES.md and commit."
