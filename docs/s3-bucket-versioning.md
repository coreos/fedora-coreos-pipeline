# **Steps to Create and Configure a Versioned S3 Bucket**

This guide provides step-by-step instructions to create and configure an Amazon S3 bucket with versioning and lifecycle management to optimize storage and reduce costs. Versioning in S3 allows you to maintain multiple versions of an object. This feature is particularly useful in scenarios of:

* **Accidental Overwrites**: If a file is accidentally overwritten, you can restore a previous version without data loss.  
* **Accidental Deletions**: When an object is deleted, S3 creates a delete marker instead of immediately removing the object, enabling easy recovery of previous versions.  
* **Garbage Collection and Storage Optimization**: Over time, old versions, non-current versions, and delete markers accumulate, taking up storage. Combining versioning with **lifecycle policies** automates the cleanup of outdated versions and unnecessary delete markers. This ensures your bucket stays organized and prevents unneeded storage costs from piling up.

---

## **Set a Variable for the Bucket Name**
You can use the name of an existing bucket or specify a new bucket name if you are creating one:
```bash
BUCKET_NAME=temp-bucket-for-versioning
```

---

## **Create a New S3 Bucket**
To create a new bucket, use the following command. Ensure the correct region is specified. Skip this step if you are working with an existing bucket.
```bash
aws s3api create-bucket --bucket $BUCKET_NAME --region your-region
```

---

## **Enable Versioning on the Bucket**
This command enables versioning to track changes in your objects.

```bash
aws s3api put-bucket-versioning \
--bucket $BUCKET_NAME \
--versioning-configuration Status=Enabled
```

---

## **Add Lifecycle Configuration**
The following lifecycle policy will:
1. Delete **non-current versions** of objects after 14 days.
2. Remove **expired delete markers** when no versions remain.

Create a `lifecycle.json` file with the following content:
```bash
cat <<EOF > lifecycle.json
{
    "Rules": [
        {
            "ID": "DeleteOldVersions",
            "Filter": {},
            "Status": "Enabled",
            "NoncurrentVersionExpiration": {
                "NoncurrentDays": 14
            },
            "Expiration": {
                "ExpiredObjectDeleteMarker": true
            }
        }
    ]
}
EOF
```

Apply the lifecycle configuration:
```bash
aws s3api put-bucket-lifecycle-configuration \
--bucket $BUCKET_NAME \
--lifecycle-configuration file://lifecycle.json
```

---

## **Verify Configuration**
Check if the lifecycle configuration has been applied correctly:
```bash
aws s3api get-bucket-lifecycle-configuration --bucket $BUCKET_NAME
```

---

## **Notes**
- **NoncurrentVersionExpiration**: Removes old object versions 14 days after they become non-current.
- **ExpiredObjectDeleteMarker**: Deletes delete markers when no object versions are left, keeping the bucket tidy.

This configuration helps manage storage efficiently by automatically removing obsolete data.
