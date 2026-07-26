package com.marketshop.application.catalog;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.catalog.CatalogAssetPort.AssetMetadata;
import com.marketshop.application.catalog.CatalogAssetStoragePort.StoredAsset;
import com.marketshop.application.catalog.CatalogAssetUseCase.UploadAssetCommand;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.application.proof.OrderProofPorts.SanitizedImage;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogAssetApplicationServiceTest {

    @Test
    void sanitizesStoresAndPublishesStableCatalogAssetUrl() {
        var metadata = new MetadataFake();
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit);

        var asset = service.upload(8, new UploadAssetCommand("封面.jpg", new byte[]{1, 2, 3}));

        assertThat(storage.putMediaType).isEqualTo("image/webp");
        assertThat(storage.putBytes).containsExactly(9, 8);
        assertThat(metadata.saved.originalFilename()).isEqualTo("封面.jpg");
        assertThat(asset.url()).isEqualTo("/api/v1/catalog/assets/41");
        assertThat(asset.uploadedByAdminId()).isEqualTo(8);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("CATALOG_ASSET_UPLOADED");
            assertThat(record.resourceId()).isEqualTo("41");
        });
    }

    @Test
    void removesStoredObjectWhenMetadataPersistenceFails() {
        var metadata = new MetadataFake();
        metadata.failSave = true;
        var storage = new StorageFake();
        var service = service(metadata, storage, new AuditFake());

        assertThatThrownBy(() -> service.upload(8, new UploadAssetCommand("封面.jpg", new byte[]{1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database");

        assertThat(storage.deletedKeys).containsExactly("catalog/object.webp");
    }

    @Test
    void deleteRequiresReasonAndAuditsSuccessfulDeletion() {
        var metadata = new MetadataFake();
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit);

        assertThatThrownBy(() -> service.delete(8, 41, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("原因");

        service.delete(8, 41, "素材已过期");

        assertThat(storage.deletedKeys).containsExactly("catalog/object.webp");
        assertThat(metadata.deletedIds).containsExactly(41L);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("CATALOG_ASSET_DELETED");
            assertThat(record.reason()).isEqualTo("素材已过期");
        });
    }

    private static CatalogAssetApplicationService service(
            CatalogAssetPort metadata,
            CatalogAssetStoragePort storage,
            AdminAuditPort audit
    ) {
        ProofSanitizerPort sanitizer = bytes -> new SanitizedImage("image/webp", "webp", new byte[]{9, 8});
        return new CatalogAssetApplicationService(metadata, storage, sanitizer, audit);
    }

    private static final class MetadataFake implements CatalogAssetPort {
        private AssetMetadata saved;
        private boolean failSave;
        private final List<Long> deletedIds = new ArrayList<>();

        @Override
        public long save(AssetMetadata metadata) {
            if (failSave) {
                throw new IllegalStateException("database unavailable");
            }
            saved = metadata;
            return 41;
        }

        @Override
        public List<AssetMetadata> assets() {
            return List.of(existing());
        }

        @Override
        public AssetMetadata find(long assetId) {
            return existing();
        }

        @Override
        public void markDeleted(long assetId) {
            deletedIds.add(assetId);
        }

        private static AssetMetadata existing() {
            return new AssetMetadata(
                    41,
                    "catalog/object.webp",
                    "sha256",
                    "封面.jpg",
                    "image/webp",
                    2,
                    8,
                    Instant.parse("2026-07-26T00:00:00Z")
            );
        }
    }

    private static final class StorageFake implements CatalogAssetStoragePort {
        private String putMediaType;
        private byte[] putBytes;
        private final List<String> deletedKeys = new ArrayList<>();

        @Override
        public StoredAsset put(String originalFilename, String mediaType, byte[] bytes) {
            putMediaType = mediaType;
            putBytes = bytes;
            return new StoredAsset("catalog/object.webp", "sha256", bytes.length);
        }

        @Override
        public byte[] get(String objectKey) {
            return new byte[]{9, 8};
        }

        @Override
        public void deleteAsset(String objectKey) {
            deletedKeys.add(objectKey);
        }
    }

    private static final class AuditFake implements AdminAuditPort {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditView> search(AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(AuditQuery query) {
            return 0;
        }
    }
}
