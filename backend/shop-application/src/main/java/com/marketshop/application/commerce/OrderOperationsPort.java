package com.marketshop.application.commerce;

import com.marketshop.application.commerce.OrderOperationsUseCase.DashboardView;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderNoteView;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderPage;
import com.marketshop.application.commerce.OrderOperationsUseCase.OrderSearchQuery;

import java.util.List;

public interface OrderOperationsPort {

    OrderPage search(OrderSearchQuery query);

    List<OrderNoteView> notes(long orderId);

    void addNote(long adminId, long orderId, String note);

    DashboardView dashboard(int lowInventoryThreshold);
}
