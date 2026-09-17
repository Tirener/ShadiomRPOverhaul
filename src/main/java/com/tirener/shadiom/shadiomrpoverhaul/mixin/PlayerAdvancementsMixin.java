package com.tirener.shadiom.shadiomrpoverhaul.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The vanilla advancement-earned chat broadcast reads {@link ServerPlayer#getDisplayName()},
 *  which {@code Player} caches lazily on first access and only recomputes via
 *  {@code refreshDisplayName()}. If anything reads it before this mod's name/title is applied
 *  (e.g. the vanilla join message, which fires before the name picker resolves), the cache
 *  sticks with the plain account name forever after, regardless of {@code NameEventHandler}
 *  calling {@code refreshDisplayName()} on pick - that call updates the cache, but doesn't
 *  retroactively fix anything that already read the stale value in the meantime. Force a fresh
 *  recompute right before this specific read instead, so the advancement announcement always
 *  matches the current nameplate/tab-list name. */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Redirect(method = "award", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component shadiomrpoverhaul$freshDisplayName(ServerPlayer player) {
        player.refreshDisplayName();
        return player.getDisplayName();
    }
}
