package com.marketshop.application.catalog;

public interface CatalogAssetStoragePort {

    StoredAsset put(String originalFilename, String mediaType, byte[] bytes);

    byte[] get(String objectKey);

    void deleteAsset(String objectKey);

    record StoredAsset(String objectKey, String sha256, long sizeBytes) {
    }
}
