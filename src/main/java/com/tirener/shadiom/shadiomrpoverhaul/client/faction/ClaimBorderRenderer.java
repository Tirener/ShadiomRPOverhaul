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
