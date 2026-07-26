package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.OperationPersistenceModels.SettingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OperationMapper {

    @Select("""
            SELECT setting_key, setting_value
            FROM operation_setting
            WHERE setting_key IN (
                'AFTERSALE_RETURN_RECEIVER',
                'AFTERSALE_RETURN_PHONE',
                'AFTERSALE_RETURN_ADDRESS',
                'LOW_INVENTORY_THRESHOLD'
            )
            """)
    List<SettingRow> settings();

    @Select("SELECT setting_value FROM operation_setting WHERE setting_key = #{key}")
    String value(@Param("key") String key);

    @Insert("""
            INSERT INTO operation_setting (setting_key, setting_value, updated_by_admin_id)
            VALUES (#{key}, #{value}, #{adminId})
            ON DUPLICATE KEY UPDATE
                setting_value = VALUES(setting_value),
                updated_by_admin_id = VALUES(updated_by_admin_id),
                version = version + 1
            """)
    int upsert(@Param("adminId") long adminId, @Param("key") String key, @Param("value") String value);
}
