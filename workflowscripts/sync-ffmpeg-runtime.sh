#!/usr/bin/env bash
set -euo pipefail

# Syncs the FFmpeg engine packages into Cloudflare KV and deploys the Worker.
# Requirements: CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$ROOT/cloudflare-worker/wrangler.ffmpeg.toml"
KV_ID="2542959da8864f33917de9cc9ece3815"
API="https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID:?}/storage/kv/namespaces/$KV_ID"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

declare -A ASSETS=(
  [arm64-v8a]="arm64-v8a-full"
  [armeabi-v7a]="armv7-a-full"
)

MANIFEST_FILE="$OUT/manifest.json"
echo '{}' > "$MANIFEST_FILE"
for abi in "${!ASSETS[@]}"; do
  asset="${ASSETS[$abi]}"
  tar="$OUT/$asset.tar.bz2"
  dir="$OUT/$abi"
  bin="$dir/ffmpeg"
  zip="$OUT/$abi.zip"

  echo "==> Fetching $abi"
  curl -fsSL "https://github.com/Khang-NT/ffmpeg-binary-android/releases/download/2018-07-31/$asset.tar.bz2" -o "$tar"
  python3 - "$tar" "$dir" <<'PY'
import sys, tarfile
with tarfile.open(sys.argv[1], "r:bz2") as t:
    t.extractall(sys.argv[2])
PY
  chmod 755 "$bin"
  (cd "$dir" && zip -q -9 -X ffmpeg.zip ffmpeg && mv ffmpeg.zip "$zip")

  sha="$(sha256sum "$zip" | cut -d' ' -f1)"
  size="$(stat -c%s "$zip")"
  MANIFEST_FILE="$MANIFEST_FILE" python3 - "$abi" "$sha" "$size" <<'PY'
import json, os, sys
abi, sha, size = sys.argv[1], sys.argv[2], sys.argv[3]
path = os.environ["MANIFEST_FILE"]
manifest = json.load(open(path))
manifest[abi] = {"url": f"https://sanin-ffmpeg.shemaus58.workers.dev/ffmpeg/{abi}.zip", "sha256": sha, "size": int(size)}
open(path, "w").write(json.dumps(manifest, indent=2))
PY

  echo "==> Uploading $abi ($size bytes)"
  curl -fsS -X PUT "$API/values/$abi.zip" \
    -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN:?}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$zip" > /dev/null
done

python3 - "$MANIFEST_FILE" <<'PY'
import json, sys
path = sys.argv[1]
artifacts = json.load(open(path))
manifest = {"version": "android-2018", "artifacts": artifacts}
open(path, "w").write(json.dumps(manifest, indent=2) + "\n")
PY

echo "==> Uploading manifest"
curl -fsS -X PUT "$API/values/manifest.json" \
  -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN:?}" \
  -H "Content-Type: application/json" \
  --data-binary "@$OUT/manifest.json" > /dev/null

echo "==> Deploying Worker"
npx --yes wrangler@4 deploy --config "$CONFIG"

echo "==> Done: https://sanin-ffmpeg.shemaus58.workers.dev/manifest.json"
