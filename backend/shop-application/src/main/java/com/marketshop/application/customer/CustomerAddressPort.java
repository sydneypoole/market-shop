package com.marketshop.application.customer;

import com.marketshop.application.customer.CustomerAddressUseCase.AddressView;
import com.marketshop.application.customer.CustomerAddressUseCase.SaveAddressCommand;

import java.util.List;

public interface CustomerAddressPort {

    List<AddressView> addresses(long userId);

    AddressView save(long userId, SaveAddressCommand command);

    void delete(long userId, long addressId, int version);
}
