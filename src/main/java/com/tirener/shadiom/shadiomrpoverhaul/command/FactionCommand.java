package com.tirener.shadiom.shadiomrpoverhaul.command;

import com.mojang.brigadier.CommandDispatcher;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.faction.FactionEventHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opens the faction management GUI - every actual action happens through that screen, not
 *  subcommands, per the design spec. Any player may run it (not nested under the op-only
 *  {@code /shadiomrp} config root - this isn't a config command). */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class FactionCommand {

    private FactionCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("faction")
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
                    FactionEventHandler.openScreen(player);
                    return 1;
                }));
    }
}
