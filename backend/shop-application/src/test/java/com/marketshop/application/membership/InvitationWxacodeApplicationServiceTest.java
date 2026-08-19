package com.marketshop.application.membership;

import com.marketshop.application.identity.IdentityPorts.VerifiedPhone;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.application.identity.IdentityPorts.WxaCodeCommand;
import com.marketshop.application.identity.IdentityPorts.WxaCodeImage;
import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.WxacodeView;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationWxacodeApplicationServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};

    @Test
    void mintsOfficialWxacodeFromTheReadOnlyActiveInvitation() {
        CapturingPort port = new CapturingPort();
        CapturingWeChat weChat = new CapturingWeChat();
        MembershipApplicationService service = new MembershipApplicationService(port, weChat);
        port.currentInvitation = new InvitationView(
                "MSABCDEF1234",
                "ACTIVE",
                2,
                "/pages/register/register?inviteCode=MSABCDEF1234",
                Instant.parse("2027-08-13T00:00:00Z")
        );

        WxacodeView view = service.invitationWxacode(42L);

        assertThat(port.currentInvitationUserId).isEqualTo(42L);
        assertThat(port.ensureInvitationCalls).isZero();
        assertThat(weChat.command.page()).isEqualTo("pages/register/register");
        assertThat(weChat.command.scene()).isEqualTo("MSABCDEF1234");
        assertThat(weChat.command.path()).isEqualTo("pages/register/register?inviteCode=MSABCDEF1234");
        assertThat(view.contentType()).isEqualTo("image/png");
        assertThat(view.imageBase64()).isEqualTo(Base64.getEncoder().encodeToString(PNG));
    }

    @Test
    void missingOrInactiveInvitationNeverCreatesACodeOrCallsWeChat() {
        InvitationView[] invitations = {
                null,
                new InvitationView(
                        "MSABCDEF1234",
                        "REVOKED",
                        0,
                        "/pages/register/register?inviteCode=MSABCDEF1234",
                        Instant.parse("2027-08-13T00:00:00Z")
                ),
                new InvitationView(
                        "  ",
                        "ACTIVE",
                        0,
                        "/pages/register/register?inviteCode=MSABCDEF1234",
                        Instant.parse("2027-08-13T00:00:00Z")
                ),
                new InvitationView(
                        null,
                        "ACTIVE",
                        0,
                        "/pages/register/register?inviteCode=MSABCDEF1234",
                        Instant.parse("2027-08-13T00:00:00Z")
                )
        };
        for (InvitationView invitation : invitations) {
            CapturingPort port = new CapturingPort();
            CapturingWeChat weChat = new CapturingWeChat();
            MembershipApplicationService service = new MembershipApplicationService(port, weChat);
            port.currentInvitation = invitation;

            assertThatThrownBy(() -> service.invitationWxacode(42L))
                    .isInstanceOf(DomainException.class)
                    .extracting("code")
                    .isEqualTo("INVITATION_NOT_FOUND");
            assertThat(port.ensureInvitationCalls).isZero();
            assertThat(weChat.command).isNull();
        }
    }

    private static final class CapturingPort implements MembershipPort {
        private InvitationView currentInvitation;
        private long currentInvitationUserId;
        private int ensureInvitationCalls;

        @Override
        public MembershipUseCase.ProfileView profile(long userId) {
            return null;
        }

        @Override
        public InvitationView currentInvitation(long userId) {
            currentInvitationUserId = userId;
            return currentInvitation;
        }

        @Override
        public InvitationView ensureInvitation(long userId) {
            ensureInvitationCalls++;
            return null;
        }

        @Override
        public void revokeInvitation(long userId) {
        }

        @Override
        public InvitationView regenerateInvitation(long userId, int validityDays) {
            return null;
        }

        @Override
        public List<MembershipUseCase.DirectMemberView> directMembers(long userId) {
            return List.of();
        }

        @Override
        public List<MembershipUseCase.LedgerEntryView> ledger(long userId) {
            return List.of();
        }

        @Override
        public List<MembershipUseCase.RuleView> rules() {
            return List.of();
        }

        @Override
        public MembershipUseCase.RuleView publishRule(long adminId, MembershipUseCase.PublishRuleCommand command) {
            return null;
        }

        @Override
        public void cancelRule(long adminId, long ruleId, String reason) {
        }
    }

    private static final class CapturingWeChat implements WeChatMiniprogramPort {
        private WxaCodeCommand command;

        @Override
        public WeChatIdentity exchangeMiniprogramCode(String jsCode) {
            throw new UnsupportedOperationException("exchangeMiniprogramCode");
        }

        @Override
        public VerifiedPhone exchangePhoneCode(String dynamicCode) {
            throw new UnsupportedOperationException("exchangePhoneCode");
        }

        @Override
        public WxaCodeImage createWxaCode(WxaCodeCommand command) {
            this.command = command;
            return new WxaCodeImage("image/png", PNG);
        }
    }
}
