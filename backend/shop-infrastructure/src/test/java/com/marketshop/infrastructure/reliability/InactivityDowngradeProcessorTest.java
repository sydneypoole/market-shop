package com.marketshop.infrastructure.reliability;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InactiveMemberRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InactivityDowngradeProcessorTest {

    @Mock
    private DistributionMapper mapper;

    @InjectMocks
    private InactivityDowngradeProcessor processor;

    @Test
    void safelyStopsWhenNoMemberNeedsDowngrade() {
        RuleRow rule = rule();
        when(mapper.activeInactivityRuleVersion()).thenReturn(rule);
        when(mapper.activeMembershipLevelExists(any())).thenReturn(1);
        when(mapper.lockInactiveMember(eq("DIVIDEND_MEMBER"), eq("SUPER_MEMBER"), any()))
                .thenReturn(null);

        assertThat(processor.processNext()).isFalse();

        verify(mapper, never()).downgradeInactiveMember(any(Long.class), any(), any());
    }

    @Test
    void downgradesAndRecordsStableIdempotencyKeyOnce() {
        RuleRow rule = rule();
        InactiveMemberRow member = new InactiveMemberRow();
        member.userId = 42L;
        member.beforeLevel = "DIVIDEND_MEMBER";
        member.targetLevel = "SUPER_MEMBER";
        member.performanceReference = LocalDateTime.of(2026, 1, 1, 12, 0);
        when(mapper.activeInactivityRuleVersion()).thenReturn(rule);
        when(mapper.activeMembershipLevelExists(any())).thenReturn(1);
        when(mapper.lockInactiveMember(eq("DIVIDEND_MEMBER"), eq("SUPER_MEMBER"), any()))
                .thenReturn(member);
        when(mapper.downgradeInactiveMember(42, "DIVIDEND_MEMBER", "SUPER_MEMBER")).thenReturn(1);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(activeEligibility());

        assertThat(processor.processNext()).isTrue();

        verify(mapper).insertLevelChange(
                42, "DIVIDEND_MEMBER", "SUPER_MEMBER", "INACTIVITY_DOWNGRADE",
                "2026-01-01T12:00", 41L, "SYSTEM", "inactivity-downgrade-job",
                "连续 5 个月无有效直属业绩", "inactivity:41:42:2026-01-01T12:00"
        );
        verify(mapper, never()).revokeInvitations(42L);
        var sequence = inOrder(mapper);
        sequence.verify(mapper).lockInactiveMember(eq("DIVIDEND_MEMBER"), eq("SUPER_MEMBER"), any());
        sequence.verify(mapper).lockActiveInvitations(42L);
        sequence.verify(mapper).downgradeInactiveMember(42, "DIVIDEND_MEMBER", "SUPER_MEMBER");
        sequence.verify(mapper).lockInvitationEligibility(42L);
    }

    @Test
    void downgradeToNonInvitingLevelRevokesOutstandingInvitations() {
        RuleRow rule = rule();
        rule.parametersJson = "{\"inactiveMonths\":5,\"sourceLevel\":\"DIVIDEND_MEMBER\","
                + "\"targetLevel\":\"BASIC\"}";
        InactiveMemberRow member = new InactiveMemberRow();
        member.userId = 42L;
        member.beforeLevel = "DIVIDEND_MEMBER";
        member.targetLevel = "BASIC";
        when(mapper.activeInactivityRuleVersion()).thenReturn(rule);
        when(mapper.activeMembershipLevelExists(any())).thenReturn(1);
        when(mapper.lockInactiveMember(eq("DIVIDEND_MEMBER"), eq("BASIC"), any()))
                .thenReturn(member);
        when(mapper.downgradeInactiveMember(42, "DIVIDEND_MEMBER", "BASIC")).thenReturn(1);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(ineligibleEligibility());

        assertThat(processor.processNext()).isTrue();

        verify(mapper).revokeInvitations(42L);
    }

    @Test
    void concurrentDowngradeConflictRollsBackInsteadOfDuplicatingHistory() {
        RuleRow rule = rule();
        InactiveMemberRow member = new InactiveMemberRow();
        member.userId = 42L;
        member.beforeLevel = "DIVIDEND_MEMBER";
        member.targetLevel = "SUPER_MEMBER";
        when(mapper.activeInactivityRuleVersion()).thenReturn(rule);
        when(mapper.activeMembershipLevelExists(any())).thenReturn(1);
        when(mapper.lockInactiveMember(eq("DIVIDEND_MEMBER"), eq("SUPER_MEMBER"), any()))
                .thenReturn(member);
        when(mapper.downgradeInactiveMember(42, "DIVIDEND_MEMBER", "SUPER_MEMBER")).thenReturn(0);

        assertThatThrownBy(processor::processNext)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("并发冲突");

        verify(mapper, never()).insertLevelChange(
                any(Long.class), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    private static RuleRow rule() {
        RuleRow rule = new RuleRow();
        rule.id = 41L;
        rule.ruleCode = "DIVIDEND_INACTIVITY_DOWNGRADE";
        rule.ruleType = "INACTIVITY_DOWNGRADE";
        rule.parametersJson = "{\"inactiveMonths\":5,\"sourceLevel\":\"DIVIDEND_MEMBER\","
                + "\"targetLevel\":\"SUPER_MEMBER\"}";
        return rule;
    }

    private static InvitationEligibilityRow activeEligibility() {
        InvitationEligibilityRow row = new InvitationEligibilityRow();
        row.userStatus = "ACTIVE";
        row.levelStatus = "ACTIVE";
        row.invitationEnabled = true;
        return row;
    }

    private static InvitationEligibilityRow ineligibleEligibility() {
        InvitationEligibilityRow row = activeEligibility();
        row.invitationEnabled = false;
        return row;
    }
}
