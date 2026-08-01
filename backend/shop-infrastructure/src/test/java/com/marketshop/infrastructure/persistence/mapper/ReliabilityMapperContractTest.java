package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReliabilityMapperContractTest {

    @Test
    void retryUpdateUsesCompareAndSetAndCanTransitionToDead() throws Exception {
        Method method = ReliabilityMapper.class.getMethod(
                "recordFailure",
                long.class,
                int.class,
                LocalDateTime.class,
                String.class,
                boolean.class
        );
        String sql = String.join("\n", method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("attempt_count = attempt_count + 1")
                .contains("status = CASE WHEN #{dead} THEN 'DEAD' ELSE 'PENDING' END")
                .contains("attempt_count = #{expectedAttemptCount}")
                .contains("last_error = #{lastError}");
    }

    @Test
    void deadLetterQueryDoesNotExposePayloadAndReplayRequiresDeadState() throws Exception {
        String query = String.join("\n", ReliabilityMapper.class
                .getMethod("deadLetters", int.class, int.class)
                .getAnnotation(Select.class)
                .value());
        String replay = String.join("\n", ReliabilityMapper.class
                .getMethod("replayDeadLetter", long.class, long.class)
                .getAnnotation(Update.class)
                .value());

        assertThat(query).contains("status = 'DEAD'").doesNotContain("payload_json");
        assertThat(replay)
                .contains("WHERE id = #{outboxId} AND status = 'DEAD'")
                .contains("replay_count = replay_count + 1")
                .contains("last_replayed_by_admin_id = #{adminId}");
    }
}
