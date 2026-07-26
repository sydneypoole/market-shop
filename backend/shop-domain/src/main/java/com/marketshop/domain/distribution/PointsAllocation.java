package com.marketshop.domain.distribution;

public record PointsAllocation(long availableAPoints, long frozenBPoints) {

    public static final PointsAllocation NONE = new PointsAllocation(0, 0);

    public long total() {
        return Math.addExact(availableAPoints, frozenBPoints);
    }

    public boolean eligible() {
        return total() > 0;
    }
}
