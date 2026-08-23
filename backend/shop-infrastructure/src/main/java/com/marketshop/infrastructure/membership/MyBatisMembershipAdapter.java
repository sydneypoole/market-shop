package com.marketshop.infrastructure.membership;

import com.marketshop.application.membership.MembershipPort;
import com.marketshop.application.membership.MembershipUseCase.DirectMemberView;
import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.LedgerEntryView;
import com.marketshop.application.membership.MembershipUseCase.ProfileView;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.application.membership.MembershipUseCase.RuleView;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.invitation.FixedInvitationCodes;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.DirectMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.LedgerEntryRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MembershipProfileRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
                row.avatarUrl,
                row.phoneMasked,
                instant(row.phoneVerifiedAt),
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
        requireInvitationEligibility(mapper.lockInvitationEligibility(userId));
        mapper.lockActiveInvitations(userId);
        InvitationRow row = mapper.invitation(userId);
        if (row == null) {
            insertFixedInvitation(userId);
            row = mapper.invitation(userId);
        }
        return invitation(row);
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
    public boolean activeMembershipLevelExists(String levelCode) {
        return levelCode != null && mapper.activeMembershipLevelExists(levelCode.trim()) > 0;
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

    private static void requireInvitationEligibility(InvitationEligibilityRow row) {
        if (row == null
                || !"ACTIVE".equals(row.userStatus)
                || !"ACTIVE".equals(row.levelStatus)
                || !Boolean.TRUE.equals(row.invitationEnabled)) {
            throw new DomainException("INVITATION_NOT_ALLOWED", "当前会员暂不具备邀请资格");
        }
    }

    private void insertFixedInvitation(long userId) {
        for (int attempt = 0; attempt < FixedInvitationCodes.INSERT_ATTEMPTS; attempt++) {
            if (mapper.insertInvitation(userId, FixedInvitationCodes.generate()) == 1) {
                return;
            }
        }
        throw new DomainException("INVITATION_CREATE_FAILED", "固定邀请码生成失败，请重试");
    }

    private static InvitationView invitation(InvitationRow row) {
        if (row == null) {
            throw new DomainException("INVITATION_NOT_FOUND", "邀请码不存在");
        }
        return new InvitationView(
                row.code,
                row.status,
                row.useCount,
                "/pages/register/register?inviteCode=" + encodeQueryComponent(row.code),
                instant(row.expiresAt)
        );
    }

    private static String encodeQueryComponent(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int character = current & 0xff;
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '.'
                    || character == '_' || character == '~') {
                encoded.append((char) character);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(character >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(character & 0x0f, 16)));
            }
        }
        return encoded.toString();
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
                row.sourceOrderId,
                row.ruleVersionId,
                row.originalEntryId,
                row.frozenBatchId,
                row.frozenBatchOriginalPoints,
                row.frozenBatchRemainingPoints,
                row.frozenBatchStatus,
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
