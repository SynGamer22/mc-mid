package com.bartermarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfferSelectionTest {
    private static final OfferSelection.StackAccess<TestStack> STACKS = new OfferSelection.StackAccess<>() {
        @Override
        public boolean valid(TestStack stack) {
            return stack.amount() > 0;
        }

        @Override
        public boolean similar(TestStack first, TestStack second) {
            return first.kind().equals(second.kind());
        }

        @Override
        public int amount(TestStack stack) {
            return stack.amount();
        }

        @Override
        public int maxStackSize(TestStack stack) {
            return stack.maxStackSize();
        }

        @Override
        public TestStack withAmount(TestStack stack, int amount) {
            return new TestStack(stack.kind(), amount, stack.maxStackSize());
        }
    };

    @Test
    void rejectsDifferentItemTypeWithoutChangingOffer() {
        List<TestStack> offer = new ArrayList<>(List.of(new TestStack("STONE", 32, 64)));

        OfferSelection.Result result = OfferSelection.add(offer, new TestStack("DIRT", 1, 64), 7, STACKS);

        assertEquals(OfferSelection.Result.DIFFERENT_ITEM, result);
        assertEquals(1, offer.size());
        assertEquals("STONE", offer.get(0).kind());
        assertEquals(32, offer.get(0).amount());
    }

    @Test
    void packsOneItemKindAcrossAvailableOfferSlots() {
        List<TestStack> offer = new ArrayList<>();

        assertEquals(OfferSelection.Result.ADDED, OfferSelection.add(offer, new TestStack("STONE", 64, 64), 7, STACKS));
        assertEquals(OfferSelection.Result.ADDED, OfferSelection.add(offer, new TestStack("STONE", 64, 64), 7, STACKS));

        assertEquals(2, offer.size());
        assertTrue(offer.stream().allMatch(item -> item.kind().equals("STONE")));
        assertEquals(128, offer.stream().mapToInt(TestStack::amount).sum());
    }

    @Test
    void rejectsQuantitiesBeyondRemainingOfferSlots() {
        List<TestStack> offer = new ArrayList<>();
        assertEquals(OfferSelection.Result.ADDED, OfferSelection.add(offer, new TestStack("STONE", 64, 64), 1, STACKS));

        OfferSelection.Result result = OfferSelection.add(offer, new TestStack("STONE", 1, 64), 1, STACKS);

        assertEquals(OfferSelection.Result.CAPACITY_EXCEEDED, result);
        assertEquals(1, offer.size());
        assertEquals(64, offer.get(0).amount());
    }

    private record TestStack(String kind, int amount, int maxStackSize) {
    }
}
