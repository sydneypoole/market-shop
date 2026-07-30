package com.marketshop.infrastructure.membership;

import com.marketshop.application.membership.MemberAdminPort;
import com.marketshop.application.membership.MemberAdminUseCase.EvidenceView;
import com.marketshop.application.membership.MemberAdminUseCase.LedgerView;
import com.marketshop.application.membership.MemberAdminUseCase.LevelChangeView;
import com.marketshop.application.membership.MemberAdminUseCase.MemberDetail;
import com.marketshop.application.membership.MemberAdminUseCase.MemberPage;
import com.marketshop.application.membership.MemberAdminUseCase.MemberQuery;
import com.marketshop.application.membership.MemberAdminUseCase.MemberSummary;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectRuleRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberAdminRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberLevelRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisMemberAdminAdapter implements MemberAdminPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final DistributionMapper mapper;

    public MyBatisMemberAdminAdapter(DistributionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MemberPage search(MemberQuery query) {
        int offset = Math.multiplyExact(query.page() - 1, query.size());
        List<MemberSummary> items = mapper.adminMembers(
                query.keyword(), query.levelCode(), query.status(), offset, query.size()
        ).stream().map(MyBatisMemberAdminAdapter::summary).toList();
        return new MemberPage(
                items,
                mapper.countAdminMembers(query.keyword(), query.levelCode(), query.status()),
                query.page(),
                query.size()
        );
    }

    @Override
    public MemberDetail detail(long userId) {
        MemberAdminRow row = mapper.adminMember(userId);
        if (row == null) {
            throw notFound();
        }
        return new MemberDetail(
                summary(row),
                mapper.memberEvidence(userId).stream().map(value -> new EvidenceView(
                        value.id, value.evidenceType, value.sourceOrderId, value.ruleVersionId,
                        value.valueJson, value.status, instant(value.createdAt), instant(value.invalidatedAt)
                )).toList(),
                mapper.memberLevelChanges(userId).stream().map(value -> new LevelChangeView(
                        value.id, value.beforeLevelCode, value.afterLevelCode, value.triggerType,
                        value.triggerId, value.ruleVersionId, value.actorType, value.actorId,
                        value.reason, instant(value.occurredAt)
                )).toList(),
                mapper.memberLedgerDetail(userId).stream().map(value -> new LedgerView(
                        value.id, value.entryType, value.availableDelta, value.frozenDelta,
                        value.sourceType, value.sourceId, value.sourceOrderId, value.ruleVersionId,
                        value.originalEntryId, value.frozenBatchId, value.frozenBatchOriginalPoints,
                        value.frozenBatchRemainingPoints, value.frozenBatchStatus, instant(value.occurredAt)
                )).toList()
        );
    }

    @Override
    public String status(long userId) {
        String status = mapper.memberStatus(userId);
        if (status == null) {
            throw notFound();
        }
        return status;
    }

    @Override
    public void updateStatus(long userId, String status) {
        if (mapper.updateMemberStatus(userId, status) != 1) {
            throw notFound();
        }
    }

    @Override
    @Transactional
    public LevelTransition recompute(long userId, long adminId, String reason, String requestId) {
        MemberLevelRow before = mapper.lockMemberLevel(userId);
        if (before == null) {
            throw notFound();
        }
        mapper.resetMemberToBasic(userId);
        String evidenceLevel = mapper.highestEvidenceLevel(userId);
        if (evidenceLevel != null) {
            mapper.promoteMember(userId, evidenceLevel);
        }
        DirectRuleRow directRule = mapper.activeDirectRule();
        if (directRule != null && mapper.activeDirectCount(userId) >= directRule.requiredCount) {
            mapper.promoteMember(userId, directRule.targetLevel);
        }
        MemberLevelRow after = mapper.lockMemberLevel(userId);
        if (!before.code.equals(after.code)) {
            mapper.insertLevelChange(
                    userId, before.code, after.code, "ADMIN_RECOMPUTE", requestId, null,
                    "ADMIN", Long.toString(adminId), reason, "manual-recompute:" + requestId
            );
        }
        return new LevelTransition(before.code, after.code);
    }

    private static MemberSummary summary(MemberAdminRow row) {
        return new MemberSummary(
                row.userId, row.publicId, row.nickname, row.status, row.levelCode, row.levelName,
                row.superiorUserId, row.directCount, row.qualifiedDirectCount,
                row.availablePoints, row.frozenPoints, instant(row.createdAt)
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static DomainException notFound() {
        return new DomainException("MEMBER_NOT_FOUND", "会员不存在");
    }
}
