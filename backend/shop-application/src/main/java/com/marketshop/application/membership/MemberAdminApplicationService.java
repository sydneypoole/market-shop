package com.marketshop.application.membership;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.AccountSessionControlPort;
import com.marketshop.application.membership.MemberAdminPort.LevelTransition;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
@Transactional
public class MemberAdminApplicationService implements MemberAdminUseCase {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED", "LOCKED");
    private static final Set<String> LEVELS = Set.of(
            "BASIC", "EXPERIENCE_OFFICER", "SUPER_MEMBER", "DIVIDEND_MEMBER"
    );

    private final MemberAdminPort port;
    private final AdminAuditPort audit;
    private final AccountSessionControlPort sessionControlPort;

    public MemberAdminApplicationService(
            MemberAdminPort port,
            AdminAuditPort audit,
            AccountSessionControlPort sessionControlPort
    ) {
        this.port = port;
        this.audit = audit;
        this.sessionControlPort = sessionControlPort;
    }

    @Override
    public MemberPage search(MemberQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 100));
        return port.search(new MemberQuery(trim(query.keyword()), trim(query.levelCode()),
                trim(query.status()), page, size));
    }

    @Override
    public MemberDetail detail(long userId) {
        return port.detail(userId);
    }

    @Override
    public void updateStatus(long adminId, long userId, StatusCommand command) {
        String status = command.status() == null ? "" : command.status().trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new DomainException("MEMBER_STATUS_INVALID", "会员状态无效");
        }
        require(command.reason(), "修改会员状态必须填写原因");
        require(command.requestId(), "请求号不能为空");
        String before = port.status(userId);
        port.updateStatus(userId, status);
        record(adminId, userId, "MEMBER_STATUS_UPDATED",
                "{\"status\":\"" + before + "\"}",
                "{\"status\":\"" + status + "\"}",
                command.reason().trim(), command.requestId().trim());
        sessionControlPort.invalidateMemberSessions(userId);
    }

    @Override
    public void updateLevel(long adminId, long userId, LevelCommand command) {
        String level = command.levelCode() == null ? "" : command.levelCode().trim().toUpperCase();
        if (!LEVELS.contains(level)) {
            throw new DomainException("MEMBER_LEVEL_INVALID", "会员等级无效");
        }
        require(command.reason(), "修改会员等级必须填写原因");
        require(command.requestId(), "请求号不能为空");
        LevelTransition transition = port.assignLevel(
                userId, level, adminId, command.reason().trim(), command.requestId().trim()
        );
        record(adminId, userId, "MEMBER_LEVEL_UPDATED",
                "{\"level\":\"" + transition.beforeLevel() + "\"}",
                "{\"level\":\"" + transition.afterLevel() + "\"}",
                command.reason().trim(), command.requestId().trim());
    }

    @Override
    public void recompute(long adminId, long userId, RecomputeCommand command) {
        require(command.reason(), "资格重算必须填写原因");
        require(command.requestId(), "请求号不能为空");
        LevelTransition transition = port.recompute(
                userId, adminId, command.reason().trim(), command.requestId().trim()
        );
        record(adminId, userId, "MEMBER_LEVEL_RECOMPUTED",
                "{\"level\":\"" + transition.beforeLevel() + "\"}",
                "{\"level\":\"" + transition.afterLevel() + "\"}",
                command.reason().trim(), command.requestId().trim());
    }

    private void record(long adminId, long userId, String action, String before, String after,
                        String reason, String requestId) {
        audit.record(new AuditRecord(
                "ADMIN", Long.toString(adminId), action, "MEMBER", Long.toString(userId),
                before, after, reason, requestId, null, null, Instant.now()
        ));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException("MEMBER_COMMAND_INVALID", message);
        }
    }
}
