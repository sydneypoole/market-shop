package com.marketshop.interfaces.commerce;

import com.marketshop.application.proof.OrderProofUseCase;
import com.marketshop.application.proof.OrderProofUseCase.UploadCommand;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class OrderProofController {

    private final OrderProofUseCase proofs;

    public OrderProofController(OrderProofUseCase proofs) {
        this.proofs = proofs;
    }

    @PostMapping(value = "/orders/{orderId}/proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OrderProofUseCase.ProofView> upload(
            @PathVariable long orderId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return ApiResponse.ok(proofs.upload(
                StpUserKit.logic().getLoginIdAsLong(),
                new UploadCommand(orderId, file.getOriginalFilename(), file.getContentType(), file.getBytes())
        ));
    }

    @GetMapping("/orders/{orderId}/proofs")
    public ApiResponse<List<OrderProofUseCase.ProofView>> userList(@PathVariable long orderId) {
        return ApiResponse.ok(proofs.listUser(
                StpUserKit.logic().getLoginIdAsLong(),
                orderId
        ));
    }

    @GetMapping("/order-proofs/{proofId}/download")
    public ApiResponse<OrderProofUseCase.DownloadView> userDownload(@PathVariable long proofId) {
        return ApiResponse.ok(proofs.userDownload(StpUserKit.logic().getLoginIdAsLong(), proofId));
    }

    @GetMapping("/admin/order-proofs/{proofId}/download")
    public ApiResponse<OrderProofUseCase.DownloadView> adminDownload(@PathVariable long proofId) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(proofs.adminDownload(StpAdminKit.logic().getLoginIdAsLong(), proofId));
    }

    @GetMapping("/admin/orders/{orderId}/proofs")
    public ApiResponse<List<OrderProofUseCase.ProofView>> adminList(@PathVariable long orderId) {
        StpAdminKit.requirePermission("order:read");
        return ApiResponse.ok(proofs.listAdmin(
                StpAdminKit.logic().getLoginIdAsLong(),
                orderId
        ));
    }

    @DeleteMapping("/order-proofs/{proofId}")
    public ApiResponse<Void> userDelete(@PathVariable long proofId) {
        proofs.userDelete(StpUserKit.logic().getLoginIdAsLong(), proofId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/admin/order-proofs/{proofId}")
    public ApiResponse<Void> adminDelete(@PathVariable long proofId, @RequestBody DeleteRequest request) {
        StpAdminKit.requirePermission("order:audit");
        proofs.adminDelete(StpAdminKit.logic().getLoginIdAsLong(), proofId, request.reason());
        return ApiResponse.ok(null);
    }

    public record DeleteRequest(String reason) {
    }
}
