package com.marketshop.interfaces.security;

import com.marketshop.application.identity.AccountSessionControlPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class SaTokenSessionControlAdapter implements AccountSessionControlPort {

    @Override
    public void invalidateMemberSessions(long userId) {
        afterCommit(() -> StpUserKit.logic().kickout(userId));
    }

    @Override
    public void invalidateAdminSessions(long adminId) {
        afterCommit(() -> StpAdminKit.logic().kickout(adminId));
    }

    private static void afterCommit(Runnable invalidation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            invalidation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidation.run();
            }
        });
    }
}
