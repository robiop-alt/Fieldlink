"""Durable SQLite snapshots in Cloudflare R2 for a single OFFSET server instance.

This is a single-instance solution, not a multi-writer database. Do not run
multiple Render workers or replicas against the same snapshot key.
"""
import logging
import os
import sqlite3
import tempfile
import threading
from pathlib import Path

from botocore.exceptions import ClientError
from r2_storage import r2_client, r2_enabled

LOCK = threading.RLock()
KEY = "database/fieldlink.sqlite3"
BUCKET = lambda: os.environ.get("R2_BUCKET", "offset-rushes")


def restore(database_path):
    """Restore the authoritative snapshot before opening the application DB."""
    if not r2_enabled():
        logging.warning("R2 not configured: SQLite database is NOT durable")
        return
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with LOCK:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".db-restore-", delete=False) as f:
            temporary = Path(f.name)
        try:
            try:
                r2_client().download_file(BUCKET(), KEY, str(temporary))
            except ClientError as exc:
                if exc.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
                    if path.exists():
                        # First-time migration from an existing local SQLite database.
                        # Upload before accepting any further writes.
                        snapshot(path)
                    return
                raise
            # Validate the snapshot before replacing any local database.
            with sqlite3.connect(temporary) as check:
                if check.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise RuntimeError("R2 database snapshot failed integrity check")
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def snapshot(database_path):
    """Use SQLite's online backup API, then upload a consistent snapshot."""
    if not r2_enabled():
        return
    path = Path(database_path)
    with LOCK:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".db-snapshot-", delete=False) as f:
            temporary = Path(f.name)
        try:
            with sqlite3.connect(path) as source, sqlite3.connect(temporary) as target:
                source.backup(target)
            r2_client().upload_file(str(temporary), BUCKET(), KEY)
        finally:
            temporary.unlink(missing_ok=True)


class DurableConnection:
    """Serialize DB access and snapshot committed writes before returning."""
    def __init__(self, database_path):
        self.path = database_path
        self.connection = sqlite3.connect(database_path)
        self.connection.row_factory = sqlite3.Row

    def __enter__(self):
        LOCK.acquire()
        return self.connection

    def __exit__(self, exc_type, exc, tb):
        try:
            changed = self.connection.total_changes > 0
            self.connection.__exit__(exc_type, exc, tb)
            self.connection.close()
            if exc_type is None and changed:
                snapshot(self.path)
        finally:
            LOCK.release()
        return False
