package com.marketshop.interfaces.commerce;

import com.marketshop.application.commerce.CommerceUseCase;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CommerceUseCase commerce;

    public CartController(CommerceUseCase commerce) {
        this.commerce = commerce;
    }

    @GetMapping
    public ApiResponse<List<CommerceUseCase.CartItemView>> cart() {
        return ApiResponse.ok(commerce.cart(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PutMapping("/items/{skuId}")
    public ApiResponse<Void> setItem(@PathVariable long skuId, @Valid @RequestBody SetCartItemRequest request) {
        commerce.setCartItem(
                StpUserKit.logic().getLoginIdAsLong(),
                skuId,
                request.quantity(),
                request.selected()
        );
        return ApiResponse.ok(null);
    }

    public record SetCartItemRequest(@Min(0) @Max(99) int quantity, boolean selected) {
    }
}
