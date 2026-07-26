# Private Proof Object Storage

## Scenario: RustFS-backed proof storage

### 1. Scope / Trigger

- Trigger: any change to order/after-sale proof upload, download, deletion, retention, S3 dependencies, or object-storage environment wiring.
- Business and domain code depend only on `PrivateObjectStoragePort`; vendor/SDK types stay in `shop-infrastructure`.

### 2. Signatures

```java
StoredObject put(long aggregateId, String originalFilename, String mediaType, byte[] bytes);
String signedGetUrl(String objectKey, Duration duration);
void delete(String objectKey);

DownloadView adminDownload(long adminId, long proofId);
```

- `StoredObject` contains `objectKey`, SHA-256, and stored byte length.
- Proof rows store the private object key, digest, detected media type, byte length, uploader, `retain_until`, and `cleaned_at`.
- Admin download commands must receive the real admin ID so the audit row never uses a placeholder actor.

### 3. Contracts

- Local provider: RustFS private bucket, accessed through AWS SDK for Java v2 with SigV4, `us-east-1`, endpoint override, and path-style addressing.
- The application never returns a permanent object URL. It returns a presigned GET URL and `expiresAt`; configured TTL is clamped to 1–60 minutes.
- Upload services are transactional for metadata plus immutable audit writes. If persistence fails after object upload, they attempt compensating object deletion before rethrowing.
- Environment keys:
  - `MARKET_SHOP_RUSTFS_ENDPOINT`
  - `MARKET_SHOP_RUSTFS_ACCESS_KEY`
  - `MARKET_SHOP_RUSTFS_SECRET_KEY`
  - `MARKET_SHOP_RUSTFS_BUCKET`
  - `MARKET_SHOP_RUSTFS_REGION`
  - `MARKET_SHOP_SIGNED_URL_MINUTES`
- Docker-only network keys: `MARKET_SHOP_RUSTFS_BIND_HOST` (safe default `127.0.0.1`), `MARKET_SHOP_RUSTFS_API_PORT`, and `MARKET_SHOP_RUSTFS_CONSOLE_PORT`.
- RustFS data uses `rustfs-data`; never mount an old provider's raw data directory as a RustFS volume. Migrate through S3 operations.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Caller is not buyer, direct superior, or permitted admin | `PROOF_ACCESS_DENIED` / context-specific access error; no URL is signed |
| Empty or oversized bytes | `PROOF_SIZE_INVALID` |
| Active file count reached | `PROOF_COUNT_EXCEEDED` |
| Magic bytes are not JPG/PNG/WebP | `PROOF_TYPE_INVALID` |
| Image decode, dimensions, RIFF/chunk lengths, or WebP frame header is invalid | `PROOF_IMAGE_INVALID` |
| S3 put/sign/delete fails | stable `OBJECT_STORAGE_FAILED`, `OBJECT_SIGNING_FAILED`, or `OBJECT_DELETE_FAILED`; no SDK detail or secret leaks |
| Admin deletion reason is blank | `REASON_REQUIRED` |
| Retention config is absent or outside 1–3650 days | use the 180-day safe default |

JPEG/PNG are decoded and re-encoded. WebP metadata chunks (`EXIF`, `XMP `, `ICCP`) are removed, corresponding `VP8X` flags are cleared, and dimensions/frame headers are validated.

### 5. Good/Base/Bad Cases

- Good: authorized buyer uploads a real PNG; sanitized bytes and SHA-256 are stored privately; a five-minute URL is audited and later expires.
- Base: cash order has no proof; order submission remains valid.
- Bad: renamed PDF, malformed WebP, unrelated user download, permanent public URL, placeholder admin actor, or direct reuse of a MinIO disk volume.

### 6. Tests Required

- Unit: actual-byte type detection; corrupt JPEG; renamed non-image; WebP metadata removal, feature-flag clearing, and fake frame rejection.
- Unit: unrelated user cannot trigger signing; admin audit contains the real actor ID; TTL clamps at 60 minutes; cleanup deletes, marks, and audits.
- Integration (enabled with `MARKET_SHOP_RUSTFS_INTEGRATION=true`): create private bucket, upload, presign, HTTP GET exact bytes, delete, then assert GET returns 404.
- Project gate: `mvn -f backend/pom.xml clean test package` and `docker compose config --quiet`.

### 7. Wrong vs Correct

#### Wrong

```java
return sdk.getPublicUrl(bucket, objectKey);
audit("ADMIN", "0", "PROOF_DOWNLOAD");
```

#### Correct

```java
Duration ttl = Duration.ofMinutes(Math.max(1, Math.min(configuredMinutes, 60)));
String url = storage.signedGetUrl(objectKey, ttl);
audit("ADMIN", Long.toString(adminId), "PROOF_DOWNLOAD");
```

This preserves DDD dependency direction, private-by-default storage, bounded credentials, and an attributable audit trail.

## Scenario: Public catalog media on RustFS

### 1. Scope / Trigger

- Trigger: product/content image upload, catalog asset metadata, public asset delivery, deletion, or the shared image sanitizer.
- Catalog media is intentionally public after upload. It must not reuse proof download semantics or expose proof object keys.

### 2. Signatures

```java
StoredAsset put(String originalFilename, String mediaType, byte[] bytes);
byte[] get(String objectKey);
void deleteAsset(String objectKey);

AssetView upload(long adminId, UploadAssetCommand command);
AssetContent content(long assetId);
void delete(long adminId, long assetId, String reason);
```

```http
GET    /api/v1/admin/catalog/assets
POST   /api/v1/admin/catalog/assets
DELETE /api/v1/admin/catalog/assets/{assetId}
GET    /api/v1/catalog/assets/{assetId}
```

### 3. Contracts

- Admin upload requires `catalog:write` or `content:write`, accepts multipart `file`, and caps the original payload at 10 MB. Listing accepts `catalog:read` or `content:write`.
- The existing `ProofSanitizerPort` validates and strips metadata before storage; metadata records the post-sanitization media type, digest, and byte length.
- The public view returns `/api/v1/catalog/assets/{id}` rather than an S3 URL or object key. Active content uses a one-hour public cache header.
- Metadata is soft-deleted. Successful upload and deletion append `CATALOG_ASSET_UPLOADED` or `CATALOG_ASSET_DELETED` with the real admin actor.
- If metadata persistence fails after object upload, the service attempts compensating RustFS deletion and rethrows the original failure.
- It uses the same `MARKET_SHOP_RUSTFS_*` environment contract and private bucket as proof storage; public delivery is mediated only by the application endpoint.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Empty or over 10 MB | `CATALOG_ASSET_SIZE_INVALID` |
| Invalid/malformed image | shared sanitizer stable image error |
| Asset ID absent or soft-deleted | stable domain failure; no storage bytes returned |
| Delete reason blank | `CATALOG_ASSET_REASON_REQUIRED` |
| S3 put/get/delete fails | translated storage failure without object key or secret leakage |
| Metadata save fails after put | compensating `deleteAsset(objectKey)` is attempted |

### 5. Good/Base/Bad Cases

- Good: admin uploads PNG, sanitizer re-encodes it, metadata is committed, and storefront reads the stable public endpoint.
- Base: a product has no cover; the UI renders its empty image state.
- Bad: save a `localhost:9000` object URL, make the entire bucket public, use a payment proof URL as a product image, or skip deletion audit.

### 6. Tests Required

- Unit: sanitized bytes/media type are sent to storage and stable URL uses the metadata ID.
- Unit: metadata failure deletes the newly stored object.
- Unit: deletion requires a reason, marks metadata, deletes the object, and audits.
- Runtime RustFS smoke: multipart upload, public HTTP GET with exact media type/bytes, delete, then public GET fails.

### 7. Wrong vs Correct

#### Wrong

```java
return new AssetView(id, storageEndpoint + "/" + bucket + "/" + objectKey);
```

#### Correct

```java
return new AssetView(id, "/api/v1/catalog/assets/" + id);
```

The endpoint remains stable if RustFS credentials, internal hostnames, or bucket layout change.
