package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.StorefrontTemplatePersistenceModels.TemplateRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface StorefrontTemplateMapper {

    String COLUMNS = "id, template_code, template_name, preset_type, status, is_active AS active, "
            + "CAST(design_tokens_json AS CHAR) AS design_tokens_json, "
            + "CAST(layout_json AS CHAR) AS layout_json, "
            + "version, published_at, updated_at";

    @Select("SELECT " + COLUMNS
            + " FROM operation_storefront_template"
            + " WHERE is_active = 1 AND status = 'PUBLISHED'"
            + " LIMIT 1")
    TemplateRow active();

    @Select("SELECT " + COLUMNS
            + " FROM operation_storefront_template"
            + " ORDER BY is_active DESC, updated_at DESC, id DESC")
    List<TemplateRow> findAll();

    @Select("SELECT " + COLUMNS
            + " FROM operation_storefront_template"
            + " WHERE id = #{templateId}")
    TemplateRow find(@Param("templateId") long templateId);

    @Insert("""
            INSERT INTO operation_storefront_template
                (template_code, template_name, preset_type, status, is_active,
                 design_tokens_json, layout_json, version, created_by_admin_id, updated_by_admin_id)
            VALUES
                (#{row.templateCode}, #{row.templateName}, #{row.presetType}, #{row.status}, 0,
                 CAST(#{row.designTokensJson} AS JSON), CAST(#{row.layoutJson} AS JSON),
                 #{row.version}, #{adminId}, #{adminId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("adminId") long adminId, @Param("row") TemplateRow row);

    @Update("""
            UPDATE operation_storefront_template
            SET template_name = #{row.templateName},
                status = #{row.status},
                is_active = 0,
                design_tokens_json = CAST(#{row.designTokensJson} AS JSON),
                layout_json = CAST(#{row.layoutJson} AS JSON),
                version = #{row.version},
                updated_by_admin_id = #{adminId},
                published_at = NULL
            WHERE id = #{row.id} AND version = #{expectedVersion} AND status <> 'ARCHIVED'
            """)
    int update(@Param("adminId") long adminId, @Param("row") TemplateRow row,
               @Param("expectedVersion") int expectedVersion);

    @Update("UPDATE operation_storefront_template SET is_active = 0 WHERE is_active = 1")
    int deactivateCurrent();

    @Update("""
            UPDATE operation_storefront_template
            SET status = 'PUBLISHED',
                is_active = 1,
                version = #{newVersion},
                updated_by_admin_id = #{adminId},
                published_at = #{publishedAt}
            WHERE id = #{templateId} AND version = #{expectedVersion} AND status <> 'ARCHIVED'
            """)
    int publish(@Param("adminId") long adminId,
                @Param("templateId") long templateId,
                @Param("expectedVersion") int expectedVersion,
                @Param("newVersion") int newVersion,
                @Param("publishedAt") LocalDateTime publishedAt);

    @Update("""
            UPDATE operation_storefront_template
            SET status = 'ARCHIVED',
                version = #{newVersion},
                updated_by_admin_id = #{adminId}
            WHERE id = #{templateId} AND version = #{expectedVersion} AND is_active = 0
            """)
    int archive(@Param("adminId") long adminId,
                @Param("templateId") long templateId,
                @Param("expectedVersion") int expectedVersion,
                @Param("newVersion") int newVersion);
}
