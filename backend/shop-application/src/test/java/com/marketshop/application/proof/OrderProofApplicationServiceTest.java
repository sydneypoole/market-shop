package com.marketshop.application.proof;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadata;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadataPort;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.application.proof.OrderProofPorts.SanitizedImage;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderProofApplicationServiceTest {

    @Test
    void refusesSignedUrlForUserOutsideTheOrderRelationship() {
        var metadata = new MetadataFake(proof());
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit, 5);

        assertThatThrownBy(() -> service.userDownload(30, 1))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("无权");
        assertThat(storage.signedDurations).isEmpty();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void recordsTheRealAdminIdWhenIssuingSignedUrl() {
        var metadata = new MetadataFake(proof());
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit, 5);

        var result = service.adminDownload(77, 1);

        assertThat(result.signedUrl()).isEqualTo("http://signed.test/object");
        assertThat(storage.signedDurations).containsExactly(Duration.ofMinutes(5));
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.actorType()).isEqualTo("ADMIN");
            assertThat(record.actorId()).isEqualTo("77");
            assertThat(record.action()).isEqualTo("PROOF_DOWNLOAD");
        });
    }

    @Test
    void listsProofsOnlyForUsersInTheOrderRelationship() {
        var metadata = new MetadataFake(proof());
        var audit = new AuditFake();
        var service = service(metadata, new StorageFake(), audit, 5);

        assertThat(service.listUser(10, 100)).singleElement().satisfies(value -> {
            assertThat(value.proofId()).isEqualTo(1);
            assertThat(value.orderId()).isEqualTo(100);
        });
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.actorType()).isEqualTo("USER");
            assertThat(record.actorId()).isEqualTo("10");
            assertThat(record.action()).isEqualTo("PROOF_LIST");
        });

        assertThatThrownBy(() -> service.listUser(30, 100))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void capsSignedUrlLifetimeAtOneHour() {
        var metadata = new MetadataFake(proof());
        var storage = new StorageFake();
        var service = service(metadata, storage, new AuditFake(), 120);

        service.adminDownload(77, 1);

        assertThat(storage.signedDurations).containsExactly(Duration.ofHours(1));
    }

    @Test
    void rejectsOversizedBytesBeforeCallingObjectStorage() {
        var metadata = new MetadataFake(proof());
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit, 5);

        assertThatThrownBy(() -> service.upload(
                10,
                new OrderProofUseCase.UploadCommand(100, "proof.png", "image/png", new byte[1025])
        ))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("大小");
        assertThat(storage.putCalls).isZero();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void rejectsUploadWhenConfiguredFileCountIsReached() {
        var metadata = new MetadataFake(proof());
        metadata.activeCount = 3;
        var storage = new StorageFake();
        var service = service(metadata, storage, new AuditFake(), 5);

        assertThatThrownBy(() -> service.upload(
                10,
                new OrderProofUseCase.UploadCommand(100, "proof.png", "image/png", new byte[10])
        ))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("数量");
        assertThat(storage.putCalls).isZero();
    }

    @Test
    void cleanupDeletesObjectMarksMetadataAndAuditsSystemActor() {
        var metadata = new MetadataFake(proof());
        metadata.expired = List.of(proof());
        var storage = new StorageFake();
        var audit = new AuditFake();
        var service = service(metadata, storage, audit, 5);

        assertThat(service.cleanupExpired()).isEqualTo(1);
        assertThat(storage.deletedKeys).containsExactly("orders/1/proof.png");
        assertThat(metadata.cleanedIds).containsExactly(1L);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.actorType()).isEqualTo("SYSTEM");
            assertThat(record.action()).isEqualTo("PROOF_DELETE");
        });
    }

    private static OrderProofApplicationService service(
            ProofMetadataPort metadata,
            PrivateObjectStoragePort storage,
            AdminAuditPort audit,
            long signedUrlMinutes
    ) {
        ProofSanitizerPort sanitizer = bytes -> new SanitizedImage("image/png", "png", bytes);
        return new OrderProofApplicationService(metadata, storage, sanitizer, audit, signedUrlMinutes);
    }

    private static ProofMetadata proof() {
        Instant now = Instant.now();
        return new ProofMetadata(
                1, 100, "orders/1/proof.png", "abc", "image/png", 10,
                10, now.plusSeconds(60), now, 10, 20, "PENDING_SUPERIOR_CONFIRMATION"
        );
    }

    private static final class MetadataFake implements ProofMetadataPort {
        private final ProofMetadata proof;
        private List<ProofMetadata> expired = List.of();
        private final List<Long> cleanedIds = new ArrayList<>();
        private int activeCount;

        private MetadataFake(ProofMetadata proof) {
            this.proof = proof;
        }

        @Override
        public boolean canUserAccessOrder(long userId, long orderId) {
            return userId == proof.buyerUserId() || userId == proof.superiorUserId();
        }

        @Override
        public int countOrderProofs(long orderId) {
            return activeCount;
        }

        @Override
        public int maxFiles() {
            return 3;
        }

        @Override
        public long maxSizeBytes() {
            return 1024;
        }

        @Override
        public long retentionDays() {
            return 180;
        }

        @Override
        public long save(ProofMetadata metadata) {
            return proof.id();
        }

        @Override
        public ProofMetadata find(long proofId) {
            return proof;
        }

        @Override
        public List<ProofMetadata> listOrderProofs(long orderId) {
            return List.of(proof);
        }

        @Override
        public List<ProofMetadata> findExpired(int limit) {
            return expired;
        }

        @Override
        public void markCleaned(long proofId) {
            cleanedIds.add(proofId);
        }
    }

    private static final class StorageFake implements PrivateObjectStoragePort {
        private final List<Duration> signedDurations = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private int putCalls;

        @Override
        public StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes) {
            putCalls++;
            return new StoredObject("orders/1/proof.png", "abc", bytes.length);
        }

        @Override
        public String signedGetUrl(String objectKey, Duration duration) {
            signedDurations.add(duration);
            return "http://signed.test/object";
        }

        @Override
        public void delete(String objectKey) {
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
