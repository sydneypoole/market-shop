package com.marketshop.infrastructure.membership;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.invitation.FixedInvitationCodes;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisMembershipAdapterTest {

    @Test
    void invitationIssueLocksAndValidatesTheInviterBeforeReadingOrCreatingCode() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible());
        when(mapper.lockActiveInvitations(42L)).thenReturn(List.of());
        when(mapper.invitation(42L)).thenReturn(invitation("ACTIVE-CODE"));

        var result = new MyBatisMembershipAdapter(mapper).ensureInvitation(42L);

        assertThat(result.code()).isEqualTo("ACTIVE-CODE");
        var sequence = inOrder(mapper);
        sequence.verify(mapper).lockInvitationEligibility(42L);
        sequence.verify(mapper).lockActiveInvitations(42L);
        sequence.verify(mapper).invitation(42L);
        verify(mapper, never()).insertInvitation(eq(42L), anyString());
    }

    @Test
    void disabledOrDemotedInviterCannotIssueAnInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        InvitationEligibilityRow eligibility = eligible();
        eligibility.userStatus = "LOCKED";
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligibility);

        assertThatThrownBy(() -> new MyBatisMembershipAdapter(mapper).ensureInvitation(42L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITATION_NOT_ALLOWED"));
        verify(mapper, never()).invitation(42L);
        verify(mapper, never()).insertInvitation(eq(42L), anyString());

        eligibility = eligible();
        eligibility.levelStatus = "DISABLED";
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligibility);

        assertThatThrownBy(() -> new MyBatisMembershipAdapter(mapper).ensureInvitation(42L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITATION_NOT_ALLOWED"));

        eligibility = eligible();
        eligibility.invitationEnabled = false;
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligibility);

        assertThatThrownBy(() -> new MyBatisMembershipAdapter(mapper).ensureInvitation(42L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITATION_NOT_ALLOWED"));
    }

    @Test
    void ensureCreatesTheCodeOnlyAfterTheLockedEligibilityCheck() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible());
        when(mapper.lockActiveInvitations(42L)).thenReturn(List.of());
        when(mapper.invitation(42L)).thenReturn(null, invitation("CREATED-CODE"));
        when(mapper.insertInvitation(eq(42L), anyString())).thenReturn(1);

        var result = new MyBatisMembershipAdapter(mapper).ensureInvitation(42L);

        assertThat(result.code()).isEqualTo("CREATED-CODE");
        var sequence = inOrder(mapper);
        sequence.verify(mapper).lockInvitationEligibility(42L);
        sequence.verify(mapper).lockActiveInvitations(42L);
        sequence.verify(mapper).invitation(42L);
        sequence.verify(mapper).insertInvitation(eq(42L), anyString());
        sequence.verify(mapper).invitation(42L);
        assertThat(result.expiresAt()).isNull();
    }

    @Test
    void codeCollisionRetriesAreBoundedAndFailWithoutReturningAnInvitation() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        when(mapper.lockInvitationEligibility(42L)).thenReturn(eligible());
        when(mapper.lockActiveInvitations(42L)).thenReturn(List.of());

        assertThatThrownBy(() -> new MyBatisMembershipAdapter(mapper).ensureInvitation(42L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITATION_CREATE_FAILED"));

        verify(mapper, org.mockito.Mockito.times(FixedInvitationCodes.INSERT_ATTEMPTS))
                .insertInvitation(eq(42L), anyString());
    }

    private static InvitationEligibilityRow eligible() {
        InvitationEligibilityRow row = new InvitationEligibilityRow();
        row.userId = 42L;
        row.userStatus = "ACTIVE";
        row.levelStatus = "ACTIVE";
        row.invitationEnabled = true;
        return row;
    }

    private static InvitationRow invitation(String code) {
        InvitationRow row = new InvitationRow();
        row.code = code;
        row.status = "ACTIVE";
        row.useCount = 0;
        return row;
    }
}
