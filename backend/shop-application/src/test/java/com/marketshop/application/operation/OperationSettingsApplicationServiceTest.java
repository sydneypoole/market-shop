package com.marketshop.application.operation;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.operation.OperationSettingsUseCase.SaveSettingsCommand;
import com.marketshop.application.operation.OperationSettingsUseCase.SettingsView;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationSettingsApplicationServiceTest {

    @Test
    void savesValidatedSettingsAndRecordsBeforeAndAfterValues() {
        var settings = new SettingsFake();
        var audit = new AuditFake();
        var service = new OperationSettingsApplicationService(settings, audit);

        SettingsView saved = service.save(19, new SaveSettingsCommand(
                " 新仓收件人 ",
                " 13800000000 ",
                " 深圳市测试路 1 号 ",
                25,
                "仓库搬迁"
        ));

        assertThat(saved.afterSaleReturnReceiver()).isEqualTo("新仓收件人");
        assertThat(saved.lowInventoryThreshold()).isEqualTo(25);
        assertThat(settings.savedBy).isEqualTo(19);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.actorId()).isEqualTo("19");
            assertThat(record.action()).isEqualTo("OPERATION_SETTINGS_CHANGED");
            assertThat(record.beforeJson()).contains("旧仓收件人");
            assertThat(record.afterJson()).contains("新仓收件人");
            assertThat(record.reason()).isEqualTo("仓库搬迁");
        });
    }

    @Test
    void rejectsMissingReasonWithoutPersistingOrAuditing() {
        var settings = new SettingsFake();
        var audit = new AuditFake();
        var service = new OperationSettingsApplicationService(settings, audit);

        assertThatThrownBy(() -> service.save(19, new SaveSettingsCommand(
                "收件人", "13800000000", "深圳市测试路 1 号", 10, " "
        )))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("修改原因");

        assertThat(settings.saveCalls).isZero();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void rejectsInventoryThresholdOutsideTheSafetyRange() {
        var service = new OperationSettingsApplicationService(new SettingsFake(), new AuditFake());

        assertThatThrownBy(() -> service.save(19, new SaveSettingsCommand(
                "收件人", "13800000000", "深圳市测试路 1 号", 100_001, "调整预警"
        )))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("低库存阈值");
    }

    private static final class SettingsFake implements OperationSettingsPort {
        private SettingsView value = new SettingsView("旧仓收件人", "0755-000000", "深圳市旧仓路 9 号", 10);
        private long savedBy;
        private int saveCalls;

        @Override
        public SettingsView load() {
            return value;
        }

        @Override
        public SettingsView save(long adminId, SettingsView settings) {
            savedBy = adminId;
            saveCalls++;
            value = settings;
            return value;
        }

        @Override
        public int lowInventoryThreshold() {
            return value.lowInventoryThreshold();
        }
    }

    private static final class AuditFake implements AdminAuditPort {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditView> search(AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(AuditQuery query) {
            return 0;
        }
    }
}
