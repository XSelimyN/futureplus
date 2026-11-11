package com.futureplus.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws colored translucent quads into the world (shared for the drawings of the allowed modules).
 * Coordinates RELATIVE TO THE CAMERA (camera = 0,0,0). Lines are drawn as thin strips facing the camera;
 * thickness is given in screen pixels and looks the same regardless of distance.
 *
 * LevelRendererMixin calls render() every frame in the main pass (after entities are drawn).
 */
public final class WorldDraw {
    private WorldDraw() {}

    /** Depth tested (what is behind a wall is hidden). */
    static final RenderPipeline QUADS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/futureplus_world_quads")
            .withCull(false)
            .build());

    /** Without depth test (visible through walls). */
    static final RenderPipeline QUADS_XRAY = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/futureplus_world_quads_xray")
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());

    /** KillEffects: vanilla lightning blending (additive), without depth test (visible through walls). */
    static final RenderPipeline LIGHTNING_XRAY = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/futureplus_lightning_xray")
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .build());

    static void init() { }

    private static final ByteBufferBuilder BYTES_DEPTH = new ByteBufferBuilder(1 << 18);
    private static final ByteBufferBuilder BYTES_XRAY = new ByteBufferBuilder(1 << 16);
    private static final ByteBufferBuilder BYTES_BOLT = new ByteBufferBuilder(1 << 16);
    private static BufferBuilder depth, xray, bolt;
    private static int nDepth, nXray, nBolt;
    private static GpuBuffer lastVb1, lastVb2, lastVb3;
    static float pxWorld = 0.001f;            // the size of 1 screen pixel at 1 block distance

    /** At the start of the frame: prepare the buffers. */
    private static void begin() {
        depth = new BufferBuilder(BYTES_DEPTH, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        xray = new BufferBuilder(BYTES_XRAY, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bolt = new BufferBuilder(BYTES_BOLT, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        nDepth = nXray = nBolt = 0;
        Minecraft mc = Minecraft.getInstance();
        double fov = 70;
        try { fov = mc.options.fov().get(); } catch (Throwable ignored) { }
        int hPx = Math.max(1, mc.getWindow().getHeight());
        pxWorld = (float) (2.0 * Math.tan(Math.toRadians(fov) / 2.0) / hPx);
    }

    static void quad(boolean seeThrough, float x1, float y1, float z1, float x2, float y2, float z2,
                     float x3, float y3, float z3, float x4, float y4, float z4, int c1, int c2, int c3, int c4) {
        BufferBuilder b = seeThrough ? xray : depth;
        if (b == null) return;
        b.addVertex(x1, y1, z1).setColor(c1);
        b.addVertex(x2, y2, z2).setColor(c2);
        b.addVertex(x3, y3, z3).setColor(c3);
        b.addVertex(x4, y4, z4).setColor(c4);
        if (seeThrough) nXray++; else nDepth++;
    }

    /** Line facing the camera: a -> b, thickness in screen pixels. */
    static void line(boolean seeThrough, Vec3 a, Vec3 b, float widthPx, int ca, int cb) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, mz = (a.z + b.z) / 2;
        // side vector = direction x (from the midpoint to the camera)
        double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        double ha = widthPx * pxWorld * Math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z) / 2 / sl;
        double hb = widthPx * pxWorld * Math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z) / 2 / sl;
        quad(seeThrough,
            (float) (a.x - sx * ha), (float) (a.y - sy * ha), (float) (a.z - sz * ha),
            (float) (a.x + sx * ha), (float) (a.y + sy * ha), (float) (a.z + sz * ha),
            (float) (b.x + sx * hb), (float) (b.y + sy * hb), (float) (b.z + sz * hb),
            (float) (b.x - sx * hb), (float) (b.y - sy * hb), (float) (b.z - sz * hb),
            ca, ca, cb, cb);
    }

    /** Box fill (6 faces). */
    static void boxFill(boolean seeThrough, AABB r, int c) {
        float x0 = (float) r.minX, y0 = (float) r.minY, z0 = (float) r.minZ;
        float x1 = (float) r.maxX, y1 = (float) r.maxY, z1 = (float) r.maxZ;
        quad(seeThrough, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, c, c, c, c);
        quad(seeThrough, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, c, c, c, c);
        quad(seeThrough, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, c, c, c, c);
        quad(seeThrough, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c, c, c, c);
        quad(seeThrough, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, c, c, c, c);
        quad(seeThrough, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, c, c, c, c);
    }

    /** Box frame (12 edges). */
    static void boxOutline(boolean seeThrough, AABB r, float widthPx, int c) {
        Vec3[] v = {
            new Vec3(r.minX, r.minY, r.minZ), new Vec3(r.maxX, r.minY, r.minZ), new Vec3(r.maxX, r.minY, r.maxZ), new Vec3(r.minX, r.minY, r.maxZ),
            new Vec3(r.minX, r.maxY, r.minZ), new Vec3(r.maxX, r.maxY, r.minZ), new Vec3(r.maxX, r.maxY, r.maxZ), new Vec3(r.minX, r.maxY, r.maxZ)};
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] k : e) line(seeThrough, v[k[0]], v[k[1]], widthPx, c, c);
    }

    /** Box with a bottom to top color gradient: side faces bottom -> top, floor in the bottom color, ceiling optional. */
    static void boxGradient(boolean seeThrough, AABB r, int bottom, int top, boolean floor, boolean roof) {
        float x0 = (float) r.minX, y0 = (float) r.minY, z0 = (float) r.minZ;
        float x1 = (float) r.maxX, y1 = (float) r.maxY, z1 = (float) r.maxZ;
        if (floor) quad(seeThrough, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, bottom, bottom, bottom, bottom);
        if (roof) quad(seeThrough, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, top, top, top, top);
        quad(seeThrough, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, bottom, top, top, bottom);
        quad(seeThrough, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, bottom, bottom, top, top);
        quad(seeThrough, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, bottom, bottom, top, top);
        quad(seeThrough, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, bottom, top, top, bottom);
    }

    /** Frame with a bottom to top color gradient. */
    static void outlineGradient(boolean seeThrough, AABB r, float widthPx, int bottom, int top) {
        Vec3[] v = {
            new Vec3(r.minX, r.minY, r.minZ), new Vec3(r.maxX, r.minY, r.minZ), new Vec3(r.maxX, r.minY, r.maxZ), new Vec3(r.minX, r.minY, r.maxZ),
            new Vec3(r.minX, r.maxY, r.minZ), new Vec3(r.maxX, r.maxY, r.minZ), new Vec3(r.maxX, r.maxY, r.maxZ), new Vec3(r.minX, r.maxY, r.maxZ)};
        for (int i = 0; i < 4; i++) {
            line(seeThrough, v[i], v[(i + 1) % 4], widthPx, bottom, bottom);
            line(seeThrough, v[4 + i], v[4 + (i + 1) % 4], widthPx, top, top);
            line(seeThrough, v[i], v[4 + i], widthPx, bottom, top);
        }
    }

    /** Only the corners of the box (three short lines from each corner). */
    static void boxCorners(boolean seeThrough, AABB r, float widthPx, double frac, int c) {
        double lx = (r.maxX - r.minX) * frac, ly = (r.maxY - r.minY) * frac, lz = (r.maxZ - r.minZ) * frac;
        for (int i = 0; i < 8; i++) {
            boolean mx = (i & 1) != 0, my = (i & 2) != 0, mz = (i & 4) != 0;
            Vec3 p = new Vec3(mx ? r.maxX : r.minX, my ? r.maxY : r.minY, mz ? r.maxZ : r.minZ);
            line(seeThrough, p, p.add(mx ? -lx : lx, 0, 0), widthPx, c, c);
            line(seeThrough, p, p.add(0, my ? -ly : ly, 0), widthPx, c, c);
            line(seeThrough, p, p.add(0, 0, mz ? -lz : lz), widthPx, c, c);
        }
    }

    // ------------------------------------------------------------- kare

    private static boolean failed = false;

    /** LevelRendererMixin: in the main pass, after entities. */
    public static void render() {
        if (failed || (!MaceAura.wantsRender() && !PistonAura.wantsRender() && !AnchorAura.wantsRender()
            && !KillEffects.wantsRender() && !AutoCrystal.wantsRender())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            begin();
            Vec3 cam = mc.gameRenderer.getMainCamera().position();
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            if (MaceAura.wantsRender()) MaceAura.emit(cam, pt);
            if (PistonAura.wantsRender()) PistonAura.emit(cam);
            if (AnchorAura.wantsRender()) AnchorAura.emit(cam);
            if (AutoCrystal.wantsRender()) AutoCrystal.emit(cam);
            if (KillEffects.wantsRender()) nBolt = KillEffects.emit(bolt, cam);
            if (lastVb1 != null) { lastVb1.close(); lastVb1 = null; }
            if (lastVb2 != null) { lastVb2.close(); lastVb2 = null; }
            if (lastVb3 != null) { lastVb3.close(); lastVb3 = null; }
            lastVb1 = flush(depth, nDepth, QUADS);
            lastVb2 = flush(xray, nXray, QUADS_XRAY);
            lastVb3 = flush(bolt, nBolt, KillEffects.throughWalls ? LIGHTNING_XRAY : RenderPipelines.LIGHTNING);
        } catch (Throwable t) {
            failed = true;
            System.err.println("[Futureplus] World rendering disabled after an error: " + t);
        } finally {
            depth = xray = bolt = null;
        }
    }

    private static GpuBuffer flush(BufferBuilder b, int quads, RenderPipeline pipe) {
        if (b == null || quads == 0) {
            if (b != null) { MeshData m = b.build(); if (m != null) m.close(); }
            return null;
        }
        GpuBuffer vb;
        try (MeshData mesh = b.buildOrThrow()) {
            vb = RenderSystem.getDevice().createBuffer(() -> "Futureplus world quads", 32, mesh.vertexBuffer());
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(
            RenderSystem.getModelViewMatrix(), new Vector4f(1f, 1f, 1f, 1f), new Vector3f(), new Matrix4f());
        RenderSystem.AutoStorageIndexBuffer idx = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer index = idx.getBuffer(quads * 6);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Futureplus world", main.getColorTextureView(), OptionalInt.empty(),
                main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipe);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dyn);
            pass.setVertexBuffer(0, vb);
            pass.setIndexBuffer(index, idx.type());
            pass.drawIndexed(0, 0, quads * 6, 1);
        }
        return vb;
    }
}
