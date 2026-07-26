package com.marketshop.application.catalog;

import java.time.Instant;
import java.util.List;

public interface CatalogAssetPort {

    long save(AssetMetadata metadata);

    List<AssetMetadata> assets();

    AssetMetadata find(long assetId);

    void markDeleted(long assetId);

    record AssetMetadata(
            long id,
            String objectKey,
            String sha256,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            long uploadedByAdminId,
            Instant createdAt
    ) {
    }
}
