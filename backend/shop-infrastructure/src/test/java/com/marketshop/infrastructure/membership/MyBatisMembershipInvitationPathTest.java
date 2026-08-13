package com.marketshop.infrastructure.membership;

import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.InvitationRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisMembershipInvitationPathTest {

    @Test
    void registrationPathTargetsTheMiniprogramRegisterPageAndRfc3986EncodesTheCode() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        InvitationRow row = new InvitationRow();
        row.code = "A b+C/邀请码?&=~";
        row.status = "ACTIVE";
        row.useCount = 0;
        when(mapper.invitation(42L)).thenReturn(row);

        var invitation = new MyBatisMembershipAdapter(mapper).currentInvitation(42L);

        assertThat(invitation.registrationPath()).isEqualTo(
                "/pages/register/register?inviteCode=A%20b%2BC%2F%E9%82%80%E8%AF%B7%E7%A0%81%3F%26%3D~"
        );
        assertThat(invitation.registrationPath()).doesNotContain("+", "/login?");
    }
}
