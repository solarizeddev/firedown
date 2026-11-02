#!/usr/bin/env bash
# Stand-in for the F-Droid folder's publish.sh in the publish-release
# self-test: asks before syncing like the real one, then puts the APK in the
# server's repo under the name `fdroid update --rename-apks` gives it.
printf 'publish.sh %s\n' "$*" >> "$FAKE_WORLD/events"
read -r -p "Proceed with sync? [y/N] " ans
[[ "$ans" =~ ^[Yy]$ ]] || { echo "aborted by user"; exit 1; }
mkdir -p "$FAKE_WORLD/server/www/fdroid/repo"
cp "$1" "$FAKE_WORLD/server/www/fdroid/repo/com.solarized.firedown_${FAKE_CODE}.apk"
echo "Published."
