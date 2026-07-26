package com.marketshop.application.catalog;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.catalog.CatalogAssetPort.AssetMetadata;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CatalogAssetApplicationService implements CatalogAssetUseCase {

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private final CatalogAssetPort metadata;
    private final CatalogAssetStoragePort storage;
    private final ProofSanitizerPort sanitizer;
    private final AdminAuditPort audit;

    public CatalogAssetApplicationService(
            CatalogAssetPort metadata,
            CatalogAssetStoragePort storage,
            ProofSanitizerPort sanitizer,
            AdminAuditPort audit
    ) {
        this.metadata = metadata;
        this.storage = storage;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    @Override
    public List<AssetView> assets() {
        return metadata.assets().stream().map(CatalogAssetApplicationService::view).toList();
    }

    @Override
    public AssetView upload(long adminId, UploadAssetCommand command) {
        if (command.bytes() == null || command.bytes().length == 0 || command.bytes().length > MAX_SIZE_BYTES) {
            throw new DomainException("CATALOG_ASSET_SIZE_INVALID", "商品图片大小必须在 1 字节到 10MB 之间");
        }
        var image = sanitizer.sanitize(command.bytes());
        var stored = storage.put(command.originalFilename(), image.mediaType(), image.bytes());
        Instant now = Instant.now();
        try {
            long id = metadata.save(new AssetMetadata(
                    0,
                    stored.objectKey(),
                    stored.sha256(),
                    safeFilename(command.originalFilename(), image.extension()),
                    image.mediaType(),
                    stored.sizeBytes(),
                    adminId,
                    now
            ));
            AssetView view = new AssetView(
                    id,
                    safeFilename(command.originalFilename(), image.extension()),
                    image.mediaType(),
                    stored.sizeBytes(),
                    adminId,
                    url(id),
                    now
            );
            record(adminId, "CATALOG_ASSET_UPLOADED", id, null);
            return view;
        } catch (RuntimeException exception) {
            try {
                storage.deleteAsset(stored.objectKey());
            } catch (RuntimeException ignored) {
                // An operator can reconcile an orphaned catalog object from storage logs.
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AssetContent content(long assetId) {
        AssetMetadata asset = metadata.find(assetId);
        return new AssetContent(asset.mediaType(), storage.get(asset.objectKey()));
    }

    @Override
    public void delete(long adminId, long assetId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException("CATALOG_ASSET_REASON_REQUIRED", "删除商品素材必须填写原因");
        }
        AssetMetadata asset = metadata.find(assetId);
        storage.deleteAsset(asset.objectKey());
        metadata.markDeleted(assetId);
        record(adminId, "CATALOG_ASSET_DELETED", assetId, reason.trim());
    }

    private void record(long adminId, String action, long assetId, String reason) {
        audit.record(new AuditRecord(
                "ADMIN",
                Long.toString(adminId),
                action,
                "CATALOG_ASSET",
                Long.toString(assetId),
                null,
                null,
                reason,
                UUID.randomUUID().toString(),
                null,
                "application-service",
                Instant.now()
        ));
    }

    private static AssetView view(AssetMetadata asset) {
        return new AssetView(
                asset.id(),
                asset.originalFilename(),
                asset.mediaType(),
                asset.sizeBytes(),
                asset.uploadedByAdminId(),
                url(asset.id()),
                asset.createdAt()
        );
    }

    private static String url(long assetId) {
        return "/api/v1/catalog/assets/" + assetId;
    }

    private static String safeFilename(String filename, String extension) {
        String value = filename == null ? "" : filename.trim();
        return value.isBlank() ? "image." + extension : value.substring(0, Math.min(value.length(), 255));
    }
}
