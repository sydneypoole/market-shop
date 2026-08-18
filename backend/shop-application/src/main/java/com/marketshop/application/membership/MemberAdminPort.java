package com.marketshop.application.membership;

import com.marketshop.application.membership.MemberAdminUseCase.MemberDetail;
import com.marketshop.application.membership.MemberAdminUseCase.MemberPage;
import com.marketshop.application.membership.MemberAdminUseCase.MemberQuery;

public interface MemberAdminPort {

    MemberPage search(MemberQuery query);

    MemberDetail detail(long userId);

    String status(long userId);

    void updateStatus(long userId, String status);

    LevelTransition assignLevel(long userId, String levelCode, long adminId, String reason, String requestId);

    LevelTransition recompute(long userId, long adminId, String reason, String requestId);

    record LevelTransition(String beforeLevel, String afterLevel) {
    }
}
