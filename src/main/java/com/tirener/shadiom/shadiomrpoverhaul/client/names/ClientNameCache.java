package com.tirener.shadiom.shadiomrpoverhaul.client.names;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of players' resolved display names, keyed by entity id. Populated by
 * {@code NameSyncPacket}; read by {@code NameEventHandler} to render names on other players
 * (and, via the same sync, on yourself, since nameplate rendering runs the NameFormat event
 * locally on every client for every entity it can see).
 */
public final class ClientNameCache {

    private ClientNameCache() {}

    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    public static void apply(int entityId, String displayName) {
        if (displayName == null || displayName.isEmpty()) NAMES.remove(entityId);
        else NAMES.put(entityId, displayName);
    }

    public static String get(int entityId) {
        return NAMES.get(entityId);
    }

    public static void clear() {
        NAMES.clear();
    }
}
