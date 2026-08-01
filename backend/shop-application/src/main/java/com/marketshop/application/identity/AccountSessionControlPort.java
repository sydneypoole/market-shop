package com.marketshop.application.identity;

/**
 * Invalidates authentication sessions after an account security boundary changes.
 *
 * <p>The application layer owns when invalidation is required. The interfaces layer
 * supplies the Sa-Token implementation so framework types do not leak inward.</p>
 */
public interface AccountSessionControlPort {

    void invalidateMemberSessions(long userId);

    void invalidateAdminSessions(long adminId);
}
