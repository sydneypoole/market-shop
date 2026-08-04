package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.ExternalIdentityPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.SponsorClaimRow;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserLoginRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

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
