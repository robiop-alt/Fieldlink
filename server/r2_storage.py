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
    r2_client().download_file(
        os.environ.get("R2_BUCKET", "offset-rushes"),
        object_key,
        str(local_path),
    )

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
    return True
