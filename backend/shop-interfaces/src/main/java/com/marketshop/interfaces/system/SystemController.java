package com.marketshop.interfaces.system;

import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

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
}
