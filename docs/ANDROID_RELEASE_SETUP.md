# OFFSET Android automatic release setup

The workflow `.github/workflows/android-apk.yml` still builds a debug APK for testing. Once the signing secrets below are present, every Android change pushed to `main` additionally builds a signed release APK and publishes `OFFSET.apk` in GitHub Releases.

## One-time signing setup

On a trusted computer with Java installed, generate a release keystore (keep this file and its passwords backed up securely):

```sh
keytool -genkeypair -v -keystore offset-release.jks -alias offset -keyalg RSA -keysize 3072 -validity 10000
```

Base64 encode the keystore:

- macOS: `base64 -i offset-release.jks | tr -d '\\n'`
- Linux: `base64 -w 0 offset-release.jks`

In **GitHub → Settings → Secrets and variables → Actions**, create these four repository secrets:

- `OFFSET_ANDROID_KEYSTORE_BASE64`: the entire base64-encoded keystore
- `OFFSET_ANDROID_KEYSTORE_PASSWORD`: keystore password
- `OFFSET_ANDROID_KEY_ALIAS`: `offset` (or your chosen alias)
- `OFFSET_ANDROID_KEY_PASSWORD`: key password

Never commit the keystore or passwords to GitHub.

## Releases and app updates

After secrets are configured, run **Actions → Build OFFSET Android APK → Run workflow** once, or push an Android change to main. Check that the signed release step succeeds and that **Releases** contains `OFFSET.apk`.

The server endpoint `/api/device/app-update` checks the latest **public** GitHub release. If the repository is private, the GitHub API will not expose releases anonymously; host the APK at a trusted HTTPS URL and configure Render variables `OFFSET_ANDROID_RELEASE_CODE`, `OFFSET_ANDROID_RELEASE_VERSION`, `OFFSET_ANDROID_RELEASE_URL` instead.

The controller's **Check for updates** action opens the APK download in the browser. Android installation approval is still required; this is not silent installation.

**Important migration:** Debug APKs use a different signing certificate from the new release. Android will refuse to install the signed release over the old debug build. Before uninstalling the debug app, record your server address, device token, active project, and selected cache folder. Uninstall the debug build, install the first signed `OFFSET.apk`, and configure it once. Subsequent releases signed with the same keystore can update in place.

Keep the signing keystore permanently. Losing it prevents future updates to installations signed with it.
