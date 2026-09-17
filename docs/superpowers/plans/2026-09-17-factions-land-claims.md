# Factions: Land Claims & Faction Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A placeable Faction Center block that founds a faction (replacing the old GUI text-field creation) and anchors chunk-based territory that grows outward from it, with build/break protection inside claims and two new faction-screen features (claim/unclaim the current chunk, a toggleable translucent territory border).

**Architecture:** `ClaimsData` (new world-level `SavedData`, mirrors `FactionsData`) tracks chunk ownership and which claimed chunks are capitals. `ClaimProtectionHandler` (new) enforces build/break rules by cancelling Forge's `BlockEvent.BreakEvent`/`EntityPlaceEvent`. Faction creation moves from the GUI's text field to placing a Faction Center as a faction-less player, tracked as a transient pending-capital (mirrors `NameEventHandler`'s `PENDING_PICKS`) until the forced create screen is submitted. `FactionScreen` gains claim/unclaim and a border-toggle button; a new client-only `ClaimBorderRenderer` draws the translucent boundary via `RenderLevelStageEvent`.

**Tech Stack:** Same as sub-project 1 (Java 17, Forge 1.20.1/47.4.23, Forge networking, Brigadier, vanilla `Screen` widgets), plus: Forge block/item registration (`DeferredRegister`, first use in this mod), and low-level world rendering (`Tesselator`/`BufferBuilder`, first use in this mod) verified against vanilla's own `LevelRenderer.renderShape` for the exact vertex-building API.

**Spec:** `docs/superpowers/specs/2026-09-17-factions-land-claims-design.md` (and `docs/superpowers/specs/2026-09-17-factions-membership-design.md` for the sub-project this depends on)

## Global Constraints

- Java 17 / Forge 1.20.1 (47.4.23) - match every existing file's language level and imports.
- No test framework exists in this repo. Verification is `./gradlew compileJava` per task plus
  an in-game smoke test at the end (Task 4) - the rendering piece in particular can only be
  visually confirmed in-game, not by the compiler.
- Follow existing conventions: package-private `SavedData` for world storage
  (`ClaimsData` mirrors `FactionsData`), static business-logic classes with a private
  constructor and silent no-op on failed validation (`FactionEventHandler`/
  `ClaimProtectionHandler` mirror `NameEventHandler`), the one-packet-per-direction networking
  pattern already established (extend the existing `FactionActionC2SPacket`/
  `OpenFactionScreenS2CPacket` rather than adding new packet types).
- Every new permission rule gets its own named method in `FactionPermissions` even where its
  body is identical to an existing one (`canManageClaims` mirrors `canInvite`,
  `canPlaceFactionCenter`/`canBreakFactionCenter` mirror `canDisband`) - they're different
  concerns that happen to share a tier today, per the design spec.
- `Faction`, `FactionsData`, `FactionPermissions`, `ClaimsData` all stay package-private
  (`faction` package) - only `FactionEventHandler`'s public methods are called from outside it.

---

### Task 1: Faction Center block registration & assets

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/block/ModBlocks.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/Shadiomrpoverhaul.java`
- Move: `src/main/resources/assets/textures/blocks/faction_center.png` to
  `src/main/resources/assets/shadiomrpoverhaul/textures/block/faction_center.png`
- Create: `src/main/resources/assets/shadiomrpoverhaul/blockstates/faction_center.json`
- Create: `src/main/resources/assets/shadiomrpoverhaul/models/block/faction_center.json`
- Create: `src/main/resources/assets/shadiomrpoverhaul/models/item/faction_center.json`
- Create: `src/main/resources/assets/shadiomrpoverhaul/lang/en_us.json`
- Create: `src/main/resources/data/shadiomrpoverhaul/loot_tables/blocks/faction_center.json`

**Interfaces:**
- Produces: `ModBlocks.FACTION_CENTER` (`RegistryObject<Block>`), `ModBlocks.FACTION_CENTER_ITEM`
  (`RegistryObject<Item>`) - both consumed by Task 3's `ClaimProtectionHandler` (block identity
  check) and nothing else in this task.

- [ ] **Step 1: Move the texture to the correct namespaced path**

```bash
mkdir -p src/main/resources/assets/shadiomrpoverhaul/textures/block
git mv src/main/resources/assets/textures/blocks/faction_center.png src/main/resources/assets/shadiomrpoverhaul/textures/block/faction_center.png
rmdir src/main/resources/assets/textures/blocks src/main/resources/assets/textures 2>/dev/null || true
```

- [ ] **Step 2: Write `ModBlocks.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.block;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {

    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Shadiomrpoverhaul.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Shadiomrpoverhaul.MODID);

    public static final RegistryObject<Block> FACTION_CENTER = BLOCKS.register("faction_center",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(3.0F)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> FACTION_CENTER_ITEM = ITEMS.register("faction_center",
            () -> new BlockItem(FACTION_CENTER.get(), new Item.Properties()));

    @Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class CreativeTab {

        private CreativeTab() {}

        @SubscribeEvent
        public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
                event.accept(FACTION_CENTER_ITEM);
            }
        }
    }
}
```

- [ ] **Step 3: Wire block/item registration into the mod constructor**

Modify `Shadiomrpoverhaul.java`:

```java
package com.tirener.shadiom.shadiomrpoverhaul;

import com.tirener.shadiom.shadiomrpoverhaul.block.ModBlocks;
import com.tirener.shadiom.shadiomrpoverhaul.names.DefaultNames;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Shared roleplay layer for Shadiom: cosmetic titles and chosen names, both purely display-side.
 * Consuming mods go through {@code ShadiomTitleAPI} and {@code ShadiomNameAPI}; everything else
 * is wired up by the event subscribers in the title and names packages.
 */
@Mod(Shadiomrpoverhaul.MODID)
public class Shadiomrpoverhaul {

    public static final String MODID = "shadiomrpoverhaul";

    public Shadiomrpoverhaul() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModNetwork.register();
        DefaultNames.register();
    }
}
```

- [ ] **Step 4: Write the blockstate**

`src/main/resources/assets/shadiomrpoverhaul/blockstates/faction_center.json`:

```json
{
  "variants": {
    "": { "model": "shadiomrpoverhaul:block/faction_center" }
  }
}
```

- [ ] **Step 5: Write the block model**

`src/main/resources/assets/shadiomrpoverhaul/models/block/faction_center.json`:

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": {
    "all": "shadiomrpoverhaul:block/faction_center"
  }
}
```

- [ ] **Step 6: Write the item model**

`src/main/resources/assets/shadiomrpoverhaul/models/item/faction_center.json`:

```json
{
  "parent": "shadiomrpoverhaul:block/faction_center"
}
```

- [ ] **Step 7: Write the lang file**

`src/main/resources/assets/shadiomrpoverhaul/lang/en_us.json` (no lang file exists yet in this
mod - this creates the first one):

```json
{
  "block.shadiomrpoverhaul.faction_center": "Faction Center"
}
```

- [ ] **Step 8: Write the loot table (so the block drops itself when broken)**

`src/main/resources/data/shadiomrpoverhaul/loot_tables/blocks/faction_center.json`:

```json
{
  "type": "minecraft:block",
  "pools": [
    {
      "rolls": 1,
      "entries": [
        {
          "type": "minecraft:item",
          "name": "shadiomrpoverhaul:faction_center"
        }
      ]
    }
  ]
}
```

- [ ] **Step 9: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. If `BuildCreativeModeTabContentsEvent.accept` doesn't take a
`RegistryObject<Item>` directly, check the exact overload via
`~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.23_mapped_official_1.20.1/forge-1.20.1-47.4.23_mapped_official_1.20.1-sources.jar`
(`net/minecraftforge/event/BuildCreativeModeTabContentsEvent.java`) and adjust - same lookup
technique used throughout this project's earlier work.

- [ ] **Step 10: In-game check that the block exists and looks right**

`./gradlew runClient`, open the creative inventory's Building Blocks tab, place a Faction
Center, confirm the texture shows correctly (not a missing-texture purple/black checker) and it
drops itself when broken in creative/survival.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "Add the Faction Center block and its assets"
```

---

### Task 2: Claims storage & permission rules

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimsData.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java`

**Interfaces:**
- Produces: `ClaimsData` (package-private, extends `SavedData`) - `get(String chunkKey)`
  (`ClaimEntry` or `null`), `claimsOf(String factionId)` (`Set<String>`), `claim(String chunkKey,
  String factionId, boolean capital)`, `unclaim(String chunkKey)`, `releaseAll(String
  factionId)`; static `chunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ)` (String),
  static `get(ServerLevel)`. Nested record `ClaimsData.ClaimEntry(String factionId, boolean
  capital)`.
- Produces: three new `FactionPermissions` methods - `canManageClaims(Faction.Role)`,
  `canPlaceFactionCenter(Faction.Role)`, `canBreakFactionCenter(Faction.Role)`, all `boolean`.

- [ ] **Step 1: Write `ClaimsData.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** World-wide chunk claims, keyed by a flat "dimension,chunkX,chunkZ" string. Storage only -
 *  {@code FactionEventHandler} and {@code ClaimProtectionHandler} hold every rule about who may
 *  claim/break what; this class just persists whatever it's told, same division of
 *  responsibility as {@link FactionsData}. */
final class ClaimsData extends SavedData {

    private final Map<String, ClaimEntry> claims = new HashMap<>();
    private final Map<String, Set<String>> claimsByFaction = new HashMap<>();

    record ClaimEntry(String factionId, boolean capital) {}

    ClaimEntry get(String chunkKey) { return claims.get(chunkKey); }

    Set<String> claimsOf(String factionId) {
        return claimsByFaction.getOrDefault(factionId, Set.of());
    }

    void claim(String chunkKey, String factionId, boolean capital) {
        ClaimEntry previous = claims.get(chunkKey);
        if (previous != null) {
            Set<String> previousOwned = claimsByFaction.get(previous.factionId());
            if (previousOwned != null) previousOwned.remove(chunkKey);
        }
        claims.put(chunkKey, new ClaimEntry(factionId, capital));
        claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(chunkKey);
        setDirty();
    }

    void unclaim(String chunkKey) {
        ClaimEntry entry = claims.remove(chunkKey);
        if (entry != null) {
            Set<String> owned = claimsByFaction.get(entry.factionId());
            if (owned != null) owned.remove(chunkKey);
        }
        setDirty();
    }

    void releaseAll(String factionId) {
        for (String chunkKey : Set.copyOf(claimsOf(factionId))) unclaim(chunkKey);
    }

    static String chunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return dimension.location() + "," + chunkX + "," + chunkZ;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        CompoundTag claimsTag = new CompoundTag();
        for (Map.Entry<String, ClaimEntry> entry : claims.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("factionId", entry.getValue().factionId());
            value.putBoolean("capital", entry.getValue().capital());
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
            String factionId = value.getString("factionId");
            boolean capital = value.getBoolean("capital");
            data.claims.put(key, new ClaimEntry(factionId, capital));
            data.claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(key);
        }
        return data;
    }

    /** Same overworld-storage convention as {@link FactionsData#get}. */
    static ClaimsData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                ClaimsData::load,
                ClaimsData::new,
                "shadiomrpoverhaul_claims"
        );
    }
}
```

- [ ] **Step 2: Add the three new methods to `FactionPermissions.java`**

Add after `canDisband`:

```java
    static boolean canManageClaims(Faction.Role actor) {
        return actor == Faction.Role.LEADER || actor == Faction.Role.OFFICER;
    }

    static boolean canPlaceFactionCenter(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    static boolean canBreakFactionCenter(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }
```

- [ ] **Step 3: Extend the self-check `main` method**

Add before `System.out.println("FactionPermissions self-check passed.");`:

```java
        check(canManageClaims(Faction.Role.LEADER), "leader can manage claims");
        check(canManageClaims(Faction.Role.OFFICER), "officer can manage claims");
        check(!canManageClaims(Faction.Role.MEMBER), "member cannot manage claims");

        check(canPlaceFactionCenter(Faction.Role.LEADER), "leader can place a faction center");
        check(!canPlaceFactionCenter(Faction.Role.OFFICER), "officer cannot place a faction center");
        check(!canPlaceFactionCenter(Faction.Role.MEMBER), "member cannot place a faction center");

        check(canBreakFactionCenter(Faction.Role.LEADER), "leader can break a faction center");
        check(!canBreakFactionCenter(Faction.Role.OFFICER), "officer cannot break a faction center");
        check(!canBreakFactionCenter(Faction.Role.MEMBER), "member cannot break a faction center");
```

- [ ] **Step 4: Run the standalone self-check (still zero Minecraft imports in this file)**

```bash
mkdir -p /tmp/faction-check2 && cd /tmp/faction-check2
javac -d . /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java
java -cp . com.tirener.shadiom.shadiomrpoverhaul.faction.FactionPermissions
```

Expected: `FactionPermissions self-check passed.`

- [ ] **Step 5: Compile the whole project**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimsData.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java
git commit -m "Add claims storage and claim/faction-center permission rules"
```

---

### Task 3: Business logic, protection, networking, and GUI

Same reasoning as sub-project 1's Task 3: `FactionEventHandler`, both packets,
`ClaimProtectionHandler`, `FactionScreen`, and `ClaimBorderRenderer` all reference each other
(handler builds and sends packets, packets open/refresh the screen, the screen calls the border
renderer and sends actions back to the handler, the protection handler calls into the handler for
pending-capital tracking) - one coupled cluster, written together, compiled once at the end.

**Files:**
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenFactionScreenS2CPacket.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/ClaimProtectionHandler.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/FactionScreen.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/ClaimBorderRenderer.java`

**Interfaces:**
- Consumes: `ClaimsData`, `FactionPermissions.canManageClaims/canPlaceFactionCenter/canBreakFactionCenter` (Task 2), `ModBlocks.FACTION_CENTER` (Task 1).
- Produces: `FactionEventHandler.hasPendingCapital(UUID)` (`boolean`) and
  `FactionEventHandler.recordPendingCapital(ServerPlayer, ResourceKey<Level>, ChunkPos)` (`void`)
  - the two entry points `ClaimProtectionHandler` calls into.

- [ ] **Step 1: Replace `FactionActionC2SPacket.java`'s `Action` enum**

Change:

```java
    public enum Action { CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND }
```

to:

```java
    public enum Action { CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND, CLAIM, UNCLAIM }
```

- [ ] **Step 2: Rewrite `OpenFactionScreenS2CPacket.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.FactionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: opens (or refreshes, after every action) the faction screen with a full
 * snapshot. {@code memberNames}/{@code invitablePlayerNames} are account usernames - used as the
 * action identifier sent back in the C2S action packet, since that's what
 * {@code PlayerList.getPlayerByName} resolves. {@code memberDisplayNames}/
 * {@code invitableDisplayNames} are what's actually shown (the RP name, if picked).
 * <p>
 * {@code mustCreateFaction} forces the create-only view (a Faction Center was placed by a
 * faction-less player, but they haven't submitted a name yet). {@code currentChunkOwner},
 * {@code canManageClaims} and {@code ownClaimedChunkKeys} are only meaningful when
 * {@code hasFaction} is true.
 */
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
        List<String> ownClaimedChunkKeys
) {

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
    }

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
                readStringList(buf)
        );
    }

    public static void handle(OpenFactionScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> openScreen(pkt)));
        ctx.get().setPacketHandled(true);
    }

    private static void openScreen(OpenFactionScreenS2CPacket pkt) {
        Minecraft.getInstance().setScreen(new FactionScreen(pkt));
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(buf.readUtf());
        return list;
    }
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

/** All faction membership and claim rules live here - validates the actor's role against
 *  {@link FactionPermissions}, mutates {@link FactionsData}/{@link ClaimsData}, and refreshes
 *  the acting player's screen afterward. Every branch below is a silent no-op on failure
 *  (offline target, wrong role, stale state, ...) per the design spec's error-handling
 *  section - the refreshed snapshot sent at the end simply shows the actor nothing changed. */
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
        data.remove(faction.id());
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
                    List.of(), List.of(), List.of(), inviteIds, inviteNames, "", false, List.of());
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

        String viewerRole = faction.roleOf(player.getUUID()).name();
        return new OpenFactionScreenS2CPacket(true, false, faction.name(), viewerRole,
                memberNames, memberDisplayNames, memberRoles, invitable, invitableDisplayNames,
                List.of(), List.of(), currentChunkOwner,
                FactionPermissions.canManageClaims(faction.roleOf(player.getUUID())), ownClaims);
    }
}
```

- [ ] **Step 4: Write `ClaimProtectionHandler.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.block.ModBlocks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Build/break protection inside claimed chunks, and the Faction Center's special
 *  placement/break rules (see the design spec) - everyone else's build/break rule is simply
 *  "member of the owning faction, or the chunk isn't claimed". */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class ClaimProtectionHandler {

    private ClaimProtectionHandler() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null) return; // unclaimed, vanilla rules apply

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());

        if (event.getState().is(ModBlocks.FACTION_CENTER.get())) {
            boolean canBreak = isMember && FactionPermissions.canBreakFactionCenter(faction.roleOf(sp.getUUID()));
            if (!canBreak) {
                event.setCanceled(true);
                return;
            }
            claims.unclaim(key);
            return;
        }

        if (!isMember) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ResourceKey<Level> dimension = level.dimension();
        String key = ClaimsData.chunkKey(dimension, chunk.x, chunk.z);
        ClaimsData claims = ClaimsData.get(level);
        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());

        if (event.getPlacedBlock().is(ModBlocks.FACTION_CENTER.get())) {
            ClaimsData.ClaimEntry existing = claims.get(key);

            if (faction == null) {
                if (existing != null) { event.setCanceled(true); return; }
                if (FactionEventHandler.hasPendingCapital(sp.getUUID())) { event.setCanceled(true); return; }
                FactionEventHandler.recordPendingCapital(sp, dimension, chunk);
                return;
            }

            if (!FactionPermissions.canPlaceFactionCenter(faction.roleOf(sp.getUUID()))) {
                event.setCanceled(true);
                return;
            }
            if (existing != null && !existing.factionId().equals(faction.id())) {
                event.setCanceled(true);
                return;
            }
            claims.claim(key, faction.id(), true);
            return;
        }

        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null) return;
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());
        if (!isMember) event.setCanceled(true);
    }
}
```

Note: this file does not need the `PlayerInteractEvent`/`Player` imports it might look like it
needs at a glance - both `event.getPlayer()`/`event.getEntity()` resolve to `Player`/`Entity`
via the `BlockEvent` supertypes already imported. Drop the unused
`net.minecraftforge.event.entity.player.PlayerInteractEvent` and
`net.minecraft.world.entity.player.Player` imports if the compiler flags them as unused.

- [ ] **Step 5: Update `FactionScreen.java`'s no-faction view for forced creation**

Replace `initNoFactionView`:

```java
    private void initNoFactionView() {
        if (state.mustCreateFaction()) {
            nameField = new EditBox(font, left, y, PANEL_W - 70, ROW_H, Component.literal("Faction name"));
            addRenderableWidget(nameField);
            addRenderableWidget(Button.builder(Component.literal("Create"), b -> onCreate())
                    .pos(left + PANEL_W - 65, y).size(65, ROW_H).build());
            return;
        }

        int shown = 0;
        for (int i = 0; i < state.pendingInviteIds().size() && shown < MAX_ROWS; i++, shown++) {
            String id = state.pendingInviteIds().get(i);
            String name = state.pendingInviteNames().get(i);
            addRow(Component.literal(name), List.of(
                    new RowButton("Accept", b -> send(Action.ACCEPT, id)),
                    new RowButton("Decline", b -> send(Action.DECLINE, id))));
        }
    }
```

- [ ] **Step 6: Make the forced-create screen undismissable**

Add to `FactionScreen`, replacing any existing `shouldCloseOnEsc`/`onClose` overrides (there are
none yet in this class - these are new):

```java
    @Override
    public boolean shouldCloseOnEsc() {
        return !state.mustCreateFaction();
    }

    @Override
    public void onClose() {
        // Deliberately empty while mustCreateFaction is true - same "only way out is completing
        // it" pattern as NamePickerScreen. Otherwise fall through to the normal close.
        if (!state.mustCreateFaction()) super.onClose();
    }
```

- [ ] **Step 7: Add the claim/unclaim and Show Faction Area buttons to `initFactionView`**

Add at the end of `initFactionView`, right before the existing Disband/Leave button block:

```java
        y += 12;
        if (state.canManageClaims()) {
            boolean ownedByMe = state.currentChunkOwner().equals(state.factionName());
            String label = ownedByMe ? "Unclaim this chunk" : "Claim this chunk";
            Action action = ownedByMe ? Action.UNCLAIM : Action.CLAIM;
            addRenderableWidget(Button.builder(Component.literal(label), b -> send(action, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
            y += ROW_GAP;
        }

        String areaLabel = ClaimBorderRenderer.isEnabled() ? "Hide Faction Area" : "Show Faction Area";
        addRenderableWidget(Button.builder(Component.literal(areaLabel),
                b -> ClaimBorderRenderer.toggle(state.ownClaimedChunkKeys()))
                .pos(left, y).size(PANEL_W, ROW_H).build());
        y += ROW_GAP;
```

Add the import:

```java
import com.tirener.shadiom.shadiomrpoverhaul.client.faction.ClaimBorderRenderer;
```

(Same file, so this is actually just a same-package reference - no import needed if
`ClaimBorderRenderer` lives in `client.faction` alongside `FactionScreen`; skip adding an import
statement, since both classes are in `com.tirener.shadiom.shadiomrpoverhaul.client.faction`.)

- [ ] **Step 8: Write `ClaimBorderRenderer.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws a translucent wall on the outer boundary of the viewer's faction territory (edges
 * between a claimed chunk and a non-claimed one only - not every internal chunk edge, so it
 * reads as a boundary rather than a grid). Toggled from {@code FactionScreen}; the chunk list is
 * a snapshot from whenever it was toggled on, not live - see the design spec.
 * <p>
 * ponytail: full world height per wall, fine for a handful of claimed chunks; if a faction's
 * territory gets huge this gets expensive to draw - cap the Y range or switch to only rendering
 * near the camera if that ever matters.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, value = Dist.CLIENT)
public final class ClaimBorderRenderer {

    private ClaimBorderRenderer() {}

    private static final float R = 0.2f, G = 0.6f, B = 1f, A = 0.35f;
    private static final double MIN_Y = -64, MAX_Y = 320;

    private static boolean enabled = false;
    private static List<ChunkPos> chunks = List.of();

    public static boolean isEnabled() { return enabled; }

    public static void toggle(List<String> ownClaimedChunkKeys) {
        if (enabled) {
            enabled = false;
            return;
        }
        chunks = parse(ownClaimedChunkKeys);
        enabled = true;
    }

    private static List<ChunkPos> parse(List<String> keys) {
        List<ChunkPos> result = new ArrayList<>();
        for (String key : keys) {
            String[] parts = key.split(",");
            if (parts.length != 3) continue;
            result.add(new ChunkPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }
        return result;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || chunks.isEmpty()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Set<ChunkPos> claimed = new HashSet<>(chunks);
        Vec3 camera = event.getCamera().getPosition();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f pose = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (ChunkPos chunk : claimed) {
            int x0 = chunk.getMinBlockX(), x1 = chunk.getMaxBlockX() + 1;
            int z0 = chunk.getMinBlockZ(), z1 = chunk.getMaxBlockZ() + 1;

            if (!claimed.contains(new ChunkPos(chunk.x - 1, chunk.z))) {
                wall(buffer, pose, x0, x0, z0, z1, MIN_Y, MAX_Y);
            }
            if (!claimed.contains(new ChunkPos(chunk.x + 1, chunk.z))) {
                wall(buffer, pose, x1, x1, z0, z1, MIN_Y, MAX_Y);
            }
            if (!claimed.contains(new ChunkPos(chunk.x, chunk.z - 1))) {
                wall(buffer, pose, x0, x1, z0, z0, MIN_Y, MAX_Y);
            }
            if (!claimed.contains(new ChunkPos(chunk.x, chunk.z + 1))) {
                wall(buffer, pose, x0, x1, z1, z1, MIN_Y, MAX_Y);
            }
        }

        tesselator.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void wall(BufferBuilder buffer, Matrix4f pose,
                              double x0, double x1, double z0, double z1, double y0, double y1) {
        buffer.vertex(pose, (float) x0, (float) y0, (float) z0).color(R, G, B, A).endVertex();
        buffer.vertex(pose, (float) x1, (float) y0, (float) z1).color(R, G, B, A).endVertex();
        buffer.vertex(pose, (float) x1, (float) y1, (float) z1).color(R, G, B, A).endVertex();
        buffer.vertex(pose, (float) x0, (float) y1, (float) z0).color(R, G, B, A).endVertex();
    }
}
```

Note: `Minecraft` is imported but unused in this listing - drop the import if the compiler flags
it (it's not needed since `event.getCamera()` already provides everything used here).

- [ ] **Step 9: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. Likely mismatches to check via the same source-lookup technique
used throughout this project if they come up:
- `RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS` (exact enum constant name)
- `BufferBuilder.vertex(Matrix4f, float, float, float)` return type chaining `.color(float,
  float, float, float).endVertex()` (verified against `LevelRenderer.renderShape` already, but
  confirm the float vs int `color()` overload resolves - `LevelRenderer` used floats)
- `BlockEvent.EntityPlaceEvent.getPlacedBlock()`/`getEntity()`/`getPos()` exact method names
- `ChunkPos.getMinBlockX()`/`getMaxBlockX()` etc.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add land claims, Faction Center creation flow, protection, and territory border rendering"
```

---

### Task 4: In-game smoke test

- [ ] **Step 1: Start a dev server and two clients**

`./gradlew runServer`, then `./gradlew runClient` for two separate players (or whatever the
project's configured run tasks are - `./gradlew tasks --group="forgegradle runs"` to check).

- [ ] **Step 2: Walk the spec's Testing checklist**

1. Player A (no faction) places a Faction Center - expect the forced create-only screen (no
   ESC, no invites shown), typing a name and clicking Create succeeds, and that chunk is now
   Player A's faction's capital.
2. Player B (no faction, not a member of A's faction) places a Faction Center elsewhere -
   expect it to succeed too (anyone faction-less may found a faction) and open Player B's own
   forced create screen.
3. Player A (now a member, not leader, of their own faction - they're the leader, so instead
   have Player A invite Player B, Player B accept, then as a MEMBER not the leader) tries to
   place a second Faction Center for Player A's faction - expect it to be blocked (not leader).
4. As Player A (leader), place a second Faction Center elsewhere unclaimed for the same
   faction - expect it to succeed and become a second, disconnected capital.
5. Open `/faction` as Player A standing in a chunk adjacent to a capital, click "Claim this
   chunk" - expect it to succeed and the button to flip to "Unclaim this chunk" on next open.
6. Try to claim a chunk NOT adjacent to any of Player A's faction's existing claims - expect no
   change (silent no-op).
7. As Player B (still just a member, not officer/leader), confirm the Claim/Unclaim button
   doesn't appear at all (not just disabled).
8. Have Player C (not in Player A's faction) try to break a block inside Player A's claimed
   territory - expect it to fail. Have Player A (a member) break/place a normal block there -
   expect it to succeed.
9. Have Player B (a member, not leader) try to break Player A's faction's Faction Center -
   expect it to fail. Have Player A (leader) break it - expect it to succeed and that chunk to
   revert to unclaimed (confirm via the "Claim this chunk" button now showing there for anyone).
10. Toggle "Show Faction Area" as Player A - expect a translucent border to appear only on the
    outer edge of the claimed chunks (not on internal edges between two adjacent owned chunks).
    Toggle again - expect it to disappear.
11. Disband Player A's faction (after re-granting leadership context if needed, or just test
    with a fresh faction) - expect all its claims to release (confirm another faction can now
    claim that land, or "Claim this chunk" shows available for it).
12. Restart the server and confirm claims and the Faction Center's block state persist (NBT
    round-trip check, same as sub-project 1's persistence check).

- [ ] **Step 3: Fix anything that doesn't match, recompiling after each fix**

Bugs are most likely in `FactionEventHandler` (adjacency/permission logic),
`ClaimProtectionHandler` (event cancellation conditions), or `ClaimBorderRenderer` (visual only -
compiles fine but looks wrong is the failure mode to watch for there specifically).

- [ ] **Step 4: Stop the dev server/clients, commit if any fixes were made**

```bash
git add -A
git commit -m "Fix issues found in land claims in-game smoke test"
```

(Skip this commit if step 3 required no changes.)
