package com.marketshop.interfaces.commerce;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import com.marketshop.application.commerce.CommerceApplicationService;
import com.marketshop.application.commerce.CommercePort;
import com.marketshop.application.commerce.CommerceUseCase.OrderActorCapabilities;
import com.marketshop.application.commerce.CommerceUseCase.OrderDetail;
import com.marketshop.application.commerce.CommerceUseCase.OrderItemView;
import com.marketshop.application.commerce.CommerceUseCase.OrderView;
import com.marketshop.interfaces.security.StpUserKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerCapabilitiesTest {

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

    @ParameterizedTest(name = "controller returns {0} actions in {2}")
    @MethodSource("controllerCapabilityMatrix")
    void derivesCapabilitiesFromTheAuthenticatedActorAndCurrentOrderState(
            String actor,
            long actorUserId,
            String status,
            OrderActorCapabilities expected
    ) {
        CommercePort port = (CommercePort) Proxy.newProxyInstance(
                CommercePort.class.getClassLoader(),
                new Class<?>[]{CommercePort.class},
                (proxy, method, arguments) -> {
                    if ("order".equals(method.getName())) {
                        assertThat(arguments).containsExactly(100L);
                        return detail(status);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        OrderController controller = new OrderController(new CommerceApplicationService(port));

        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(actorUserId);

            var response = controller.order(100);

            assertThat(response.data().actorCapabilities())
                    .as("%s capabilities in %s", actor, status)
                    .isEqualTo(expected);
        });
    }

    private static Stream<Arguments> controllerCapabilityMatrix() {
        return Stream.of("PENDING_SUPERIOR", "PENDING_ADMIN_REVIEW", "SHIPPED", "COMPLETED")
                .flatMap(status -> Stream.of(
                        Arguments.of(
                                "buyer",
                                10L,
                                status,
                                new OrderActorCapabilities(
                                        "SHIPPED".equals(status),
                                        "PENDING_SUPERIOR".equals(status),
                                        "PENDING_SUPERIOR".equals(status),
                                        false
                                )
                        ),
                        Arguments.of(
                                "superior",
                                20L,
                                status,
                                new OrderActorCapabilities(
                                        false,
                                        false,
                                        false,
                                        "PENDING_SUPERIOR".equals(status)
                                )
                        )
                ));
    }

    private static OrderDetail detail(String status) {
        Instant now = Instant.now();
        return new OrderDetail(
                new OrderView(100, "MS100", 10, 20, 2_980, status, null, now),
                "{\"recipientName\":\"张三\"}",
                List.of(new OrderItemView(
                        1,
                        "体验商品",
                        "默认规格",
                        null,
                        "UPGRADE",
                        2_980,
                        1,
                        2_980
                )),
                null,
                now,
                now,
                now.plusSeconds(86_400),
                null,
                OrderActorCapabilities.none()
        );
    }
}
