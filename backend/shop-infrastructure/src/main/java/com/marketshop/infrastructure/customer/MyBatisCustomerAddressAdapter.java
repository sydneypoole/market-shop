package com.marketshop.infrastructure.customer;

import com.marketshop.application.customer.CustomerAddressPort;
import com.marketshop.application.customer.CustomerAddressUseCase.AddressView;
import com.marketshop.application.customer.CustomerAddressUseCase.SaveAddressCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.CustomerMapper;
import com.marketshop.infrastructure.persistence.model.CustomerPersistenceModels.AddressRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class MyBatisCustomerAddressAdapter implements CustomerAddressPort {

    private final CustomerMapper mapper;

    public MyBatisCustomerAddressAdapter(CustomerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AddressView> addresses(long userId) {
        return mapper.addresses(userId).stream().map(MyBatisCustomerAddressAdapter::view).toList();
    }

    @Override
    @Transactional
    public AddressView save(long userId, SaveAddressCommand command) {
        AddressRow row = row(command);
        boolean makeDefault = command.defaultAddress() || addresses(userId).isEmpty();
        row.defaultAddress = makeDefault;
        if (makeDefault) {
            mapper.clearDefault(userId, command.addressId());
        }
        if (command.addressId() == null) {
            mapper.insert(userId, row);
        } else if (mapper.update(userId, row) != 1) {
            throw new DomainException("ADDRESS_CONFLICT", "收货地址不存在或已被修改");
        }
        return addresses(userId).stream().filter(address -> address.id() == row.id).findFirst()
                .orElseThrow(() -> new DomainException("ADDRESS_SAVE_FAILED", "收货地址保存失败"));
    }

    @Override
    public void delete(long userId, long addressId, int version) {
        if (mapper.delete(userId, addressId, version) != 1) {
            throw new DomainException("ADDRESS_CONFLICT", "收货地址不存在或已被修改");
        }
    }

    private static AddressRow row(SaveAddressCommand command) {
        AddressRow row = new AddressRow();
        row.id = command.addressId();
        row.recipientName = command.recipientName().trim();
        row.phone = command.phone().trim();
        row.province = command.province().trim();
        row.city = command.city().trim();
        row.district = command.district().trim();
        row.detailAddress = command.detailAddress().trim();
        row.postalCode = command.postalCode() == null ? null : command.postalCode().trim();
        row.version = command.version();
        return row;
    }

    private static AddressView view(AddressRow row) {
        return new AddressView(
                row.id,
                row.recipientName,
                row.phone,
                row.province,
                row.city,
                row.district,
                row.detailAddress,
                row.postalCode,
                Boolean.TRUE.equals(row.defaultAddress),
                row.version
        );
    }
}
