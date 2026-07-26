package com.marketshop.infrastructure.operation;

import com.marketshop.application.operation.OperationSettingsPort;
import com.marketshop.application.operation.OperationSettingsUseCase.SettingsView;
import com.marketshop.infrastructure.persistence.mapper.OperationMapper;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class MyBatisOperationSettingsAdapter implements OperationSettingsPort {

    private static final SettingsView DEFAULTS = new SettingsView(
            "售后仓",
            "400-000-0000",
            "请配置真实退货地址",
            10
    );

    private final OperationMapper mapper;

    public MyBatisOperationSettingsAdapter(OperationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SettingsView load() {
        Map<String, String> values = mapper.settings().stream().collect(Collectors.toMap(
                row -> row.settingKey,
                row -> row.settingValue
        ));
        return new SettingsView(
                values.getOrDefault("AFTERSALE_RETURN_RECEIVER", DEFAULTS.afterSaleReturnReceiver()),
                values.getOrDefault("AFTERSALE_RETURN_PHONE", DEFAULTS.afterSaleReturnPhone()),
                values.getOrDefault("AFTERSALE_RETURN_ADDRESS", DEFAULTS.afterSaleReturnAddress()),
                intValue(values.get("LOW_INVENTORY_THRESHOLD"), DEFAULTS.lowInventoryThreshold())
        );
    }

    @Override
    public SettingsView save(long adminId, SettingsView settings) {
        mapper.upsert(adminId, "AFTERSALE_RETURN_RECEIVER", settings.afterSaleReturnReceiver());
        mapper.upsert(adminId, "AFTERSALE_RETURN_PHONE", settings.afterSaleReturnPhone());
        mapper.upsert(adminId, "AFTERSALE_RETURN_ADDRESS", settings.afterSaleReturnAddress());
        mapper.upsert(adminId, "LOW_INVENTORY_THRESHOLD", Integer.toString(settings.lowInventoryThreshold()));
        return load();
    }

    @Override
    public int lowInventoryThreshold() {
        return intValue(mapper.value("LOW_INVENTORY_THRESHOLD"), DEFAULTS.lowInventoryThreshold());
    }

    private static int intValue(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
