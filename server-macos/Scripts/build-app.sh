#!/usr/bin/env bash
# 构建并打包为 TypeBridgeMac.app（ad-hoc 签名，仅本地运行）。
set -euo pipefail

cd "$(dirname "$0")/.."

swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path)"
APP="dist/投字.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp "$BIN_PATH/TypeBridgeMac" "$APP/Contents/MacOS/TypeBridgeMac"
cp "Resources/Info.plist" "$APP/Contents/Info.plist"
cp "Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
cp "Resources/MenuBarIcon.png" "$APP/Contents/Resources/MenuBarIcon.png"

codesign --force --sign - --entitlements "TypeBridgeMac.entitlements" "$APP"

echo "built $APP"
