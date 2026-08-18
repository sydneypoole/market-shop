package com.marketshop.application.membership;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.application.identity.AccountSessionControlPort;
import com.marketshop.application.membership.MemberAdminPort.LevelTransition;
import com.marketshop.application.membership.MemberAdminUseCase.LevelCommand;
import com.marketshop.application.membership.MemberAdminUseCase.MemberQuery;
import com.marketshop.application.membership.MemberAdminUseCase.RecomputeCommand;
import com.marketshop.application.membership.MemberAdminUseCase.StatusCommand;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberAdminApplicationServiceTest {

    @Mock
    private MemberAdminPort port;

    @Mock
    private AdminAuditPort audit;

    @Mock
    private AccountSessionControlPort sessionControlPort;

    @InjectMocks
    private MemberAdminApplicationService service;

    @Test
    void normalizesPagedMemberSearch() {
        service.search(new MemberQuery("  MS100 ", " SUPER_MEMBER ", " ACTIVE ", 0, 1_000));

        verify(port).search(new MemberQuery("MS100", "SUPER_MEMBER", "ACTIVE", 1, 100));
    }

    @Test
    void statusChangeRecordsBeforeAfterReasonAndRequestId() {
        when(port.status(42)).thenReturn("ACTIVE");

        service.updateStatus(8, 42, new StatusCommand(" disabled ", " 风控复核 ", " req-42 "));

        verify(port).updateStatus(42, "DISABLED");
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().beforeJson()).contains("\"ACTIVE\"");
        assertThat(captor.getValue().afterJson()).contains("\"DISABLED\"");
        assertThat(captor.getValue().reason()).isEqualTo("风控复核");
        assertThat(captor.getValue().requestId()).isEqualTo("req-42");
        verify(sessionControlPort).invalidateMemberSessions(42);
    }

    @Test
    void invalidStatusCannotReachPersistenceOrAudit() {
        assertThatThrownBy(() ->
                service.updateStatus(8, 42, new StatusCommand("DELETED", "测试", "req"))
        ).isInstanceOf(DomainException.class);

        verify(port, never()).updateStatus(42, "DELETED");
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void manualRecomputeRecordsLevelTransition() {
        when(port.recompute(42, 8, "售后复核", "req-recompute"))
                .thenReturn(new LevelTransition("DIVIDEND_MEMBER", "SUPER_MEMBER"));

        service.recompute(8, 42, new RecomputeCommand(" 售后复核 ", " req-recompute "));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("MEMBER_LEVEL_RECOMPUTED");
        assertThat(captor.getValue().beforeJson()).contains("DIVIDEND_MEMBER");
        assertThat(captor.getValue().afterJson()).contains("SUPER_MEMBER");
    }

    @Test
    void updateLevelRecordsAuditWithoutInvalidatingSessions() {
        when(port.assignLevel(42, "SUPER_MEMBER", 8, "人工升级", "req-level"))
                .thenReturn(new LevelTransition("BASIC", "SUPER_MEMBER"));

        service.updateLevel(8, 42, new LevelCommand(" super_member ", " 人工升级 ", " req-level "));

        verify(port).assignLevel(42, "SUPER_MEMBER", 8, "人工升级", "req-level");
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("MEMBER_LEVEL_UPDATED");
        assertThat(captor.getValue().beforeJson()).contains("BASIC");
        assertThat(captor.getValue().afterJson()).contains("SUPER_MEMBER");
        assertThat(captor.getValue().reason()).isEqualTo("人工升级");
        assertThat(captor.getValue().requestId()).isEqualTo("req-level");
        verify(sessionControlPort, never()).invalidateMemberSessions(42);
    }

    @Test
    void unknownLevelNeverCallsAssign() {
        assertThatThrownBy(() ->
                service.updateLevel(8, 42, new LevelCommand("VIP", "测试", "req"))
        ).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_LEVEL_INVALID"));

        verify(port, never()).assignLevel(anyLong(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void blankReasonNeverCallsAssign() {
        assertThatThrownBy(() ->
                service.updateLevel(8, 42, new LevelCommand("BASIC", "  ", "req"))
        ).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code()).isEqualTo("MEMBER_COMMAND_INVALID"));

        verify(port, never()).assignLevel(anyLong(), anyString(), anyLong(), anyString(), anyString());
    }
}
