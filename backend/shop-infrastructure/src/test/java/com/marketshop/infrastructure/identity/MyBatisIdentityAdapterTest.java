package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationEligibilityRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationOwnerRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.MemberProfileRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.SponsorClaimRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserLoginRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisIdentityAdapterTest {

    private static final String CLAIM_HASH = "a".repeat(64);
    @Mock
    private IdentityMapper mapper;

    @Test
    void miniprogramCanClaimTheExistingSponsorWithoutCreatingASelfRelation() {
        String provider = "WECHAT_MP";
        SponsorClaimRow claim = pendingClaim();
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(claim);
        when(mapper.claimBootstrapSponsor(9L, 3, CLAIM_HASH, provider, "app-fixture"))
                .thenReturn(1);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var result = adapter.findOrRegister(identity(provider), null, CLAIM_HASH);

        assertThat(result.userId()).isEqualTo(41L);
        assertThat(result.newlyRegistered()).isFalse();
        assertThat(result.sponsorClaimed()).isTrue();
        assertThat(result.authEpoch()).isEqualTo(6L);
        ArgumentCaptor<ExternalIdentityPo> external = ArgumentCaptor.forClass(ExternalIdentityPo.class);
        verify(mapper).insertExternalIdentity(external.capture());
        assertThat(external.getValue().userId).isEqualTo(41L);
        assertThat(external.getValue().provider).isEqualTo(provider);
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).insertRelation(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verify(mapper, never()).incrementInvitation(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void mockProviderCanNeverUseTheSponsorClaimPath() {
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(identity("WECHAT_MOCK"), null, CLAIM_HASH))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("SPONSOR_CLAIM_PROVIDER_INVALID");
        verifyNoInteractions(mapper);
    }

    @Test
    void unresolvedBootstrapRepairBlocksOnlyNewInvitationRegistration() {
        when(mapper.countUnresolvedBootstrapInvitationRepairs()).thenReturn(1);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(
                identity("WECHAT_MP"), "ORDINARY-INVITE", null
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("BOOTSTRAP_INVITATION_REPAIR_REQUIRED"));
        var sequence = inOrder(mapper);
        sequence.verify(mapper).findUserByExternal("WECHAT_MP", "app-fixture", "fixture-open");
        sequence.verify(mapper).findUserByUnionId("fixture-union");
        sequence.verify(mapper).countUnresolvedBootstrapInvitationRepairs();
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void unresolvedBootstrapRepairDoesNotBreakExistingIdentityIdempotency() {
        UserLoginRow existing = new UserLoginRow();
        existing.id = 88L;
        existing.publicId = "EXISTING-PUBLIC-ID";
        existing.nickname = "已有会员";
        existing.status = "ACTIVE";
        when(mapper.findUserByExternal("WECHAT_MP", "app-fixture", "fixture-open"))
                .thenReturn(existing);
        var result = new MyBatisIdentityAdapter(mapper).findOrRegister(
                identity("WECHAT_MP"), "IGNORED", null
        );

        assertThat(result.newlyRegistered()).isFalse();
        verify(mapper, never()).countUnresolvedBootstrapInvitationRepairs();
    }

    @Test
    void unresolvedBootstrapRepairDoesNotBreakSeparateSponsorClaimPath() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(pendingClaim());
        when(mapper.claimBootstrapSponsor(9L, 3, CLAIM_HASH, "WECHAT_MP", "app-fixture"))
                .thenReturn(1);
        var result = new MyBatisIdentityAdapter(mapper).findOrRegister(
                identity("WECHAT_MP"), null, CLAIM_HASH
        );

        assertThat(result.sponsorClaimed()).isTrue();
        verify(mapper, never()).countUnresolvedBootstrapInvitationRepairs();
    }

    @Test
    void ordinaryBootstrapInvitationAlwaysRegistersADirectChildAndNeverClaimsSponsor() {
        InvitationRow invitation = activeInvitation();
        stubInvitation(mapper, "NORMAL-INVITE-CODE", invitation, activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocation -> {
            UserAccountPo user = invocation.getArgument(0);
            user.id = 72L;
            return 1;
        });
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var result = adapter.findOrRegister(
                identity("WECHAT_MP"), "NORMAL-INVITE-CODE", null
        );

        assertThat(result.userId()).isEqualTo(72L);
        assertThat(result.newlyRegistered()).isTrue();
        assertThat(result.sponsorClaimed()).isFalse();
        verify(mapper, never()).lockSponsorClaim(any());
        verify(mapper).insertRelation(72L, 41L, 12L);
        verify(mapper).incrementInvitation(12L);
        var sequence = inOrder(mapper);
        sequence.verify(mapper).findInvitationOwner("NORMAL-INVITE-CODE");
        sequence.verify(mapper).lockInviterEligibility(41L);
        sequence.verify(mapper).lockInvitation("NORMAL-INVITE-CODE");
    }

    @Test
    void ordinaryExplicitMaxUsesInvitationStillUsesOrdinaryIncrement() {
        InvitationRow invitation = activeInvitation();
        invitation.maxUses = 1;
        stubInvitation(mapper, "ORDINARY-LIMITED", invitation, activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocationCall -> {
            UserAccountPo user = invocationCall.getArgument(0);
            user.id = 78L;
            return 1;
        });

        var result = new MyBatisIdentityAdapter(mapper).findOrRegister(
                identity("WECHAT_MP"), "ORDINARY-LIMITED", null
        );

        assertThat(result.newlyRegistered()).isTrue();
        verify(mapper).incrementInvitation(12L);
        verify(mapper, never()).consumeBootstrapInvitation(anyLong());
    }

    @Test
    void bootstrapInvitationUsesOneConditionalTerminalConsumeInsteadOfOrdinaryIncrement() {
        InvitationRow invitation = bootstrapInvitation();
        stubInvitation(mapper, "BOOTSTRAP-INVITE", invitation, activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocationCall -> {
            UserAccountPo user = invocationCall.getArgument(0);
            user.id = 76L;
            return 1;
        });
        when(mapper.consumeBootstrapInvitation(12L)).thenReturn(1);

        var result = new MyBatisIdentityAdapter(mapper).findOrRegister(
                identity("WECHAT_MP"), "BOOTSTRAP-INVITE", null
        );

        assertThat(result.newlyRegistered()).isTrue();
        verify(mapper).consumeBootstrapInvitation(12L);
        verify(mapper, never()).incrementInvitation(anyLong());
    }

    @Test
    void failedBootstrapTerminalConsumeReturnsAStableExhaustedError() {
        InvitationRow invitation = bootstrapInvitation();
        stubInvitation(mapper, "BOOTSTRAP-RACE", invitation, activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocationCall -> {
            UserAccountPo user = invocationCall.getArgument(0);
            user.id = 77L;
            return 1;
        });
        when(mapper.consumeBootstrapInvitation(12L)).thenReturn(0);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper).findOrRegister(
                identity("WECHAT_MP"), "BOOTSTRAP-RACE", null
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_EXHAUSTED"));
        verify(mapper, never()).incrementInvitation(anyLong());
    }

    @Test
    void invitationWithoutIdFailsClosedBeforeRegistrationWrites() {
        InvitationRow invitation = bootstrapInvitation();
        invitation.id = null;
        stubInvitation(mapper, "MALFORMED-ID", invitation, activeEligibility());

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "MALFORMED-ID", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));
        verify(mapper, never()).insertUser(any());
    }

    @Test
    void invitationWithoutUseCountFailsClosedBeforeRegistrationWrites() {
        InvitationRow invitation = bootstrapInvitation();
        invitation.useCount = null;
        stubInvitation(mapper, "MALFORMED-USE-COUNT", invitation, activeEligibility());

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "MALFORMED-USE-COUNT", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));
        verify(mapper, never()).insertUser(any());
    }

    @Test
    void userInsertWithoutAnAffectedRowFailsClosedBeforeIdentitySideEffects() {
        stubInvitation(mapper, "MISSING-USER-ROW", activeInvitation(), activeEligibility());

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "MISSING-USER-ROW", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Member user was not created");
        verify(mapper).insertUser(any());
        verify(mapper, never()).insertExternalIdentity(any());
        verify(mapper, never()).insertUnionPrincipal(any(), anyLong());
        verify(mapper, never()).insertCustomerProfile(anyLong());
        verify(mapper, never()).insertRelation(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).insertBasicMembership(anyLong());
        verify(mapper, never()).insertLedgerAccount(anyLong());
        verify(mapper, never()).incrementInvitation(anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(anyLong());
    }

    @Test
    void userInsertWithoutAGeneratedIdFailsClosedBeforeIdentitySideEffects() {
        stubInvitation(mapper, "MISSING-USER-ID", activeInvitation(), activeEligibility());
        when(mapper.insertUser(any())).thenReturn(1);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "MISSING-USER-ID", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Member user was not created");
        verify(mapper).insertUser(any());
        verify(mapper, never()).insertExternalIdentity(any());
        verify(mapper, never()).insertUnionPrincipal(any(), anyLong());
        verify(mapper, never()).insertCustomerProfile(anyLong());
        verify(mapper, never()).insertRelation(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).insertBasicMembership(anyLong());
        verify(mapper, never()).insertLedgerAccount(anyLong());
        verify(mapper, never()).incrementInvitation(anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(anyLong());
    }

    @Test
    void disabledInviterCannotConsumeAnOtherwiseActiveInvitation() {
        InvitationRow invitation = activeInvitation();
        InvitationEligibilityRow eligibility = activeEligibility();
        eligibility.userStatus = "DISABLED";
        stubInvitation(mapper, "DISABLED-INVITE", invitation, eligibility);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "DISABLED-INVITE", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).insertRelation(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).incrementInvitation(anyLong());
    }

    @Test
    void inactiveOrNonInvitingLevelCannotConsumeAnOtherwiseActiveInvitation() {
        InvitationRow inactiveLevel = activeInvitation();
        InvitationEligibilityRow inactiveEligibility = activeEligibility();
        inactiveEligibility.levelStatus = "DISABLED";
        stubInvitation(mapper, "INACTIVE-LEVEL-INVITE", inactiveLevel, inactiveEligibility);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "INACTIVE-LEVEL-INVITE", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));

        InvitationRow nonInvitingLevel = activeInvitation();
        InvitationEligibilityRow nonInvitingEligibility = activeEligibility();
        nonInvitingEligibility.invitationEnabled = false;
        stubInvitation(mapper, "NON-INVITING-INVITE", nonInvitingLevel, nonInvitingEligibility);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "NON-INVITING-INVITE", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).insertRelation(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).incrementInvitation(anyLong());
    }

    @Test
    void lockedInvitationEligibilityIsValidatedBeforeAnyRegistrationWrite() {
        InvitationRow invitation = activeInvitation();
        InvitationEligibilityRow eligibility = activeEligibility();
        eligibility.userStatus = "LOCKED";
        stubInvitation(mapper, "RACE-INVITE", invitation, eligibility);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "RACE-INVITE", null))
                .isInstanceOf(DomainException.class);

        verify(mapper, never()).insertExternalIdentity(any());
        verify(mapper, never()).insertCustomerProfile(anyLong());
        verify(mapper, never()).insertBasicMembership(anyLong());
        verify(mapper, never()).insertLedgerAccount(anyLong());
    }

    @Test
    void invitationOwnerMismatchIsRejectedAfterTheRootLock() {
        InvitationRow invitation = activeInvitation();
        invitation.inviterUserId = 99L;
        InvitationOwnerRow owner = new InvitationOwnerRow();
        owner.inviterUserId = 41L;
        when(mapper.findInvitationOwner("OWNER-MISMATCH")).thenReturn(owner);
        when(mapper.lockInviterEligibility(41L)).thenReturn(activeEligibility());
        when(mapper.lockInvitation("OWNER-MISMATCH")).thenReturn(invitation);

        assertThatThrownBy(() -> new MyBatisIdentityAdapter(mapper)
                .findOrRegister(identity("WECHAT_MP"), "OWNER-MISMATCH", null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVITE_CODE_INVALID"));
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).incrementInvitation(anyLong());
    }

    @Test
    void registrationGeneratesUniquePlatformNicknameAndLeavesAvatarAndPhoneEmpty() {
        InvitationRow invitation = activeInvitation();
        stubInvitation(mapper, "ONE-CLICK-INVITE", invitation, activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocation -> {
            UserAccountPo user = invocation.getArgument(0);
            user.id = 73L;
            return 1;
        });
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var result = adapter.findOrRegister(
                identity("WECHAT_MP"), "ONE-CLICK-INVITE", null
        );

        ArgumentCaptor<UserAccountPo> inserted = ArgumentCaptor.forClass(UserAccountPo.class);
        verify(mapper).insertUser(inserted.capture());
        UserAccountPo user = inserted.getValue();
        assertThat(user.nickname).isEqualTo("宏杉会员-" + user.publicId);
        assertThat(user.nickname).hasSize("宏杉会员-".length() + 26);
        assertThat(user.avatarUrl).isNull();
        assertThat(result.nickname()).isEqualTo(user.nickname);
        assertThat(result.newlyRegistered()).isTrue();
        verify(mapper).insertRelation(73L, 41L, 12L);
        verify(mapper).incrementInvitation(12L);
        verify(mapper, never()).updateWechatProfile(
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()
        );
        verify(mapper, never()).replaceMemberAvatar(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any()
        );
    }

    @Test
    void generatedPlatformNicknameUsesTheEntireUniquePublicId() throws Exception {
        var method = MyBatisIdentityAdapter.class.getDeclaredMethod(
                "generatedNickname", String.class
        );
        method.setAccessible(true);
        String firstPublicId = "1723456789012ABCDEFGHIJKLM";
        String secondPublicId = "1723456789012ABCDEFGHIJKLN";

        String first = (String) method.invoke(null, firstPublicId);
        String second = (String) method.invoke(null, secondPublicId);

        assertThat(first).isEqualTo("宏杉会员-" + firstPublicId);
        assertThat(second).isEqualTo("宏杉会员-" + secondPublicId);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.codePointCount(0, first.length())).isEqualTo(31);
    }

    @Test
    void oneClickRegistrationOfAnExistingIdentityDoesNotConsumeInvitationOrOverwriteProfile() {
        UserLoginRow existing = new UserLoginRow();
        existing.id = 88L;
        existing.publicId = "EXISTING-PUBLIC-ID";
        existing.nickname = "已有会员";
        existing.status = "ACTIVE";
        existing.authEpoch = 4L;
        when(mapper.findUserByExternal("WECHAT_MP", "app-fixture", "fixture-open"))
                .thenReturn(existing);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var result = adapter.findOrRegister(
                identity("WECHAT_MP"), "MUST-NOT-BE-CONSUMED", null
        );

        assertThat(result.userId()).isEqualTo(88L);
        assertThat(result.nickname()).isEqualTo("已有会员");
        assertThat(result.newlyRegistered()).isFalse();
        verify(mapper, never()).lockInvitation(any());
        verify(mapper, never()).insertUser(any());
        verify(mapper, never()).incrementInvitation(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper).findUserByExternal("WECHAT_MP", "app-fixture", "fixture-open");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void concurrentIdentityBindingBecomesStableConflictBeforeInvitationSideEffects() {
        stubInvitation(mapper, "RACING-INVITE", activeInvitation(), activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocation -> {
            UserAccountPo user = invocation.getArgument(0);
            user.id = 74L;
            return 1;
        });
        doThrow(new DuplicateKeyException("external identity winner already committed"))
                .when(mapper).insertExternalIdentity(any());
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(
                identity("WECHAT_MP"), "RACING-INVITE", null
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("MEMBER_REGISTRATION_CONFLICT"));

        verify(mapper, never()).insertRelation(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verify(mapper, never()).insertBasicMembership(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).insertLedgerAccount(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).incrementInvitation(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void unrelatedDuplicateKeyIsNotMisreportedAsAnIdentityRace() {
        stubInvitation(mapper, "BROKEN-INVITE", activeInvitation(), activeEligibility());
        when(mapper.insertUser(any())).thenAnswer(invocation -> {
            UserAccountPo user = invocation.getArgument(0);
            user.id = 75L;
            return 1;
        });
        doThrow(new DuplicateKeyException("membership invariant duplicate"))
                .when(mapper).insertBasicMembership(75L);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(
                identity("WECHAT_MP"), "BROKEN-INVITE", null
        )).isInstanceOf(DuplicateKeyException.class);

        verify(mapper, never()).insertLedgerAccount(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).incrementInvitation(org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).consumeBootstrapInvitation(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void sponsorClaimKeepsThePrecreatedPlatformProfileAndDoesNotUseAnInvitation() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(pendingClaim());
        when(mapper.claimBootstrapSponsor(9L, 3, CLAIM_HASH, "WECHAT_MP", "app-fixture"))
                .thenReturn(1);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var result = adapter.findOrRegister(identity("WECHAT_MP"), null, CLAIM_HASH);

        assertThat(result.sponsorClaimed()).isTrue();
        assertThat(result.nickname()).isEqualTo("商城发起人");
        verify(mapper, never()).lockInvitation(any());
        verify(mapper, never()).replaceMemberAvatar(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any()
        );
    }

    @Test
    void compareAndSetLossRollsBackTheClaimInsteadOfReturningSponsor() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(pendingClaim());
        when(mapper.claimBootstrapSponsor(9L, 3, CLAIM_HASH, "WECHAT_MP", "app-fixture"))
                .thenReturn(0);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(identity("WECHAT_MP"), null, CLAIM_HASH))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("SPONSOR_CLAIM_CONFLICT");
    }

    @Test
    void unionConflictPreventsTheClaimCasAndTransactionCanRollBackExternalBinding() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(pendingClaim());
        when(mapper.insertUnionPrincipal("fixture-union", 41L))
                .thenThrow(new DuplicateKeyException("union conflict"));
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(identity("WECHAT_MP"), null, CLAIM_HASH))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("WECHAT_UNION_CONFLICT");
        verify(mapper, never()).claimBootstrapSponsor(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void validClaimSecretWithAnAlreadyBoundIdentityFailsExplicitlyInsteadOfSilentlyLoggingIn() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(pendingClaim());
        UserLoginRow ordinaryMember = new UserLoginRow();
        ordinaryMember.id = 88L;
        when(mapper.findUserByExternal("WECHAT_MP", "app-fixture", "fixture-open"))
                .thenReturn(ordinaryMember);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(identity("WECHAT_MP"), null, CLAIM_HASH))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("SPONSOR_CLAIM_IDENTITY_CONFLICT");
        verify(mapper, never()).insertExternalIdentity(any());
        verify(mapper, never()).claimBootstrapSponsor(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any()
        );
    }

    @Test
    void usedOrUnknownClaimHashNeverFallsBackToOrdinaryRegistration() {
        when(mapper.lockSponsorClaim(CLAIM_HASH)).thenReturn(null);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.findOrRegister(identity("WECHAT_MP"), null, CLAIM_HASH))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("SPONSOR_CLAIM_SECRET_INVALID");
        verify(mapper, never()).lockInvitation(any());
        verify(mapper, never()).insertUser(any());
    }

    @Test
    void mapsAuthoritativeWechatProfileWithoutExposingAnUnmaskedPhone() {
        MemberProfileRow row = new MemberProfileRow();
        row.userId = 42L;
        row.nickname = "宏杉会员";
        row.avatarUrl = "/api/v1/member-avatars/42";
        row.phoneMasked = "138****8000";
        row.phoneVerifiedAt = LocalDateTime.of(2026, 8, 12, 9, 0);
        row.avatarObjectKey = "avatars/42/current.png";
        row.avatarMediaType = "image/png";
        row.avatarSha256 = "a".repeat(64);
        row.avatarSizeBytes = 128L;
        row.avatarUpdatedAt = LocalDateTime.of(2026, 8, 12, 9, 1);
        row.version = 4;
        when(mapper.memberProfile(42)).thenReturn(row);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        var profile = adapter.profile(42);

        assertThat(profile.nickname()).isEqualTo("宏杉会员");
        assertThat(profile.avatarUrl()).isEqualTo("/api/v1/member-avatars/42");
        assertThat(profile.phoneMasked()).isEqualTo("138****8000");
        assertThat(profile.phoneMasked()).doesNotContain("13800138000");
        assertThat(profile.avatarObjectKey()).isEqualTo("avatars/42/current.png");
        assertThat(profile.phoneVerifiedAt()).isNotNull();
    }

    @Test
    void persistsMaskedPhoneAndUsesVersionedAvatarReplacement() {
        when(mapper.updateWechatProfile(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("宏杉会员"),
                org.mockito.ArgumentMatchers.eq("138****8000"),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(mapper.replaceMemberAvatar(
                42L, 3, "/api/v1/member-avatars/42", "avatars/42/current.png",
                "image/png", "a".repeat(64), 128L, LocalDateTime.of(2026, 8, 12, 9, 1)
        )).thenReturn(1);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        adapter.updateWechatProfile(
                42, "宏杉会员", "138****8000", Instant.parse("2026-08-12T01:00:00Z")
        );
        adapter.replaceAvatar(
                42,
                3,
                "/api/v1/member-avatars/42",
                new com.marketshop.application.identity.MemberProfilePort.AvatarMetadata(
                        "avatars/42/current.png",
                        "image/png",
                        "a".repeat(64),
                        128,
                        Instant.parse("2026-08-12T01:01:00Z")
                )
        );

        verify(mapper).updateWechatProfile(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("宏杉会员"),
                org.mockito.ArgumentMatchers.eq("138****8000"),
                any(LocalDateTime.class)
        );
    }

    @Test
    void nicknameUpdateForwardsTheExpectedVersionWithoutTouchingOtherProfileFields() {
        when(mapper.updateMemberNickname(42L, 4, "杉杉")).thenReturn(1);
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        adapter.updateNickname(42, 4, "杉杉");

        verify(mapper).updateMemberNickname(42L, 4, "杉杉");
        verify(mapper, never()).updateWechatProfile(
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any(),
                any(LocalDateTime.class)
        );
        verify(mapper, never()).replaceMemberAvatar(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(),
                any(LocalDateTime.class)
        );
    }

    @Test
    void nicknameCompareAndSetLossIsAStableConflict() {
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.updateNickname(42, 4, "杉杉"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_PROFILE_CONFLICT"));
    }

    @Test
    void avatarCompareAndSetLossIsAStableConflict() {
        MyBatisIdentityAdapter adapter = new MyBatisIdentityAdapter(mapper);

        assertThatThrownBy(() -> adapter.replaceAvatar(
                42,
                3,
                "/api/v1/member-avatars/42",
                new com.marketshop.application.identity.MemberProfilePort.AvatarMetadata(
                        "avatars/42/current.png", "image/png", "a".repeat(64), 128, Instant.now()
                )
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_PROFILE_CONFLICT"));
    }

    private static SponsorClaimRow pendingClaim() {
        SponsorClaimRow row = new SponsorClaimRow();
        row.id = 9L;
        row.sponsorUserId = 41L;
        row.status = "PENDING";
        row.version = 3;
        row.publicId = "SPONSOR-PUBLIC-ID";
        row.nickname = "商城发起人";
        row.userStatus = "ACTIVE";
        row.authEpoch = 6L;
        return row;
    }

    private static InvitationRow activeInvitation() {
        InvitationRow row = new InvitationRow();
        row.id = 12L;
        row.inviterUserId = 41L;
        row.status = "ACTIVE";
        row.useCount = 0;
        return row;
    }

    private static InvitationRow bootstrapInvitation() {
        InvitationRow row = activeInvitation();
        row.bootstrap = true;
        row.maxUses = 1;
        return row;
    }

    private static InvitationEligibilityRow activeEligibility() {
        InvitationEligibilityRow row = new InvitationEligibilityRow();
        row.userId = 41L;
        row.userStatus = "ACTIVE";
        row.levelStatus = "ACTIVE";
        row.invitationEnabled = true;
        return row;
    }

    private static void stubInvitation(
            IdentityMapper mapper,
            String code,
            InvitationRow invitation,
            InvitationEligibilityRow eligibility
    ) {
        InvitationOwnerRow owner = new InvitationOwnerRow();
        owner.inviterUserId = invitation.inviterUserId;
        when(mapper.findInvitationOwner(code)).thenReturn(owner);
        when(mapper.lockInviterEligibility(owner.inviterUserId)).thenReturn(eligibility);
        when(mapper.lockInvitation(code)).thenReturn(invitation);
    }

    private static WeChatIdentity identity(String provider) {
        return new WeChatIdentity(
                provider,
                "app-fixture",
                "fixture-open",
                "fixture-union",
                "真实微信用户",
                null
        );
    }
}
