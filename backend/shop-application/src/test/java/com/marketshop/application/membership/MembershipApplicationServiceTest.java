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
    void rejectsUnsafeOrderTimerLimits() {
        assertThatThrownBy(() -> service.validateRule(new PublishRuleCommand(
                "ORDER_TIMERS",
                "ORDER_TIMER",
                """
                        {
                          "autoReceiveDaysAfterShipment": 7,
                          "afterSaleDaysAfterCompletion": 7,
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

    private static final class CapturingPort implements MembershipPort {
        private PublishRuleCommand published;
        private List<RuleView> rules = List.of();

        @Override public ProfileView profile(long userId) { return null; }
        @Override public InvitationView currentInvitation(long userId) { return null; }
        @Override public InvitationView ensureInvitation(long userId) { return null; }
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
