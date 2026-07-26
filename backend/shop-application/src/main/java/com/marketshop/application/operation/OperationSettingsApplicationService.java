package com.marketshop.application.operation;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.audit.AdminAuditPort.AuditRecord;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OperationSettingsApplicationService implements OperationSettingsUseCase {

    private final OperationSettingsPort settingsPort;
    private final AdminAuditPort auditPort;

    public OperationSettingsApplicationService(OperationSettingsPort settingsPort, AdminAuditPort auditPort) {
        this.settingsPort = settingsPort;
        this.auditPort = auditPort;
    }

    @Override
    public SettingsView settings() {
        return settingsPort.load();
    }

    @Override
    @Transactional
    public SettingsView save(long adminId, SaveSettingsCommand command) {
        SettingsView before = settingsPort.load();
        SettingsView target = new SettingsView(
                required(command.afterSaleReturnReceiver(), 80, "退货收件人"),
                required(command.afterSaleReturnPhone(), 40, "退货联系电话"),
                required(command.afterSaleReturnAddress(), 500, "退货地址"),
                range(command.lowInventoryThreshold(), 0, 100_000, "低库存阈值")
        );
        String reason = required(command.reason(), 500, "修改原因");
        SettingsView saved = settingsPort.save(adminId, target);
        auditPort.record(new AuditRecord(
                "ADMIN",
                Long.toString(adminId),
                "OPERATION_SETTINGS_CHANGED",
                "OPERATION_SETTINGS",
                "GLOBAL",
                json(before),
                json(saved),
                reason,
                UUID.randomUUID().toString(),
                null,
                "application-service",
                Instant.now()
        ));
        return saved;
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new DomainException("OPERATION_SETTING_INVALID", label + "不能为空且长度不能超过 " + maxLength);
        }
        return value.trim();
    }

    private static int range(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new DomainException("OPERATION_SETTING_INVALID", label + "必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static String json(SettingsView value) {
        return "{\"afterSaleReturnReceiver\":\"" + escape(value.afterSaleReturnReceiver())
                + "\",\"afterSaleReturnPhone\":\"" + escape(value.afterSaleReturnPhone())
                + "\",\"afterSaleReturnAddress\":\"" + escape(value.afterSaleReturnAddress())
                + "\",\"lowInventoryThreshold\":" + value.lowInventoryThreshold() + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
