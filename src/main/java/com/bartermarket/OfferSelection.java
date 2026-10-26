package com.bartermarket;

import java.util.ArrayList;
import java.util.List;

public final class OfferSelection {
    private OfferSelection() {
    }

    public static <T> Result add(List<T> current, T selected, int slotLimit, StackAccess<T> access) {
        if (selected == null || !access.valid(selected) || slotLimit <= 0) return Result.INVALID_ITEM;
        if (!current.isEmpty() && current.stream().anyMatch(item -> !access.similar(item, current.get(0)))) {
            return Result.INVALID_SELECTION;
        }
        if (!current.isEmpty() && !access.similar(current.get(0), selected)) return Result.DIFFERENT_ITEM;

        T template = current.isEmpty() ? selected : current.get(0);
        long total = access.amount(selected) + current.stream().mapToLong(access::amount).sum();
        if (total > (long) access.maxStackSize(template) * slotLimit) return Result.CAPACITY_EXCEEDED;

        List<T> replacement = new ArrayList<>();
        long remaining = total;
        while (remaining > 0) {
            int amount = (int) Math.min(remaining, access.maxStackSize(template));
            replacement.add(access.withAmount(template, amount));
            remaining -= amount;
        }
        current.clear();
        current.addAll(replacement);
        return Result.ADDED;
    }

    public interface StackAccess<T> {
        boolean valid(T stack);
        boolean similar(T first, T second);
        int amount(T stack);
        int maxStackSize(T stack);
        T withAmount(T stack, int amount);
    }

    public enum Result {
        ADDED,
        DIFFERENT_ITEM,
        CAPACITY_EXCEEDED,
        INVALID_ITEM,
        INVALID_SELECTION
    }
}
