package com.tirener.shadiom.shadiomrpoverhaul;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Carries this mod's whole per-player NBT namespace across every player rebuild Forge reports as a
 * clone: death respawn, end-portal return, and dimension changes that route through respawn.
 * <p>
 * Without this, everything stored here is silently dropped. {@code ServerPlayer.restoreFrom} only
 * copies the vanilla {@code "PlayerPersisted"} sub-tag out of {@code getPersistentData()}; any
 * other top-level tag, this mod's included, is left behind on the old player instance. For the
 * title system that cost the player their title on death; for the names system it also burned
 * their unique first+surname combo out of a finite pool with no way to reclaim it.
 * <p>
 * Both subsystems share one namespace tag on purpose, so one copy keeps them in step: a player
 * comes back from a respawn with their chosen name and their title both intact, and the two
 * still compose the same way they did before they died.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class PlayerDataCloneHandler {

    private PlayerDataCloneHandler() {}

    /** Must stay in step with the NAMESPACE constant in TitlePlayerData and NamePlayerData. */
    private static final String NAMESPACE = Shadiomrpoverhaul.MODID;

    /**
     * Copied on non-death clones too, not just death. Returning from a custom dimension also
     * rebuilds the player, and skipping that case wipes the data just as thoroughly.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        CompoundTag original = event.getOriginal().getPersistentData();
        if (!original.contains(NAMESPACE, Tag.TAG_COMPOUND)) return;
        event.getEntity().getPersistentData().put(NAMESPACE, original.getCompound(NAMESPACE).copy());
    }
}
