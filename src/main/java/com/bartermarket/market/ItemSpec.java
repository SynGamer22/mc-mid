package com.bartermarket.market;

public class ItemSpec {
    private final String material;
    private final int amount;
    private final String displayName;

    public ItemSpec(String material, int amount, String displayName) {
        this.material = material;
        this.amount = amount;
        this.displayName = displayName;
    }

    public String material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    public String displayName() {
        return material + " x" + amount;
    }

    public String rawDisplayName() {
        return displayName;
    }
}
