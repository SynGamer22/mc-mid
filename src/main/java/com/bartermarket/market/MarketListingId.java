package com.bartermarket.market;

import java.util.UUID;

public final class MarketListingId {
    private MarketListingId() {
    }

    public static String nextId() {
        return UUID.randomUUID().toString();
    }
}
