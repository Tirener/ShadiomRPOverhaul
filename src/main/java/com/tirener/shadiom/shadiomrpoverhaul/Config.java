package com.tirener.shadiom.shadiomrpoverhaul;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {

    private Config() {}

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue CHAT_RADIUS = BUILDER
            .comment("How far, in blocks, proximity chat carries.")
            .defineInRange("chatRadius", 32, 1, 512);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static volatile int chatRadius = 32;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        chatRadius = CHAT_RADIUS.get();
    }

    public static void setChatRadius(int blocks) {
        CHAT_RADIUS.set(blocks);
        CHAT_RADIUS.save();
        chatRadius = blocks;
    }
}
