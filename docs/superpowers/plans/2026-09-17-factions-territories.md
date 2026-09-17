# Factions: Territories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-blob-per-faction claim model with multiple named territories per
faction, a universal 1-chunk gap between any two territories, a 100-chunk cap per territory, a
designated capital territory whose destruction disbands the faction, an abandoned/repossessable
state for a destroyed non-capital territory, and a HUD announcement on crossing territory
boundaries.

**Architecture:** Territory identity/naming lives on `Faction` (in `FactionsData`); chunk
ownership and grouping lives on `ClaimsData.ClaimEntry`, which gains a `territoryId` and a
nullable `factionId` (null = abandoned). All adjacency/gap/cap logic is centralized in a new
pure-rules class shared by claim-extension (`FactionEventHandler`) and Faction Center placement
(`ClaimProtectionHandler`), so both paths enforce the exact same geometry.

**Tech Stack:** Java 17, Minecraft 1.20.1, Forge 47.4.23, Gradle (ForgeGradle). No test framework
in this repo - verification is `./gradlew compileJava`, `java -cp build/classes/java/main <FQCN>`
for the two classes with self-checking `main()` methods (an existing convention, see
`FactionPermissions`), and in-game smoke tests for everything Minecraft-type-dependent.

**Spec:** `docs/superpowers/specs/2026-09-17-factions-territories-design.md`

## Global Constraints

- Silent no-op on any disallowed action (never throw/message except the existing "This is no
  place for civilization" case) - matches every other action in `FactionEventHandler`.
- Never renumber existing `ModNetwork` packet ids - only append new ones.
- 100-chunk cap per territory (`TerritoryRules.MAX_TERRITORY_CHUNKS`).
- Gap/adjacency checks read only the specific neighbor chunk keys via `ClaimsData.get` - never a
  full-map scan (scans are reserved for the already-established "fine at this scale" cases:
  `chunksOfTerritory`, `claimsOf`, migration).

---

## Task 1: Territory data on `Faction`

**Files:**
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java`

**Interfaces:**
- Produces: `Faction.Territory` (nested record, `id`/`name`), `Faction#territories()` (returns
  `Map<String, Territory>`), `Faction#territory(String id)`, `Faction#addTerritory(Territory)`,
  `Faction#removeTerritory(String id)`, `Faction#renameTerritory(String id, String name)`,
  `Faction#capitalTerritoryId()`, `Faction#setCapitalTerritoryId(String id)` - all package-private,
  used by every later task.

- [ ] **Step 1: Add territory fields, the nested `Territory` record, and accessors to `Faction`**

Edit `Faction.java`: add `import java.util.Map;` alongside the existing imports, then add these
fields right after the existing `members` field, and these methods right after `allMembers()`:

```java
    private final Map<String, Territory> territories = new HashMap<>();
    private String capitalTerritoryId;
```

```java
    Map<String, Territory> territories() { return territories; }

    Territory territory(String id) { return territories.get(id); }

    void addTerritory(Territory territory) { territories.put(territory.id(), territory); }

    void removeTerritory(String id) { territories.remove(id); }

    void renameTerritory(String id, String name) {
        Territory existing = territories.get(id);
        if (existing != null) territories.put(id, new Territory(id, name));
    }

    String capitalTerritoryId() { return capitalTerritoryId; }

    void setCapitalTerritoryId(String id) { capitalTerritoryId = id; }
```

Add the nested record next to the existing `enum Role`:

```java
    /** One of a faction's separately-founded, separately-capped landmasses, each anchored by its
     *  own Faction Center. {@code name} is null until the leader sets one - see
     *  the territories design spec for the naming UI. */
    record Territory(String id, String name) {}
```

Also add a needed import: `java.util.HashMap` (the file already imports `HashSet`; add `HashMap`
next to it).

- [ ] **Step 2: Add a self-check `main()` to `Faction.java`, matching `FactionPermissions`'s convention**

Add at the bottom of the class, before the closing brace, after the `Role` enum:

```java
    public static void main(String[] args) {
        Faction faction = new Faction("test", "Test Faction", UUID.randomUUID());
        check(faction.territories().isEmpty(), "new faction has no territories");
        check(faction.capitalTerritoryId() == null, "new faction has no capital yet");

        faction.addTerritory(new Territory("t1", null));
        check(faction.territory("t1") != null, "territory t1 was added");
        check(faction.territory("t1").name() == null, "territory t1 starts unnamed");

        faction.renameTerritory("t1", "Home");
        check("Home".equals(faction.territory("t1").name()), "territory t1 renamed to Home");

        faction.setCapitalTerritoryId("t1");
        check("t1".equals(faction.capitalTerritoryId()), "t1 set as capital");

        faction.addTerritory(new Territory("t2", null));
        faction.removeTerritory("t2");
        check(faction.territory("t2") == null, "territory t2 removed");

        System.out.println("Faction self-check passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Faction self-check failed: " + description);
    }
```

- [ ] **Step 3: Compile and run the self-check**

Run: `./gradlew compileJava -q`
Expected: no errors.

Run: `java -cp build/classes/java/main com.tirener.shadiom.shadiomrpoverhaul.faction.Faction`
Expected: `Faction self-check passed.`

- [ ] **Step 4: Persist territories and capital in `FactionsData`**

Edit `FactionsData.java`'s `writeFaction`:

```java
    private static CompoundTag writeFaction(Faction faction) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", faction.id());
        tag.putString("name", faction.name());
        tag.putUUID("leader", faction.leader());
        tag.put("officers", writeUuidList(faction.officers()));
        tag.put("members", writeUuidList(faction.members()));

        ListTag territoriesTag = new ListTag();
        for (Faction.Territory territory : faction.territories().values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", territory.id());
            if (territory.name() != null) t.putString("name", territory.name());
            territoriesTag.add(t);
        }
        tag.put("territories", territoriesTag);
        if (faction.capitalTerritoryId() != null) {
            tag.putString("capitalTerritoryId", faction.capitalTerritoryId());
        }
        return tag;
    }
```

And `readFaction`:

```java
    private static Faction readFaction(CompoundTag tag) {
        Faction faction = new Faction(tag.getString("id"), tag.getString("name"), tag.getUUID("leader"));
        for (String uuid : readUuidStrings(tag.getList("officers", Tag.TAG_STRING))) {
            faction.addOfficer(UUID.fromString(uuid));
        }
        for (String uuid : readUuidStrings(tag.getList("members", Tag.TAG_STRING))) {
            faction.addMember(UUID.fromString(uuid));
        }

        ListTag territoriesTag = tag.getList("territories", Tag.TAG_COMPOUND);
        for (int i = 0; i < territoriesTag.size(); i++) {
            CompoundTag t = territoriesTag.getCompound(i);
            String name = t.contains("name") ? t.getString("name") : null;
            faction.addTerritory(new Faction.Territory(t.getString("id"), name));
        }
        if (tag.contains("capitalTerritoryId")) {
            faction.setCapitalTerritoryId(tag.getString("capitalTerritoryId"));
        }
        return faction;
    }
```

An old-format save simply has no `"territories"`/`"capitalTerritoryId"` keys, so this reads back
an empty territories map and a null capital - exactly the state Task 3's migration detects and
fixes.

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava -q`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java
git commit -m "$(cat <<'EOF'
Add territory data to Faction: multiple named territories per faction, one designated capital

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `ClaimsData` restructure - nullable owner, territory grouping

**Files:**
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimsData.java`

**Interfaces:**
- Consumes: nothing new from Task 1.
- Produces: `ClaimsData.ClaimEntry(String factionId, String territoryId, boolean factionCenter)`
  (factionId now nullable = abandoned), `claim(chunkKey, factionId, territoryId, factionCenter)`,
  `abandon(chunkKey)`, `reassignTerritoryId(chunkKey, territoryId)`,
  `repossess(Set<String> chunkKeys, String newFactionId, String newTerritoryId, String
  factionCenterChunkKey)`, `chunksOfTerritory(String territoryId)` - all used by later tasks.
  `unclaim`, `claimsOf`, `releaseAll`, `get`, `chunkKey` keep their existing signatures/behavior.

- [ ] **Step 1: Replace `ClaimEntry` and the mutation methods**

Edit `ClaimsData.java`. Replace the `ClaimEntry` record:

```java
    record ClaimEntry(String factionId, String territoryId, boolean factionCenter) {}
```

Replace the existing `claim`/`unclaim` methods and add the new ones (keep `get`, `claimsOf`,
`releaseAll`, `chunkKey` as they are):

```java
    void claim(String chunkKey, String factionId, String territoryId, boolean factionCenter) {
        unindexPrevious(chunkKey);
        claims.put(chunkKey, new ClaimEntry(factionId, territoryId, factionCenter));
        claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(chunkKey);
        setDirty();
    }

    void unclaim(String chunkKey) {
        ClaimEntry entry = claims.remove(chunkKey);
        if (entry != null && entry.factionId() != null) {
            Set<String> owned = claimsByFaction.get(entry.factionId());
            if (owned != null) owned.remove(chunkKey);
        }
        setDirty();
    }

    /** Abandons a territory's chunk: keeps its territoryId (so a repossessing Faction Center can
     *  find the rest of the blob later) but clears ownership and the factionCenter flag, since
     *  the block that made it an anchor no longer exists. */
    void abandon(String chunkKey) {
        ClaimEntry entry = claims.get(chunkKey);
        if (entry == null) return;
        unindexPrevious(chunkKey);
        claims.put(chunkKey, new ClaimEntry(null, entry.territoryId(), false));
        setDirty();
    }

    /** Rewrites a chunk's territoryId in place without changing its owner - used only by
     *  territory migration, to merge per-chunk placeholder ids into one shared id per
     *  connected blob read from a pre-territories save. */
    void reassignTerritoryId(String chunkKey, String territoryId) {
        ClaimEntry entry = claims.get(chunkKey);
        if (entry == null) return;
        claims.put(chunkKey, new ClaimEntry(entry.factionId(), territoryId, entry.factionCenter()));
        setDirty();
    }

    /** Transfers every chunk of an abandoned territory to a new faction/territory at once -
     *  used when a Faction Center is planted inside abandoned land. */
    void repossess(Set<String> chunkKeys, String newFactionId, String newTerritoryId, String factionCenterChunkKey) {
        for (String chunkKey : chunkKeys) {
            boolean isAnchor = chunkKey.equals(factionCenterChunkKey);
            claims.put(chunkKey, new ClaimEntry(newFactionId, newTerritoryId, isAnchor));
        }
        claimsByFaction.computeIfAbsent(newFactionId, k -> new HashSet<>()).addAll(chunkKeys);
        setDirty();
    }

    /** Every chunk sharing a territoryId, regardless of owner (including abandoned chunks) -
     *  a plain scan, same "fine at this scale" precedent as {@link #releaseAll}. */
    Set<String> chunksOfTerritory(String territoryId) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ClaimEntry> entry : claims.entrySet()) {
            if (territoryId.equals(entry.getValue().territoryId())) result.add(entry.getKey());
        }
        return result;
    }

    private void unindexPrevious(String chunkKey) {
        ClaimEntry previous = claims.get(chunkKey);
        if (previous != null && previous.factionId() != null) {
            Set<String> previousOwned = claimsByFaction.get(previous.factionId());
            if (previousOwned != null) previousOwned.remove(chunkKey);
        }
    }
```

- [ ] **Step 2: Update save/load for the new shape, reading old saves as placeholder territories**

Replace `save` and `load`:

```java
    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        CompoundTag claimsTag = new CompoundTag();
        for (Map.Entry<String, ClaimEntry> entry : claims.entrySet()) {
            CompoundTag value = new CompoundTag();
            if (entry.getValue().factionId() != null) value.putString("factionId", entry.getValue().factionId());
            value.putString("territoryId", entry.getValue().territoryId());
            value.putBoolean("factionCenter", entry.getValue().factionCenter());
            claimsTag.put(entry.getKey(), value);
        }
        nbt.put("claims", claimsTag);
        return nbt;
    }

    static ClaimsData load(CompoundTag nbt) {
        ClaimsData data = new ClaimsData();
        CompoundTag claimsTag = nbt.getCompound("claims");
        for (String key : claimsTag.getAllKeys()) {
            CompoundTag value = claimsTag.getCompound(key);
            String factionId = value.contains("factionId") ? value.getString("factionId") : null;
            // Pre-territories saves have "capital" instead of "factionCenter", and no
            // "territoryId" at all - each such chunk gets its own placeholder id (its own chunk
            // key), later merged per connected blob by TerritoryMigration.
            boolean factionCenter = value.contains("factionCenter")
                    ? value.getBoolean("factionCenter")
                    : value.getBoolean("capital");
            String territoryId = value.contains("territoryId") ? value.getString("territoryId") : key;
            data.claims.put(key, new ClaimEntry(factionId, territoryId, factionCenter));
            if (factionId != null) data.claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(key);
        }
        return data;
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava -q`
Expected: errors in `FactionEventHandler.java` and `ClaimProtectionHandler.java` (they still call
the old two-argument `claim(key, factionId, capital)` and read `entry.capital()`) - this is
expected here; Task 4 fixes both call sites. Confirm the *only* errors are in those two files and
mention the removed `capital()`/old `claim` signature - if anything else fails to compile, stop
and re-check Step 1/2 against this file's current content before continuing.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimsData.java
git commit -m "$(cat <<'EOF'
Restructure ClaimsData for multi-territory claims with a nullable (abandoned) owner

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

This commit intentionally leaves the build broken (two call sites reference the old API) -
Task 4 fixes them next. If your workflow requires green commits, squash Tasks 2 and 4 together
instead of committing here.

---

## Task 3: One-time migration for pre-territories saves

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryMigration.java`

**Interfaces:**
- Consumes: `Faction#capitalTerritoryId()`/`addTerritory`/`setCapitalTerritoryId` (Task 1),
  `ClaimsData#claimsOf`/`reassignTerritoryId`/`get` (Task 2, existing).
- Produces: nothing consumed by later tasks - this is a leaf, server-start-only class.

- [ ] **Step 1: Write the migration class**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One-time upgrade for worlds saved before territories existed. {@code ClaimsData.load} already
 * gives every pre-territories chunk its own placeholder territoryId (see that class); this pass
 * merges same-faction, connected chunks into one real territory each, and picks a capital for
 * every faction that doesn't have one yet. Idempotent by construction - a faction that already
 * has a {@code capitalTerritoryId} (migrated on a previous start, or created fresh under the new
 * system) is skipped entirely, so this is safe and cheap to run on every server start.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class TerritoryMigration {

    private TerritoryMigration() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        FactionsData factions = FactionsData.get(overworld);
        ClaimsData claims = ClaimsData.get(overworld);

        for (Faction faction : factions.all()) {
            if (faction.capitalTerritoryId() != null) continue;
            migrate(claims, faction);
        }
    }

    private static void migrate(ClaimsData claims, Faction faction) {
        Set<String> remaining = new HashSet<>(claims.claimsOf(faction.id()));
        if (remaining.isEmpty()) return; // nothing to migrate - leave capital null

        List<String> territoryIdsInOrder = new ArrayList<>();
        String capitalCandidate = null;

        while (!remaining.isEmpty()) {
            String start = remaining.iterator().next();
            Set<String> component = floodFill(remaining, start);

            String territoryId = UUID.randomUUID().toString();
            boolean hasCenter = false;
            for (String chunkKey : component) {
                claims.reassignTerritoryId(chunkKey, territoryId);
                if (claims.get(chunkKey).factionCenter()) hasCenter = true;
            }
            faction.addTerritory(new Faction.Territory(territoryId, null));
            territoryIdsInOrder.add(territoryId);
            if (hasCenter && capitalCandidate == null) capitalCandidate = territoryId;
        }

        faction.setCapitalTerritoryId(capitalCandidate != null ? capitalCandidate : territoryIdsInOrder.get(0));
    }

    /** 4-directional BFS over already-claimed chunk keys, matching the adjacency rule these
     *  chunks were originally grown under (see the old {@code isAdjacentToOwnClaim}). Removes
     *  every visited key from {@code remaining}. */
    private static Set<String> floodFill(Set<String> remaining, String start) {
        Set<String> component = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        remaining.remove(start);

        while (!queue.isEmpty()) {
            String key = queue.remove();
            component.add(key);
            for (String neighborKey : orthogonalNeighborKeys(key)) {
                if (remaining.remove(neighborKey)) queue.add(neighborKey);
            }
        }
        return component;
    }

    private static String[] orthogonalNeighborKeys(String chunkKey) {
        String[] parts = chunkKey.split(",");
        String dimension = parts[0];
        int x = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return new String[] {
                dimension + "," + (x + 1) + "," + z,
                dimension + "," + (x - 1) + "," + z,
                dimension + "," + x + "," + (z + 1),
                dimension + "," + x + "," + (z - 1)
        };
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava -q`
Expected: no new errors from this file (the pre-existing Task 2 errors in `FactionEventHandler`/
`ClaimProtectionHandler` are still there until Task 4 - that's expected).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryMigration.java
git commit -m "$(cat <<'EOF'
Add one-time territory migration for worlds saved before territories existed

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

**Note on verification:** this class can't be exercised without a real pre-territories save file
(no test harness exists to fabricate one). There is realistically no such save at stake for this
project yet - if one turns up later, verify by backing it up, starting the server once, and
confirming every pre-existing faction ends up with a non-null `capitalTerritoryId` and its claimed
chunks split into as many territories as it had disconnected blobs (check via the in-game
`/faction` screen, or by inspecting the world's `data/shadiomrpoverhaul_factions.dat` /
`shadiomrpoverhaul_claims.dat` with an NBT viewer).

---

## Task 4: Unified claim rule, Faction Center founding/repossession/breaking

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryRules.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimProtectionHandler.java`

**Interfaces:**
- Consumes: everything from Tasks 1-2.
- Produces: `TerritoryRules.MAX_TERRITORY_CHUNKS` (int, 100),
  `TerritoryRules.territoryToExtend(claims, factionId, dimension, chunk)`,
  `TerritoryRules.anyOtherTerritoryAdjacent(claims, territoryId, dimension, chunk)`,
  `TerritoryRules.anyTerritoryAdjacent(claims, dimension, chunk)`,
  `TerritoryRules.territoryChunkCount(claims, territoryId)`,
  `FactionEventHandler.destroyFactionCenter(server, faction, territoryId)` (package-private,
  used by `ClaimProtectionHandler` in this task and nowhere else yet).

- [ ] **Step 1: Write the shared geometry rules**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure geometry rules shared by claim-extension ({@link FactionEventHandler#claim}) and Faction
 * Center placement ({@link ClaimProtectionHandler}): the universal 1-chunk gap between any two
 * territories (own or foreign, active or abandoned), enforced via 8-way adjacency, and 4-way
 * growth-adjacency for extending your own land (unchanged from the pre-territories rule). See
 * the territories design spec for why one 8-way "no other territoryId nearby" check covers both
 * the anti-slither case against enemies and the "can't bridge two of my own territories" case
 * without needing to special-case either.
 */
final class TerritoryRules {

    private TerritoryRules() {}

    static final int MAX_TERRITORY_CHUNKS = 100;

    private static final int[][] ORTHOGONAL = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    private static final int[][] SURROUNDING_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** The one faction-owned territory adjacent (4-dir) to this chunk, or null if zero or more
     *  than one match - either way there's no single territory to unambiguously extend. */
    static String territoryToExtend(ClaimsData claims, String factionId,
                                     ResourceKey<Level> dimension, ChunkPos chunk) {
        Set<String> ownTerritories = new HashSet<>();
        for (int[] d : ORTHOGONAL) {
            ClaimsData.ClaimEntry neighbor = claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]));
            if (neighbor != null && factionId.equals(neighbor.factionId())) ownTerritories.add(neighbor.territoryId());
        }
        return ownTerritories.size() == 1 ? ownTerritories.iterator().next() : null;
    }

    /** True if any of the 8 surrounding chunks belongs to a territory other than
     *  {@code territoryId} - own or foreign, active or abandoned. The universal gap rule. */
    static boolean anyOtherTerritoryAdjacent(ClaimsData claims, String territoryId,
                                              ResourceKey<Level> dimension, ChunkPos chunk) {
        for (int[] d : SURROUNDING_8) {
            ClaimsData.ClaimEntry neighbor = claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]));
            if (neighbor != null && !territoryId.equals(neighbor.territoryId())) return true;
        }
        return false;
    }

    /** True if any of the 8 surrounding chunks belongs to any territory at all - used when
     *  founding a brand new territory, which has no chunks of its own yet to be the exception. */
    static boolean anyTerritoryAdjacent(ClaimsData claims, ResourceKey<Level> dimension, ChunkPos chunk) {
        for (int[] d : SURROUNDING_8) {
            if (claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1])) != null) return true;
        }
        return false;
    }

    static int territoryChunkCount(ClaimsData claims, String territoryId) {
        return claims.chunksOfTerritory(territoryId).size();
    }
}
```

- [ ] **Step 2: Rewrite `claim`/`unclaim`/`create`/`disband` in `FactionEventHandler`**

Delete the existing `isAdjacentToOwnClaim` method entirely. Replace `create`, `claim`, `unclaim`,
and `disband`:

```java
    private static void create(ServerPlayer player, String name) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        PendingCapital pending = PENDING_CAPITALS.get(player.getUUID());
        if (pending == null) return; // must place a Faction Center first
        if (name == null || name.isBlank()) return;

        String id = slug(name);
        if (data.exists(id)) return;

        Faction faction = new Faction(id, name, player.getUUID());
        String territoryId = UUID.randomUUID().toString();
        faction.addTerritory(new Faction.Territory(territoryId, null));
        faction.setCapitalTerritoryId(territoryId);
        data.put(faction);

        ClaimsData.get(player.serverLevel()).claim(
                ClaimsData.chunkKey(pending.dimension(), pending.chunkX(), pending.chunkZ()),
                id, territoryId, true);
        PENDING_CAPITALS.remove(player.getUUID());
        DiplomacyReportWriter.write(player.getServer());
    }
```

```java
    private static void disband(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDisband(faction.roleOf(player.getUUID()))) return;

        disbandFaction(player.getServer(), faction);
    }

    private static void disbandFaction(MinecraftServer server, Faction faction) {
        ClaimsData.get(server.overworld()).releaseAll(faction.id());
        DiplomacyData.get(server.overworld()).releaseAll(faction.id());
        FactionsData.get(server.overworld()).remove(faction.id());
        DiplomacyReportWriter.write(server);
    }

    /** Breaking a Faction Center block: the capital territory's disbands the whole faction (same
     *  outcome as before territories existed); any other territory's just abandons that
     *  territory - its land stays reserved (still counts for the gap rule, see TerritoryRules)
     *  but opens up to build like wilderness until some faction plants a new Faction Center
     *  inside it and repossesses the whole blob. Called from ClaimProtectionHandler. */
    static void destroyFactionCenter(MinecraftServer server, Faction faction, String territoryId) {
        if (territoryId.equals(faction.capitalTerritoryId())) {
            disbandFaction(server, faction);
            return;
        }

        ClaimsData claims = ClaimsData.get(server.overworld());
        for (String chunkKey : claims.chunksOfTerritory(territoryId)) claims.abandon(chunkKey);
        faction.removeTerritory(territoryId);
        FactionsData.get(server.overworld()).setDirty();
        DiplomacyReportWriter.write(server);
    }
```

```java
    private static void claim(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageClaims(faction.roleOf(player.getUUID()))) return;

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos chunk = player.chunkPosition();
        ResourceKey<Level> dimension = player.level().dimension();
        String key = ClaimsData.chunkKey(dimension, chunk.x, chunk.z);
        if (claims.get(key) != null) return; // already claimed, or abandoned, by someone

        String territoryId = TerritoryRules.territoryToExtend(claims, faction.id(), dimension, chunk);
        if (territoryId == null) return; // not touching exactly one of the faction's own territories
        if (TerritoryRules.anyOtherTerritoryAdjacent(claims, territoryId, dimension, chunk)) return;
        if (TerritoryRules.territoryChunkCount(claims, territoryId) >= TerritoryRules.MAX_TERRITORY_CHUNKS) return;

        claims.claim(key, faction.id(), territoryId, false);
        broadcastClaims(player.getServer(), faction);
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
        if (entry == null || !faction.id().equals(entry.factionId())) return;
        if (entry.factionCenter()) return; // must break the Faction Center instead

        claims.unclaim(key);
        broadcastClaims(player.getServer(), faction);
    }
```

(`broadcastClaims` itself is unchanged from the diplomacy live-update work - it still filters
`claimsOf(faction.id())` by dimension prefix, which already excludes abandoned chunks since
`claimsByFaction` never indexes a null-owner chunk.)

- [ ] **Step 3: Rewrite Faction Center placement/breaking in `ClaimProtectionHandler`**

Replace the `FACTION_CENTER` branch inside `onPlace`:

```java
        if (event.getPlacedBlock().is(ModBlocks.FACTION_CENTER.get())) {
            if (!CIVILIZED_DIMENSIONS.contains(dimension)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Component.literal("This is no place for civilization."));
                return;
            }

            ClaimsData.ClaimEntry existing = claims.get(key);

            if (faction == null) {
                if (existing != null) { event.setCanceled(true); return; } // claimed or abandoned land
                if (FactionEventHandler.hasPendingCapital(sp.getUUID())) { event.setCanceled(true); return; }
                if (TerritoryRules.anyTerritoryAdjacent(claims, dimension, chunk)) { event.setCanceled(true); return; }
                FactionEventHandler.recordPendingCapital(sp, dimension, chunk);
                return;
            }

            if (!FactionPermissions.canPlaceFactionCenter(faction.roleOf(sp.getUUID()))) {
                event.setCanceled(true);
                return;
            }

            if (existing == null) {
                if (TerritoryRules.anyTerritoryAdjacent(claims, dimension, chunk)) { event.setCanceled(true); return; }
                String territoryId = java.util.UUID.randomUUID().toString();
                faction.addTerritory(new Faction.Territory(territoryId, null));
                claims.claim(key, faction.id(), territoryId, true);
                return;
            }

            if (existing.factionId() == null) {
                // Abandoned land - repossess the whole former territory, not just this chunk.
                Set<String> blob = claims.chunksOfTerritory(existing.territoryId());
                String territoryId = java.util.UUID.randomUUID().toString();
                faction.addTerritory(new Faction.Territory(territoryId, null));
                claims.repossess(blob, faction.id(), territoryId, key);
                return;
            }

            event.setCanceled(true); // already claimed by someone, including your own faction
            return;
        }
```

Add `import java.util.Set;` to this file's imports (needed for the `blob` variable above).

Replace `onBreak` in full:

```java
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null || claimEntry.factionId() == null) return; // unclaimed or abandoned - open

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());

        if (event.getState().is(ModBlocks.FACTION_CENTER.get())) {
            boolean canBreak = isMember && FactionPermissions.canBreakFactionCenter(faction.roleOf(sp.getUUID()));
            if (!canBreak) {
                event.setCanceled(true);
                return;
            }
            FactionEventHandler.destroyFactionCenter(sp.getServer(), faction, claimEntry.territoryId());
            return;
        }

        if (!isMember) event.setCanceled(true);
    }
```

And update the generic protection tail of `onPlace` (the non-Faction-Center case, after the
`FACTION_CENTER` branch's `return`) to also treat abandoned land as open:

```java
        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null || claimEntry.factionId() == null) return;
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());
        if (!isMember) event.setCanceled(true);
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava -q`
Expected: no errors.

- [ ] **Step 5: In-game smoke test**

Run the dev client (`./gradlew runClient`) against a fresh world and, as a single test faction
(or two, for the cross-faction cases), verify:
1. Found a faction (place a Faction Center, submit a name) - it becomes both a territory and the
   capital.
2. Claim a chunk orthogonally adjacent to the capital - succeeds.
3. Try to claim a chunk 2+ chunks away from any owned territory - fails (no change, no message).
4. Place a second Faction Center far enough away (9+ chunks clear on every side) - founds a
   second territory, not the capital.
5. Place a third Faction Center only 1 chunk away from the second territory (diagonal counts) -
   fails.
6. Claim chunks until the second territory has 100 - the 101st claim fails; claiming from the
   first (capital) territory still works.
7. Break the second (non-capital) territory's Faction Center - its chunks become buildable by a
   different test faction/player, and that other faction can place a Faction Center inside the
   old footprint and receives every chunk that territory used to have.
8. Break the capital territory's Faction Center - the whole faction disbands (claims released,
   diplomacy relations cleared, faction gone from `/faction`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryRules.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimProtectionHandler.java
git commit -m "$(cat <<'EOF'
Enforce the unified territory claim rule: 1-chunk gap, 100-chunk cap, capital-only disband

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Territory naming and capital designation

**Files:**
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimProtectionHandler.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenTerritoryScreenS2CPacket.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/TerritoryScreen.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java`

**Interfaces:**
- Consumes: `Faction#territory`/`renameTerritory`/`setCapitalTerritoryId`/`capitalTerritoryId`
  (Task 1), `ClaimsData#chunksOfTerritory` (Task 2).
- Produces: `FactionActionC2SPacket.Action.RENAME_TERRITORY`/`SET_CAPITAL_TERRITORY`,
  `FactionEventHandler.openTerritoryScreen(player, faction, territoryId)` (used by
  `ClaimProtectionHandler`), `OpenTerritoryScreenS2CPacket` (id 7).

- [ ] **Step 1: Add the leader-only territory-management permission**

In `FactionPermissions.java`, add next to `canManageDiplomacy`:

```java
    static boolean canManageTerritory(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }
```

Add to `main()`, next to the `canManageDiplomacy` checks:

```java
        check(canManageTerritory(Faction.Role.LEADER), "leader can manage territory");
        check(!canManageTerritory(Faction.Role.OFFICER), "officer cannot manage territory");
        check(!canManageTerritory(Faction.Role.MEMBER), "member cannot manage territory");
```

- [ ] **Step 2: Compile and re-run the permissions self-check**

Run: `./gradlew compileJava -q`
Expected: no errors.

Run: `java -cp build/classes/java/main com.tirener.shadiom.shadiomrpoverhaul.faction.FactionPermissions`
Expected: `FactionPermissions self-check passed.`

- [ ] **Step 3: Add the two new actions**

In `FactionActionC2SPacket.java`, extend the enum:

```java
    public enum Action {
        CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND, CLAIM, UNCLAIM,
        DECLARE_WAR, MAKE_PEACE, PROPOSE_ALLIANCE, ACCEPT_ALLIANCE, DECLINE_ALLIANCE, BREAK_ALLIANCE,
        RENAME_TERRITORY, SET_CAPITAL_TERRITORY
    }
```

- [ ] **Step 4: Handle the two actions and add the screen-opening entry point in `FactionEventHandler`**

Add two cases to the `handleAction` switch:

```java
            case RENAME_TERRITORY -> renameTerritory(player, arg);
            case SET_CAPITAL_TERRITORY -> setCapitalTerritory(player, arg);
```

Add the handler methods (near `disband`/the other leader-only actions) and the screen-opening
entry point (near `openScreen`):

```java
    private static void renameTerritory(ServerPlayer actor, String arg) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(actor.getUUID()))) return;

        String[] parts = arg.split("\\|", 2);
        if (parts.length != 2) return;
        String territoryId = parts[0];
        String name = parts[1];
        if (faction.territory(territoryId) == null || name.isBlank()) return;

        faction.renameTerritory(territoryId, name);
        data.setDirty();
    }

    private static void setCapitalTerritory(ServerPlayer actor, String territoryId) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(actor.getUUID()))) return;
        if (faction.territory(territoryId) == null) return;

        faction.setCapitalTerritoryId(territoryId);
        data.setDirty();
    }

    /** Opens the territory management screen for one territory - only call after already
     *  verifying the player is that faction's leader (see ClaimProtectionHandler's right-click
     *  handler, the only caller). */
    static void openTerritoryScreen(ServerPlayer player, Faction faction, String territoryId) {
        Faction.Territory territory = faction.territory(territoryId);
        if (territory == null) return;
        int chunkCount = ClaimsData.get(player.serverLevel()).chunksOfTerritory(territoryId).size();
        boolean isCapital = territoryId.equals(faction.capitalTerritoryId());

        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenTerritoryScreenS2CPacket(territoryId,
                        territory.name() == null ? "" : territory.name(), chunkCount, isCapital));
    }
```

Add the needed import: `com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket`
(alongside the existing `network.faction` imports in this file).

- [ ] **Step 5: Add the right-click handler in `ClaimProtectionHandler`**

Add a new subscriber method:

```java
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(ModBlocks.FACTION_CENTER.get())) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry entry = claims.get(key);
        if (entry == null || entry.factionId() == null) return;

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        if (faction == null || !faction.id().equals(entry.factionId())) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(sp.getUUID()))) return;

        event.setCanceled(true);
        FactionEventHandler.openTerritoryScreen(sp, faction, entry.territoryId());
    }
```

Add the needed import: `net.minecraftforge.event.entity.player.PlayerInteractEvent`.

- [ ] **Step 6: Write the S2C packet**

```java
package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.TerritoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: opens the territory management screen for one territory the viewer's
 *  faction owns - only ever sent after the server has already verified they're that faction's
 *  leader (see ClaimProtectionHandler's right-click handler). */
public record OpenTerritoryScreenS2CPacket(
        String territoryId,
        String name,
        int chunkCount,
        boolean isCapital
) {

    public static void encode(OpenTerritoryScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.territoryId());
        buf.writeUtf(pkt.name());
        buf.writeVarInt(pkt.chunkCount());
        buf.writeBoolean(pkt.isCapital());
    }

    public static OpenTerritoryScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenTerritoryScreenS2CPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(OpenTerritoryScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        Minecraft.getInstance().setScreen(new TerritoryScreen(pkt))));
        ctx.get().setPacketHandled(true);
    }
}
```

- [ ] **Step 7: Write the client screen**

```java
package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket.Action;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Rename a territory and/or set it as the faction's capital. No live refresh after acting -
 *  unlike FactionScreen there's no packet loop keeping this open, so both actions just close the
 *  screen; right-clicking the Faction Center again shows the updated state. */
@OnlyIn(Dist.CLIENT)
public class TerritoryScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    private final OpenTerritoryScreenS2CPacket state;
    private EditBox nameField;
    private int left, top;

    public TerritoryScreen(OpenTerritoryScreenS2CPacket state) {
        super(Component.literal("Territory"));
        this.state = state;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - 120) / 2;

        nameField = new EditBox(font, left, top + 30, PANEL_W, ROW_H, Component.literal("Territory name"));
        nameField.setValue(state.name());
        nameField.setMaxLength(48);
        addRenderableWidget(nameField);

        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> onRename())
                .pos(left, top + 30 + ROW_GAP).size(PANEL_W, ROW_H).build());

        Button capitalButton = Button.builder(Component.literal("Set as Capital"), b -> onSetCapital())
                .pos(left, top + 30 + ROW_GAP * 2).size(PANEL_W, ROW_H).build();
        capitalButton.active = !state.isCapital();
        addRenderableWidget(capitalButton);
    }

    private void onRename() {
        String name = nameField.getValue();
        if (name.isBlank()) return;
        send(Action.RENAME_TERRITORY, state.territoryId() + "|" + name);
        onClose();
    }

    private void onSetCapital() {
        send(Action.SET_CAPITAL_TERRITORY, state.territoryId());
        onClose();
    }

    private void send(Action action, String arg) {
        ModNetwork.CHANNEL.sendToServer(new FactionActionC2SPacket(action, arg));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        int cx = left + PANEL_W / 2;
        g.drawCenteredString(font, "Territory", cx, top, 0xFFFFFF);
        g.drawCenteredString(font, state.chunkCount() + " / 100 chunks claimed",
                cx, top + 14, ChatFormatting.GRAY.getColor());
        if (state.isCapital()) {
            g.drawCenteredString(font, "Capital Territory", cx, top + 30 + ROW_GAP * 3 + 4, ChatFormatting.GOLD.getColor());
        }
        super.render(g, mx, my, partial);
    }
}
```

- [ ] **Step 8: Register the new packet**

In `ModNetwork.java`, add the import, the id constant, and the registration:

```java
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket;
```

```java
    private static final int ID_OPEN_TERRITORY_SCREEN = 7;
```

```java
        CHANNEL.registerMessage(
                ID_OPEN_TERRITORY_SCREEN,
                OpenTerritoryScreenS2CPacket.class,
                OpenTerritoryScreenS2CPacket::encode,
                OpenTerritoryScreenS2CPacket::decode,
                OpenTerritoryScreenS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
```

- [ ] **Step 9: Compile**

Run: `./gradlew compileJava -q`
Expected: no errors.

- [ ] **Step 10: In-game smoke test**

1. As the faction leader, right-click your capital Faction Center - `TerritoryScreen` opens,
   "Set as Capital" is disabled (it already is the capital).
2. Type a name, click Rename - screen closes; right-click again and confirm the name field now
   shows it.
3. Right-click a non-capital territory's Faction Center, click "Set as Capital" - right-click the
   old capital's Faction Center again and confirm its button is now enabled (no longer capital),
   and the new one's is disabled.
4. As a non-leader member, right-click any Faction Center - nothing opens.
5. Break the (new) capital territory's Faction Center - confirm the faction disbands (this
   exercises `destroyFactionCenter` reading the *current* `capitalTerritoryId`, not whichever
   territory was capital at founding).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimProtectionHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenTerritoryScreenS2CPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/TerritoryScreen.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java
git commit -m "$(cat <<'EOF'
Add territory naming and capital designation via a Faction Center right-click screen

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: HUD announcement on crossing a territory boundary

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/TerritoryHudS2CPacket.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryHudHandler.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/TerritoryHudRenderer.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java`

**Interfaces:**
- Consumes: `ClaimsData.get`/`chunkKey` (Task 2), `Faction#territory` (Task 1),
  `FactionsData.get`.
- Produces: nothing consumed by earlier tasks - this is the final leaf.

- [ ] **Step 1: Write the S2C packet**

```java
package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.TerritoryHudRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: announce a new top-center HUD label because the player just crossed into a
 *  different territory (or wilderness). Sent at most once per actual change - see
 *  TerritoryHudHandler for the tick-based crossing detection. */
public record TerritoryHudS2CPacket(String text) {

    public static void encode(TerritoryHudS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.text());
    }

    public static TerritoryHudS2CPacket decode(FriendlyByteBuf buf) {
        return new TerritoryHudS2CPacket(buf.readUtf());
    }

    public static void handle(TerritoryHudS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TerritoryHudRenderer.show(pkt.text())));
        ctx.get().setPacketHandled(true);
    }
}
```

- [ ] **Step 2: Write the server-side crossing detector**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.TerritoryHudS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Announces the current territory's name at the top center of the HUD whenever a player crosses
 *  into a different one. Checked once per second per player (not every tick - chunk crossings
 *  are rare relative to the tick rate) by comparing against the last chunk key announced for
 *  them; a login/first tick always announces, since there's no prior entry to match. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class TerritoryHudHandler {

    private TerritoryHudHandler() {}

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final Map<UUID, String> LAST_ANNOUNCED_CHUNK = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        ChunkPos chunk = player.chunkPosition();
        String chunkKey = ClaimsData.chunkKey(player.level().dimension(), chunk.x, chunk.z);
        if (chunkKey.equals(LAST_ANNOUNCED_CHUNK.get(player.getUUID()))) return;
        LAST_ANNOUNCED_CHUNK.put(player.getUUID(), chunkKey);

        String text = describe(player, chunkKey);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TerritoryHudS2CPacket(text));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ANNOUNCED_CHUNK.remove(event.getEntity().getUUID());
    }

    private static String describe(ServerPlayer player, String chunkKey) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(player.serverLevel()).get(chunkKey);
        if (entry == null) return "Wilderness";
        if (entry.factionId() == null) return "Abandoned Territory";

        Faction faction = FactionsData.get(player.serverLevel()).get(entry.factionId());
        if (faction == null) return "Wilderness"; // orphaned entry, shouldn't normally happen

        Faction.Territory territory = faction.territory(entry.territoryId());
        if (territory != null && territory.name() != null) return territory.name();
        return faction.name();
    }
}
```

- [ ] **Step 3: Write the client-side fading HUD overlay**

```java
package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.mojang.blaze3d.systems.RenderSystem;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Top-center fading text shown for a few seconds whenever the server announces a territory
 *  crossing (see TerritoryHudHandler). Fade timing mirrors a vanilla advancement toast: fully
 *  visible for a couple of seconds, then a short fade-out, rather than a persistent element. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TerritoryHudRenderer {

    private TerritoryHudRenderer() {}

    private static final long VISIBLE_MS = 2500;
    private static final long FADE_MS = 800;

    private static String text = "";
    private static long shownAt = 0;

    public static void show(String newText) {
        text = newText;
        shownAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("territory_hud", TerritoryHudRenderer::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics,
                                float partialTick, int screenWidth, int screenHeight) {
        if (text.isEmpty()) return;
        long elapsed = System.currentTimeMillis() - shownAt;
        if (elapsed > VISIBLE_MS + FADE_MS) return;

        float alpha = elapsed <= VISIBLE_MS ? 1f : 1f - (float) (elapsed - VISIBLE_MS) / FADE_MS;
        int argb = ((int) (alpha * 255) << 24) | 0xFFFFFF;

        RenderSystem.enableBlend();
        graphics.drawCenteredString(gui.getFont(), text, screenWidth / 2, 10, argb);
        RenderSystem.disableBlend();
    }
}
```

- [ ] **Step 4: Register the new packet**

In `ModNetwork.java`, add the import, the id constant, and the registration:

```java
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.TerritoryHudS2CPacket;
```

```java
    private static final int ID_TERRITORY_HUD_SYNC = 8;
```

```java
        CHANNEL.registerMessage(
                ID_TERRITORY_HUD_SYNC,
                TerritoryHudS2CPacket.class,
                TerritoryHudS2CPacket::encode,
                TerritoryHudS2CPacket::decode,
                TerritoryHudS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava -q`
Expected: no errors.

**If this fails specifically on `RegisterGuiOverlaysEvent`, `ForgeGui`, or `registerAboveAll`**:
this is the one API in the whole plan not cross-checked against this project's actual Forge
47.4.23 sources (everything else was verified against files already in this repo). Open the
Forge/MinecraftForge sources for `net.minecraftforge.client.gui.overlay` (via your IDE's "Go to
Definition" on the import, or the decompiled Forge jar) and adjust the registration call and
`IGuiOverlay`'s exact method signature to match what's actually there - the rest of the class
(the fade-timer logic, `show()`, the packet wiring) does not depend on getting this exactly right
on the first try.

- [ ] **Step 6: In-game smoke test**

1. Log in inside unclaimed land - "Wilderness" briefly appears top-center.
2. Walk into a named territory - its name appears.
3. Walk into a territory that hasn't been named yet - the faction's name appears instead.
4. Walk into an abandoned (null-owned) territory (from Task 4's step 7 test) - "Abandoned
   Territory" appears.
5. Stand still inside one territory for several seconds - the text fades out and does not
   reappear until you actually cross into a different chunk's territory.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/TerritoryHudS2CPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/TerritoryHudHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/TerritoryHudRenderer.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java
git commit -m "$(cat <<'EOF'
Show a fading top-center HUD label when crossing into a different territory

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
