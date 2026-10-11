package org.enthusia.tempchallenges.paper;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChallengeSignalListenerTest {
    @Test
    void completeNetheriteSetDoesNotDependOnArmorSlots() {
        assertTrue(ChallengeSignalListener.hasCompleteNetheriteSet(List.of(
                new ItemStack(Material.NETHERITE_BOOTS),
                new ItemStack(Material.NETHERITE_HELMET),
                new ItemStack(Material.NETHERITE_LEGGINGS),
                new ItemStack(Material.NETHERITE_CHESTPLATE))));
    }

    @Test
    void incompleteNetheriteSetDoesNotClaim() {
        assertFalse(ChallengeSignalListener.hasCompleteNetheriteSet(List.of(
                new ItemStack(Material.NETHERITE_HELMET),
                new ItemStack(Material.NETHERITE_LEGGINGS),
                new ItemStack(Material.NETHERITE_CHESTPLATE))));
    }
}
