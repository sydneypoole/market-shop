package com.marketshop.application.customer;

import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerAddressApplicationService implements CustomerAddressUseCase {

    private final CustomerAddressPort port;

    public CustomerAddressApplicationService(CustomerAddressPort port) {
        this.port = port;
    }

    @Override
    public List<AddressView> addresses(long userId) {
        return port.addresses(userId);
    }

    @Override
    public AddressView save(long userId, SaveAddressCommand command) {
        text(command.recipientName(), "ADDRESS_RECIPIENT_REQUIRED", "收货人不能为空", 80);
        String phone = text(command.phone(), "ADDRESS_PHONE_REQUIRED", "联系电话不能为空", 32);
        if (!phone.matches("[0-9+\\- ]{6,32}")) {
            throw new DomainException("ADDRESS_PHONE_INVALID", "联系电话格式不正确");
        }
        text(command.province(), "ADDRESS_REGION_REQUIRED", "省份不能为空", 80);
        text(command.city(), "ADDRESS_REGION_REQUIRED", "城市不能为空", 80);
        text(command.district(), "ADDRESS_REGION_REQUIRED", "区县不能为空", 80);
        text(command.detailAddress(), "ADDRESS_DETAIL_REQUIRED", "详细地址不能为空", 255);
        if (command.postalCode() != null && command.postalCode().length() > 20) {
            throw new DomainException("ADDRESS_POSTAL_INVALID", "邮政编码过长");
        }
        if (command.addressId() == null && port.addresses(userId).size() >= 20) {
            throw new DomainException("ADDRESS_LIMIT_EXCEEDED", "最多保存 20 个收货地址");
        }
        return port.save(userId, command);
    }

    @Override
    public void delete(long userId, long addressId, int version) {
        port.delete(userId, addressId, version);
    }

    private static String text(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainException(code, message);
        }
        if (value.trim().length() > maxLength) {
            throw new DomainException(code, message + "或长度超限");
        }
        return value.trim();
    }
}
