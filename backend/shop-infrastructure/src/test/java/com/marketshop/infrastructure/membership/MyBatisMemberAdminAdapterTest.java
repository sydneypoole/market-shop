package com.marketshop.infrastructure.membership;

import com.marketshop.application.membership.MemberAdminPort.LevelTransition;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberLevelRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisMemberAdminAdapterTest {

    @Test
    void disablingMemberPreservesTheFixedInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.updateMemberStatus(42L, "DISABLED")).thenReturn(1);

        new MyBatisMemberAdminAdapter(mapper).updateStatus(42L, "DISABLED");

        var sequence = inOrder(mapper);
        sequence.verify(mapper).lockInvitationEligibility(42L);
        sequence.verify(mapper).updateMemberStatus(42L, "DISABLED");
        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void lockingMemberAlsoPreservesTheFixedInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.updateMemberStatus(42L, "LOCKED")).thenReturn(1);

        new MyBatisMemberAdminAdapter(mapper).updateStatus(42L, "LOCKED");

        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void activatingMemberKeepsTheSameFixedInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.updateMemberStatus(42L, "ACTIVE")).thenReturn(1);

        new MyBatisMemberAdminAdapter(mapper).updateStatus(42L, "ACTIVE");

        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void levelChangePreservesTheFixedInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(false));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("DIVIDEND_MEMBER"));
        when(mapper.assignMemberLevel(42L, "BASIC")).thenReturn(1);

        new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "BASIC", 8, "人工降级", "req-demote");

        var sequence = inOrder(mapper);
        sequence.verify(mapper).lockInvitationEligibility(42L);
        sequence.verify(mapper).lockMemberLevel(42L);
        sequence.verify(mapper).assignMemberLevel(42L, "BASIC");
        sequence.verify(mapper).insertLevelChange(
                42L, "DIVIDEND_MEMBER", "BASIC", "ADMIN_ADJUST", "req-demote", null,
                "ADMIN", "8", "人工降级", "manual-level:req-demote"
        );
        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void eligibleLevelChangeDoesNotLockOrMutateInvitations() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("BASIC"));
        when(mapper.assignMemberLevel(42L, "SUPER_MEMBER")).thenReturn(1);
        new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "SUPER_MEMBER", 8, "人工升级", "req-promote");

        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void failedLevelMutationDoesNotTouchInvitations() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("DIVIDEND_MEMBER"));
        when(mapper.assignMemberLevel(42L, "BASIC")).thenReturn(0);

        assertThatThrownBy(() -> new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "BASIC", 8, "人工降级", "req-failed"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_LEVEL_INVALID"));
        verify(mapper, never()).lockActiveInvitations(42L);
    }

    @Test
    void assignLevelSameCodeDoesNotWriteAccountOrHistory() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("SUPER_MEMBER"));

        LevelTransition transition = new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "SUPER_MEMBER", 8, "重复调整", "req-same");

        assertThat(transition).isEqualTo(new LevelTransition("SUPER_MEMBER", "SUPER_MEMBER"));
        verify(mapper, never()).assignMemberLevel(anyLong(), anyString());
        verify(mapper, never()).insertLevelChange(
                anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void assignLevelMissingMemberIsNotFound() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockMemberLevel(42L)).thenReturn(null);

        assertThatThrownBy(() -> new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "BASIC", 8, "原因", "req-missing"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_NOT_FOUND"));
        verify(mapper, never()).assignMemberLevel(anyLong(), anyString());
    }

    @Test
    void assignLevelUnknownActiveLevelIsInvalid() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(true));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("BASIC"));
        when(mapper.assignMemberLevel(42L, "GHOST")).thenReturn(0);

        assertThatThrownBy(() -> new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "GHOST", 8, "原因", "req-ghost"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_LEVEL_INVALID"));
        verify(mapper, never()).insertLevelChange(
                anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void assignLevelDowngradeWritesAdminAdjustHistory() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible(false));
        when(mapper.lockMemberLevel(42L)).thenReturn(level("DIVIDEND_MEMBER"));
        when(mapper.assignMemberLevel(42L, "BASIC")).thenReturn(1);

        LevelTransition transition = new MyBatisMemberAdminAdapter(mapper)
                .assignLevel(42, "BASIC", 8, "人工降级", "req-9");

        assertThat(transition).isEqualTo(new LevelTransition("DIVIDEND_MEMBER", "BASIC"));
        verify(mapper).assignMemberLevel(42L, "BASIC");
        verify(mapper).insertLevelChange(
                42L, "DIVIDEND_MEMBER", "BASIC", "ADMIN_ADJUST", "req-9", null,
                "ADMIN", "8", "人工降级", "manual-level:req-9"
        );
    }

    private static MemberLevelRow level(String code) {
        MemberLevelRow row = new MemberLevelRow();
        row.code = code;
        return row;
    }

    private static InvitationEligibilityRow eligible(boolean invitationEnabled) {
        InvitationEligibilityRow row = new InvitationEligibilityRow();
        row.userStatus = "ACTIVE";
        row.levelStatus = "ACTIVE";
        row.invitationEnabled = invitationEnabled;
        return row;
    }
}
