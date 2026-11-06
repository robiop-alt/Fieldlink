import os
import boto3

def r2_client():
    return boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )

def upload_file(local_path, object_key):
    r2_client().upload_file(
        str(local_path),
        os.environ.get("R2_BUCKET", "offset-rushes"),
        object_key,
    )

def download_file(object_key, local_path):
    """Download to a temporary file, then atomically publish the completed clip."""
    from pathlib import Path
    import tempfile

    destination = Path(local_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=destination.parent, prefix=".r2-download-", delete=False
        ) as temporary:
            temp_path = Path(temporary.name)
        r2_client().download_file(
            os.environ.get("R2_BUCKET", "offset-rushes"),
            object_key,
            str(temp_path),
        )
        os.replace(temp_path, destination)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

def delete_file(object_key):
    r2_client().delete_object(
        Bucket=os.environ.get("R2_BUCKET", "offset-rushes"),
        Key=object_key,
    )

def r2_enabled():
    return all(os.environ.get(k) for k in (
        "R2_ENDPOINT",
        "R2_ACCESS_KEY_ID",
        "R2_SECRET_ACCESS_KEY",
    ))

def upload_clip_if_enabled(local_path, clip_id):
    if not r2_enabled():
        return False
    upload_file(local_path, f"clips/{clip_id}")
    # A successful SDK call is not sufficient: confirm the remote object size.
    response = r2_client().head_object(
        Bucket=os.environ.get("R2_BUCKET", "offset-rushes"),
        Key=f"clips/{clip_id}",
    )
    from pathlib import Path
    if int(response["ContentLength"]) != Path(local_path).stat().st_size:
        raise RuntimeError("R2 object size differs from uploaded clip")
    return True

def presigned_clip_url(clip_id, expires_seconds=900):
    """Temporary read-only R2 URL. Only call after project review authorization."""
    return r2_client().generate_presigned_url(
        "get_object",
        Params={
            "Bucket": os.environ.get("R2_BUCKET", "offset-rushes"),
            "Key": f"clips/{clip_id}",
            "ResponseContentType": "video/mp4",
            "ResponseContentDisposition": "inline",
        },
        ExpiresIn=expires_seconds,
    )
