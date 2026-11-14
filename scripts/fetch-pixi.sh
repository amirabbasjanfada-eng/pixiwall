#!/usr/bin/env bash
# Bundles the pinned PixiJS build into the app's assets (app/src/main/assets/engine/pixi.min.js).
# Needs Node/npm and internet access ONCE, at build time. The finished app never touches the network.
# The version comes from PIXI_VERSION in app/src/main/java/com/pixiwall/core/Prompt.kt.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/engine"
VERSION="$(sed -n 's/.*PIXI_VERSION = "\(.*\)".*/\1/p' "$ROOT/app/src/main/java/com/pixiwall/core/Prompt.kt")"
[ -n "$VERSION" ] || { echo "Could not read PIXI_VERSION from Prompt.kt" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Fetching pixi.js@$VERSION from npm ..."
( cd "$TMP" && npm pack "pixi.js@$VERSION" --silent >/dev/null && tar -xzf "pixi.js-$VERSION.tgz" )

SRC="$TMP/package/dist/pixi.min.js"
[ -f "$SRC" ] || SRC="$TMP/package/dist/pixi.js"
[ -f "$SRC" ] || { echo "No dist/pixi.min.js inside pixi.js@$VERSION" >&2; exit 1; }

mkdir -p "$DEST"
cp "$SRC" "$DEST/pixi.min.js"
[ -f "$TMP/package/LICENSE" ] && cp "$TMP/package/LICENSE" "$DEST/pixi.LICENSE.txt"

SIZE="$(wc -c < "$DEST/pixi.min.js" | tr -d ' ')"
[ "$SIZE" -gt 100000 ] || { echo "pixi.min.js is suspiciously small ($SIZE bytes)" >&2; exit 1; }
grep -q "PIXI" "$DEST/pixi.min.js" || { echo "pixi.min.js does not define PIXI" >&2; exit 1; }
echo "Bundled PixiJS $VERSION ($SIZE bytes) -> app/src/main/assets/engine/pixi.min.js"
