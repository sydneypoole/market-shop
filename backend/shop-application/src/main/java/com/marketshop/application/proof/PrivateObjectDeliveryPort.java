package com.marketshop.application.proof;

public interface PrivateObjectDeliveryPort {

    PrivateContent readSigned(String token);

    record PrivateContent(String mediaType, byte[] bytes) {
    }
}
