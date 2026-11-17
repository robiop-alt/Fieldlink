# Private Android updates

The existing controller APK can keep using Settings → Check for updates.
The server authenticates its metadata request using the existing device token.
With `OFFSET_GITHUB_RELEASE_TOKEN` configured, GitHub access stays on the server
and the app receives a signed download link valid for ten minutes. The browser
then receives a GitHub release-storage link; no GitHub token is sent to it.
Android still asks the user to confirm installation, using the same signing key.

## Setup order

1. On GitHub, create a fine-grained personal access token owned by `robiop-alt`.
   Select **Only select repositories → Fieldlink** and grant repository
   **Contents: Read-only**. Set an expiry you can maintain. Do not put the
   token in the APK, repository, screenshots or chat.
2. In Render → Fieldlink service → Environment, add
   `OFFSET_GITHUB_RELEASE_TOKEN` with that token as its value. Save and deploy.
   Leave `OFFSET_ANDROID_RELEASE_URL` unset: that older override bypasses
   GitHub discovery. Optional `OFFSET_PUBLIC_URL` must be the service's public
   HTTPS base URL; its default is `https://fieldlink-xrd1.onrender.com`.
3. Test a real update check and download on a configured controller. If the
   installed version is already latest, publish a new signed version through
   the existing Android workflow before testing its Download update button.
4. Confirm Render's GitHub integration has access to Fieldlink, then open
   repository Settings → General → Danger Zone → Change repository visibility
   and choose Private. This requires repository administrator access.
5. Recheck controller update/download, Render deployment and GitHub Actions.
   Anonymous visitors must no longer see the repository or its releases.

The server returns an update-check error if GitHub credentials expire or lose
access. Renew the token in Render to restore updates. Changing the controller
device token also invalidates previously issued OFFSET download tickets.
Already downloaded copies of old public APKs are not recalled by this change.

## Verification

`PYTHONPATH=server python3 -m unittest discover -s server/tests -p test_android_updates.py`

Covers authenticated metadata, expiring/tamper-resistant download tickets,
device-token revocation, private metadata without credential disclosure,
redirect handling without forwarding credentials, streamed assets and the
existing public-release fallback. Private-source end-to-end verification
requires the read token and repository visibility setup above.
