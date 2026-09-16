package com.tirener.shadiom.shadiomrpoverhaul.client.title;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of players' active title ids, keyed by entity id.
 * Populated by {@code TitleSyncPacket}; read by {@code TitleEventHandler} to render titles
 * on other players (and, via the same sync, on yourself).
 */
public final class ClientTitleCache {

    private ClientTitleCache() {}

    private static final Map<Integer, String> TITLES = new ConcurrentHashMap<>();

    public static void apply(int entityId, String titleId) {
        if (titleId == null || titleId.isEmpty()) TITLES.remove(entityId);
        else TITLES.put(entityId, titleId);
    }

    public static String get(int entityId) {
        return TITLES.get(entityId);
    }

    public static void clear() {
        TITLES.clear();
    }
}
