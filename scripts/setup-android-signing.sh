#!/usr/bin/env bash
# Run locally by the repository owner. Never commit the generated key or passwords.
set -euo pipefail
umask 077
for command in gh keytool openssl base64; do
  command -v "$command" >/dev/null || { echo "Install $command first (keytool is included with a Java JDK)." >&2; exit 1; }
done
gh auth status
signing_dir="${OFFSET_SIGNING_DIRECTORY:-$HOME/.config/offset/android-signing}"
mkdir -p "$signing_dir"
if [[ -e "$signing_dir/offset-release.jks" || -e "$signing_dir/password" ]]; then
  echo "Existing signing material found at $signing_dir. Keep it; do not generate a replacement." >&2
  exit 1
fi
openssl rand -hex 24 > "$signing_dir/password"
keytool -genkeypair -noprompt -keystore "$signing_dir/offset-release.jks" \
  -storepass:file "$signing_dir/password" -keypass:file "$signing_dir/password" \
  -storetype PKCS12 -alias offset -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=OFFSET Android, O=OFFSET, C=NO'
base64 < "$signing_dir/offset-release.jks" | tr -d '\n' | gh secret set OFFSET_ANDROID_KEYSTORE_BASE64 --repo robiop-alt/Fieldlink
tr -d '\n' < "$signing_dir/password" | gh secret set OFFSET_ANDROID_KEYSTORE_PASSWORD --repo robiop-alt/Fieldlink
tr -d '\n' < "$signing_dir/password" | gh secret set OFFSET_ANDROID_KEY_PASSWORD --repo robiop-alt/Fieldlink
printf '%s' offset | gh secret set OFFSET_ANDROID_KEY_ALIAS --repo robiop-alt/Fieldlink
echo "Signing configured. Back up $signing_dir securely; all later updates must use this key."
echo "The first signed build cannot replace an old APK signed with a different debug key. Preserve settings before migration."
