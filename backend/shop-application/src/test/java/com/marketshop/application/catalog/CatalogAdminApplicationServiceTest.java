package com.marketshop.application.catalog;

import com.marketshop.application.catalog.CatalogAdminUseCase.ProductAdminView;
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

    private static SaveProductCommand product(String attributes) {
        return new SaveProductCommand(
                null, 1, " 青山礼盒 ", " 品质复购 ", null, "<p>介绍</p>",
                "repurchase", "on_sale", 10, null, " gift-green ", " 青色 ",
                19_980, 21_980L, attributes, "on_sale", 20
        );
    }
}
