# OFFSET 2.0 – RC Plus field acceptance checklist

**Branch:** `offset-reliability-20261106` (draft PR #3). **Not production-certified.**

## Before installing
- Use a non-critical DJI RC Plus and disposable test project. Do not use live production footage for the first test.
- The GitHub Actions artifact is a **debug APK**, not a signed production update. Android may reject installing it over an existing release signed with another certificate. Do not uninstall the existing app without first preserving any necessary app settings and queue state: uninstalling clears private app data including the upload ledger, persisted folder permission and in-progress upload IDs.
- If the build secret `OFFSET_DEFAULT_DEVICE_TOKEN` is absent, the Device token field **will not** be prefilled. Configure it privately; never commit the token to this public repository.
- Expect the server field to default to `https://fieldlink-xrd1.onrender.com`. One-time cache-folder and overlay permissions still require the operator.
- On Android 15+ foreground data-sync services may not be started directly from BOOT_COMPLETED. Test actual DJI firmware, and treat startup that requires opening OFFSET as a known limitation rather than claiming zero-touch boot.

## Acceptance runs (use a disposable test project)
1. **First launch:** select the correct DJI `CACHE_VIDEO` folder once, grant persisted read access, confirm server/token, select project. Verify no extra setup on reopening.
2. **Idle:** DJI Pilot foreground for 30 minutes; check status pill mode (DJI-only if configured), upload watcher alive, no distracting popups.
3. **Normal upload:** create a short **non-critical** proxy video. Observe `Incoming rushes`, upload percentage, then `Ready`; check playable Safari/iPhone web review and thumbnail.
4. **Wi-Fi loss:** while a second test clip uploads, disconnect Wi-Fi and reconnect. Verify no duplicate and remote resume starts at server-confirmed offset.
5. **Power off:** interrupt a third test upload by rebooting RC Plus. On restart, verify same project and safe continuation (account for Android 15 restriction); verify no duplicate.
6. **Low battery:** use a controlled test (never intentionally drain a flight controller during flight). With Low Battery Protection on, verify that below 20% work pauses after the current chunk and pill shows the warning. Verify automatic resume at **25% or above**; below 25% it should remain paused.
7. **Low Data Mode:** enable only for non-critical footage, compare upload size and playback, check heat/performance while DJI Pilot is running. The original cache file must remain untouched. Check interruption and restart.
8. **Project switch:** while another upload is queued, select a different disposable project. Verify neither queued nor finished clip is silently rebound to the new project.
9. **Diagnostics:** More tools → Run diagnostics should only read server and permissions; no uploaded test clip is created.
10. **Server reliability:** compare local original SHA-256 / server completed record as available and confirm exactly one `ready` entry after retry.

## Release blockers
- Android build or backend tests failing.
- Loss or duplication of clips; failed hash/size verification; queued file silently assigned to another project.
- Upload actively continuing while controller is below the configured threshold.
- Hanging or unresponsive DJI Pilot while transcoding.
- Unexplained permission prompts on every restart.
- **A clean CI build does not replace a physical RC Plus field trial.**

## Known limitations
- Debug APK signature may not match an installed signed release.
- Battery and thermal telemetry are Android-reported; validate on the exact RC Plus firmware.
- Low Data Mode hardware encoder compatibility and speed remain unverified on the physical controller.
- Current device token is shared and must be replaced with per-device credentials before multi-user/international launch.
