package com.tirener.shadiom.shadiomrpoverhaul.names;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * All known first names and surnames, plus the filter chains applied when computing what one
 * player may currently pick from. Internal storage only; consuming mods go through
 * {@link ShadiomNameAPI}.
 */
final class NameRegistry {

    private NameRegistry() {}

    private static final Set<String> FIRST_NAMES = new LinkedHashSet<>();
    private static final Set<String> SURNAMES = new LinkedHashSet<>();
    private static final List<NameFilter> FIRST_NAME_FILTERS = new ArrayList<>();
    private static final List<NameFilter> SURNAME_FILTERS = new ArrayList<>();

    static void registerFirstName(String name) { FIRST_NAMES.add(name); }
    static void registerSurname(String name) { SURNAMES.add(name); }
    static void addFirstNameFilter(NameFilter filter) { FIRST_NAME_FILTERS.add(filter); }
    static void addSurnameFilter(NameFilter filter) { SURNAME_FILTERS.add(filter); }

    static List<String> candidateFirstNames(ServerPlayer player) {
        return apply(FIRST_NAMES, FIRST_NAME_FILTERS, player);
    }

    static List<String> candidateSurnames(ServerPlayer player) {
        return apply(SURNAMES, SURNAME_FILTERS, player);
    }

    private static List<String> apply(Set<String> base, List<NameFilter> filters, ServerPlayer player) {
        List<String> candidates = new ArrayList<>(base);
        for (NameFilter filter : filters) candidates = filter.apply(player, candidates);
        return candidates;
    }
}
