package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void ordinaryBootstrapInvitationAlwaysRegistersADirectChildAndNeverClaimsSponsor() {
        InvitationRow invitation = activeInvitation();
        when(mapper.lockInvitation("NORMAL-INVITE-CODE")).thenReturn(invitation);
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
