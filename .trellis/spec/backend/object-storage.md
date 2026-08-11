# Private Proof Object Storage

## Scenario: Provider-backed private proof storage

### 1. Scope / Trigger

- Trigger: any change to order/after-sale proof upload, download, deletion, retention, S3/local-file dependencies, provider selection, or object-storage environment wiring.
- Business and domain code depend only on `PrivateObjectStoragePort`; vendor/SDK types stay in `shop-infrastructure`.

### 2. Signatures

```java
StoredObject put(long aggregateId, String originalFilename, String mediaType, byte[] bytes);
String signedGetUrl(String objectKey, Duration duration);
void delete(String objectKey);

DownloadView adminDownload(long adminId, long proofId);

PrivateContent readSigned(String token);
```

- `StoredObject` contains `objectKey`, SHA-256, and stored byte length.
- Proof rows store the private object key, digest, detected media type, byte length, uploader, `retain_until`, and `cleaned_at`.
- Admin download commands must receive the real admin ID so the audit row never uses a placeholder actor.

### 3. Contracts

- `MARKET_SHOP_STORAGE_PROVIDER` selects exactly one conditional infrastructure adapter: `s3` (default) or `local`. Controllers and application services do not branch on the provider.
- S3 provider: RustFS/private S3 bucket, accessed through AWS SDK for Java v2 with SigV4, `us-east-1`, endpoint override, and path-style addressing.
- Local provider: files live under one normalized, application-owned root. The directory is never exposed through Nginx/static resource mappings.
- The application never returns a permanent object URL. It returns a presigned GET URL and `expiresAt`; configured TTL is clamped to 1–60 minutes.
- In local mode, `signedGetUrl` returns `/api/v1/storage/private/{token}`. The token signs `expiresAt + objectKey` with HMAC-SHA256; delivery verifies the signature, expiry, image bytes, and normalized path before reading.
- Upload services are transactional for metadata plus immutable audit writes. If persistence fails after object upload, they attempt compensating object deletion before rethrowing.
- Destructive proof operations re-read metadata with a `FOR UPDATE` row lock (covering the owning order) immediately before deleting the object. The lock is held through the storage call and `cleaned_at` update so an order transition cannot win a check-then-delete race.
- Environment keys:
  - `MARKET_SHOP_STORAGE_PROVIDER`
  - `MARKET_SHOP_RUSTFS_ENDPOINT`
  - `MARKET_SHOP_RUSTFS_ACCESS_KEY`
  - `MARKET_SHOP_RUSTFS_SECRET_KEY`
  - `MARKET_SHOP_RUSTFS_BUCKET`
  - `MARKET_SHOP_RUSTFS_REGION`
  - `MARKET_SHOP_SIGNED_URL_MINUTES`
  - `MARKET_SHOP_LOCAL_STORAGE_ROOT`
  - `MARKET_SHOP_LOCAL_STORAGE_SIGNING_SECRET` (required in local mode, at least 32 characters)
  - `MARKET_SHOP_LOCAL_STORAGE_PRIVATE_BASE_URL`
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
| Local signing secret is absent or shorter than 32 characters | fail application startup; never fall back to a hard-coded production key |
| Local token is malformed, tampered, expired, points outside the root, or resolves to a non-image | `OBJECT_SIGNING_INVALID`; no filesystem path is returned |
| Local file read/write/delete fails | the same stable storage error family as S3; no filesystem path or OS detail leaks |
| Admin deletion reason is blank | `REASON_REQUIRED` |
| Retention config is absent or outside 1–3650 days | use the 180-day safe default |

JPEG/PNG are decoded and re-encoded. WebP metadata chunks (`EXIF`, `XMP `, `ICCP`) are removed, corresponding `VP8X` flags are cleared, and dimensions/frame headers are validated.

### 5. Good/Base/Bad Cases

- Good: authorized buyer uploads a real PNG; sanitized bytes and SHA-256 are stored privately; a five-minute URL is audited and later expires.
- Good: `provider=local` stores the same sanitized PNG under the configured root and returns an application-relative HMAC URL that survives browser access without exposing the root path.
- Base: cash order has no proof; order submission remains valid.
- Bad: renamed PDF, malformed WebP, unrelated user download, permanent public URL, placeholder admin actor, static-serving the local upload root, or direct reuse of a MinIO disk volume.

### 6. Tests Required

- Unit: actual-byte type detection; corrupt JPEG; renamed non-image; WebP metadata removal, feature-flag clearing, and fake frame rejection.
- Unit: unrelated user cannot trigger signing; admin audit contains the real actor ID; TTL clamps at 60 minutes; cleanup deletes, marks, and audits.
- Unit local adapter: catalog/private round-trip; stable SHA-256; media type; HMAC tampering; expiry; traversal rejection; deletion; short-secret startup failure.
- Integration (enabled with `MARKET_SHOP_RUSTFS_INTEGRATION=true`): create private bucket, upload, presign, wait for that URL to truly expire, obtain and read a fresh signed URL, delete, then assert the still-unexpired fresh URL returns 403/404/410 and metadata is cleaned.
- Project gate: `mvn -f backend/pom.xml clean test package` and `docker compose config --quiet`.

### 7. Wrong vs Correct

#### Wrong

```java
return sdk.getPublicUrl(bucket, objectKey);
audit("ADMIN", "0", "PROOF_DOWNLOAD");
registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + uploadRoot);
```

#### Correct

```java
Duration ttl = Duration.ofMinutes(Math.max(1, Math.min(configuredMinutes, 60)));
String url = storage.signedGetUrl(objectKey, ttl);
audit("ADMIN", Long.toString(adminId), "PROOF_DOWNLOAD");
```

This preserves DDD dependency direction, private-by-default storage, bounded credentials, an attributable audit trail, and provider independence.

## Scenario: Public catalog media on the configured provider

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
- The public view returns `/api/v1/catalog/assets/{id}` rather than an S3 URL, local path, or object key. Active content uses a one-hour public cache header.
- Metadata is soft-deleted. Successful upload and deletion append `CATALOG_ASSET_UPLOADED` or `CATALOG_ASSET_DELETED` with the real admin actor.
- If metadata persistence fails after object upload, the service attempts compensating deletion through the selected provider and rethrows the original failure.
- It uses the same selected provider as proof storage. Public delivery is mediated only by the application endpoint, whether bytes originate in an S3 private bucket or the local storage root.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Multipart file part missing | `UPLOAD_CONTENT_REQUIRED`, HTTP 400 |
| Catalog file part present but empty | `CATALOG_ASSET_CONTENT_REQUIRED`, HTTP 400 |
| Original payload over 10 MB | `CATALOG_ASSET_SIZE_INVALID`, HTTP 413 |
| Real type unsupported | `CATALOG_ASSET_TYPE_INVALID`, HTTP 415 |
| Image bytes malformed or undecodable | `CATALOG_ASSET_IMAGE_INVALID`, HTTP 415 |
| Asset ID absent or soft-deleted | stable domain failure; no storage bytes returned |
| Delete reason blank | `CATALOG_ASSET_REASON_REQUIRED` |
| Provider put/get/delete fails | translated storage failure, HTTP 503, without object key, filesystem path, or secret leakage |
| Metadata save fails after put | compensating `deleteAsset(objectKey)` is attempted |

### 5. Good/Base/Bad Cases

- Good: admin uploads PNG, sanitizer re-encodes it, metadata is committed, and clients read the stable public endpoint.
- Base: a product has no cover; the UI renders its empty image state.
- Bad: save a `localhost:9000` object URL, make the entire bucket public, use a payment proof URL as a product image, or skip deletion audit.

### 6. Tests Required

- Unit: sanitized bytes/media type are sent to storage and stable URL uses the metadata ID.
- Unit: missing/empty, oversized, unsupported-type, malformed-image, and provider failures map to 400/413/415/415/503.
- Unit: metadata failure deletes the newly stored object.
- Unit: deletion requires a reason, marks metadata, deletes the object, and audits.
- Runtime/provider smoke: multipart upload, public HTTP GET with exact media type/bytes, delete, then public GET fails.

### 7. Wrong vs Correct

#### Wrong

```java
return new AssetView(id, storageEndpoint + "/" + bucket + "/" + objectKey);
```

#### Correct

```java
return new AssetView(id, "/api/v1/catalog/assets/" + id);
```

The endpoint remains stable if the storage provider, credentials, internal hostnames, or object layout change.

## Scenario: Provider-backed member avatar storage

### Scope and signatures

Member avatars share the configured local/S3 infrastructure but use identity-owned application ports rather than the proof or catalog storage contracts:

```java
IdentityAvatarStoragePort.StoredAvatar putAvatar(
    long userId, String originalFilename, String mediaType, byte[] bytes);
byte[] readAvatar(String objectKey);
void deleteAvatar(String objectKey);
```

### Contracts

- `IdentityAvatarStoragePort` and `IdentityAvatarSanitizerPort` belong to the identity application boundary. Identity services do not import proof or catalog use cases, SDK types, filesystem paths, or S3 models.
- Local and S3/RustFS adapters implement identical put/read/delete behavior under the existing `MARKET_SHOP_STORAGE_PROVIDER`. The S3 bucket remains private; neither adapter creates a public provider URL.
- `chooseAvatar` bytes are detected and sanitized as JPG/PNG/WebP before storage. Client filenames, declared content type, `wxfile://` paths, EXIF/XMP/ICC metadata, malformed dimensions and fake WebP frames are not trusted.
- The database stores the identity-owned object key plus detected media type, SHA-256, byte length and update time. Public projections store/return only `/api/v1/member-avatars/{userId}` and never expose the key.
- `GET /api/v1/member-avatars/{userId}` resolves the current object server-side, reads it through the port and returns the detected media type. It is the only public avatar byte endpoint and is stable across provider changes.
- Replacing an avatar writes the new immutable object first and atomically swaps versioned metadata. A failed metadata write or any later transaction rollback compensates by deleting the new object; the previous object is removed only after the database commit.
- Missing/failed avatars degrade in clients to the member nickname initial. A corporate Logo, external avatar URL or proof-signed URL is never used as a member fallback.

### Validation and tests

| Condition | Required result |
|---|---|
| Empty or oversized avatar | `MEMBER_AVATAR_CONTENT_REQUIRED` / `MEMBER_AVATAR_SIZE_INVALID` |
| Renamed/corrupt/non-image bytes | stable `MEMBER_AVATAR_TYPE_INVALID` or `MEMBER_AVATAR_IMAGE_INVALID` |
| Provider put/read/delete failure | stable 503 avatar-storage error without key/path/vendor detail |
| Metadata concurrency conflict | Delete the newly written object and preserve the winning profile |
| No current object metadata | 404 `MEMBER_AVATAR_NOT_FOUND` |

Unit tests cover actual-byte detection, metadata stripping, local round-trip/path containment, S3 key/payload/read/delete calls, persistence/rollback compensation, after-commit replacement cleanup and stable same-origin delivery. Provider integration tests should include member-avatar round-trip alongside the existing private-proof lifecycle when RustFS integration is enabled.
