package com.marketshop.application.membership;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleRuntimeResolverTest {

    @Test
    void publicationRequiresTheCanonicalCodeTypePairAndRejectsUnknownFields() {
        assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                "SUPER_MEMBER_UPGRADE",
                "DIRECT_REFERRAL_TASK",
                "{\"minimumCompletedOrderAmountFen\":199800,\"eligibleSalesScenes\":[\"UPGRADE\"],\"targetLevel\":\"SUPER_MEMBER\"}"
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.code()).isEqualTo("RULE_CODE_TYPE_INVALID"));

        assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                "SUPER_MEMBER_UPGRADE",
                "SELF_ORDER_TASK",
                "{\"minimumCompletedOrderAmountFen\":199800,\"eligibleSalesScenes\":[\"UPGRADE\"],\"targetLevel\":\"SUPER_MEMBER\",\"ignored\":true}"
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.code()).isEqualTo("RULE_PARAMETERS_INVALID"));
    }

    @Test
    void pointsRuleUsesQualificationAndPoolTotalsAsOneTypedContract() {
        RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodeForPublication(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}"
        );

        assertThat(decoded.parameters()).isInstanceOf(DirectReferralPointsParameters.class);
        DirectReferralPointsParameters parameters = (DirectReferralPointsParameters) decoded.parameters();
        assertThat(parameters.pointsStartOrdinal()).isGreaterThan(parameters.qualificationCount());
        assertThat(parameters.totalPoints()).isEqualTo(parameters.availableAPoints() + parameters.frozenBPoints());
        assertThat(parameters.maxRewardDepth()).isEqualTo(1);

        assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":6,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}"
        )).isInstanceOf(DomainException.class)
                .hasMessageContaining("pointsStartOrdinal");
    }

    @Test
    void legacyThreeFieldPointsShapeIsRejectedForPublication() {
        assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}"
        )).isInstanceOf(DomainException.class)
                .hasMessageContaining("规范必填参数");
    }

    @Test
    void persistedLegacyRowsAreRepairedOnlyInMemory() {
        RuleParameterCodec.Decoded timer = RuleParameterCodec.decodePersisted(
                "ORDER_TIMERS",
                "ORDER_TIMER",
                "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                        + "\"proofRetentionDays\":180,\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}"
        );
        assertThat(timer.repaired()).isTrue();
        assertThat(timer.normalizedJson()).contains("pendingSuperiorTimeoutDays");
        assertThat(((OrderTimerParameters) timer.parameters()).pendingShipmentTimeoutDays()).isEqualTo(7);

        RuleParameterCodec.Decoded points = RuleParameterCodec.decodePersisted(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}"
        );
        assertThat(points.repaired()).isTrue();
        assertThat(points.normalizedJson()).isEqualTo(
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}"
        );
    }

    @Test
    void persistedProofRetentionRepairsOnlyMissingOrOutOfRangeRetention() {
        String common = "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,";
        String[] persistedPayloads = {
                common + "\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}",
                common + "\"proofRetentionDays\":0,\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}",
                common + "\"proofRetentionDays\":3651,\"maxProofFiles\":3,\"maxProofSizeBytes\":8388608}"
        };

        for (String payload : persistedPayloads) {
            RuleParameterCodec.Decoded decoded = RuleParameterCodec.decodePersisted(
                    "ORDER_TIMERS", "ORDER_TIMER", payload
            );
            OrderTimerParameters parameters = (OrderTimerParameters) decoded.parameters();
            assertThat(decoded.repaired()).isTrue();
            assertThat(parameters.proofRetentionDays()).isEqualTo(180);
            assertThat(parameters.maxProofFiles()).isEqualTo(3);
            assertThat(parameters.maxProofSizeBytes()).isEqualTo(8_388_608L);

            OrderTimerParameters resolved = RuleRuntimeResolver.orderTimer(payload);
            assertThat(resolved.proofRetentionDays()).isEqualTo(180);
            assertThat(resolved.maxProofFiles()).isEqualTo(3);
            assertThat(resolved.maxProofSizeBytes()).isEqualTo(8_388_608L);

            assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                    "ORDER_TIMERS", "ORDER_TIMER", payload
            )).isInstanceOf(DomainException.class);
        }

        String invalidOtherField = common
                + "\"proofRetentionDays\":0,\"maxProofFiles\":21,\"maxProofSizeBytes\":8388608}";
        assertThatThrownBy(() -> RuleRuntimeResolver.orderTimer(invalidOtherField))
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ORDER_TIMER_SETTINGS_INVALID"));
    }

    @Test
    void integerRuleJsonRejectsFractionExponentAndDuplicateKeys() {
        for (String json : new String[]{
                "{\"maxProofFiles\":1.0}",
                "{\"maxProofFiles\":1e3}",
                "{\"maxProofFiles\":1,\"maxProofFiles\":2}"
        }) {
            assertThatThrownBy(() -> RuleParameterCodec.decodeForPublication(
                    "ORDER_TIMERS", "ORDER_TIMER", json
            )).isInstanceOf(DomainException.class);
        }

        RuleParameterCodec.Decoded valid = RuleParameterCodec.decodeForPublication(
                "DIRECT_REFERRAL_POINTS", "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,"
                        + "\"totalPoints\":9007199254740991,"
                        + "\"availableAPoints\":9007199254740991,\"frozenBPoints\":0,"
                        + "\"maxRewardDepth\":1,\"eligibleSalesScenes\":[\"UPGRADE\"]}"
        );
        assertThat(valid.parameters()).isInstanceOf(DirectReferralPointsParameters.class);
    }

    @Test
    void runtimeResolverFailsClosedForMalformedTimerJson() {
        assertThatThrownBy(() -> RuleRuntimeResolver.orderTimer("[]"))
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ORDER_TIMER_SETTINGS_INVALID"));
    }
}
