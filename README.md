# FIELDLINK — Drone Rushes Delivery (developer preview 0.2)

FIELDLINK is a configurable Android uploader + private browser review portal for drone proxy/cache clips. The server address is entered in the Android app and the browser uses relative URLs, so the domain/server can be changed later without rebuilding the web portal. Changing the uploader server address in the app is also supported.

## What is included

- FastAPI server with SQLite metadata and persistent local media storage.
- Admin portal to create projects and generate a private review URL + six-digit PIN.
- Review portal with PIN unlock, browser video playback (HTTP byte ranges), and downloads.
- Device-token-protected upload API with 4 MiB resumable chunks and SHA-256 verification.
- Native Android app source for RC Plus testing: server/project/device-token settings, one-time folder selection, foreground monitoring, MP4 discovery, stability checks, upload queue, and resume using server-reported offsets.

## Important: this is a developer preview, not a field-certified release

The Android source has not been compiled against the exact RC Plus firmware in this environment. No APK is included. Build it in Android Studio (instructions below), then install and verify on the actual controller. In particular, Android/ DJI may restrict access to `DJI/com.dji.industry.pilot/CACHE_VIDEO`. The operator must select the folder through Android's folder picker; if DJI's cache folder is not exposed there, this implementation cannot read it until we validate an alternate supported access method.

The uploader waits for a file to remain unchanged across multiple scans before starting upload. It runs as a visible foreground service while active. This reduces the risk of uploading a clip while it is still being written, but must be validated with real Inspire 3 cache behavior. Keep the notification visible. Do not rely on this preview for a paid production or critical approval workflow until tested.

## Run server locally

1. Install Docker + Docker Compose.
2. Copy `.env.example` to `.env` and replace all secrets with long random values.
3. Run `docker compose up --build`.
4. Open `http://localhost:8000` on the same machine/network.
5. Enter `FIELDLINK_ADMIN_KEY`, create a project, and copy the project ID and PIN. The PIN is shown only once.
6. Use `FIELDLINK_DEVICE_TOKEN` as the Android app's device token.

For an internet-facing server, deploy behind HTTPS. No domain is hardcoded: configure DNS/reverse proxy later, then enter the new `https://` address in the Android app. The API and browser portal are served from the same origin. Back up the Docker volume.

## Build Android app

1. Open the `android/` folder in Android Studio (recent version with Android Gradle Plugin 8.8.2 support).
2. Allow Gradle to sync and download dependencies.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Install the debug APK on the RC Plus only after confirming the device permits third-party app installation under the applicable DJI policy.
5. In FIELDLINK, enter server URL, project ID and device token; choose the DJI `CACHE_VIDEO` folder if visible; tap **Start automatisk opplasting**.
6. Confirm the notification stays active, a newly created test MP4 is detected, and the clip appears on the review page.

For a first test, use a local HTTPS tunnel or a test server with a valid TLS certificate. The app intentionally rejects plain HTTP for remote hosts. `localhost` and Android emulator host `10.0.2.2` are allowed for development only.

## Upload API

- `POST /api/upload/init` JSON: `project_id`, `filename`, `total_size`.
- `PUT /api/upload/{upload_id}/chunk` multipart field `chunk`, header `X-Offset`.
- `GET /api/upload/{upload_id}` returns server-confirmed `received` bytes for resume.
- `POST /api/upload/{upload_id}/complete` finalizes and checks file size/SHA-256.

All uploader requests require `X-Device-Token` matching `FIELDLINK_DEVICE_TOKEN`.

## Before production use

Add individual device enrollment, admin accounts, PIN rate limiting/lockout, project expiry/revocation, audit logs, per-project access rules, object storage, quotas/retention, upload cleanup, backups, monitoring, and security review. Add comments/approval workflow after the core transfer path is validated. The current PIN/session design is a preview and should not be considered a complete security assessment.

## Project layout

- `server/` — API and web portals
- `android/` — Android Studio project
- `docker-compose.yml` — server deployment
