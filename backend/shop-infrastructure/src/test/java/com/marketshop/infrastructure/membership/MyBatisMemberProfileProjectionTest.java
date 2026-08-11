package com.marketshop.infrastructure.membership;

import com.marketshop.application.membership.MemberAdminUseCase.MemberQuery;
import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.MemberAdminRow;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisMemberProfileProjectionTest {

    @Test
    void listAndDetailExposeOwnedAvatarMaskedPhoneAndVerificationTime() {
        DistributionMapper mapper = mock(DistributionMapper.class);
        MemberAdminRow row = memberRow();
        when(mapper.adminMembers("138****", null, null, 0, 20)).thenReturn(List.of(row));
        when(mapper.countAdminMembers("138****", null, null)).thenReturn(1L);
        when(mapper.adminMember(42)).thenReturn(row);
        when(mapper.memberEvidence(42)).thenReturn(List.of());
        when(mapper.memberLevelChanges(42)).thenReturn(List.of());
        when(mapper.memberLedgerDetail(42)).thenReturn(List.of());
        MyBatisMemberAdminAdapter adapter = new MyBatisMemberAdminAdapter(mapper);

        var summary = adapter.search(new MemberQuery("138****", null, null, 1, 20)).items().getFirst();
        var detail = adapter.detail(42).member();

        assertThat(summary.avatarUrl()).isEqualTo("/api/v1/member-avatars/42");
        assertThat(summary.phoneMasked()).isEqualTo("138****8000");
        assertThat(summary.phoneVerifiedAt()).isNotNull();
        assertThat(detail.avatarUrl()).isEqualTo(summary.avatarUrl());
        assertThat(detail.phoneMasked()).isEqualTo(summary.phoneMasked());
    }

    @Test
    void memberKeywordFilterUsesOnlyThePersistedMaskedPhoneInListAndCount() throws Exception {
        String listSql = selectSql(DistributionMapper.class.getMethod(
                "adminMembers", String.class, String.class, String.class, int.class, int.class
        ));
        String countSql = selectSql(DistributionMapper.class.getMethod(
                "countAdminMembers", String.class, String.class, String.class
        ));

        assertThat(listSql).contains("u.phone_masked LIKE").doesNotContain("phone_number");
        assertThat(countSql).contains("u.phone_masked LIKE").doesNotContain("phone_number");
    }

    private static String selectSql(Method method) {
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private static MemberAdminRow memberRow() {
        MemberAdminRow row = new MemberAdminRow();
        row.userId = 42L;
        row.publicId = "MEMBER-PUBLIC-ID";
        row.nickname = "宏杉会员";
        row.avatarUrl = "/api/v1/member-avatars/42";
        row.phoneMasked = "138****8000";
        row.phoneVerifiedAt = LocalDateTime.of(2026, 8, 12, 9, 0);
        row.status = "ACTIVE";
        row.levelCode = "BASIC_MEMBER";
        row.levelName = "普通会员";
        row.directCount = 0;
        row.qualifiedDirectCount = 0;
        row.availablePoints = 0L;
        row.frozenPoints = 0L;
        row.createdAt = LocalDateTime.of(2026, 8, 12, 8, 0);
        return row;
    }
}
