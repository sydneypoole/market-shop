package com.marketshop.infrastructure.reliability;

import com.marketshop.application.membership.InactivityDowngradeParameters;
import com.marketshop.application.membership.RuleRuntimeResolver;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactiveMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class InactivityDowngradeProcessor {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final DistributionMapper mapper;

    public InactivityDowngradeProcessor(DistributionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public boolean processNext() {
        RuleRow rawRule = mapper.activeInactivityRuleVersion();
        if (rawRule == null) {
            return false;
        }
        InactivityDowngradeParameters rule = resolve(rawRule);
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusMonths(rule.inactiveMonths());
        InactiveMemberRow member = mapper.lockInactiveMember(
                rule.sourceLevel(),
                rule.targetLevel(),
                cutoff
        );
        if (member == null) {
            return false;
        }
        mapper.lockActiveInvitations(member.userId);
        if (mapper.downgradeInactiveMember(member.userId, rule.sourceLevel(), rule.targetLevel()) != 1) {
            throw new DomainException("INACTIVITY_DOWNGRADE_CONFLICT", "失活降级发生并发冲突");
        }
        revokeInvitationIfIneligible(member.userId, mapper.lockInvitationEligibility(member.userId));
        String reference = member.performanceReference == null
                ? "unknown"
                : member.performanceReference.toString();
        mapper.insertLevelChange(
                member.userId,
                member.beforeLevel,
                member.targetLevel,
                "INACTIVITY_DOWNGRADE",
                reference,
                rawRule.id,
                "SYSTEM",
                "inactivity-downgrade-job",
                "连续 " + rule.inactiveMonths() + " 个月无有效直属业绩",
                "inactivity:" + rawRule.id + ":" + member.userId + ":" + reference
        );
        return true;
    }

    private void revokeInvitationIfIneligible(long userId, InvitationEligibilityRow eligibility) {
        if (eligibility == null
                || !"ACTIVE".equals(eligibility.userStatus)
                || !"ACTIVE".equals(eligibility.levelStatus)
                || !Boolean.TRUE.equals(eligibility.invitationEnabled)) {
            mapper.revokeInvitations(userId);
        }
    }

    private InactivityDowngradeParameters resolve(RuleRow row) {
        if (!"DIVIDEND_INACTIVITY_DOWNGRADE".equals(row.ruleCode)
                || row.parametersJson == null) {
            throw new DomainException("RULE_RUNTIME_INVALID", "当前规则版本类型无效");
        }
        InactivityDowngradeParameters parameters = RuleRuntimeResolver.inactivityDowngrade(
                row.ruleCode, row.ruleType, row.parametersJson
        );
        if (mapper.activeMembershipLevelExists(parameters.sourceLevel()) == 0
                || mapper.activeMembershipLevelExists(parameters.targetLevel()) == 0) {
            throw new DomainException("RULE_TARGET_LEVEL_INVALID", "规则引用的会员等级不存在或未启用");
        }
        return parameters;
    }
}
