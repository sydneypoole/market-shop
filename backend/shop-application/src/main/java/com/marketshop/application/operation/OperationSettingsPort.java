package com.marketshop.application.operation;

import com.marketshop.application.operation.OperationSettingsUseCase.SettingsView;

public interface OperationSettingsPort {

    SettingsView load();

    SettingsView save(long adminId, SettingsView settings);

    int lowInventoryThreshold();
}
