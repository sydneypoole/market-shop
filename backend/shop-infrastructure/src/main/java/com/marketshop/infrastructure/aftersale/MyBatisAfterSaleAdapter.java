package com.marketshop.infrastructure.aftersale;

import com.marketshop.application.aftersale.AfterSalePort;
import com.marketshop.application.aftersale.AfterSaleUseCase.ApplyCommand;
import com.marketshop.application.aftersale.AfterSaleUseCase.View;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.mapper.AfterSaleMapper.InsertRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.EligibilityRow;
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

    public MyBatisAfterSaleAdapter(AfterSaleMapper mapper, NotificationMapper notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @Override
    public Optional<OrderEligibility> orderEligibility(long orderId) {
        EligibilityRow row = mapper.orderEligibility(orderId);
        return row == null ? Optional.empty() : Optional.of(new OrderEligibility(
                row.orderId,
                row.buyerUserId,
                row.status,
                instant(row.completedAt),
                row.activeAfterSaleCount
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
        InsertRow row = new InsertRow();
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
        if (data.emitCompletedEvent()) {
            mapper.insertCompletedOutbox(UUID.randomUUID().toString(), String.valueOf(afterSaleId));
        }
        notifyTransition(view(mapper.afterSale(afterSaleId)));
    }

    @Override
    public int afterSaleWindowDays() {
        Integer days = mapper.afterSaleWindowDays();
        if (days == null || days < 1 || days > 365) {
            // Eligibility windows come from the immutable ORDER_TIMERS
            // version.  Falling back here would allow an application under a
            // policy that was never published.
            throw new DomainException("ORDER_TIMER_SETTINGS_INVALID", "订单时效规则缺失或无效");
        }
        return days;
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
