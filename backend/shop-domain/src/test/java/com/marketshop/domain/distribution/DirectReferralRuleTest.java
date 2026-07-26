package com.marketshop.domain.distribution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectReferralRuleTest {

    private final DirectReferralRule rule = new DirectReferralRule(5, 6, 320, 160, 160);

    @Test
    void firstFiveOnlyQualifyAndSixthStartsPoints() {
        assertThat(rule.allocate(5)).isEqualTo(PointsAllocation.NONE);
        assertThat(rule.allocate(6)).isEqualTo(new PointsAllocation(160, 160));
        assertThat(rule.allocate(12).total()).isEqualTo(320);
    }
}
