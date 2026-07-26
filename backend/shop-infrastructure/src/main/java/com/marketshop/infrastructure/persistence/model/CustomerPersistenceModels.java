package com.marketshop.infrastructure.persistence.model;

public final class CustomerPersistenceModels {

    private CustomerPersistenceModels() {
    }

    public static class AddressRow {
        public Long id;
        public String recipientName;
        public String phone;
        public String province;
        public String city;
        public String district;
        public String detailAddress;
        public String postalCode;
        public Boolean defaultAddress;
        public Integer version;
    }
}
