package com.marketshop.application.aftersale;

import com.marketshop.application.aftersale.AfterSaleProofPort.Metadata;
import com.marketshop.application.aftersale.AfterSaleProofPort.UploadAccess;
import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.ProofMetadataPort;
import com.marketshop.application.proof.OrderProofPorts.ProofSanitizerPort;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSaleProofApplicationServiceTest {

    @Test
    void retentionReReadsExpiredProofUnderLockBeforeDeletingObject() {
        Metadata proof = proof(7);
        PortFake port = new PortFake(proof);
        port.expired = List.of(proof);
        StorageFake storage = new StorageFake();

        int cleaned = service(port, storage).cleanupExpired();

        assertThat(cleaned).isEqualTo(1);
        assertThat(port.findForUpdateCalls).containsExactly(7L);
        assertThat(storage.deletedKeys).containsExactly("after-sales/7/proof.png");
        assertThat(port.cleanedIds).containsExactly(7L);
    }

    @Test
    void retentionSkipsAProofAlreadyCleanedByAnotherWorker() {
        Metadata candidate = proof(8);
        PortFake port = new PortFake(candidate);
        port.expired = List.of(candidate);
        port.locked = null;

        assertThat(service(port, new StorageFake()).cleanupExpired()).isZero();
        assertThat(port.findForUpdateCalls).containsExactly(8L);
    }

    @Test
    void retentionPropagatesUnexpectedLockFailures() {
        Metadata candidate = proof(9);
        PortFake port = new PortFake(candidate);
        port.expired = List.of(candidate);
        port.lockFailure = new DomainException("AFTERSALE_STORAGE_CONFLICT", "冲突");

        assertThatThrownBy(() -> service(port, new StorageFake()).cleanupExpired())
                .isSameAs(port.lockFailure);
    }

    @Test
    void uploadLocksParentBeforeCountingAndRejectsTerminalStatus() {
        PortFake port = new PortFake(proof(10));
        port.uploadAccess = new UploadAccess(10, 20, "COMPLETED");

        assertThatThrownBy(() -> service(port, new StorageFake())
                .uploadUser(10, 10, "RETURN", new byte[]{1}))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_STATUS_CONFLICT"));
        assertThat(port.calls).containsExactly("lock");
    }

    @Test
    void uploadChecksTheCountWhileTheParentRowLockIsHeld() {
        PortFake port = new PortFake(proof(11));
        port.uploadAccess = new UploadAccess(10, 20, "PENDING_ADMIN_REVIEW");
        port.countValue = 3;

        assertThatThrownBy(() -> service(port, new StorageFake())
                .uploadUser(10, 11, "APPLICATION", new byte[]{1}))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PROOF_COUNT_EXCEEDED"));
        assertThat(port.calls).containsExactly("lock", "count");
    }

    @Test
    void downloadsReReadProofUnderLockBeforeSigningUrl() {
        PortFake port = new PortFake(proof(12));

        assertThat(service(port, new StorageFake()).userDownload(10, 12).signedUrl())
                .isEqualTo("unused");
        assertThat(port.findForUpdateCalls).containsExactly(12L);
    }

    private static AfterSaleProofApplicationService service(AfterSaleProofPort port,
                                                              PrivateObjectStoragePort storage) {
        ProofMetadataPort configuration = new ProofMetadataPort() {
            @Override public com.marketshop.application.proof.OrderProofPorts.OrderProofAccess orderAccess(long orderId) { return null; }
            @Override public boolean canUserAccessOrder(long userId, long orderId) { return false; }
            @Override public int countOrderProofs(long orderId) { return 0; }
            @Override public int maxFiles() { return 3; }
            @Override public long maxSizeBytes() { return 1024; }
            @Override public long retentionDays() { return 180; }
            @Override public long save(com.marketshop.application.proof.OrderProofPorts.ProofMetadata metadata) { return 0; }
            @Override public com.marketshop.application.proof.OrderProofPorts.ProofMetadata find(long proofId) { return null; }
            @Override public List<com.marketshop.application.proof.OrderProofPorts.ProofMetadata> listOrderProofs(long orderId) { return List.of(); }
            @Override public List<com.marketshop.application.proof.OrderProofPorts.ProofMetadata> findExpired(int limit) { return List.of(); }
            @Override public void markCleaned(long proofId) { }
        };
        ProofSanitizerPort sanitizer = bytes -> new com.marketshop.application.proof.OrderProofPorts.SanitizedImage(
                "image/png", "png", bytes
        );
        return new AfterSaleProofApplicationService(
                port, configuration, sanitizer, storage, new AuditFake(), 5, 0
        );
    }

    private static Metadata proof(long id) {
        Instant now = Instant.now();
        return new Metadata(
                id, id, "RETURN", "after-sales/" + id + "/proof.png", "sha256", "image/png",
                10, 10L, now.minusSeconds(1), now.minusSeconds(120), 10, 20
        );
    }

    private static final class PortFake implements AfterSaleProofPort {
        private final Metadata candidate;
        private List<Metadata> expired = List.of();
        private Metadata locked;
        private DomainException lockFailure;
        private UploadAccess uploadAccess = new UploadAccess(10, 20, "PENDING_ADMIN_REVIEW");
        private int countValue;
        private final List<String> calls = new ArrayList<>();
        private final List<Long> findForUpdateCalls = new ArrayList<>();
        private final List<Long> cleanedIds = new ArrayList<>();

        private PortFake(Metadata candidate) {
            this.candidate = candidate;
            this.locked = candidate;
        }

        @Override public UploadAccess lockForUpload(long afterSaleId) {
            calls.add("lock");
            return uploadAccess;
        }
        @Override public boolean canUserAccess(long userId, long afterSaleId) { return true; }
        @Override public int count(long afterSaleId) { calls.add("count"); return countValue; }
        @Override public long save(Metadata metadata) { return metadata.id(); }
        @Override public List<Metadata> list(long afterSaleId) { return List.of(candidate); }
        @Override public Metadata find(long proofId) { return candidate; }
        @Override public Metadata findForUpdate(long proofId) {
            findForUpdateCalls.add(proofId);
            if (lockFailure != null) throw lockFailure;
            if (locked == null) {
                throw new DomainException("AFTERSALE_PROOF_NOT_FOUND", "已清理");
            }
            return locked;
        }
        @Override public List<Metadata> expired(int limit) { return expired; }
        @Override public void markCleaned(long proofId) { cleanedIds.add(proofId); }
    }

    private static final class StorageFake implements PrivateObjectStoragePort {
        private final List<String> deletedKeys = new ArrayList<>();
        @Override public StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes) {
            return new StoredObject("unused", "unused", bytes.length);
        }
        @Override public String signedGetUrl(String objectKey, Duration duration) { return "unused"; }
        @Override public void delete(String objectKey) { deletedKeys.add(objectKey); }
    }

    private static final class AuditFake implements AdminAuditPort {
        @Override public void record(AuditRecord record) { }
        @Override public List<AuditView> search(AuditQuery query) { return List.of(); }
        @Override public long count(AuditQuery query) { return 0; }
    }
}
