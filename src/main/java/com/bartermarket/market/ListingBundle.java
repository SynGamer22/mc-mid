package com.bartermarket.market;

public class ListingBundle {
    private ItemSpec offer;
    private ItemSpec demand;

    public ItemSpec getOffer() {
        return offer;
    }

    public void setOffer(ItemSpec offer) {
        this.offer = offer;
    }

    public ItemSpec getDemand() {
        return demand;
    }

    public void setDemand(ItemSpec demand) {
        this.demand = demand;
    }
}
