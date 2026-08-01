package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.identity.AdminManagementUseCase.AdminView;
import com.marketshop.application.identity.AdminManagementUseCase.ResetPasswordCommand;
import com.marketshop.application.identity.AdminManagementUseCase.RoleView;
import com.marketshop.application.identity.AdminManagementUseCase.RolesCommand;
import com.marketshop.application.identity.AdminManagementUseCase.SaveRoleCommand;
import com.marketshop.application.identity.AdminManagementUseCase.StatusCommand;
import com.marketshop.application.identity.IdentityPorts.AdminCredential;
import com.marketshop.application.identity.IdentityPorts.AdminIdentityPort;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminManagementApplicationServiceTest {

    @Mock
    private AdminManagementPort managementPort;

    @Mock
    private AdminIdentityPort identityPort;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private AdminAuditPort auditPort;

    @Mock
    private AccountSessionControlPort sessionControlPort;

    @InjectMocks
    private AdminManagementApplicationService service;

    @BeforeEach
    void allowSuperAdminReauthentication() {
        when(identityPort.findById(1)).thenReturn(Optional.of(credential(1)));
        when(passwordHasher.matches("actor-password", "actor-hash")).thenReturn(true);
        lenient().when(managementPort.hasRole(1, "SUPER_ADMIN")).thenReturn(true);
    }

    @Test
    void resetPasswordInvalidatesEveryExistingTargetSession() {
        when(managementPort.admins()).thenReturn(List.of(admin(2, Set.of("ORDER_REVIEWER"))));
        when(passwordHasher.encode("temporary-123")).thenReturn("new-hash");

        service.resetPassword(1, 2, new ResetPasswordCommand(
                "actor-password", "temporary-123", "账号接管复核"
        ));

        verify(managementPort).updatePassword(2, "new-hash", true);
        verify(sessionControlPort).invalidateAdminSessions(2);
    }

    @Test
    void statusAndRoleAssignmentInvalidateTargetSessions() {
        when(managementPort.admins()).thenReturn(List.of(admin(2, Set.of("ORDER_REVIEWER"))));
        when(managementPort.roles()).thenReturn(List.of(
                new RoleView("ORDER_REVIEWER", "订单审核员", true, Set.of("order:review")),
                new RoleView("AUDIT_VIEWER", "审计查看员", true, Set.of("audit:read"))
        ));

        service.changeStatus(1, 2, new StatusCommand("actor-password", " disabled ", "离职停用"));
        service.assignRoles(1, 2, new RolesCommand(
                "actor-password", Set.of("AUDIT_VIEWER"), "职责调整"
        ));

        verify(managementPort).updateStatus(2, "DISABLED");
        verify(managementPort).replaceRoles(2, Set.of("AUDIT_VIEWER"), 1);
        verify(sessionControlPort, org.mockito.Mockito.times(2)).invalidateAdminSessions(2);
    }

    @Test
    void changingCustomRolePermissionsInvalidatesAllAssignedAdmins() {
        RoleView before = new RoleView("CUSTOM_OPS", "自定义运营", false, Set.of("order:read"));
        RoleView after = new RoleView(
                "CUSTOM_OPS", "自定义运营", false, Set.of("order:read", "order:review")
        );
        when(managementPort.roles()).thenReturn(List.of(before));
        when(managementPort.permissions()).thenReturn(Set.of("order:read", "order:review"));
        when(managementPort.adminIdsWithRole("CUSTOM_OPS")).thenReturn(Set.of(2L, 3L));
        when(managementPort.saveRole(
                "CUSTOM_OPS", "自定义运营", Set.of("order:read", "order:review")
        )).thenReturn(after);

        service.saveRole(1, new SaveRoleCommand(
                "CUSTOM_OPS",
                "自定义运营",
                Set.of("order:read", "order:review"),
                "actor-password",
                "补充审核职责"
        ));

        verify(sessionControlPort).invalidateAdminSessions(2);
        verify(sessionControlPort).invalidateAdminSessions(3);
        verify(managementPort).incrementAdminAuthEpoch(2);
        verify(managementPort).incrementAdminAuthEpoch(3);
    }

    @Test
    void activePasswordChangeReturnsTheNewEpochWithoutKickingTheCurrentBrowser() {
        when(identityPort.findById(1)).thenReturn(
                Optional.of(credential(1, 0L)),
                Optional.of(credential(1, 1L))
        );
        when(passwordHasher.matches("new-password-123", "actor-hash")).thenReturn(false);
        when(passwordHasher.encode("new-password-123")).thenReturn("changed-hash");

        AdminManagementUseCase.PasswordChangeResult result = service.changePassword(
                1, new AdminManagementUseCase.ChangePasswordCommand(
                "actor-password", "new-password-123"
        ));

        verify(managementPort).updatePassword(1, "changed-hash", false);
        verify(sessionControlPort, never()).invalidateAdminSessions(1);
        org.assertj.core.api.Assertions.assertThat(result.authEpoch()).isEqualTo(1L);
    }

    private static AdminCredential credential(long id) {
        return credential(id, 0L);
    }

    private static AdminCredential credential(long id, long authEpoch) {
        return new AdminCredential(
                id,
                "admin",
                "actor-hash",
                "超级管理员",
                "ACTIVE",
                false,
                0,
                null,
                authEpoch,
                Set.of("SUPER_ADMIN"),
                Set.of("admin:account:manage", "admin:role:manage")
        );
    }

    private static AdminView admin(long id, Set<String> roles) {
        return new AdminView(
                id,
                "target",
                "目标管理员",
                "ACTIVE",
                null,
                false,
                0,
                null,
                null,
                roles
        );
    }
}
