package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a translucent wall on the outer boundary of every known territory (edges between two
 * chunks of a different territoryId only - not every internal chunk edge, so each territory reads
 * as its own boundary rather than one big grid). Every faction gets its own color, deterministic
 * from a hash of its id so no extra data has to travel over the wire for it; an abandoned
 * territory (no faction) is grey. Toggled from {@code FactionScreen}, which requests a fresh
 * snapshot from the server rather than using only client-known data, since this shows every
 * faction's land, not just the viewer's own; kept live afterward by
 * {@link #applyUpdate}, driven by a server-side broadcast sent whenever any claim changes
 * anywhere (see {@code FactionEventHandler#broadcastTerritoryMap}).
 * <p>
 * ponytail: full world height per wall, fine for a handful of claimed chunks; if the world's
 * total claimed area gets huge this gets expensive to draw - cap the Y range or switch to only
 * rendering near the camera if that ever matters.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, value = Dist.CLIENT)
public final class ClaimBorderRenderer {

    private ClaimBorderRenderer() {}

    private static final float A = 0.35f;
    private static final float[] GREY = {0.5f, 0.5f, 0.5f};
    private static final double MIN_Y = -64, MAX_Y = 320;

    /** Pushes each wall slightly outward off the exact chunk-boundary plane so it doesn't
     *  z-fight with block faces that happen to sit on that same plane. */
    private static final double EPSILON = 0.02;

    private record TerritoryChunk(String factionId, String territoryId) {}

    private static boolean enabled = false;
    private static Map<ChunkPos, TerritoryChunk> chunks = Map.of();

    public static boolean isEnabled() { return enabled; }

    /** Turns the map on (or refreshes it) with a full snapshot - the direct response to the
     *  player explicitly asking to see it. */
    public static void show(List<String> chunkKeys, List<String> factionIds, List<String> territoryIds) {
        chunks = parse(chunkKeys, factionIds, territoryIds);
        enabled = true;
    }

    /** Applies a broadcasted snapshot, but only while already showing - a change made by some
     *  other faction shouldn't turn the map on for a player who never asked to see it. */
    public static void applyUpdate(List<String> chunkKeys, List<String> factionIds, List<String> territoryIds) {
        if (!enabled) return;
        chunks = parse(chunkKeys, factionIds, territoryIds);
    }

    public static void hide() {
        enabled = false;
    }

    private static Map<ChunkPos, TerritoryChunk> parse(List<String> chunkKeys, List<String> factionIds, List<String> territoryIds) {
        Map<ChunkPos, TerritoryChunk> result = new HashMap<>();
        for (int i = 0; i < chunkKeys.size(); i++) {
            String[] parts = chunkKeys.get(i).split(",");
            if (parts.length != 3) continue;
            ChunkPos pos = new ChunkPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            result.put(pos, new TerritoryChunk(factionIds.get(i), territoryIds.get(i)));
        }
        return result;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || chunks.isEmpty()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

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

        for (Map.Entry<ChunkPos, TerritoryChunk> entry : chunks.entrySet()) {
            ChunkPos chunk = entry.getKey();
            String territoryId = entry.getValue().territoryId();
            float[] color = colorFor(entry.getValue().factionId());

            int x0 = chunk.getMinBlockX(), x1 = chunk.getMaxBlockX() + 1;
            int z0 = chunk.getMinBlockZ(), z1 = chunk.getMaxBlockZ() + 1;

            if (differentTerritory(chunk.x - 1, chunk.z, territoryId)) {
                wall(buffer, pose, x0 - EPSILON, x0 - EPSILON, z0 - EPSILON, z1 + EPSILON, MIN_Y, MAX_Y, color);
            }
            if (differentTerritory(chunk.x + 1, chunk.z, territoryId)) {
                wall(buffer, pose, x1 + EPSILON, x1 + EPSILON, z0 - EPSILON, z1 + EPSILON, MIN_Y, MAX_Y, color);
            }
            if (differentTerritory(chunk.x, chunk.z - 1, territoryId)) {
                wall(buffer, pose, x0 - EPSILON, x1 + EPSILON, z0 - EPSILON, z0 - EPSILON, MIN_Y, MAX_Y, color);
            }
            if (differentTerritory(chunk.x, chunk.z + 1, territoryId)) {
                wall(buffer, pose, x0 - EPSILON, x1 + EPSILON, z1 + EPSILON, z1 + EPSILON, MIN_Y, MAX_Y, color);
            }
        }

        tesselator.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static boolean differentTerritory(int chunkX, int chunkZ, String territoryId) {
        TerritoryChunk neighbor = chunks.get(new ChunkPos(chunkX, chunkZ));
        return neighbor == null || !neighbor.territoryId().equals(territoryId);
    }

    /** Grey for an abandoned (ownerless) territory; otherwise a color hashed from the faction id,
     *  so every faction reads as a distinct, stable color without sending color data over the
     *  wire at all. */
    private static float[] colorFor(String factionId) {
        if (factionId == null || factionId.isEmpty()) return GREY;

        float hue = (Math.floorMod(factionId.hashCode(), 360)) / 360f;
        int rgb = Color.HSBtoRGB(hue, 0.65f, 1f);
        return new float[] {
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
        };
    }

    private static void wall(BufferBuilder buffer, Matrix4f pose,
                              double x0, double x1, double z0, double z1, double y0, double y1, float[] color) {
        buffer.vertex(pose, (float) x0, (float) y0, (float) z0).color(color[0], color[1], color[2], A).endVertex();
        buffer.vertex(pose, (float) x1, (float) y0, (float) z1).color(color[0], color[1], color[2], A).endVertex();
        buffer.vertex(pose, (float) x1, (float) y1, (float) z1).color(color[0], color[1], color[2], A).endVertex();
        buffer.vertex(pose, (float) x0, (float) y1, (float) z0).color(color[0], color[1], color[2], A).endVertex();
    }
}
