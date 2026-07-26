package com.marketshop.interfaces.customer;

import com.marketshop.application.customer.CustomerAddressUseCase;
import com.marketshop.application.customer.CustomerAddressUseCase.SaveAddressCommand;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/addresses")
public class CustomerAddressController {

    private final CustomerAddressUseCase addresses;

    public CustomerAddressController(CustomerAddressUseCase addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    public ApiResponse<List<CustomerAddressUseCase.AddressView>> list() {
        return ApiResponse.ok(addresses.addresses(userId()));
    }

    @PostMapping
    public ApiResponse<CustomerAddressUseCase.AddressView> create(@Valid @RequestBody SaveAddressRequest request) {
        return ApiResponse.ok(addresses.save(userId(), request.command(null, 0)));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<CustomerAddressUseCase.AddressView> update(
            @PathVariable long addressId,
            @Valid @RequestBody SaveAddressRequest request
    ) {
        return ApiResponse.ok(addresses.save(userId(), request.command(addressId, request.version())));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(@PathVariable long addressId, @RequestParam int version) {
        addresses.delete(userId(), addressId, version);
        return ApiResponse.ok(null);
    }

    private static long userId() {
        return StpUserKit.logic().getLoginIdAsLong();
    }

    public record SaveAddressRequest(
            @NotBlank @Size(max = 80) String recipientName,
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 80) String province,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String district,
            @NotBlank @Size(max = 255) String detailAddress,
            @Size(max = 20) String postalCode,
            boolean defaultAddress,
            int version
    ) {
        SaveAddressCommand command(Long id, int expectedVersion) {
            return new SaveAddressCommand(
                    id, recipientName, phone, province, city, district, detailAddress,
                    postalCode, defaultAddress, expectedVersion
            );
        }
    }
}
