package com.marketshop.application.audit;

import com.marketshop.application.audit.AdminAuditPort.AuditQuery;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class AuditApplicationService implements AuditUseCase {

    private final AdminAuditPort auditPort;

    public AuditApplicationService(AdminAuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @Override
    public AuditPage search(SearchCommand command) {
        int page = Math.max(command.page(), 1);
        int pageSize = Math.min(Math.max(command.pageSize(), 1), 100);
        if (command.from() != null && command.to() != null && command.from().isAfter(command.to())) {
            throw new DomainException("AUDIT_TIME_RANGE_INVALID", "审计开始时间不能晚于结束时间");
        }
        AuditQuery query = new AuditQuery(
                blankToNull(command.actorType()),
                blankToNull(command.actorId()),
                blankToNull(command.action()),
                blankToNull(command.resourceType()),
                blankToNull(command.resourceId()),
                blankToNull(command.requestId()),
                command.from(),
                command.to(),
                (page - 1) * pageSize,
                pageSize
        );
        return new AuditPage(auditPort.search(query), auditPort.count(query), page, pageSize);
    }

    @Override
    public String exportCsv(SearchCommand command) {
        validateRange(command);
        AuditQuery query = new AuditQuery(
                blankToNull(command.actorType()),
                blankToNull(command.actorId()),
                blankToNull(command.action()),
                blankToNull(command.resourceType()),
                blankToNull(command.resourceId()),
                blankToNull(command.requestId()),
                command.from(),
                command.to(),
                0,
                10_000
        );
        StringBuilder csv = new StringBuilder(
                "\uFEFF时间,主体类型,主体ID,动作,资源类型,资源ID,原因,请求号,IP,客户端\r\n"
        );
        auditPort.search(query).forEach(row -> csv
                .append(cell(row.occurredAt() == null ? "" : row.occurredAt().toString())).append(',')
                .append(cell(row.actorType())).append(',')
                .append(cell(row.actorId())).append(',')
                .append(cell(row.action())).append(',')
                .append(cell(row.resourceType())).append(',')
                .append(cell(row.resourceId())).append(',')
                .append(cell(row.reason())).append(',')
                .append(cell(row.requestId())).append(',')
                .append(cell(row.maskedIp())).append(',')
                .append(cell(row.userAgentSummary()))
                .append("\r\n"));
        if (csv.toString().getBytes(StandardCharsets.UTF_8).length > 20 * 1024 * 1024) {
            throw new DomainException("AUDIT_EXPORT_TOO_LARGE", "审计导出结果过大，请缩小筛选范围");
        }
        return csv.toString();
    }

    private static void validateRange(SearchCommand command) {
        if (command.from() != null && command.to() != null && command.from().isAfter(command.to())) {
            throw new DomainException("AUDIT_TIME_RANGE_INVALID", "审计开始时间不能晚于结束时间");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (safe.startsWith("=") || safe.startsWith("+") || safe.startsWith("-") || safe.startsWith("@")) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
