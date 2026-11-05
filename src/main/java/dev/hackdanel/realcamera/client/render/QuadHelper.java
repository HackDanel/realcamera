package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector3f;

/** Emits lit, textured quads and boxes with the winding fixed up for the given normal. */
public final class QuadHelper {
    private QuadHelper() {}

    /**
     * Quad from 4 corners (any consistent loop order) with per-corner UVs. Order is reversed if
     * needed so the front side faces {@code normal}.
     */
    public static void quad(PoseStack.Pose pose, VertexConsumer vc, float[][] p, float[][] uv, Vector3f normal,
                            int color, int light) {
        Vector3f a = new Vector3f(p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]);
        Vector3f b = new Vector3f(p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]);
        boolean flip = a.cross(b).dot(normal) < 0;
        for (int k = 0; k < 4; k++) {
            int i = flip ? 3 - k : k;
            vc.addVertex(pose, p[i][0], p[i][1], p[i][2]).setColor(color).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
        }
    }

    /** Axis-aligned rectangle in the z-plane, facing -z (the viewer side of a wall frame) or +z. */
    public static void rectZ(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z,
                             boolean facingMinusZ, float u0, float v0, float u1, float v1, int color, int light) {
        // u runs from the viewer's left to right; facing -z the viewer's right is -x.
        float ul = facingMinusZ ? u1 : u0, ur = facingMinusZ ? u0 : u1;
        float[][] p = {{x0, y0, z}, {x1, y0, z}, {x1, y1, z}, {x0, y1, z}};
        float[][] uv = {{ul, v1}, {ur, v1}, {ur, v0}, {ul, v0}};
        quad(pose, vc, p, uv, new Vector3f(0, 0, facingMinusZ ? -1 : 1), color, light);
    }

    /** Solid box textured with an atlas sprite on every face (UVs scaled to face size, 1 block = full sprite). */
    public static void box(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1,
                           float z1, TextureAtlasSprite s, int color, int light) {
        float[][][] faces = {
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}},
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
        };
        Vector3f[] normals = {new Vector3f(0, 0, -1), new Vector3f(0, 0, 1), new Vector3f(-1, 0, 0),
                new Vector3f(1, 0, 0), new Vector3f(0, -1, 0), new Vector3f(0, 1, 0)};
        float[][] sizes = {{x1 - x0, y1 - y0}, {x1 - x0, y1 - y0}, {z1 - z0, y1 - y0}, {z1 - z0, y1 - y0},
                {x1 - x0, z1 - z0}, {x1 - x0, z1 - z0}};
        for (int f = 0; f < 6; f++) {
            float us = Math.min(1, Math.abs(sizes[f][0])), vs = Math.min(1, Math.abs(sizes[f][1]));
            float u0 = s.getU0(), v0 = s.getV0(), u1 = s.getU(us), v1 = s.getV(vs);
            float[][] uv = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
            quad(pose, vc, faces[f], uv, normals[f], color, light);
        }
    }
}
