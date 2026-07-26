package com.marketshop.infrastructure.reliability;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactiveMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactivityRuleRow;
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
        InactivityRuleRow rule = mapper.activeInactivityRule();
        if (rule == null || rule.inactiveMonths == null || rule.inactiveMonths < 1) {
            return false;
        }
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusMonths(rule.inactiveMonths);
        InactiveMemberRow member = mapper.lockInactiveMember(
                rule.sourceLevel,
                rule.targetLevel,
                cutoff
        );
        if (member == null) {
            return false;
        }
        if (mapper.downgradeInactiveMember(member.userId, rule.sourceLevel, rule.targetLevel) != 1) {
            throw new DomainException("INACTIVITY_DOWNGRADE_CONFLICT", "失活降级发生并发冲突");
        }
        String reference = member.performanceReference == null
                ? "unknown"
                : member.performanceReference.toString();
        mapper.insertLevelChange(
                member.userId,
                member.beforeLevel,
                member.targetLevel,
                "INACTIVITY_DOWNGRADE",
                reference,
                rule.id,
                "SYSTEM",
                "inactivity-downgrade-job",
                "连续 " + rule.inactiveMonths + " 个月无有效直属业绩",
                "inactivity:" + rule.id + ":" + member.userId + ":" + reference
        );
        return true;
    }
}
