package com.marketshop.application.identity;

import com.marketshop.application.identity.AdminManagementUseCase.AdminView;
import com.marketshop.application.identity.AdminManagementUseCase.RoleView;

import java.util.List;
import java.util.Set;

public interface AdminManagementPort {

    List<AdminView> admins();

    List<RoleView> roles();

    Set<String> permissions();

    Set<Long> adminIdsWithRole(String roleCode);

    void incrementAdminAuthEpoch(long adminId);

    RoleView saveRole(String code, String name, Set<String> permissions);

    void deleteRole(String code);

    boolean usernameExists(String username);

    boolean userExists(long userId);

    boolean hasRole(long adminId, String roleCode);

    AdminView create(String username, String passwordHash, String displayName, Long linkedUserId,
                     Set<String> roles, long grantedBy);

    void updatePassword(long adminId, String passwordHash, boolean mustChangePassword);

    void updateStatus(long adminId, String status);

    void unlock(long adminId);

    void replaceRoles(long adminId, Set<String> roles, long grantedBy);

    void updateLinkedUser(long adminId, Long linkedUserId);
}
