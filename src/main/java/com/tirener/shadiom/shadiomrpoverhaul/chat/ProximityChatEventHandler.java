package com.tirener.shadiom.shadiomrpoverhaul.chat;

import com.mojang.logging.LogUtils;
import com.tirener.shadiom.shadiomrpoverhaul.Config;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.names.ShadiomNameAPI;
import com.tirener.shadiom.shadiomrpoverhaul.title.ShadiomTitleAPI;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Replaces the vanilla server-wide chat broadcast with a same-dimension, in-range one, stacked
 *  as name / title / text instead of vanilla's single "&lt;name&gt; text" line. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class ProximityChatEventHandler {

    private ProximityChatEventHandler() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        event.setCanceled(true);

        ServerPlayer sender = event.getPlayer();
        String rawText = event.getRawText();
        String name = ShadiomNameAPI.hasPicked(sender)
                ? ShadiomNameAPI.displayName(sender)
                : sender.getGameProfile().getName();
        Component message = buildMessage(sender, name, rawText);
        LOGGER.info("{}: {}", name, rawText);

        long rangeSq = (long) Config.chatRadius * Config.chatRadius;
        for (ServerPlayer target : sender.getServer().getPlayerList().getPlayers()) {
            if (!target.level().dimension().equals(sender.level().dimension())) continue;
            if (target.distanceToSqr(sender) > rangeSq) continue;
            target.sendSystemMessage(message);
        }
    }

    private static Component buildMessage(ServerPlayer sender, String name, String rawText) {
        MutableComponent result = Component.literal("").append(ShadiomTitleAPI.styledName(sender, name));

        Component titleDisplay = ShadiomTitleAPI.titleDisplay(sender);
        if (titleDisplay != null) {
            result.append(", ").append(titleDisplay.copy().withStyle(ChatFormatting.ITALIC));
        }

        return result.append("\n» \"").append(ChatMarkup.parse(rawText)).append("\"");
    }
}
