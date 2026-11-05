# OFFSET development checklist

This branch is **not production-ready**. Do not merge into `main` or deploy to Render until the checklist below passes.

## Admin sign-in setup (Render environment variables)

- `OFFSET_ADMIN_USERNAME`: administrator username
- `OFFSET_ADMIN_PASSWORD`: long, unique administrator password
- `FIELDLINK_SECRET`: long, randomly generated signing secret, **not** `development-only-change-me`
- `FIELDLINK_ADMIN_KEY`: legacy administrator key. Once password sign-in is configured, the web UI does not use it.
- `FIELDLINK_DEVICE_TOKEN`: uploader-to-server authentication secret; it remains internal to the Android uploader.
- R2: `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`

Set secrets only in the hosting provider's protected environment configuration, never in the repository or chat.

## Automated checks

GitHub Actions workflow `.github/workflows/offset-checks.yml` checks backend syntax/import and attempts an Android debug APK build. Passing a build is **not** proof of safe uploads or secure access.

## Manual acceptance tests before release

- Wrong admin credentials are rejected, repeated failures are throttled, logout invalidates browser access.
- Login survives a normal page reload; non-admin cannot list projects or update site text.
- Project list shows all projects, and edits to website text survive a server restart.
- Standard share links require PIN. PIN-free links work for 24 hours and fail after expiry.
- No video original is evicted unless the matching R2 object is verified.
- Database/project metadata survives Render restart, including an empty database and interrupted writes.
- Upload interruption/retry preserves project isolation, file integrity and accurate history.
- Android app compiles and starts on the actual DJI controller; overlay, folder access and background operation are tested.
- Verify the approved OFFSET logo artwork against launcher and website assets before publishing.

## Known limitations

- Admin password reset and multiple viewer accounts are not yet implemented.
- Rate limiting is process-local and resets when the service restarts.
- R2 SQLite snapshots are a single-instance approach; not safe for concurrent server replicas.
- App settings still include a device token field; this is not yet an end-user account sign-in.
