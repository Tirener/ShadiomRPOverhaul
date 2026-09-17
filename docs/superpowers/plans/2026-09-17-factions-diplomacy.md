# Factions: Diplomacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Relations between factions (neutral/ally/war, leader-managed through the GUI), a static HTML diplomacy report regenerated on every change, and a read-only public `ShadiomFactionAPI`.

**Architecture:** `DiplomacyData` (new world-level `SavedData`, mirrors `ClaimsData`) stores an undirected relation per faction pair plus pending alliance proposals. Diplomacy actions live in `FactionEventHandler` alongside membership/claims (no new Forge event to hook, same request/response shape as membership). `DiplomacyReportWriter` (new, self-contained) owns both the HTML generation and its own server-start trigger; `FactionEventHandler` calls it explicitly after every state-changing action. `ShadiomFactionAPI` (new) is a thin read-only facade over the existing package-private storage classes, matching `ShadiomNameAPI`/`ShadiomTitleAPI`'s role in their packages.

**Tech Stack:** Same as sub-projects 1 and 2 - no new libraries. File I/O via `java.nio.file` and `server.getWorldPath(LevelResource.ROOT)`, first use of either in this mod.

**Spec:** `docs/superpowers/specs/2026-09-17-factions-diplomacy-design.md` (depends on `docs/superpowers/specs/2026-09-17-factions-membership-design.md`; independent of the land-claims spec, though it reuses `ClaimsData`'s storage pattern as a template)

## Global Constraints

- Java 17 / Forge 1.20.1 (47.4.23) - match every existing file's language level and imports.
- No test framework exists in this repo. Verification is `./gradlew compileJava` per task plus
  an in-game smoke test at the end - the HTML report in particular needs opening in an actual
  browser to confirm, not just compiling.
- Follow existing conventions: package-private `SavedData` for world storage (`DiplomacyData`
  mirrors `ClaimsData`), silent no-op on failed validation, action routing through the existing
  `FactionActionC2SPacket`/`OpenFactionScreenS2CPacket` pair rather than new packet types, every
  permission rule gets its own named method in `FactionPermissions` even where identical to an
  existing one (`canManageDiplomacy` mirrors `canDisband`).
- `DiplomacyData` stays package-private (`faction` package) - only `ShadiomFactionAPI`'s public
  methods and `FactionEventHandler`'s existing public methods are called from outside it.
- `ShadiomFactionAPI` is read-only in this pass - no mutation methods (see the design spec).

---

### Task 1: Diplomacy storage & permission rules

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/DiplomacyData.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java`

**Interfaces:**
- Produces: `DiplomacyData` (package-private, extends `SavedData`) - nested enum
  `DiplomacyData.Relation { ALLY, WAR }` (no NEUTRAL constant - absence means neutral); instance
  methods `relationBetween(String, String)` (`Relation` or `null`), `setRelation(String, String,
  Relation)`, `clearRelation(String, String)`, `proposalsFor(String factionId)` (`Set<String>`),
  `propose(String from, String to)`, `clearProposal(String from, String to)`,
  `allRelations()` (`Set<Map.Entry<String, Relation>>`), `releaseAll(String factionId)`; static
  `pairKey(String, String)` (String), static `get(ServerLevel)`.
- Produces: `FactionPermissions.canManageDiplomacy(Faction.Role)` (`boolean`).
- Produces: `FactionsData.all()` (`Collection<Faction>`, unmodifiable).

- [ ] **Step 1: Write `DiplomacyData.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** World-wide relations between factions, keyed by a canonical "idA|idB" pair (sorted so order
 *  doesn't matter), plus pending incoming alliance proposals. Storage only - {@code
 *  FactionEventHandler} holds every rule about who may change what, same division of
 *  responsibility as {@link ClaimsData}. */
final class DiplomacyData extends SavedData {

    enum Relation { ALLY, WAR }

    private final Map<String, Relation> relations = new HashMap<>();
    private final Map<String, Set<String>> allianceProposals = new HashMap<>();

    static String pairKey(String factionIdA, String factionIdB) {
        return factionIdA.compareTo(factionIdB) <= 0
                ? factionIdA + "|" + factionIdB
                : factionIdB + "|" + factionIdA;
    }

    /** {@code null} means NEUTRAL - only ALLY/WAR are ever actually stored. */
    Relation relationBetween(String factionIdA, String factionIdB) {
        return relations.get(pairKey(factionIdA, factionIdB));
    }

    void setRelation(String factionIdA, String factionIdB, Relation relation) {
        relations.put(pairKey(factionIdA, factionIdB), relation);
        setDirty();
    }

    void clearRelation(String factionIdA, String factionIdB) {
        relations.remove(pairKey(factionIdA, factionIdB));
        setDirty();
    }

    Set<String> proposalsFor(String factionId) {
        return allianceProposals.getOrDefault(factionId, Set.of());
    }

    void propose(String fromFactionId, String toFactionId) {
        allianceProposals.computeIfAbsent(toFactionId, k -> new HashSet<>()).add(fromFactionId);
        setDirty();
    }

    void clearProposal(String fromFactionId, String toFactionId) {
        Set<String> proposals = allianceProposals.get(toFactionId);
        if (proposals == null) return;
        proposals.remove(fromFactionId);
        if (proposals.isEmpty()) allianceProposals.remove(toFactionId);
        setDirty();
    }

    Set<Map.Entry<String, Relation>> allRelations() {
        return relations.entrySet();
    }

    /** Removes every relation and proposal involving {@code factionId} - called on disband, same
     *  orphan-prevention concern {@code ClaimsData.releaseAll} solves for claims. A plain linear
     *  scan, not a reverse index - see the design spec for why that's fine at this scale. */
    void releaseAll(String factionId) {
        relations.keySet().removeIf(key -> involves(key, factionId));
        allianceProposals.keySet().removeIf(id -> id.equals(factionId));
        for (Set<String> proposers : allianceProposals.values()) proposers.remove(factionId);
        allianceProposals.values().removeIf(Set::isEmpty);
        setDirty();
    }

    private static boolean involves(String pairKey, String factionId) {
        String[] parts = pairKey.split("\\|", 2);
        return parts.length == 2 && (parts[0].equals(factionId) || parts[1].equals(factionId));
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        CompoundTag relationsTag = new CompoundTag();
        for (Map.Entry<String, Relation> entry : relations.entrySet()) {
            relationsTag.putString(entry.getKey(), entry.getValue().name());
        }
        nbt.put("relations", relationsTag);

        CompoundTag proposalsTag = new CompoundTag();
        for (Map.Entry<String, Set<String>> entry : allianceProposals.entrySet()) {
            ListTag list = new ListTag();
            for (String id : entry.getValue()) list.add(StringTag.valueOf(id));
            proposalsTag.put(entry.getKey(), list);
        }
        nbt.put("allianceProposals", proposalsTag);
        return nbt;
    }

    static DiplomacyData load(CompoundTag nbt) {
        DiplomacyData data = new DiplomacyData();

        CompoundTag relationsTag = nbt.getCompound("relations");
        for (String key : relationsTag.getAllKeys()) {
            data.relations.put(key, Relation.valueOf(relationsTag.getString(key)));
        }

        CompoundTag proposalsTag = nbt.getCompound("allianceProposals");
        for (String key : proposalsTag.getAllKeys()) {
            ListTag list = proposalsTag.getList(key, Tag.TAG_STRING);
            Set<String> set = new HashSet<>();
            for (int i = 0; i < list.size(); i++) set.add(list.getString(i));
            data.allianceProposals.put(key, set);
        }
        return data;
    }

    static DiplomacyData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                DiplomacyData::load,
                DiplomacyData::new,
                "shadiomrpoverhaul_diplomacy"
        );
    }
}
```

- [ ] **Step 2: Add `canManageDiplomacy` to `FactionPermissions.java`**

Add after `canBreakFactionCenter`:

```java
    static boolean canManageDiplomacy(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }
```

Add to the self-check `main` method, before `System.out.println(...)`:

```java
        check(canManageDiplomacy(Faction.Role.LEADER), "leader can manage diplomacy");
        check(!canManageDiplomacy(Faction.Role.OFFICER), "officer cannot manage diplomacy");
        check(!canManageDiplomacy(Faction.Role.MEMBER), "member cannot manage diplomacy");
```

- [ ] **Step 3: Run the standalone self-check (still zero Minecraft imports)**

```bash
mkdir -p /tmp/faction-check3 && cd /tmp/faction-check3
javac -d . /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java
java -cp . com.tirener.shadiom.shadiomrpoverhaul.faction.FactionPermissions
```

Expected: `FactionPermissions self-check passed.`

- [ ] **Step 4: Add `all()` to `FactionsData.java`**

Add the imports `java.util.Collection` and `java.util.Collections`, and this method (next to
`exists`):

```java
    Collection<Faction> all() {
        return Collections.unmodifiableCollection(factions.values());
    }
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/DiplomacyData.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java
git commit -m "Add diplomacy storage and permission rules"
```

---

### Task 2: Diplomacy report writer & public API

Both of these depend only on Task 1's storage classes (`FactionsData.all()`, `DiplomacyData`)
plus `ClaimsData` and `FactionsData`/`Faction` from earlier sub-projects - nothing here depends
on `FactionEventHandler`, the packets, or `FactionScreen`, so this is independently compilable
and even independently testable (the server-start report trigger works before any diplomacy
action is wired up anywhere else).

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/DiplomacyReportWriter.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ShadiomFactionAPI.java`

**Interfaces:**
- Consumes: `FactionsData.all/get/factionOf`, `Faction.id/name/leader/roleOf/allMembers`,
  `ClaimsData.get/get(chunkKey)/chunkKey`, `DiplomacyData.get/relationBetween/allRelations`.
- Produces: `DiplomacyReportWriter.write(MinecraftServer)` (package-private - only called from
  within the `faction` package). Produces the full `ShadiomFactionAPI` public surface listed in
  the design spec.

- [ ] **Step 1: Write `DiplomacyReportWriter.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.mojang.logging.LogUtils;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes a static HTML snapshot of the current diplomatic situation to
 *  "&lt;world save folder&gt;/ShadiomRP/diplomacy.html" - see the design spec. Regenerated after
 *  every action that changes what it would show (called explicitly from
 *  {@link FactionEventHandler}) and once on server start. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class DiplomacyReportWriter {

    private DiplomacyReportWriter() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        write(event.getServer());
    }

    static void write(MinecraftServer server) {
        try {
            FactionsData factions = FactionsData.get(server.overworld());
            DiplomacyData diplomacy = DiplomacyData.get(server.overworld());
            String html = buildHtml(factions, diplomacy);

            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ShadiomRP");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("diplomacy.html"), html);
        } catch (IOException e) {
            LOGGER.warn("Failed to write the diplomacy report", e);
        }
    }

    private static String buildHtml(FactionsData factions, DiplomacyData diplomacy) {
        List<Faction> all = new ArrayList<>(factions.all());
        all.sort(Comparator.comparing(Faction::name, String.CASE_INSENSITIVE_ORDER));

        Map<String, List<String>> allies = new HashMap<>();
        Map<String, List<String>> wars = new HashMap<>();
        for (Map.Entry<String, DiplomacyData.Relation> entry : diplomacy.allRelations()) {
            String[] ids = entry.getKey().split("\\|", 2);
            if (ids.length != 2) continue;
            Faction a = factions.get(ids[0]);
            Faction b = factions.get(ids[1]);
            if (a == null || b == null) continue;
            Map<String, List<String>> bucket =
                    entry.getValue() == DiplomacyData.Relation.ALLY ? allies : wars;
            bucket.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(b.name());
            bucket.computeIfAbsent(b.id(), k -> new ArrayList<>()).add(a.name());
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Shadiom Diplomacy</title><style>")
                .append("body{font-family:sans-serif;background:#1b1b1b;color:#eee;padding:24px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #444;padding:8px;text-align:left}")
                .append("th{background:#2a2a2a}.ally{color:#7CFC7C}.war{color:#FF7C7C}.none{color:#888}")
                .append("</style></head><body>")
                .append("<h1>Shadiom Diplomacy</h1>")
                .append("<p>Generated ").append(Instant.now()).append("</p>")
                .append("<table><tr><th>Faction</th><th>Allies</th><th>At War With</th></tr>");

        for (Faction faction : all) {
            List<String> factionAllies = allies.get(faction.id());
            List<String> factionWars = wars.get(faction.id());
            html.append("<tr><td>").append(escape(faction.name())).append("</td>")
                    .append("<td class=\"").append(factionAllies == null ? "none" : "ally").append("\">")
                    .append(joinOrNone(factionAllies)).append("</td>")
                    .append("<td class=\"").append(factionWars == null ? "none" : "war").append("\">")
                    .append(joinOrNone(factionWars)).append("</td></tr>");
        }

        html.append("</table></body></html>");
        return html.toString();
    }

    private static String joinOrNone(List<String> names) {
        if (names == null || names.isEmpty()) return "None";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(escape(names.get(i)));
        }
        return joined.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 2: Write `ShadiomFactionAPI.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public, read-only entry point for the factions feature - membership, the faction directory,
 * claim ownership, and diplomacy. No mutation methods in this pass (create/kick/declare war/
 * etc. already exist as the in-game GUI flow with permission checks tied to the acting player -
 * see the design spec for why a programmatic mutation surface is a separate question). Mirrors
 * {@code ShadiomNameAPI}/{@code ShadiomTitleAPI}'s role as the public facade over this package's
 * otherwise package-private storage.
 */
public final class ShadiomFactionAPI {

    private ShadiomFactionAPI() {}

    // -- Membership --

    public static boolean hasFaction(ServerPlayer player) {
        return FactionsData.get(player.serverLevel()).factionOf(player.getUUID()) != null;
    }

    public static String factionOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        return faction == null ? null : faction.name();
    }

    public static String factionIdOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        return faction == null ? null : faction.id();
    }

    public static String roleOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        if (faction == null) return null;
        Faction.Role role = faction.roleOf(player.getUUID());
        return role == null ? null : role.name();
    }

    public static UUID leaderOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.leader();
    }

    public static List<UUID> memberIdsOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? List.of() : List.copyOf(faction.allMembers());
    }

    // -- Directory --

    public static List<String> allFactionIds(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (Faction faction : FactionsData.get(server.overworld()).all()) ids.add(faction.id());
        return ids;
    }

    public static String factionName(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.name();
    }

    // -- Claims --

    public static String claimOwner(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry == null ? null : entry.factionId();
    }

    public static boolean isCapital(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry != null && entry.capital();
    }

    // -- Diplomacy --

    public static String relationBetween(MinecraftServer server, String factionIdA, String factionIdB) {
        DiplomacyData.Relation relation = DiplomacyData.get(server.overworld())
                .relationBetween(factionIdA, factionIdB);
        return relation == null ? "NEUTRAL" : relation.name();
    }

    public static boolean areAllied(MinecraftServer server, String factionIdA, String factionIdB) {
        return DiplomacyData.get(server.overworld()).relationBetween(factionIdA, factionIdB)
                == DiplomacyData.Relation.ALLY;
    }

    public static boolean areAtWar(MinecraftServer server, String factionIdA, String factionIdB) {
        return DiplomacyData.get(server.overworld()).relationBetween(factionIdA, factionIdB)
                == DiplomacyData.Relation.WAR;
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. If `server.getWorldPath(LevelResource.ROOT)` doesn't resolve, check
the exact signature via
`~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.23_mapped_official_1.20.1/forge-1.20.1-47.4.23_mapped_official_1.20.1-sources.jar`
(`net/minecraft/server/MinecraftServer.java` and `net/minecraft/world/level/storage/LevelResource.java`).

- [ ] **Step 4: In-game check that the report file appears on server start**

`./gradlew runServer`, let it fully start, then stop it. Confirm
`run/saves/<world name>/ShadiomRP/diplomacy.html` exists (path depends on the configured
`runServer` working directory - check `run/` under the project root first) and opens in a
browser showing every existing faction with "None"/"None" for allies/wars (since no diplomacy
actions exist yet at this point in the plan).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/DiplomacyReportWriter.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ShadiomFactionAPI.java
git commit -m "Add the diplomacy HTML report writer and public FactionAPI"
```

---

### Task 3: Business logic, networking, and GUI

Same coupled-cluster reasoning as both earlier sub-projects: the packets, `FactionEventHandler`,
and `FactionScreen` all reference each other, so they're written together and compiled once.

**Files:**
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenFactionScreenS2CPacket.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/FactionScreen.java`

- [ ] **Step 1: Extend `FactionActionC2SPacket.java`'s `Action` enum**

Change:

```java
    public enum Action { CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND, CLAIM, UNCLAIM }
```

to:

```java
    public enum Action {
        CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND, CLAIM, UNCLAIM,
        DECLARE_WAR, MAKE_PEACE, PROPOSE_ALLIANCE, ACCEPT_ALLIANCE, DECLINE_ALLIANCE, BREAK_ALLIANCE
    }
```

- [ ] **Step 2: Extend `OpenFactionScreenS2CPacket.java`'s record and encode/decode**

Change the record declaration from ending at `ownClaimedChunkKeys` to add four more fields:

```java
public record OpenFactionScreenS2CPacket(
        boolean hasFaction,
        boolean mustCreateFaction,
        String factionName,
        String viewerRole,
        List<String> memberNames,
        List<String> memberDisplayNames,
        List<String> memberRoles,
        List<String> invitablePlayerNames,
        List<String> invitableDisplayNames,
        List<String> pendingInviteIds,
        List<String> pendingInviteNames,
        String currentChunkOwner,
        boolean canManageClaims,
        List<String> ownClaimedChunkKeys,
        List<String> otherFactionNames,
        List<String> otherFactionRelations,
        List<String> incomingProposalNames,
        boolean canManageDiplomacy
) {
```

Replace the whole `encode` method with:

```java
    public static void encode(OpenFactionScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasFaction());
        buf.writeBoolean(pkt.mustCreateFaction());
        buf.writeUtf(pkt.factionName());
        buf.writeUtf(pkt.viewerRole());
        writeStringList(buf, pkt.memberNames());
        writeStringList(buf, pkt.memberDisplayNames());
        writeStringList(buf, pkt.memberRoles());
        writeStringList(buf, pkt.invitablePlayerNames());
        writeStringList(buf, pkt.invitableDisplayNames());
        writeStringList(buf, pkt.pendingInviteIds());
        writeStringList(buf, pkt.pendingInviteNames());
        buf.writeUtf(pkt.currentChunkOwner());
        buf.writeBoolean(pkt.canManageClaims());
        writeStringList(buf, pkt.ownClaimedChunkKeys());
        writeStringList(buf, pkt.otherFactionNames());
        writeStringList(buf, pkt.otherFactionRelations());
        writeStringList(buf, pkt.incomingProposalNames());
        buf.writeBoolean(pkt.canManageDiplomacy());
    }
```

Replace the whole `decode` method with:

```java
    public static OpenFactionScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenFactionScreenS2CPacket(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                buf.readUtf(),
                buf.readBoolean(),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                buf.readBoolean()
        );
    }
```

- [ ] **Step 3: Rewrite `FactionEventHandler.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.names.ShadiomNameAPI;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All faction membership, claim, and diplomacy rules live here - validates the actor's role
 *  against {@link FactionPermissions}, mutates {@link FactionsData}/{@link ClaimsData}/
 *  {@link DiplomacyData}, and refreshes the acting player's screen afterward. Every branch below
 *  is a silent no-op on failure (offline target, wrong role, stale state, ...) per the design
 *  spec's error-handling section - the refreshed snapshot sent at the end simply shows the actor
 *  nothing changed. */
public final class FactionEventHandler {

    private FactionEventHandler() {}

    /** A faction-less player's not-yet-submitted capital, from placing a Faction Center before
     *  a faction exists to claim it for. In-memory only, like {@code NameEventHandler}'s
     *  PENDING_PICKS - see the design spec for why that's fine here. */
    private record PendingCapital(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

    private static final Map<UUID, PendingCapital> PENDING_CAPITALS = new ConcurrentHashMap<>();

    public static boolean hasPendingCapital(UUID player) {
        return PENDING_CAPITALS.containsKey(player);
    }

    public static void recordPendingCapital(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos chunk) {
        PENDING_CAPITALS.put(player.getUUID(), new PendingCapital(dimension, chunk.x, chunk.z));
        openScreen(player);
    }

    public static void openScreen(ServerPlayer player) {
        send(player);
    }

    public static void handleAction(ServerPlayer player, FactionActionC2SPacket.Action action, String arg) {
        switch (action) {
            case CREATE -> create(player, arg);
            case INVITE -> invite(player, arg);
            case ACCEPT -> acceptInvite(player, arg);
            case DECLINE -> declineInvite(player, arg);
            case KICK -> kick(player, arg);
            case PROMOTE -> promote(player, arg);
            case DEMOTE -> demote(player, arg);
            case LEAVE -> leave(player);
            case DISBAND -> disband(player);
            case CLAIM -> claim(player);
            case UNCLAIM -> unclaim(player);
            case DECLARE_WAR -> declareWar(player, arg);
            case MAKE_PEACE -> makePeace(player, arg);
            case PROPOSE_ALLIANCE -> proposeAlliance(player, arg);
            case ACCEPT_ALLIANCE -> acceptAlliance(player, arg);
            case DECLINE_ALLIANCE -> declineAlliance(player, arg);
            case BREAK_ALLIANCE -> breakAlliance(player, arg);
        }
        send(player);
    }

    private static void create(ServerPlayer player, String name) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        PendingCapital pending = PENDING_CAPITALS.get(player.getUUID());
        if (pending == null) return; // must place a Faction Center first
        if (name == null || name.isBlank()) return;

        String id = slug(name);
        if (data.exists(id)) return;

        data.put(new Faction(id, name, player.getUUID()));
        ClaimsData.get(player.serverLevel()).claim(
                ClaimsData.chunkKey(pending.dimension(), pending.chunkX(), pending.chunkZ()), id, true);
        PENDING_CAPITALS.remove(player.getUUID());
        DiplomacyReportWriter.write(player.getServer());
    }

    private static void invite(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canInvite(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (data.factionOf(target.getUUID()) != null) return; // also rejects self-invite

        data.addInvite(target.getUUID(), faction.id());
        target.sendSystemMessage(Component.literal(
                "You've been invited to join " + faction.name() + ". Run /faction to respond."));
    }

    private static void acceptInvite(ServerPlayer player, String factionId) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        if (!data.invitesOf(player.getUUID()).contains(factionId)) return;

        Faction faction = data.get(factionId);
        if (faction == null) {
            data.removeInvite(player.getUUID(), factionId);
            return;
        }

        faction.addMember(player.getUUID());
        data.reindex(faction);
        clearInvites(data, player.getUUID());
    }

    private static void declineInvite(ServerPlayer player, String factionId) {
        data(player).removeInvite(player.getUUID(), factionId);
    }

    private static void kick(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        Faction.Role targetRole = faction.roleOf(target.getUUID());
        if (targetRole == null) return;
        if (!FactionPermissions.canKick(faction.roleOf(actor.getUUID()), targetRole)) return;

        faction.removeMember(target.getUUID());
        data.reindex(faction);
        target.sendSystemMessage(Component.literal("You've been kicked from " + faction.name() + "."));
    }

    private static void promote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canPromote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.MEMBER) return;

        faction.addOfficer(target.getUUID());
        data.reindex(faction);
    }

    private static void demote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDemote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.OFFICER) return;

        faction.demoteToMember(target.getUUID());
        data.reindex(faction);
    }

    private static void leave(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (faction.roleOf(player.getUUID()) == Faction.Role.LEADER) return; // must disband instead

        faction.removeMember(player.getUUID());
        data.reindex(faction);
    }

    private static void disband(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDisband(faction.roleOf(player.getUUID()))) return;

        ClaimsData.get(player.serverLevel()).releaseAll(faction.id());
        DiplomacyData.get(player.serverLevel()).releaseAll(faction.id());
        data.remove(faction.id());
        DiplomacyReportWriter.write(player.getServer());
    }

    private static void claim(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageClaims(faction.roleOf(player.getUUID()))) return;

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos chunk = player.chunkPosition();
        ResourceKey<Level> dimension = player.level().dimension();
        String key = ClaimsData.chunkKey(dimension, chunk.x, chunk.z);
        if (claims.get(key) != null) return; // already claimed by someone
        if (!isAdjacentToOwnClaim(claims, faction.id(), dimension, chunk)) return;

        claims.claim(key, faction.id(), false);
    }

    private static void unclaim(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageClaims(faction.roleOf(player.getUUID()))) return;

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos chunk = player.chunkPosition();
        String key = ClaimsData.chunkKey(player.level().dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry entry = claims.get(key);
        if (entry == null || !entry.factionId().equals(faction.id())) return;
        if (entry.capital()) return; // must break the Faction Center instead

        claims.unclaim(key);
    }

    private static boolean isAdjacentToOwnClaim(ClaimsData claims, String factionId,
                                                 ResourceKey<Level> dimension, ChunkPos chunk) {
        int[][] deltas = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : deltas) {
            String neighborKey = ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]);
            ClaimsData.ClaimEntry neighbor = claims.get(neighborKey);
            if (neighbor != null && neighbor.factionId().equals(factionId)) return true;
        }
        return false;
    }

    private static void declareWar(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null || target.id().equals(faction.id())) return;

        DiplomacyData.get(actor.serverLevel()).setRelation(faction.id(), target.id(), DiplomacyData.Relation.WAR);
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void makePeace(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) != DiplomacyData.Relation.WAR) return;

        diplomacy.clearRelation(faction.id(), target.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void proposeAlliance(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null || target.id().equals(faction.id())) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) == DiplomacyData.Relation.ALLY) return;

        diplomacy.propose(faction.id(), target.id());
    }

    private static void acceptAlliance(ServerPlayer actor, String proposerName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction proposer = data.get(slug(proposerName));
        if (proposer == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (!diplomacy.proposalsFor(faction.id()).contains(proposer.id())) return;

        diplomacy.setRelation(faction.id(), proposer.id(), DiplomacyData.Relation.ALLY);
        diplomacy.clearProposal(proposer.id(), faction.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void declineAlliance(ServerPlayer actor, String proposerName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction proposer = data.get(slug(proposerName));
        if (proposer == null) return;

        DiplomacyData.get(actor.serverLevel()).clearProposal(proposer.id(), faction.id());
    }

    private static void breakAlliance(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) != DiplomacyData.Relation.ALLY) return;

        diplomacy.clearRelation(faction.id(), target.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void clearInvites(FactionsData data, UUID player) {
        for (String id : List.copyOf(data.invitesOf(player))) data.removeInvite(player, id);
    }

    private static String slug(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private static FactionsData data(ServerPlayer player) {
        return FactionsData.get(player.serverLevel());
    }

    /** The RP name (Name & Surname), or the account username if the player hasn't picked one -
     *  same fallback {@code ProximityChatEventHandler} already uses. */
    private static String displayNameOf(ServerPlayer player) {
        return ShadiomNameAPI.hasPicked(player)
                ? ShadiomNameAPI.displayName(player)
                : player.getGameProfile().getName();
    }

    // -- Snapshot --

    private static void send(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
    }

    private static OpenFactionScreenS2CPacket buildSnapshot(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());

        if (faction == null) {
            boolean mustCreate = PENDING_CAPITALS.containsKey(player.getUUID());
            List<String> inviteIds = new ArrayList<>();
            List<String> inviteNames = new ArrayList<>();
            if (!mustCreate) {
                for (String id : data.invitesOf(player.getUUID())) {
                    Faction invited = data.get(id);
                    if (invited == null) continue;
                    inviteIds.add(id);
                    inviteNames.add(invited.name());
                }
            }
            return new OpenFactionScreenS2CPacket(false, mustCreate, "", "", List.of(), List.of(),
                    List.of(), List.of(), List.of(), inviteIds, inviteNames, "", false, List.of(),
                    List.of(), List.of(), List.of(), false);
        }

        List<String> memberNames = new ArrayList<>();
        List<String> memberDisplayNames = new ArrayList<>();
        List<String> memberRoles = new ArrayList<>();
        MinecraftServer server = player.getServer();
        for (UUID uuid : faction.allMembers()) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue; // offline members aren't listed - see design spec
            memberNames.add(member.getGameProfile().getName());
            memberDisplayNames.add(displayNameOf(member));
            memberRoles.add(faction.roleOf(uuid).name());
        }

        List<String> invitable = new ArrayList<>();
        List<String> invitableDisplayNames = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (data.factionOf(online.getUUID()) != null) continue;
            invitable.add(online.getGameProfile().getName());
            invitableDisplayNames.add(displayNameOf(online));
        }

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos here = player.chunkPosition();
        ResourceKey<Level> dimension = player.level().dimension();
        ClaimsData.ClaimEntry hereClaim = claims.get(ClaimsData.chunkKey(dimension, here.x, here.z));
        String currentChunkOwner = "";
        if (hereClaim != null) {
            Faction owner = data.get(hereClaim.factionId());
            currentChunkOwner = owner == null ? "" : owner.name();
        }

        List<String> ownClaims = new ArrayList<>();
        String dimensionPrefix = dimension.location() + ",";
        for (String key : claims.claimsOf(faction.id())) {
            if (key.startsWith(dimensionPrefix)) ownClaims.add(key);
        }

        DiplomacyData diplomacy = DiplomacyData.get(player.serverLevel());
        List<String> otherFactionNames = new ArrayList<>();
        List<String> otherFactionRelations = new ArrayList<>();
        for (Faction other : data.all()) {
            if (other.id().equals(faction.id())) continue;
            DiplomacyData.Relation relation = diplomacy.relationBetween(faction.id(), other.id());
            otherFactionNames.add(other.name());
            otherFactionRelations.add(relation == null ? "NEUTRAL" : relation.name());
        }

        List<String> incomingProposals = new ArrayList<>();
        for (String proposerId : diplomacy.proposalsFor(faction.id())) {
            Faction proposer = data.get(proposerId);
            if (proposer != null) incomingProposals.add(proposer.name());
        }

        String viewerRole = faction.roleOf(player.getUUID()).name();
        Faction.Role role = faction.roleOf(player.getUUID());
        return new OpenFactionScreenS2CPacket(true, false, faction.name(), viewerRole,
                memberNames, memberDisplayNames, memberRoles, invitable, invitableDisplayNames,
                List.of(), List.of(), currentChunkOwner,
                FactionPermissions.canManageClaims(role), ownClaims,
                otherFactionNames, otherFactionRelations, incomingProposals,
                FactionPermissions.canManageDiplomacy(role));
    }
}
```

- [ ] **Step 4: Add the diplomacy section to `FactionScreen.java`'s `initFactionView`**

Insert this block right after the existing "Show Faction Area" button (after its
`y += ROW_GAP;`) and before the `if (isLeader) { ... Disband ... } else { ... Leave ... }` block:

```java
        y += 12;
        shown = 0;
        for (int i = 0; i < state.otherFactionNames().size() && shown < MAX_ROWS; i++, shown++) {
            String name = state.otherFactionNames().get(i);
            String relation = state.otherFactionRelations().get(i);

            List<RowButton> buttons = new ArrayList<>();
            if (state.canManageDiplomacy()) {
                if (relation.equals("WAR")) {
                    buttons.add(new RowButton("Make Peace", b -> send(Action.MAKE_PEACE, name)));
                } else {
                    buttons.add(new RowButton("Declare War", b -> send(Action.DECLARE_WAR, name)));
                }
                if (relation.equals("ALLY")) {
                    buttons.add(new RowButton("Break Alliance", b -> send(Action.BREAK_ALLIANCE, name)));
                } else {
                    buttons.add(new RowButton("Propose Alliance", b -> send(Action.PROPOSE_ALLIANCE, name)));
                }
            }
            addRow(Component.literal(name + " - " + relation), buttons);
        }

        if (!state.incomingProposalNames().isEmpty()) {
            y += 12;
            shown = 0;
            for (int i = 0; i < state.incomingProposalNames().size() && shown < MAX_ROWS; i++, shown++) {
                String proposer = state.incomingProposalNames().get(i);
                addRow(Component.literal(proposer + " proposes an alliance"), List.of(
                        new RowButton("Accept", b -> send(Action.ACCEPT_ALLIANCE, proposer)),
                        new RowButton("Decline", b -> send(Action.DECLINE_ALLIANCE, proposer))));
            }
        }

```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add -A -- src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenFactionScreenS2CPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/FactionScreen.java
git commit -m "Add diplomacy actions, networking, and GUI"
```

---

### Task 4: In-game smoke test

- [ ] **Step 1: Start a dev server and two clients, each with their own faction already created**

`./gradlew runServer`, then `./gradlew runClient` for two players, each founding their own
faction via a Faction Center (per sub-project 2's flow) if they don't already have one.

- [ ] **Step 2: Walk the spec's Testing checklist**

1. Player A declares war on Player B's faction - expect both factions' `/faction` screens to
   show `WAR` for each other on next open.
2. Player A makes peace - expect both back to `NEUTRAL`.
3. Player A proposes an alliance to Player B's faction - expect Player B's `/faction` screen to
   show the incoming proposal with Accept/Decline, and Player A's screen shows nothing special
   yet (no "pending" indicator in this pass - see design spec's out-of-scope list).
4. Player B accepts - expect both factions to show `ALLY`, and the proposal to no longer appear
   anywhere.
5. Player B (leader) breaks the alliance - expect both back to `NEUTRAL` immediately (no
   confirmation needed from Player A).
6. Re-propose and accept an alliance between the two factions, then have Player A declare war
   while allied - expect it to succeed and immediately show `WAR` (overriding the alliance with
   no guard, rather than being blocked or requiring the alliance to be broken first).
7. As a non-leader member of Player A's faction, confirm none of the diplomacy buttons appear at
   all (not just disabled) - matches how Kick/claim buttons are already hidden for those without
   permission.
8. Disband Player A's faction while it has an active relation with Player B's - expect Player
   B's faction list to no longer show Player A's faction at all afterward.
9. Open `<world save folder>/ShadiomRP/diplomacy.html` in a browser after each relation change
   above and confirm it reflects the current state (allies/wars columns update, the disbanded
   faction's row disappears).
10. Restart the server and confirm relations persist (NBT round-trip check, same as the other
    two sub-projects' persistence checks) and the HTML file regenerates correctly on that
    restart too.

- [ ] **Step 3: Fix anything that doesn't match, recompiling after each fix**

Bugs are most likely in `FactionEventHandler`'s diplomacy methods (permission/transition logic)
or `DiplomacyReportWriter` (HTML content correctness - compiles fine either way, so this is a
"looks wrong" failure mode to watch for specifically, same caveat as the claims border renderer
in sub-project 2).

- [ ] **Step 4: Stop the dev server/clients, commit if any fixes were made**

```bash
git add -A
git commit -m "Fix issues found in diplomacy in-game smoke test"
```

(Skip this commit if step 3 required no changes.)
