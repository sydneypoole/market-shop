package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.AuditPersistenceModels.AuditRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditMapper {

    @Insert("""
            INSERT INTO operation_audit_log
                (actor_type, actor_id, action, resource_type, resource_id,
                 before_json, after_json, reason, request_id, ip_masked, user_agent_summary, occurred_at)
            VALUES
                (#{actorType}, #{actorId}, #{action}, #{resourceType}, #{resourceId},
                 CAST(#{beforeJson} AS JSON), CAST(#{afterJson} AS JSON), #{reason},
                 #{requestId}, #{maskedIp}, #{userAgentSummary}, #{occurredAt})
            """)
    int insert(
            @Param("actorType") String actorType,
            @Param("actorId") String actorId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson,
            @Param("reason") String reason,
            @Param("requestId") String requestId,
            @Param("maskedIp") String maskedIp,
            @Param("userAgentSummary") String userAgentSummary,
            @Param("occurredAt") LocalDateTime occurredAt
    );

    @Select("""
            <script>
            SELECT id, actor_type, actor_id, action, resource_type, resource_id,
                   CAST(before_json AS CHAR) AS before_json,
                   CAST(after_json AS CHAR) AS after_json,
                   reason, request_id, ip_masked, user_agent_summary, occurred_at
            FROM operation_audit_log
            <where>
                <if test="actorType != null">AND actor_type = #{actorType}</if>
                <if test="actorId != null">AND actor_id = #{actorId}</if>
                <if test="action != null">AND action = #{action}</if>
                <if test="resourceType != null">AND resource_type = #{resourceType}</if>
                <if test="resourceId != null">AND resource_id = #{resourceId}</if>
                <if test="requestId != null">AND request_id = #{requestId}</if>
                <if test="from != null">AND occurred_at &gt;= #{from}</if>
                <if test="to != null">AND occurred_at &lt;= #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditRow> search(
            @Param("actorType") String actorType,
            @Param("actorId") String actorId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("requestId") String requestId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM operation_audit_log
            <where>
                <if test="actorType != null">AND actor_type = #{actorType}</if>
                <if test="actorId != null">AND actor_id = #{actorId}</if>
                <if test="action != null">AND action = #{action}</if>
                <if test="resourceType != null">AND resource_type = #{resourceType}</if>
                <if test="resourceId != null">AND resource_id = #{resourceId}</if>
                <if test="requestId != null">AND request_id = #{requestId}</if>
                <if test="from != null">AND occurred_at &gt;= #{from}</if>
                <if test="to != null">AND occurred_at &lt;= #{to}</if>
            </where>
            </script>
            """)
    long count(
            @Param("actorType") String actorType,
            @Param("actorId") String actorId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("requestId") String requestId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
