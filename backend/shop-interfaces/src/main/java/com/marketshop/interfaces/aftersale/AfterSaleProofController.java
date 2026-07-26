package com.marketshop.interfaces.aftersale;

import com.marketshop.application.aftersale.AfterSaleProofUseCase;
import com.marketshop.interfaces.security.StpAdminKit;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AfterSaleProofController {

    private final AfterSaleProofUseCase proofs;

    public AfterSaleProofController(AfterSaleProofUseCase proofs) {
        this.proofs = proofs;
    }

    @PostMapping(value = "/after-sales/{afterSaleId}/proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AfterSaleProofUseCase.ProofView> upload(
            @PathVariable long afterSaleId,
            @RequestParam(defaultValue = "APPLICATION") String proofType,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return ApiResponse.ok(proofs.uploadUser(
                StpUserKit.logic().getLoginIdAsLong(), afterSaleId, proofType, file.getBytes()
        ));
    }

    @GetMapping("/after-sales/{afterSaleId}/proofs")
    public ApiResponse<List<AfterSaleProofUseCase.ProofView>> list(@PathVariable long afterSaleId) {
        return ApiResponse.ok(proofs.listUser(StpUserKit.logic().getLoginIdAsLong(), afterSaleId));
    }

    @GetMapping("/after-sale-proofs/{proofId}/download")
    public ApiResponse<AfterSaleProofUseCase.DownloadView> userDownload(@PathVariable long proofId) {
        return ApiResponse.ok(proofs.userDownload(StpUserKit.logic().getLoginIdAsLong(), proofId));
    }

    @GetMapping("/admin/after-sale-proofs/{proofId}/download")
    public ApiResponse<AfterSaleProofUseCase.DownloadView> adminDownload(@PathVariable long proofId) {
        StpAdminKit.requirePermission("aftersale:review");
        return ApiResponse.ok(proofs.adminDownload(StpAdminKit.logic().getLoginIdAsLong(), proofId));
    }

    @GetMapping("/admin/after-sales/{afterSaleId}/proofs")
    public ApiResponse<List<AfterSaleProofUseCase.ProofView>> adminList(@PathVariable long afterSaleId) {
        StpAdminKit.requirePermission("aftersale:review");
        return ApiResponse.ok(proofs.listAdmin(
                StpAdminKit.logic().getLoginIdAsLong(),
                afterSaleId
        ));
    }
}
