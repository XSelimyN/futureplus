/*
 * Derived from Homovore SwapManager by dev.leonetic.
 * This file is licensed under AGPL-3.0.
 */
package com.futureplus.client;

import com.futureplus.client.mixin.MultiPlayerGameModeAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Hidden: not in the ClickGui, the base of the Grim modes.
 *
 * Hotbar swaps for the Grim modes. A silent swap selects a slot, runs one action and selects back within the tick.
 * A tick gets a few of them, ranked by priority: the highest refused one has the last slot kept for it on the next
 * tick. A latch is the one visible swap, and it holds its slot until released.
 *
 * The counterparts of InventoryUtils.switchToSlot / switchToSlotGhost are here too; the slot the server knows is vanilla's
 * carriedIndex field (counterpart of RotationManager.serverSlot).
 */
public final class GrimSwapManager {
    private GrimSwapManager() {}

    static final int OFFHAND = 500;
    static final int PLACEMENT = 400;
    static final int CRYSTAL = 350;
    static final int ANCHOR = 300;
    static final int WEAPON = 200;
    static final int ESCAPE = 175;
    static final int USER_ACTION = 150;
    static final int MINING = 50;

    private static final int SILENT_BUDGET = 3;
    private static final int HOTBAR_SLOT_FIRST = 36;
    private static final int HOTBAR_SLOT_LAST = 44;
    private static final int OFFHAND_BUTTON = 40;

    private static int latchedSlot = -1;
    private static int serverHoldSlot = -1;
    private static int latchOrigin = -1;
    private static int silentUsed;
    private static int reserved = Integer.MIN_VALUE;
    private static int starved = Integer.MIN_VALUE;

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static boolean nullCheck() { Minecraft mc = mc(); return mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null; }

    /** Whether a Grim mode is on. */
    static boolean grimActive() { return PistonAura.isEnabled() && PistonAura.grim; }

    /** Ahead of everything else in the tick, so nothing reads the selected slot before the latch has it back. */
    static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> onTick());
    }

    /** False when the swap did not happen (including when it lost the tick's budget); the caller retries later. */
    static boolean withSwap(int slot, int priority, Runnable action) {
        if (nullCheck() || !mc().isSameThread() || slot < 0 || slot > 8) return false;

        if (serverHoldSlot != -1) return withSwapDuringHold(slot, priority, action);

        if (slot == selected()) {
            action.run();
            return true;
        }

        if (!claimSilent(priority)) return false;

        if (interruptLatch() && slot == selected()) {
            action.run();
            return true;
        }
        if (mc().player.isUsingItem()) return false;

        int last = selected();
        switchToSlot(slot);
        try {
            action.run();
        } finally {
            switchToSlot(last);
        }

        commitSilent(priority);
        return true;
    }

    /** With the server holding another slot, an action moves it there and back, whatever the client has selected. */
    private static boolean withSwapDuringHold(int slot, int priority, Runnable action) {
        if (slot == serverHoldSlot) {
            action.run();
            return true;
        }

        if (!claimSilent(priority)) return false;

        switchToSlotGhost(slot);
        try {
            action.run();
        } finally {
            switchToSlotGhost(serverHoldSlot);
        }
        commitSilent(priority);
        return true;
    }

    /** Makes the server hold a slot while the hand on screen stays the same, for what only counts with a tool held across ticks. */
    static boolean holdOnServer(int slot) {
        if (nullCheck() || !mc().isSameThread() || slot < 0 || slot > 8) return false;
        if (mc().player.isUsingItem() && mc().player.getUsedItemHand() == InteractionHand.MAIN_HAND) return false;

        serverHoldSlot = slot;
        switchToSlotGhost(slot);
        return true;
    }

    static void releaseServerHold() {
        if (serverHoldSlot == -1) return;

        serverHoldSlot = -1;
        if (!nullCheck()) switchToSlotGhost(selected());
    }

    /** Visibly selects the slot and keeps it until released. Fails during another latch or while using an item. */
    static boolean latch(int slot) {
        if (nullCheck() || !mc().isSameThread() || slot < 0 || slot > 8) return false;
        if (slot == selected()) return true;
        if (latchedSlot != -1 || mc().player.isUsingItem()) return false;

        latchOrigin = selected();
        latchedSlot = slot;
        switchToSlot(slot);
        return true;
    }

    static void release() {
        if (latchedSlot == -1) return;

        if (!nullCheck()) switchToSlot(latchOrigin);
        latchedSlot = -1;
        latchOrigin = -1;
    }

    static boolean isLatched() {
        return latchedSlot != -1;
    }

    /** Whether an action needing the main hand has to wait: held by a latch, or in use already. */
    static boolean shouldDefer() {
        if (mc().player == null) return false;
        return isLatched() || (mc().player.isUsingItem() && mc().player.getUsedItemHand() == InteractionHand.MAIN_HAND);
    }

    /** A silent swap outranks a latch: it comes down, along with the item use it was holding. */
    private static boolean interruptLatch() {
        if (latchedSlot == -1) return false;

        release();
        if (!nullCheck() && mc().player.isUsingItem()) mc().player.stopUsingItem();
        return true;
    }

    private static boolean claimSilent(int priority) {
        int available = priority < reserved ? SILENT_BUDGET - 1 : SILENT_BUDGET;
        if (silentUsed >= available) {
            if (priority > starved) starved = priority;
            return false;
        }
        return true;
    }

    private static void commitSilent(int priority) {
        silentUsed++;
        if (priority >= reserved) reserved = Integer.MIN_VALUE;
    }

    private static void onTick() {
        if (latchedSlot != -1) {
            if (nullCheck()) {
                latchedSlot = -1;
                latchOrigin = -1;
            } else if (selected() != latchedSlot) {
                switchToSlot(latchedSlot);
            }
        }

        // A slot chosen by hand or by a latch goes out by itself, so the hold is put back behind it.
        if (serverHoldSlot != -1 && !nullCheck()) switchToSlotGhost(serverHoldSlot);

        silentUsed = 0;
        reserved = starved;
        starved = Integer.MIN_VALUE;
    }

    /** The server echoing a slot we swapped through would otherwise replace the stack in hand and restart its animation. */
    public static boolean cancelSlotEcho(ClientboundContainerSetSlotPacket update) {
        if (!grimActive() || mc().player == null) return false;
        if (update.getContainerId() != 0 || update.getSlot() < HOTBAR_SLOT_FIRST || update.getSlot() > HOTBAR_SLOT_LAST) return false;

        ItemStack incoming = update.getItem();
        ItemStack hand = mc().player.getMainHandItem();
        if (incoming.isEmpty() || hand.isEmpty()) return false;
        return ItemStack.isSameItem(incoming, hand) && incoming.getCount() == hand.getCount();
    }

    /**
     * Throws an item from anywhere in the inventory with a look of its own. A window swap moves it into the held slot and
     * back, so the selected slot never changes and this works while an item is in use.
     */
    static boolean throwItem(Item item, int lookPriority, float yaw, float pitch, int amount) {
        if (nullCheck()) return false;

        GrimRotation.submitSilent(lookPriority, yaw, pitch);
        syncItem();

        if (mc().player.getOffhandItem().is(item)) {
            sendThrows(InteractionHand.OFF_HAND, yaw, pitch, amount);
            return true;
        }
        if (mc().player.getMainHandItem().is(item)) {
            sendThrows(InteractionHand.MAIN_HAND, yaw, pitch, amount);
            return true;
        }

        int slot = -1;
        for (int i = 0; i < 36 && slot == -1; i++)
            if (mc().player.getInventory().getItem(i).is(item)) slot = i;
        if (slot == -1) return false;

        int containerSlot = slot < 9 ? slot + HOTBAR_SLOT_FIRST : slot;
        int held = selected();
        click(containerSlot, held);
        sendThrows(InteractionHand.MAIN_HAND, yaw, pitch, amount);
        click(containerSlot, held);
        return true;
    }

    /** Moves whatever is in this inventory slot to the offhand. */
    static void swapToOffhand(int slot) {
        click(slot < 9 ? slot + HOTBAR_SLOT_FIRST : slot, OFFHAND_BUTTON);
    }

    private static void sendThrows(InteractionHand hand, float yaw, float pitch, int amount) {
        for (int i = 0; i < amount; i++)
            ((MultiPlayerGameModeAccessor) mc().gameMode).futureplus$predict(mc().level, seq -> new ServerboundUseItemPacket(hand, seq, yaw, pitch));
    }

    private static void click(int containerSlot, int button) {
        mc().gameMode.handleContainerInput(mc().player.containerMenu.containerId, containerSlot, button, ContainerInput.SWAP, mc().player);
    }

    private static int selected() {
        return mc().player.getInventory().getSelectedSlot();
    }

    // ------------------------------------------------------------- InventoryUtils counterparts

    /** InventoryUtils.switchToSlot: select and tell the server (no packet if unchanged). */
    static void switchToSlot(int slot) {
        mc().player.getInventory().setSelectedSlot(slot);
        syncItem();
    }

    /** InventoryUtils.switchToSlotGhost: a slot to the server without selecting it on the client. Sending the slot the server already has is an instant bad packet on Grim. */
    static void switchToSlotGhost(int slot) {
        MultiPlayerGameModeAccessor gm = (MultiPlayerGameModeAccessor) mc().gameMode;
        if (slot != -1 && slot != gm.futureplus$carriedIndex()) {
            mc().getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            gm.futureplus$setCarriedIndex(slot);
        }
    }

    /** InventoryUtils.syncItem. */
    static void syncItem() {
        ((MultiPlayerGameModeAccessor) mc().gameMode).futureplus$syncCarried();
    }

    /** InventoryUtils.getHotbarItemSlot. */
    static int hotbar(Item item) {
        var inv = mc().player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }
}
