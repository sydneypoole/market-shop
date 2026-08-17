package com.marketshop.application.membership;

import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.ProfileView;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.application.membership.MembershipUseCase.RuleView;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
                """
                        {
                          "pointsStartOrdinal": 6,
                          "availableAPoints": 160,
                          "frozenBPoints": 160
                        }
                        """,
                Instant.parse("2026-08-01T00:00:00Z")
        ));

        assertThat(port.published.parametersJson())
                .isEqualTo("{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}");
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
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}",
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
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}",
                now
        ))).isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("RULE_CURRENT_INVALID");
        assertThat(port.published).isNull();
    }

    @Test
    void permitsFirstPublicationWhenTheServerHasNoCurrentVersion() {
        port.rules = List.of(ruleWithParameters(
                41,
                "DIRECT_REFERRAL_POINTS",
                1,
                "DIRECT_REFERRAL_POINTS",
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}",
                now().minusSeconds(3600),
                now().minusSeconds(60)
        ));

        service.publishRule(9, new PublishRuleCommand(
                "DIRECT_REFERRAL_POINTS",
                "DIRECT_REFERRAL_POINTS",
                "{\"pointsStartOrdinal\":6,\"availableAPoints\":160,\"frozenBPoints\":160}",
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
                "{\"pointsStartOrdinal\":1,\"availableAPoints\":9000000000000000000,"
                        + "\"frozenBPoints\":9000000000000000000}",
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
        port.rules = List.of(
                rule(1, "POINTS", 1, "ACTIVE", now.minusSeconds(3600), now.plusSeconds(3600)),
                rule(2, "POINTS", 2, "ACTIVE", now.minusSeconds(60), null),
                rule(3, "POINTS", 3, "ACTIVE", now.plusSeconds(3600), null),
                rule(4, "TIMER", 1, "CANCELLED", now.minusSeconds(60), null),
                rule(5, "DOWNGRADE", 1, "ACTIVE", now.minusSeconds(60), null)
        );

        assertThat(service.activeRules())
                .extracting(RuleView::id)
                .containsExactly(2L, 5L);
    }

    private static RuleView rule(long id, String code, int version, String status,
                                 Instant effectiveFrom, Instant effectiveTo) {
        return new RuleView(
                id, code, version, "DIRECT_REFERRAL_POINTS",
                "{}", status, effectiveFrom, effectiveTo
        );
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
        @Override public RuleView publishRule(long adminId, PublishRuleCommand command) {
            published = command;
            return null;
        }
        @Override public void cancelRule(long adminId, long ruleId, String reason) { }
    }
}
