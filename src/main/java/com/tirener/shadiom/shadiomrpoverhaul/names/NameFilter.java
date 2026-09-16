package com.tirener.shadiom.shadiomrpoverhaul.names;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Narrows or replaces the candidate pool for one player. Filters run in registration order, each
 * receiving the previous filter's result. Returning a list unrelated to the input (not just a
 * subset) is how a hard lock works, e.g. returning {@code List.of("Ashi")} regardless of what was
 * passed in, so a locked value doesn't need to be a normal pool entry at all.
 */
public interface NameFilter {
    List<String> apply(ServerPlayer player, List<String> candidates);
}
