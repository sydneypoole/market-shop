package com.marketshop.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(long fen) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (fen < 0) {
            throw new DomainException("MONEY_NEGATIVE", "金额不能为负数");
        }
    }

    public static Money ofYuan(BigDecimal yuan) {
        Objects.requireNonNull(yuan, "yuan");
        return new Money(yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
    }

    public Money add(Money other) {
        return new Money(Math.addExact(fen, other.fen));
    }

    public Money multiply(int quantity) {
        if (quantity <= 0) {
            throw new DomainException("QUANTITY_INVALID", "商品数量必须大于零");
        }
        return new Money(Math.multiplyExact(fen, quantity));
    }

    public BigDecimal toYuan() {
        return BigDecimal.valueOf(fen, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(fen, other.fen);
    }
}
