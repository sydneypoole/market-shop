package com.marketshop.application.operation;

public interface OperationSettingsUseCase {

    SettingsView settings();

    SettingsView save(long adminId, SaveSettingsCommand command);

    record SettingsView(
            String afterSaleReturnReceiver,
            String afterSaleReturnPhone,
            String afterSaleReturnAddress,
            int lowInventoryThreshold
    ) {
    }

    record SaveSettingsCommand(
            String afterSaleReturnReceiver,
            String afterSaleReturnPhone,
            String afterSaleReturnAddress,
            int lowInventoryThreshold,
            String reason
    ) {
    }
}
