package com.marketshop.application.membership;

import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.ProfileView;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.application.membership.MembershipUseCase.RuleView;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipApplicationServiceTest {

    private final CapturingPort port = new CapturingPort();
    private final MembershipApplicationService service = new MembershipApplicationService(port);

    @Test
    void currentInvitationIsReadOnlyAndPreservesTheNativeRegistrationPath() {
        InvitationView expected = new InvitationView(
                "INVITE +/?&",
                "ACTIVE",
                2,
                "/pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26",
                Instant.parse("2027-08-13T00:00:00Z")
        );
        port.currentInvitation = expected;

        InvitationView actual = service.currentInvitation(42L);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.registrationPath())
                .isEqualTo("/pages/register/register?inviteCode=INVITE%20%2B%2F%3F%26");
        assertThat(port.currentInvitationUserId).isEqualTo(42L);
        assertThat(port.ensureInvitationCalls).isZero();
    }

    @Test
    void validatesAndNormalizesPointsRuleBeforePublishing() {
        service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                Instant.parse("2026-08-01T00:00:00Z")
        ));

        assertThat(port.published.parametersJson())
                .isEqualTo("{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}");
    }

    @Test
    void refusesToPublishOverAnExistingMalformedCurrentVersion() {
        Instant now = Instant.now();
        port.rules = List.of(ruleWithParameters(
                40,
                "DIRECT_REFERRAL_POINTS",
                1,
                "DIRECT_REFERRAL_POINTS",
                "[]",
                now.minusSeconds(60),
                null
        ));

        assertThatThrownBy(() -> service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                now
        ))).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("RULE_CURRENT_INVALID");
        assertThat(port.published).isNull();
    }

    @Test
    void refusesToPublishWhenAFutureActiveVersionIsMalformed() {
        Instant now = Instant.now();
        port.rules = List.of(ruleWithParameters(
                42,
                "DIRECT_REFERRAL_POINTS",
                2,
                "DIRECT_REFERRAL_POINTS",
                "[]",
                now.plusSeconds(3600),
                null
        ));

        assertThatThrownBy(() -> service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                now
        ))).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("RULE_CURRENT_INVALID");
        assertThat(port.published).isNull();
    }

    @Test
    void permitsFirstPublicationWhenNoVersionExists() {
        service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                        + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                now()
        ));
        assertThat(port.published).isNotNull();
    }

    @Test
    void permitsFirstPublicationWhenTheServerHasNoCurrentVersion() {
        port.rules = List.of(ruleWithParameters(
                41,
                "DIRECT_REFERRAL_POINTS",
                1,
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                now().minusSeconds(3600),
                now().minusSeconds(60)
        ));

        service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                now()
        ));

        assertThat(port.published).isNotNull();
    }

    @Test
    void rejectsUnsafeOrderTimerLimits() {
        assertThatThrownBy(() -> service.validateOrderTimer(new PublishRuleCommand(
                "ORDER_TIMERS",
                "ORDER_TIMER",
                """
                        {
                          "autoReceiveDaysAfterShipment": 7,
                          "afterSaleDaysAfterCompletion": 7,
                          "pendingSuperiorTimeoutDays": 7,
                          "pendingAdminReviewTimeoutDays": 7,
                          "pendingShipmentTimeoutDays": 7,
                          "proofRetentionDays": 180,
                          "maxProofFiles": 99,
                          "maxProofSizeBytes": 8388608
                        }
                        """,
                null
        ))).isInstanceOf(DomainException.class)
                .hasMessageContaining("maxProofFiles");
    }

    @Test
    void rejectsOrderTimerMissingPendingTimeouts() {
        String missing = "{\"autoReceiveDaysAfterShipment\":7,"
                + "\"afterSaleDaysAfterCompletion\":7,"
                + "\"proofRetentionDays\":180,"
                + "\"maxProofFiles\":2,"
                + "\"maxProofSizeBytes\":8388608}";

        assertThatThrownBy(() -> service.validateOrderTimer(new PublishRuleCommand(
                "ORDER_TIMERS", "ORDER_TIMER", missing, null
        ))).isInstanceOf(DomainException.class)
                .hasMessageContaining("pendingSuperiorTimeoutDays");
    }

    @Test
    void rejectsFractionalJsonNumbersInsteadOfTruncatingThem() {
        String valid = "{\"autoReceiveDaysAfterShipment\":7,"
                + "\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,"
                + "\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,"
                + "\"proofRetentionDays\":180,"
                + "\"maxProofFiles\":2,"
                + "\"maxProofSizeBytes\":8388608}";
        String[] fractionalFields = {
                "autoReceiveDaysAfterShipment",
                "afterSaleDaysAfterCompletion",
                "pendingSuperiorTimeoutDays",
                "pendingAdminReviewTimeoutDays",
                "pendingShipmentTimeoutDays",
                "proofRetentionDays",
                "maxProofFiles",
                "maxProofSizeBytes"
        };
        for (String field : fractionalFields) {
            String candidate = valid.replace("\"" + field + "\":7", "\"" + field + "\":7.5")
                    .replace("\"" + field + "\":180", "\"" + field + "\":180.5")
                    .replace("\"" + field + "\":2", "\"" + field + "\":2.5")
                    .replace("\"" + field + "\":8388608", "\"" + field + "\":8388608.5");
            assertThatThrownBy(() -> service.validateOrderTimer(new PublishRuleCommand(
                    "ORDER_TIMERS", "ORDER_TIMER", candidate, null
            ))).as("fractional %s", field)
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(field);
        }
    }

    @Test
    void rejectsPointsTotalThatOverflowsLong() {
        assertThatThrownBy(() -> service.validateRule(new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"qualificationCount\":1,\"pointsStartOrdinal\":2,"
                        + "\"totalPoints\":9000000000000000000,"
                        + "\"availableAPoints\":9000000000000000000,"
                        + "\"frozenBPoints\":9000000000000000000,\"maxRewardDepth\":1,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"]}",
                null
        ))).isInstanceOf(DomainException.class)
                .hasMessageContaining("安全范围");
    }

    @Test
    void genericRuleEndpointsRejectOrderTimerEvenWhenCodeHasWhitespace() {
        PublishRuleCommand command = new PublishRuleCommand(
                "  ORDER_TIMERS  ",
                "ORDER_TIMER",
                "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                        + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                        + "\"pendingShipmentTimeoutDays\":7,"
                        + "\"proofRetentionDays\":180,\"maxProofFiles\":2,\"maxProofSizeBytes\":8388608}",
                null
        );

        assertThatThrownBy(() -> service.validateRule(command))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ORDER_TIMER_SETTINGS_ONLY");
        assertThatThrownBy(() -> service.publishRule(9, command))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("ORDER_TIMER_SETTINGS_ONLY");
        assertThat(port.published).isNull();
    }

    @Test
    void dedicatedOrderTimerEndpointNormalizesCodeBeforePublishing() {
        PublishRuleCommand command = new PublishRuleCommand(
                "  ORDER_TIMERS  ",
                "ORDER_TIMER",
                "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                        + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                        + "\"pendingShipmentTimeoutDays\":7,"
                        + "\"proofRetentionDays\":180,\"maxProofFiles\":2,\"maxProofSizeBytes\":8388608}",
                null
        );

        service.publishOrderTimer(9, command);

        assertThat(port.published).isNotNull();
        assertThat(port.published.ruleCode()).isEqualTo("ORDER_TIMERS");
        assertThat(port.published.ruleType()).isEqualTo("ORDER_TIMER");
    }

    @Test
    void rejectsDowngradeToSameLevel() {
        assertThatThrownBy(() -> service.validateRule(new PublishRuleCommand(
                "DOWNGRADE",
                "INACTIVITY_DOWNGRADE",
                """
                        {"inactiveMonths":5,"sourceLevel":"SUPER_MEMBER","targetLevel":"SUPER_MEMBER"}
                        """,
                null
        ))).isInstanceOf(DomainException.class)
                .hasMessageContaining("不能相同");
    }

    @Test
    void exposesOnlyTheLatestCurrentlyEffectiveRulePerCode() {
        Instant now = Instant.now();
        String points = "{\"qualificationCount\":5,\"pointsStartOrdinal\":6,\"totalPoints\":320,"
                + "\"availableAPoints\":160,\"frozenBPoints\":160,\"maxRewardDepth\":1,"
                + "\"eligibleSalesScenes\":[\"UPGRADE\"]}";
        String timer = "{\"autoReceiveDaysAfterShipment\":7,\"afterSaleDaysAfterCompletion\":7,"
                + "\"pendingSuperiorTimeoutDays\":7,\"pendingAdminReviewTimeoutDays\":7,"
                + "\"pendingShipmentTimeoutDays\":7,\"proofRetentionDays\":180,"
                + "\"maxProofFiles\":2,\"maxProofSizeBytes\":8388608}";
        String downgrade = "{\"inactiveMonths\":5,\"sourceLevel\":\"DIVIDEND_MEMBER\","
                + "\"targetLevel\":\"SUPER_MEMBER\"}";
        port.rules = List.of(
                rule(1, "DIRECT_REFERRAL_POINTS", 1, "DIRECT_REFERRAL_POINTS", points,
                        "ACTIVE", now.minusSeconds(3600), now.plusSeconds(3600)),
                rule(2, "DIRECT_REFERRAL_POINTS", 2, "DIRECT_REFERRAL_POINTS", points,
                        "ACTIVE", now.minusSeconds(60), null),
                rule(3, "DIRECT_REFERRAL_POINTS", 3, "DIRECT_REFERRAL_POINTS", points,
                        "ACTIVE", now.plusSeconds(3600), null),
                rule(4, "ORDER_TIMERS", 1, "ORDER_TIMER", timer,
                        "CANCELLED", now.minusSeconds(60), null),
                rule(5, "DIVIDEND_INACTIVITY_DOWNGRADE", 1, "INACTIVITY_DOWNGRADE", downgrade,
                        "ACTIVE", now.minusSeconds(60), null)
        );

        assertThat(service.activeRules())
                .extracting(RuleView::id)
                .containsExactly(2L, 5L);
    }

    @Test
    void targetLevelAuthoritySupportsCustomActiveLevelsAndRejectsInactiveOnes() {
        port.activeLevels = Set.of("CUSTOM_ACTIVE_LEVEL");
        service.validateRule(new PublishRuleCommand(
                "SUPER_MEMBER_UPGRADE",
                "SELF_ORDER_TASK",
                "{\"minimumCompletedOrderAmountFen\":199800,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"],"
                        + "\"targetLevel\":\"CUSTOM_ACTIVE_LEVEL\"}",
                null
        ));

        port.activeLevels = Set.of();
        assertThatThrownBy(() -> service.validateRule(new PublishRuleCommand(
                "SUPER_MEMBER_UPGRADE",
                "SELF_ORDER_TASK",
                "{\"minimumCompletedOrderAmountFen\":199800,"
                        + "\"eligibleSalesScenes\":[\"UPGRADE\"],"
                        + "\"targetLevel\":\"CUSTOM_ACTIVE_LEVEL\"}",
                null
        ))).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.code()).isEqualTo("RULE_TARGET_LEVEL_INVALID"));
    }

    @Test
    void activeRulesNeverExposeUnknownOrMismatchedActiveVersions() {
        Instant now = Instant.now();
        port.rules = List.of(
                new RuleView(90, "UNKNOWN_RULE", 1, "DIRECT_REFERRAL_POINTS",
                        "{}", "ACTIVE", now.minusSeconds(60), null)
        );

        assertThatThrownBy(service::activeRules)
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RULE_CURRENT_INVALID"));

        port.rules = List.of(
                new RuleView(91, "DIRECT_REFERRAL_POINTS", 1, "ORDER_TIMER",
                        "{}", "ACTIVE", now.minusSeconds(60), null)
        );
        assertThatThrownBy(service::activeRules)
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RULE_CURRENT_INVALID"));
    }

    @Test
    void currentOrderTimerWrapsMalformedPayloadWithStableSettingsError() {
        port.rules = List.of(new RuleView(
                92, "ORDER_TIMERS", 1, "ORDER_TIMER", "[]", "ACTIVE",
                Instant.now().minusSeconds(60), null
        ));

        assertThatThrownBy(service::currentOrderTimer)
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ORDER_TIMER_SETTINGS_INVALID"));
    }

    private static RuleView rule(long id, String code, int version, String type, String parameters,
                                 String status, Instant effectiveFrom, Instant effectiveTo) {
        return new RuleView(id, code, version, type, parameters, status, effectiveFrom, effectiveTo);
    }

    private static RuleView ruleWithParameters(long id, String code, int version, String type,
                                               String parameters, Instant effectiveFrom, Instant effectiveTo) {
        return new RuleView(
                id, code, version, type,
                parameters, "ACTIVE", effectiveFrom, effectiveTo
        );
    }

    private static Instant now() {
        return Instant.now();
    }

    private static final class CapturingPort implements MembershipPort {
        private PublishRuleCommand published;
        private List<RuleView> rules = List.of();
        private InvitationView currentInvitation;
        private long currentInvitationUserId;
        private int ensureInvitationCalls;
        private Set<String> activeLevels = Set.of(
                "BASIC", "EXPERIENCE_OFFICER", "SUPER_MEMBER", "DIVIDEND_MEMBER"
        );

        @Override public ProfileView profile(long userId) { return null; }
        @Override public InvitationView currentInvitation(long userId) {
            currentInvitationUserId = userId;
            return currentInvitation;
        }
        @Override public InvitationView ensureInvitation(long userId) {
            ensureInvitationCalls++;
            return null;
        }
        @Override public void revokeInvitation(long userId) { }
        @Override public InvitationView regenerateInvitation(long userId, int validityDays) { return null; }
        @Override public List<MembershipUseCase.DirectMemberView> directMembers(long userId) { return List.of(); }
        @Override public List<MembershipUseCase.LedgerEntryView> ledger(long userId) { return List.of(); }
        @Override public List<RuleView> rules() { return rules; }
        @Override public boolean activeMembershipLevelExists(String levelCode) {
            return activeLevels.contains(levelCode);
        }
        @Override public RuleView publishRule(long adminId, PublishRuleCommand command) {
            published = command;
            return null;
        }
        @Override public void cancelRule(long adminId, long ruleId, String reason) { }
    }
}
