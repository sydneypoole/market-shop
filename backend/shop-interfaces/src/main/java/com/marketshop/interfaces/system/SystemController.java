package com.marketshop.interfaces.system;

import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final boolean devLoginEnabled;
    private final boolean wechatLoginEnabled;

    public SystemController(
            @Value("${market-shop.wechat.mock-enabled:false}") boolean devLoginEnabled,
            @Value("${market-shop.wechat.enabled:false}") boolean wechatLoginEnabled
    ) {
        this.devLoginEnabled = devLoginEnabled;
        this.wechatLoginEnabled = wechatLoginEnabled;
    }

    @GetMapping("/about")
    public ApiResponse<Map<String, Object>> about() {
        return ApiResponse.ok(Map.of(
                "name", "特殊分销商城演示版",
                "onlinePaymentEnabled", false,
                "cashWithdrawalEnabled", false,
                "pointsCashEquivalent", false,
                "rewardDepth", 1
        ));
    }

    @GetMapping("/capabilities")
    public ApiResponse<RuntimeCapabilities> capabilities() {
        return ApiResponse.ok(new RuntimeCapabilities(devLoginEnabled, wechatLoginEnabled));
    }

    public record RuntimeCapabilities(boolean devLoginEnabled, boolean wechatLoginEnabled) {
    }
}
