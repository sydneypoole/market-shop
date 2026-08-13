package com.marketshop.application.catalog;

import com.marketshop.application.catalog.CatalogAdminUseCase.ProductAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.ContentAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveContentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveProductCommand;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogAdminApplicationServiceTest {

    @Mock
    private CatalogAdminPort port;

    @InjectMocks
    private CatalogAdminApplicationService service;

    @Test
    void rejectsMalformedSkuAttributesInsteadOfPersistingJsonLikeText() {
        SaveProductCommand command = product("{\"颜色\":}");

        assertThatThrownBy(() -> service.saveProduct(command))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("合法 JSON");

        verify(port, never()).saveProduct(command);
    }

    @Test
    void normalizesProductCommandBeforePersistence() {
        SaveProductCommand command = product(" {\"颜色\":\"青色\"} ");
        when(port.saveProduct(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            SaveProductCommand saved = invocation.getArgument(0);
            return new ProductAdminView(
                    1, saved.categoryId(), saved.name(), saved.subtitle(), saved.coverUrl(),
                    saved.descriptionHtml(), saved.salesScene(), saved.status(), saved.sortOrder(),
                    2, saved.skuCode(), saved.skuName(), saved.priceFen(), saved.marketPriceFen(),
                    saved.attributesJson(), saved.skuStatus(), saved.initialInventory(), 0
            );
        });

        service.saveProduct(command);

        ArgumentCaptor<SaveProductCommand> captor = ArgumentCaptor.forClass(SaveProductCommand.class);
        verify(port).saveProduct(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("青山礼盒");
        assertThat(captor.getValue().skuCode()).isEqualTo("GIFT-GREEN");
        assertThat(captor.getValue().salesScene()).isEqualTo("REPURCHASE");
        assertThat(captor.getValue().attributesJson()).isEqualTo("{\"颜色\":\"青色\"}");
    }

    @Test
    void sanitizesRichTextAndPreservesOnlyBoundedCatalogImageWidths() {
        String html = "<h2 onclick=alert(1)>亮点</h2>"
                + "<img src=\"/api/v1/catalog/assets/41\" width=\"75%\" height=\"400\" style=\"width:999px\">"
                + "<img src=\"/api/v1/catalog/assets/42\" width=\"10%\">"
                + "<img src=\"/api/v1/catalog/assets/43\" width=\"9%\">"
                + "<img src=\"/api/v1/catalog/assets/44\" width=\"101%\">"
                + "<img src=\"https://tracker.example/image.png\" width=\"50%\">"
                + "<img src=\"data:image/png;base64,AA==\">"
                + "<img src=\"blob:https://example.test/id\">"
                + "<img src=\"/api/v1/catalog/assets/45\" onerror=\"alert(1)\">"
                + "<script>alert(1)</script>";
        SaveProductCommand original = product("{}");
        SaveProductCommand command = new SaveProductCommand(
                original.productId(), original.categoryId(), original.name(), original.subtitle(),
                original.coverUrl(), html, original.salesScene(), original.status(), original.sortOrder(),
                original.skuId(), original.skuCode(), original.skuName(), original.priceFen(),
                original.marketPriceFen(), original.attributesJson(), original.skuStatus(), original.initialInventory()
        );
        when(port.saveProduct(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            SaveProductCommand saved = invocation.getArgument(0);
            return new ProductAdminView(
                    1, saved.categoryId(), saved.name(), saved.subtitle(), saved.coverUrl(),
                    saved.descriptionHtml(), saved.salesScene(), saved.status(), saved.sortOrder(),
                    2, saved.skuCode(), saved.skuName(), saved.priceFen(), saved.marketPriceFen(),
                    saved.attributesJson(), saved.skuStatus(), saved.initialInventory(), 0
            );
        });

        service.saveProduct(command);

        ArgumentCaptor<SaveProductCommand> captor = ArgumentCaptor.forClass(SaveProductCommand.class);
        verify(port).saveProduct(captor.capture());
        assertThat(captor.getValue().descriptionHtml())
                .contains("<h2>亮点</h2>")
                .contains("src=\"/api/v1/catalog/assets/41\" width=\"75%\"")
                .contains("src=\"/api/v1/catalog/assets/42\" width=\"10%\"")
                .contains("src=\"/api/v1/catalog/assets/45\"")
                .doesNotContain("width=\"9%\"")
                .doesNotContain("width=\"101%\"")
                .doesNotContain("tracker.example", "data:image", "blob:", "script", "onclick", "onerror",
                        "style", "height");
    }

    @Test
    void sanitizesEditorialBodyAndDangerousLinksBeforePersistence() {
        String html = "<p>活动详情</p>"
                + "<a href=\"java&#x0A;script:alert(1)\">危险链接</a>"
                + "<a href=\"/rules\" onclick=\"alert(1)\">站内规则</a>"
                + "<a href=\"https://help.example.test/page\" target=\"_blank\">帮助</a>"
                + "<a href=\"https://help.example.test/other\" target=\"_evil\">其他帮助</a>"
                + "<img src=\"/api/v1/catalog/assets/9\" width=\"100%\" style=\"height:20px\">"
                + "<iframe src=\"https://evil.example\"></iframe>";
        SaveContentCommand command = new SaveContentCommand(
                null, "NOTICE", " 夏日活动 ", " 活动摘要 ", null, "/rules", html, "DRAFT", 3
        );
        when(port.saveContent(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            SaveContentCommand saved = invocation.getArgument(0);
            return new ContentAdminView(
                    1, saved.contentType(), saved.title(), saved.summary(), saved.coverUrl(), saved.targetUrl(),
                    saved.bodyHtml(), saved.status(), saved.sortOrder()
            );
        });

        service.saveContent(command);

        ArgumentCaptor<SaveContentCommand> captor = ArgumentCaptor.forClass(SaveContentCommand.class);
        verify(port).saveContent(captor.capture());
        assertThat(captor.getValue().bodyHtml())
                .contains("危险链接")
                .doesNotContain("javascript:")
                .contains("href=\"/rules\"")
                .contains("href=\"https://help.example.test/page\"")
                .contains("target=\"_blank\"")
                .contains("rel=\"noopener noreferrer\"")
                .contains("src=\"/api/v1/catalog/assets/9\" width=\"100%\"")
                .doesNotContain("target=\"_evil\"", "onclick", "style", "iframe", "evil.example");
    }

    private static SaveProductCommand product(String attributes) {
        return new SaveProductCommand(
                null, 1, " 青山礼盒 ", " 品质复购 ", null, "<p>介绍</p>",
                "repurchase", "on_sale", 10, null, " gift-green ", " 青色 ",
                19_980, 21_980L, attributes, "on_sale", 20
        );
    }
}
