package com.marketshop.application.membership;

import com.marketshop.application.membership.MembershipUseCase.DirectMemberView;
import com.marketshop.application.membership.MembershipUseCase.InvitationView;
import com.marketshop.application.membership.MembershipUseCase.LedgerEntryView;
import com.marketshop.application.membership.MembershipUseCase.ProfileView;
import com.marketshop.application.membership.MembershipUseCase.PublishRuleCommand;
import com.marketshop.application.membership.MembershipUseCase.RuleView;

import java.util.List;

public interface MembershipPort {

    ProfileView profile(long userId);

    InvitationView currentInvitation(long userId);

    InvitationView ensureInvitation(long userId);

    void revokeInvitation(long userId);

    InvitationView regenerateInvitation(long userId, int validityDays);

    List<DirectMemberView> directMembers(long userId);

    List<LedgerEntryView> ledger(long userId);

    List<RuleView> rules();

    boolean activeMembershipLevelExists(String levelCode);

    RuleView publishRule(long adminId, PublishRuleCommand command);

    void cancelRule(long adminId, long ruleId, String reason);
}
