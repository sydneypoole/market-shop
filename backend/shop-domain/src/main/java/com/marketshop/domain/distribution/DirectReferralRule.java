package com.marketshop.domain.distribution;

import com.marketshop.domain.shared.DomainException;

public record DirectReferralRule(
        int qualificationCount,
        int pointsStartOrdinal,
        long totalPoints,
        long availableAPoints,
        long frozenBPoints
) {
    public DirectReferralRule {
        if (qualificationCount < 1
                || pointsStartOrdinal <= qualificationCount
                || totalPoints <= 0
                || availableAPoints < 0
                || frozenBPoints < 0
                || Math.addExact(availableAPoints, frozenBPoints) != totalPoints) {
            throw new DomainException("DISTRIBUTION_RULE_INVALID", "直推积分规则配置无效");
        }
    }

    public PointsAllocation allocate(int completedDirectReferralOrdinal) {
        if (completedDirectReferralOrdinal < pointsStartOrdinal) {
            return PointsAllocation.NONE;
        }
        return new PointsAllocation(availableAPoints, frozenBPoints);
    }
}
