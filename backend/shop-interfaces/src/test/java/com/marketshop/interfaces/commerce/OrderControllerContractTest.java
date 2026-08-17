package com.marketshop.interfaces.commerce;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import com.marketshop.application.commerce.CommerceUseCase;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.application.commerce.CommerceUseCase.SubmitOrderCommand;
import com.marketshop.interfaces.commerce.OrderController.AddressRequest;
import com.marketshop.interfaces.commerce.OrderController.ItemRequest;
import com.marketshop.interfaces.commerce.OrderController.SubmitOrderRequest;
import com.marketshop.interfaces.security.StpUserKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerContractTest {

    private SaTokenDao previousDao;

    @BeforeEach
    void useIsolatedSessionStore() {
        previousDao = SaManager.getSaTokenDao();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    }

    @AfterEach
    void restoreSessionStore() {
        SaTokenContextMockUtil.clearContext();
        SaManager.setSaTokenDao(previousDao);
    }

    @Test
    void mapsMiniprogramSourceAndBuyerNoteIntoTheApplicationCommand() {
        AtomicReference<SubmitOrderCommand> captured = new AtomicReference<>();
        CommerceUseCase useCase = (CommerceUseCase) Proxy.newProxyInstance(
                CommerceUseCase.class.getClassLoader(),
                new Class<?>[]{CommerceUseCase.class},
                (proxy, method, arguments) -> {
                    if ("submit".equals(method.getName())) {
                        assertThat(arguments[0]).isEqualTo(10L);
                        captured.set((SubmitOrderCommand) arguments[1]);
                        return new OrderView(
                                100,
                                "MS100",
                                10,
                                20,
                                2_980,
                                "PENDING_SUPERIOR",
                                null,
                                Instant.EPOCH
                        );
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        OrderController controller = new OrderController(useCase);

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(10);

            var response = controller.submit(new SubmitOrderRequest(
                    "mp-request-100",
                    "MINIPROGRAM",
                    new AddressRequest(
                            "张三",
                            "13800138000",
                            "广东省",
                            "深圳市",
                            "南山区",
                            "科技园 1 号",
                            null
                    ),
                    List.of(new ItemRequest(1, 1, 2_980)),
                    "工作日配送"
            ));

            assertThat(response.data().id()).isEqualTo(100);
            assertThat(captured.get()).satisfies(command -> {
                assertThat(command.source()).isEqualTo("MINIPROGRAM");
                assertThat(command.buyerNote()).isEqualTo("工作日配送");
                assertThat(command.items()).singleElement().satisfies(item -> {
                    assertThat(item.skuId()).isEqualTo(1L);
                    assertThat(item.quantity()).isEqualTo(1);
                    assertThat(item.unitPriceFen()).isEqualTo(2_980L);
                });
            });
        });
    }
}
