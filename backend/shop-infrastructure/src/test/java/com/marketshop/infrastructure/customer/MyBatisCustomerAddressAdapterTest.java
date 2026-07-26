package com.marketshop.infrastructure.customer;

import com.marketshop.application.customer.CustomerAddressUseCase.SaveAddressCommand;
import com.marketshop.infrastructure.persistence.mapper.CustomerMapper;
import com.marketshop.infrastructure.persistence.model.CustomerPersistenceModels.AddressRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisCustomerAddressAdapterTest {

    @Test
    void updatingCurrentDefaultAddressMustNotInvalidateItsOptimisticLockVersion() {
        RecordingCustomerMapper mapper = new RecordingCustomerMapper();
        MyBatisCustomerAddressAdapter adapter = new MyBatisCustomerAddressAdapter(mapper);

        var saved = adapter.save(3L, new SaveAddressCommand(
                1L, "验收用户", "13800000000", "广东省", "深圳市", "南山区",
                "测试路2号", "518000", true, 0
        ));

        assertThat(mapper.excludedAddressId).isEqualTo(1L);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.detailAddress()).isEqualTo("测试路2号");
    }

    private static final class RecordingCustomerMapper implements CustomerMapper {

        private final AddressRow row = initialRow();
        private Long excludedAddressId;

        @Override
        public List<AddressRow> addresses(long userId) {
            return List.of(row);
        }

        @Override
        public int clearDefault(long userId, Long excludeAddressId) {
            excludedAddressId = excludeAddressId;
            if (!row.id.equals(excludeAddressId) && Boolean.TRUE.equals(row.defaultAddress)) {
                row.defaultAddress = false;
                row.version++;
            }
            return 0;
        }

        @Override
        public int insert(long userId, AddressRow row) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(long userId, AddressRow updated) {
            if (!row.id.equals(updated.id) || !row.version.equals(updated.version)) {
                return 0;
            }
            row.recipientName = updated.recipientName;
            row.phone = updated.phone;
            row.province = updated.province;
            row.city = updated.city;
            row.district = updated.district;
            row.detailAddress = updated.detailAddress;
            row.postalCode = updated.postalCode;
            row.defaultAddress = updated.defaultAddress;
            row.version++;
            return 1;
        }

        @Override
        public int delete(long userId, long addressId, int version) {
            throw new UnsupportedOperationException();
        }

        private static AddressRow initialRow() {
            AddressRow row = new AddressRow();
            row.id = 1L;
            row.recipientName = "验收用户";
            row.phone = "13800000000";
            row.province = "广东省";
            row.city = "深圳市";
            row.district = "南山区";
            row.detailAddress = "测试路1号";
            row.postalCode = "518000";
            row.defaultAddress = true;
            row.version = 0;
            return row;
        }
    }
}
