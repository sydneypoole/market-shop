package com.marketshop.application.customer;

import java.util.List;

public interface CustomerAddressUseCase {

    List<AddressView> addresses(long userId);

    AddressView save(long userId, SaveAddressCommand command);

    void delete(long userId, long addressId, int version);

    record SaveAddressCommand(
            Long addressId,
            String recipientName,
            String phone,
            String province,
            String city,
            String district,
            String detailAddress,
            String postalCode,
            boolean defaultAddress,
            int version
    ) {
    }

    record AddressView(
            long id,
            String recipientName,
            String phone,
            String province,
            String city,
            String district,
            String detailAddress,
            String postalCode,
            boolean defaultAddress,
            int version
    ) {
    }
}
