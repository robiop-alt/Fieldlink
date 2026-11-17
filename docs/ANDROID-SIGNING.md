# Stable Android updates

All builds must use the same private signing key to install as updates and preserve app settings.
The workflow signs both the downloadable debug artifact and release APK with this key when the four signing secrets exist. Version codes increase with the workflow run number.

## One-time setup on the owner's Mac

Install a Java JDK (for `keytool`) and the GitHub CLI (`gh`), then sign in with `gh auth login` using an account authorized to manage this repository's Actions secrets.
From the repository root run:

```bash
bash scripts/setup-android-signing.sh
```

The script generates a private key and password in `~/.config/offset/android-signing`, with restrictive permissions, and uploads them to the four Actions secrets. It refuses to replace existing key material. Back up this directory securely and do not commit it. No key or password is included in this repository or printed by the script.

Trigger **Build OFFSET Android APK** on `main` after setup. Check that the signed-release steps succeed and that a release appears. Future builds must keep the same secrets and key. Debug artifacts without these secrets are temporary test builds and cannot be relied on for in-place updates.

## Existing test installations

Old debug builds were signed with different ephemeral keys. A newly generated key cannot update those installations in place. Preserve controller settings and plan a one-time migration before uninstalling anything. This setup does not recover an old signing key or migrate the existing app's private data automatically.
