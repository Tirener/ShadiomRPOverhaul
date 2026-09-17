package com.tirener.shadiom.shadiomrpoverhaul;

import net.minecraft.world.level.border.WorldBorder;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Pins the overworld's border to a fixed 7500x7500 area centered on spawn every server start,
 *  regardless of what an op may have set it to in between. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class WorldBorderHandler {

    private WorldBorderHandler() {}

    private static final double SIZE = 7500;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WorldBorder border = event.getServer().overworld().getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(SIZE);
    }
}
