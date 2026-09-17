# Factions: Territories (v1)

Sub-project 4 of the factions effort, building on sub-project 2
(`2026-09-17-factions-land-claims-design.md`), whose single-blob-per-faction claim model this
replaces. Scope: multiple named territories per faction (each anchored by its own Faction
Center), a universal 1-chunk gap between any two territories (own or foreign) enforced via 8-way
adjacency, a 100-chunk cap per territory, one designated capital territory whose destruction
disbands the faction, an abandoned/repossessable state for a destroyed non-capital territory, and
a HUD announcement when a player crosses into a different territory.

## Data model

`Faction` gains:
- `Map<String, Territory> territories` - `Territory` is `{id, name}` (`name` nullable until set).
- `String capitalTerritoryId` - always non-null once the faction exists; set at founding and
  changeable by the leader afterward (see "Setting the capital" below).

`ClaimsData.ClaimEntry` changes from `(factionId, capital)` to
`(factionId, territoryId, factionCenter)`:
- `factionId` is now nullable - `null` means the chunk is part of an **abandoned** territory (see
  below), not that it's unclaimed. A chunk with no entry at all in `claims` is still plain
  unclaimed wilderness, unchanged from sub-project 2.
- `territoryId` groups chunks into a territory regardless of whether it currently has an owning
  faction. There's no reverse `Map<territoryId, Set<chunkKey>>` index - membership/count is
  computed by filtering `claims.entrySet()` (or, for a faction's own territory, the existing
  `claimsByFaction` index) by `territoryId`, a plain scan matching the "fine at this scale"
  precedent sub-project 2 already established for `releaseAll`.
- `factionCenter` replaces `capital`: true only for the exact chunk holding that territory's
  Faction Center block. It no longer means "the faction's one capital" - that's now
  `capitalTerritoryId` on `Faction`, a territory-level concept, not a chunk-level one.

`claimsByFaction` (`Map<factionId, Set<chunkKey>>`) drops any chunk whose `factionId` becomes
`null` - abandoned chunks aren't indexed under any faction.

**Save compatibility**: existing worlds have `ClaimEntry(factionId, capital)` with no territory
concept. On `ClaimsData.load`, any entry read in the old shape is migrated into a single
newly-generated territory per faction (named `null`, i.e. unnamed) collecting all of that
faction's pre-existing chunks; the chunk that had `capital=true` keeps `factionCenter=true` and
that generated territory becomes the faction's `capitalTerritoryId` (set on `FactionsData` load,
after `ClaimsData` has produced the migrated territory ids - load order matters here).

## The unified claim rule

Every check below reads the 8 surrounding chunks (or 4, where noted) via direct `ClaimsData.get`
lookups on the specific neighbor keys - not a scan - so this stays cheap regardless of world size.

An abandoned territory (see below) still counts as "occupied" for every gap check in this
section, even though it's open to build/break like wilderness - its chunks keep their old
`territoryId`, and any entry with a *different* `territoryId` than the one being extended trips
the "none of the 8 neighbors" rule regardless of whether that neighbor's `factionId` is null. This
keeps the reserved shape intact for repossession instead of letting an unrelated faction's claim
creep up against (or absorb the border of) land that's mid-abandonment.

**Extending a territory** (the existing Claim button, `FactionEventHandler.claim`): target chunk
must be unclaimed (no entry, not even an abandoned one). Of its 4 orthogonal neighbors
(unchanged from sub-project 2), collect the distinct `territoryId`s belonging to the acting
faction - there must be **exactly one**. Then check all 8 neighbors: **none** may belong to a
`territoryId` other than that one (own other territories and foreign territories are both
"other" here - one check covers both, which is what makes the "can't bridge two of your own
territories" case fall out for free rather than needing special-casing: bridging two of your own
territories would require a candidate chunk adjacent to both, which is only possible if they're
exactly 1 apart, and claiming into that gap would put a foreign-to-the-other-territory chunk
within 8-range of it, tripping this same check). That territory's current chunk count (scanned as
above) must be `< 100`. On success: `claims.claim(key, factionId, territoryId, factionCenter=false)`.

**Founding a new territory** (placing a Faction Center on unclaimed land, no existing entry at
all): none of the 8 surrounding chunks may belong to any territory, own or foreign. On success: a
new `territoryId` (random UUID string) is generated, added to `faction.territories` as
`{id, name=null}`, and the chunk is claimed with `factionCenter=true`. If this is the faction's
very first territory (the faction-founding flow, sub-project 2's pending-capital path), it's also
set as `capitalTerritoryId`.

**Placing a Faction Center on a chunk you already own** is no longer allowed (sub-project 2 had
this as a way to move the capital flag around; that's superseded by "Setting the capital" below,
which doesn't require touching a block). Each territory already has exactly one anchor, fixed at
founding.

**Founding on an abandoned chunk** (entry exists, `factionId == null`): skips the "8 neighbors
must be clear" check entirely - see "Repossessing an abandoned territory" below instead.

## Faction Center interactions

- **Right-click an owned Faction Center, as the faction's leader**: opens a new client screen
  (`TerritoryScreen`, same construction pattern as `FactionScreen` - built from a server-pushed
  snapshot record) for that specific territory: an editable name field, its current chunk
  count/100 cap, and a "Set as Capital" button (hidden or disabled if it's already the capital).
  Right-click by a non-leader, or on a chunk that isn't a Faction Center, does nothing new
  (falls through to vanilla interaction).
- **Renaming**: a new `FactionActionC2SPacket` action, `RENAME_TERRITORY`, argument is
  `territoryId|newName` (pipe-split, mirroring `DiplomacyData.pairKey`'s convention elsewhere in
  this codebase) - server re-validates the actor is still that faction's leader before writing.
  No length cap or filter beyond what `FriendlyByteBuf#writeUtf`'s default limit already enforces
  - out of scope to add a profanity filter here, unlike the separate player-naming feature which
    has one for a different reason (a finite shared pool of names).
- **Setting the capital**: a new action, `SET_CAPITAL_TERRITORY`, argument is the `territoryId`.
  Server validates the actor is the leader and that the territory belongs to their faction, then
  sets `faction.capitalTerritoryId`. No chunk/claim changes - purely a designation.
- **Breaking the capital territory's Faction Center**: unchanged from sub-project 2 - disbands the
  whole faction (`ClaimsData.releaseAll` + `DiplomacyData.releaseAll` + `data.remove`).
- **Breaking a non-capital territory's Faction Center**: that territory becomes **abandoned**,
  not disbanded and not simply unclaimed. Every chunk with that `territoryId` has its `factionId`
  set to `null` and `factionCenter` reset to `false` (the block is physically gone, so no chunk in
  it is an anchor anymore); the chunks stay in `claims` un-removed, so their `territoryId` still
  groups them for repossession. The faction's `territories` map drops that entry.

## Abandoned territories

An abandoned territory is a `ClaimEntry` group with `factionId == null` sharing a `territoryId`.
Per your call: **open like wilderness** for ordinary build/break - `ClaimProtectionHandler`'s
generic "claimed → members only" branch only applies when `factionId != null`, so abandoned
chunks fall through to vanilla rules exactly like never-claimed land. The only thing that treats
them specially is Faction Center placement and the HUD.

**Repossession**: placing a Faction Center on any chunk belonging to an abandoned territory
transfers the *entire* former territory to the placing faction atomically - every chunk sharing
that old `territoryId` is rewritten to a freshly-generated `territoryId`, the placing faction's
id, and `factionCenter=false` except the newly-placed chunk (`true`). A new `Territory` entry
(unnamed) is added to the faction's `territories` map. The old name (if the dissolved territory
had one) is not carried over - abandoned land has no name of its own by definition (see HUD
below), so there's nothing to inherit. The repossessing faction's own gap rule against *other,
still-active* territories doesn't need re-checking here: the blob's shape and position are
unchanged from when it was originally, validly founded, so no new adjacency violation can be
introduced by a same-shape ownership transfer.

## HUD

Client-side, top-center fading text (title-style, a few seconds, matching the visual weight of a
vanilla advancement toast rather than a persistent element) shown whenever the player's current
chunk's owning territory changes:
- Named territory → the territory's name.
- Unnamed territory → the owning faction's name (fallback).
- Abandoned territory (`factionId == null` entry present) → literal text `"Abandoned Territory"`.
- No entry at all → `"Wilderness"`.

**Delivery**: server-side, `FactionEventHandler` (or a small new class alongside it) tracks each
online player's last-announced chunk key via `PlayerTickEvent` (checked at a low cadence, e.g.
once per second per player via a tick counter - not every tick - since chunk crossings are rare
relative to the tick rate); on change, looks up the new chunk's `ClaimEntry`, resolves the display
string per the rules above, and sends a new lightweight S2C packet (`TerritoryHudS2CPacket`,
single string field) only to that player - same "small packet, no screen touched" shape as
`FactionClaimsSyncS2CPacket` from the diplomacy live-update work. Client applies it to a new
`TerritoryHudRenderer` (client-only, subscribed to a GUI overlay render event) that owns the
fade timer and current text.

## Networking

New packets (registered in `ModNetwork`, next free ids `7` and `8` - never renumber existing
ones):
- `OpenTerritoryScreenS2CPacket` (id 7, PLAY_TO_CLIENT): territory id, current name, chunk count,
  whether it's the capital - opens `TerritoryScreen`.
- `TerritoryHudS2CPacket` (id 8, PLAY_TO_CLIENT): the display string to show/fade in.

`FactionActionC2SPacket.Action` gains `RENAME_TERRITORY` and `SET_CAPITAL_TERRITORY`, following
the existing `arg` (single string) convention - `RENAME_TERRITORY` packs `territoryId|newName`
into that one field the same way other pipe-joined args already work elsewhere in this codebase.

## Error handling

Same silent-no-op-on-failure convention as every other faction action in this codebase: a
disallowed claim, an unauthorized rename/capital-set, or renaming a nonexistent territory id all
just do nothing and let the next snapshot show the actor that nothing changed.

## Testing

No test harness exists in this repo (same as every prior sub-project) - verification is
`./gradlew compileJava` per task plus an in-game smoke test covering: founding a second territory
sufficiently far from the first (succeeds) versus too close (blocked, 8-way including diagonal),
claiming a chunk that would bridge two of the faction's own territories (blocked), hitting the
100-chunk cap on one territory while another territory of the same faction still has room
(blocked on the full one, allowed on the other), renaming a territory and setting a different one
as capital via the new screen, breaking the capital Faction Center (faction disbands) versus a
non-capital one (territory abandons, land becomes buildable by anyone, then a rival faction plants
a Faction Center inside it and receives the whole former blob), and walking across a territory
boundary to confirm the HUD text changes appropriately for named/unnamed/abandoned/wilderness.

## Out of scope

- Migrating/renaming a territory's `territoryId` for reasons other than repossession.
- Any limit on how many territories a single faction may found (only per-territory chunk count is
  capped).
- Diplomacy interactions with territories (e.g., allied factions sharing a border) - per your
  answer, the gap rule applies uniformly regardless of relation; no exemption exists to design.
- Undoing/objecting to a capital-territory Faction Center break beyond the existing leader-only
  permission check - if the leader breaks it, the faction disbands, full stop.
- A cap on how large an abandoned blob can get before repossession (it's whatever the dissolved
  territory already was, always ≤ 100).
