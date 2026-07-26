package com.marketshop.application.catalog;

import com.marketshop.application.catalog.CatalogAdminUseCase.CategoryView;
import com.marketshop.application.catalog.CatalogAdminUseCase.ContentAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.InventoryAdjustmentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.InventoryAdjustmentView;
import com.marketshop.application.catalog.CatalogAdminUseCase.ProductAdminView;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveCategoryCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveContentCommand;
import com.marketshop.application.catalog.CatalogAdminUseCase.SaveProductCommand;

import java.util.List;

public interface CatalogAdminPort {

    List<CategoryView> categories();

    CategoryView saveCategory(SaveCategoryCommand command);

    void disableCategory(long categoryId);

    List<ProductAdminView> products();

    ProductAdminView saveProduct(SaveProductCommand command);

    void adjustInventory(long adminId, InventoryAdjustmentCommand command);

    List<InventoryAdjustmentView> inventoryAdjustments(long skuId);

    List<ContentAdminView> contents();

    ContentAdminView saveContent(SaveContentCommand command);

    void deleteContent(long contentId);
}
