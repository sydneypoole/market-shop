package com.marketshop.infrastructure.aftersale;

import com.marketshop.application.aftersale.AfterSalePort;
import com.marketshop.application.membership.OrderTimerParameters;
import com.marketshop.application.membership.RuleRuntimeResolver;
import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.application.aftersale.AfterSaleUseCase.View;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper.InsertRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.EligibilityRow;
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisAfterSaleAdapter implements AfterSalePort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final AfterSaleMapper mapper;
    private final NotificationMapper notifications;
    private final CommerceMapper commerce;

    public MyBatisAfterSaleAdapter(
            AfterSaleMapper mapper,
            NotificationMapper notifications,
            CommerceMapper commerce
    ) {
        this.mapper = mapper;
        this.notifications = notifications;
        this.commerce = commerce;
    }

    @Override
    public Optional<OrderEligibility> orderEligibility(long orderId) {
        EligibilityRow row = mapper.orderEligibility(orderId);
        return row == null ? Optional.empty() : Optional.of(new OrderEligibility(
                row.orderId,
                row.buyerUserId,
                row.status,
                instant(row.completedAt),
                row.activeAfterSaleCount == null ? 0 : row.activeAfterSaleCount,
                row.completedAfterSaleCount == null ? 0 : row.completedAfterSaleCount
        ));
    }

    @Override
    public Optional<View> findByClientRequest(long userId, String clientRequestId) {
        return Optional.ofNullable(mapper.findByClientRequest(userId, clientRequestId))
                .map(MyBatisAfterSaleAdapter::view);
    }

    @Override
    @Transactional
    public View create(long userId, String afterSaleNo, ApplyCommand command) {
        commerce.lockOrderForUpdate(command.orderId());
        EligibilityRow locked = mapper.orderEligibility(command.orderId());
        if (locked == null) {
            throw new DomainException("ORDER_NOT_FOUND", "订单不存在");
        }
        int completed = locked.completedAfterSaleCount == null ? 0 : locked.completedAfterSaleCount;
        int active = locked.activeAfterSaleCount == null ? 0 : locked.activeAfterSaleCount;
        if (completed > 0) {
            throw new DomainException("AFTERSALE_ALREADY_COMPLETED", "该订单已完成售后，不能再次申请");
        }
        if (active > 0) {
            throw new DomainException("AFTERSALE_ALREADY_EXISTS", "当前订单已有进行中的售后");
        }
        InsertRow row = new InsertRow();
        try {
            mapper.insertAfterSale(
                    row,
                    afterSaleNo,
                    command.orderId(),
                    userId,
                    command.type(),
                    command.reason(),
                    command.description(),
                    command.clientRequestId()
            );
        } catch (DuplicateKeyException exception) {
            AfterSaleRow existing = mapper.findByClientRequest(userId, command.clientRequestId());
            if (existing != null) {
                return view(existing);
            }
            if (isCompletedOrderUnique(exception)) {
                throw new DomainException("AFTERSALE_ALREADY_COMPLETED", "该订单已完成售后，不能再次申请");
            }
            throw exception;
        }
        View created = view(mapper.afterSale(row.id));
        notify(created.superiorUserId(), "AFTERSALE_APPLIED", "直属下级提交售后申请",
                "售后单 " + created.afterSaleNo() + " 已提交，等待后台审核。", created.id(), created.status());
        return created;
    }

    @Override
    public List<View> userAfterSales(long userId) {
        return mapper.userAfterSales(userId).stream().map(MyBatisAfterSaleAdapter::view).toList();
    }

    @Override
    public List<View> superiorAfterSales(long superiorUserId) {
        return mapper.superiorAfterSales(superiorUserId).stream().map(MyBatisAfterSaleAdapter::view).toList();
    }

    @Override
    public List<View> adminAfterSales(String status) {
        return mapper.adminAfterSales(status).stream().map(MyBatisAfterSaleAdapter::view).toList();
    }

    @Override
    public View load(long afterSaleId) {
        AfterSaleRow row = mapper.afterSale(afterSaleId);
        if (row == null) {
            throw new DomainException("AFTERSALE_NOT_FOUND", "售后单不存在");
        }
        return view(row);
    }

    @Override
    @Transactional
    public void transition(long afterSaleId, String expectedStatus, String targetStatus, TransitionData data) {
        AfterSaleRow current = null;
        if ("COMPLETED".equals(targetStatus)) {
            current = mapper.afterSale(afterSaleId);
            if (current == null) {
                throw new DomainException("AFTERSALE_NOT_FOUND", "售后单不存在");
            }
            commerce.lockOrderForUpdate(current.orderId);
        }
        int updated = mapper.transition(
                afterSaleId,
                expectedStatus,
                targetStatus,
                data.adminReason(),
                data.returnAddressJson(),
                data.returnCarrier(),
                data.returnTrackingNo(),
                data.refundConfirmedByUserId(),
                local(data.refundConfirmedAt()),
                local(data.completedAt())
        );
        if (updated != 1) {
            throw new DomainException("AFTERSALE_CONCURRENT_MODIFICATION", "售后单已更新，请刷新后重试");
        }
        if (current != null && "RETURN_REFUND".equals(current.type)) {
            restockReturnedItems(current.orderId);
        }
        if (data.emitCompletedEvent()) {
            mapper.insertCompletedOutbox(UUID.randomUUID().toString(), String.valueOf(afterSaleId));
        }
        notifyTransition(view(mapper.afterSale(afterSaleId)));
    }

    private void restockReturnedItems(long orderId) {
        for (OrderItemRow item : commerce.orderItems(orderId)) {
            if (commerce.restockAvailableInventory(item.skuId, item.quantity) != 1) {
                throw new DomainException("INVENTORY_RESTOCK_FAILED", "退货退款完成时回补库存失败");
            }
        }
    }

    private static boolean isCompletedOrderUnique(DuplicateKeyException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause == null ? exception.getMessage() : cause.getMessage();
        return message != null && message.contains("uk_after_sale_completed_order");
    }

    @Override
    public int afterSaleWindowDays() {
        RuleRow current = mapper.activeOrderTimerRule();
        if (current == null) {
            throw RuleRuntimeResolver.invalidOrderTimer();
        }
        return timer(current).afterSaleDaysAfterCompletion();
    }

    private static OrderTimerParameters timer(RuleRow row) {
        return RuleRuntimeResolver.orderTimer(row.ruleCode, row.ruleType, row.parametersJson);
    }

    private static View view(AfterSaleRow row) {
        return new View(
                row.id,
                row.afterSaleNo,
                row.orderId,
                row.applicantUserId,
                row.superiorUserId,
                row.type,
                row.status,
                row.reason,
                row.adminReason,
                row.returnAddressJson,
                row.returnCarrier,
                row.returnTrackingNo,
                instant(row.createdAt),
                instant(row.completedAt)
        );
    }

    private void notifyTransition(View sale) {
        switch (sale.status()) {
            case "AWAITING_RETURN" -> notify(
                    sale.applicantUserId(), "AFTERSALE_AWAITING_RETURN", "售后审核通过，请寄回商品",
                    "售后单 " + sale.afterSaleNo() + " 已审核通过，请按退货信息寄回商品。",
                    sale.id(), sale.status()
            );
            case "PENDING_OFFLINE_REFUND" -> notify(
                    sale.superiorUserId(), "AFTERSALE_PENDING_OFFLINE_REFUND", "售后待线下退款",
                    "售后单 " + sale.afterSaleNo() + " 已进入线下退款阶段，请完成退款后在系统确认。",
                    sale.id(), sale.status()
            );
            case "PENDING_BUYER_REFUND_CONFIRMATION" -> notify(
                    sale.applicantUserId(), "AFTERSALE_REFUND_CONFIRMED", "直属上级已确认线下退款",
                    "售后单 " + sale.afterSaleNo() + " 已由直属上级确认退款，请核对后确认收款。",
                    sale.id(), sale.status()
            );
            case "REJECTED" -> notify(
                    sale.applicantUserId(), "AFTERSALE_REJECTED", "售后申请未通过",
                    "售后单 " + sale.afterSaleNo() + " 未通过审核，请查看原因。",
                    sale.id(), sale.status()
            );
            case "COMPLETED" -> {
                notify(
                        sale.applicantUserId(), "AFTERSALE_COMPLETED", "售后已完成",
                        "售后单 " + sale.afterSaleNo() + " 已完成。",
                        sale.id(), sale.status()
                );
                notify(
                        sale.superiorUserId(), "AFTERSALE_COMPLETED", "直属下级售后已完成",
                        "售后单 " + sale.afterSaleNo() + " 已完成。",
                        sale.id(), sale.status()
                );
            }
            default -> {
                // Only lifecycle milestones that require attention generate notifications.
            }
        }
    }

    private void notify(long userId, String template, String title, String content,
                        long afterSaleId, String status) {
        notifications.insertUser(
                userId, template, title, content, "AFTERSALE", Long.toString(afterSaleId),
                "aftersale-notification:" + afterSaleId + ":" + status + ":" + userId
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }
}
