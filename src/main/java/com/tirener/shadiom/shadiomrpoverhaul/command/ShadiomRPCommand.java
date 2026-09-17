package com.tirener.shadiom.shadiomrpoverhaul.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tirener.shadiom.shadiomrpoverhaul.Config;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Root for every "shadiomrp &lt;target&gt; &lt;value&gt;" config command (op-only), e.g.
 * "shadiomrp chatrange 64". Add each new config command as another .then() branch below.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class ShadiomRPCommand {

    private ShadiomRPCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("shadiomrp")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("chatrange")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 512))
                                .executes(ShadiomRPCommand::setChatRange))));
    }

    private static int setChatRange(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
        Config.setChatRadius(blocks);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Proximity chat range set to " + blocks + " blocks."), true);
        return blocks;
    }
}
