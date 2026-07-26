package com.marketshop.application.catalog;

import java.time.Instant;
import java.util.List;

public interface CatalogAssetUseCase {

    List<AssetView> assets();

    AssetView upload(long adminId, UploadAssetCommand command);

    AssetContent content(long assetId);

    void delete(long adminId, long assetId, String reason);

    record UploadAssetCommand(String originalFilename, byte[] bytes) {
    }

    record AssetView(
            long id,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            long uploadedByAdminId,
            String url,
            Instant createdAt
    ) {
    }

    record AssetContent(String mediaType, byte[] bytes) {
    }
}
