package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.AdminAccountPo;
import com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.UserAccountPo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class BootstrapIdentityInitializer implements ApplicationRunner {

    private final IdentityMapper mapper;
    private final boolean enabled;
    private final String adminUsername;
    private final String bootstrapPassword;
    private final String inviteCode;

    public BootstrapIdentityInitializer(
            IdentityMapper mapper,
            @Value("${market-shop.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${market-shop.bootstrap-admin.username:admin}") String adminUsername,
            @Value("${market-shop.bootstrap-admin.password:}") String bootstrapPassword,
            @Value("${market-shop.bootstrap-admin.invite-code:BOOTSTRAP2026}") String inviteCode
    ) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.adminUsername = adminUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.inviteCode = inviteCode;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (bootstrapPassword == null || bootstrapPassword.length() < 12) {
            throw new IllegalStateException("Bootstrap admin password must contain at least 12 characters");
        }
        createSponsorIfNecessary();
        if (mapper.countAdmins() > 0) {
            return;
        }
        String passwordHash = BCrypt.hashpw(bootstrapPassword, BCrypt.gensalt(12));
        AdminAccountPo admin = new AdminAccountPo();
        admin.username = adminUsername;
        admin.passwordHash = passwordHash;
        admin.displayName = "超级管理员";
        admin.status = "ACTIVE";
        admin.mustChangePassword = true;
        mapper.insertAdmin(admin);
        mapper.assignRole(admin.id, "SUPER_ADMIN");
    }

    private void createSponsorIfNecessary() {
        if (mapper.countUsers() > 0) {
            return;
        }
        UserAccountPo sponsor = new UserAccountPo();
        sponsor.publicId = String.format("%013d", System.currentTimeMillis())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        sponsor.status = "ACTIVE";
        sponsor.nickname = "商城发起人";
        mapper.insertUser(sponsor);
        mapper.insertCustomerProfile(sponsor.id);
        mapper.insertBasicMembership(sponsor.id);
        mapper.promoteBootstrapSponsor(sponsor.id);
        mapper.insertLedgerAccount(sponsor.id);
        mapper.insertBootstrapInvitation(inviteCode, sponsor.id);
    }

}
