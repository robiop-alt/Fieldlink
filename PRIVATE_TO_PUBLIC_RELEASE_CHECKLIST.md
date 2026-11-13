# OFFSET — checklist before any public release

**STOP: Private Cesium ion token sharing is enabled for the private admin planner.**

Before making OFFSET, the planner, its admin area, any preview link, or the repository publicly accessible:

- [ ] Remove or disable `/api/admin/planner/cesium-token` (GET and PUT), which currently returns the shared token to authenticated admin browsers.
- [ ] Rotate/revoke the shared Cesium ion token in ion.cesium.com; do not reuse it for a public release.
- [ ] Remove the encrypted shared token from the OFFSET planner database.
- [ ] Replace it with a public-use credential strategy compliant with Cesium ion and Google Photorealistic 3D Tiles terms, including quotas, billing, domains and access restrictions.
- [ ] Review that authentication and authorization protect every planner and admin endpoint; check token exposure in logs, frontend assets, backups and browser storage.
- [ ] Verify third-party data licensing, credits, Google/Cesium usage limitations and commercial eligibility before enabling public access.

The private-device-sharing feature exists only while a single administrator uses OFFSET. **Do not publicize without this review.**
