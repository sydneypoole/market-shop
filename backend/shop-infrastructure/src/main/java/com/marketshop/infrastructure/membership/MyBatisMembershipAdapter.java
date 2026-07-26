package com.marketshop.infrastructure.membership;

import com.marketshop.application.membership.MembershipPort;
import com.marketshop.application.membership.MembershipUseCase.DirectMemberView;
import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.LedgerEntryView;
import com.marketshop.application.membership.MembershipUseCase.ProfileView;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.application.membership.MembershipUseCase.RuleView;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerEntryRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MembershipProfileRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisMembershipAdapter implements MembershipPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final DistributionMapper mapper;

    public MyBatisMembershipAdapter(DistributionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProfileView profile(long userId) {
        MembershipProfileRow row = mapper.profile(userId);
        if (row == null) {
            throw new DomainException("MEMBERSHIP_NOT_FOUND", "会员账户不存在");
        }
        return new ProfileView(
                row.userId,
                row.nickname,
                row.levelCode,
                row.levelName,
                row.availablePoints,
                row.frozenPoints,
                row.qualifiedDirectCount
        );
    }

    @Override
    public InvitationView currentInvitation(long userId) {
        InvitationRow row = mapper.invitation(userId);
        return row == null ? null : invitation(row);
    }

    @Override
    @Transactional
    public InvitationView ensureInvitation(long userId) {
        MembershipProfileRow profile = mapper.profile(userId);
        if (profile == null || !Boolean.TRUE.equals(profile.invitationEnabled)) {
            throw new DomainException("INVITATION_NOT_ALLOWED", "当前会员等级尚未开放邀请功能");
        }
        InvitationRow row = mapper.invitation(userId);
        if (row == null) {
            String code = "MS" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 10).toUpperCase();
            mapper.insertInvitation(userId, code, LocalDateTime.now(BUSINESS_ZONE).plusDays(365));
            row = mapper.invitation(userId);
        }
        return invitation(row);
    }

    @Override
    @Transactional
    public void revokeInvitation(long userId) {
        mapper.revokeInvitations(userId);
    }

    @Override
    @Transactional
    public InvitationView regenerateInvitation(long userId, int validityDays) {
        MembershipProfileRow profile = mapper.profile(userId);
        if (profile == null || !Boolean.TRUE.equals(profile.invitationEnabled)) {
            throw new DomainException("INVITATION_NOT_ALLOWED", "当前会员等级尚未开放邀请功能");
        }
        mapper.revokeInvitations(userId);
        String code = "MS" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        mapper.insertInvitation(userId, code, LocalDateTime.now(BUSINESS_ZONE).plusDays(validityDays));
        return invitation(mapper.invitation(userId));
    }

    @Override
    public List<DirectMemberView> directMembers(long userId) {
        return mapper.directMembers(userId).stream().map(MyBatisMembershipAdapter::directMember).toList();
    }

    @Override
    public List<LedgerEntryView> ledger(long userId) {
        return mapper.ledger(userId).stream().map(MyBatisMembershipAdapter::ledgerEntry).toList();
    }

    @Override
    public List<RuleView> rules() {
        return mapper.rules().stream().map(MyBatisMembershipAdapter::rule).toList();
    }

    @Override
    @Transactional
    public RuleView publishRule(long adminId, PublishRuleCommand command) {
        LocalDateTime effective = LocalDateTime.ofInstant(command.effectiveFrom(), BUSINESS_ZONE);
        int version = mapper.maxRuleVersion(command.ruleCode()) + 1;
        mapper.supersedeRules(command.ruleCode(), effective);
        mapper.insertRule(
                command.ruleCode(),
                version,
                command.ruleType(),
                command.parametersJson(),
                effective,
                adminId
        );
        return rule(mapper.rule(mapper.lastInsertId()));
    }

    @Override
    @Transactional
    public void cancelRule(long adminId, long ruleId, String reason) {
        if (mapper.cancelFutureRule(ruleId) != 1) {
            throw new DomainException("RULE_CANCEL_NOT_ALLOWED", "仅可取消尚未生效的规则版本");
        }
    }

    private static InvitationView invitation(InvitationRow row) {
        if (row == null) {
            throw new DomainException("INVITATION_NOT_FOUND", "邀请码不存在");
        }
        return new InvitationView(
                row.code,
                row.status,
                row.useCount,
                "/login?inviteCode=" + row.code,
                instant(row.expiresAt)
        );
    }

    private static DirectMemberView directMember(DirectMemberRow row) {
        return new DirectMemberView(
                row.userId,
                row.publicId,
                row.nickname,
                row.levelName,
                row.completedOrdinal,
                row.performanceFen,
                row.performanceStatus
        );
    }

    private static LedgerEntryView ledgerEntry(LedgerEntryRow row) {
        return new LedgerEntryView(
                row.id,
                row.entryType,
                row.availableDelta,
                row.frozenDelta,
                row.sourceType,
                row.sourceId,
                instant(row.occurredAt)
        );
    }

    private static RuleView rule(RuleRow row) {
        if (row == null) {
            throw new DomainException("RULE_NOT_FOUND", "规则版本不存在");
        }
        return new RuleView(
                row.id,
                row.ruleCode,
                row.versionNo,
                row.ruleType,
                row.parametersJson,
                row.status,
                instant(row.effectiveFrom),
                instant(row.effectiveTo)
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }
}
