package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.processing.GpuBlur;
import java.nio.FloatBuffer;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * The photo's depth-of-field layers on the graphics card ({@link GpuBlur.Backend}): for every layer that holds
 * anything, a downsampling pass (layer weights and occupancy), the convolution with the aperture disc (the same
 * kernel as {@code BokehKernel.kernel}, cat's-eye variants per 16-cell tile) and the "over" composite into the
 * background or foreground, plus the copy of what lies behind each foreground pixel. {@code LensBlur} does the rest
 * (in-focus pixels, hole filling, final mix) in Java and does everything when this fails.
 *
 * <p>Runs on the render thread (the development worker waits for it): raw OpenGL with every touched binding saved and
 * restored, so Minecraft's state caches stay valid.
 */
public final class GpuLensBlur implements GpuBlur.Backend {
    private static final GpuLensBlur INSTANCE = new GpuLensBlur();
    /** Set after a failure (a driver without float targets...): Java does the blur from then on. */
    private static volatile boolean broken;

    /** Render-thread time of the current job, ms. */
    private volatile long renderMs;
    private int prepProgram, convProgram, compositeProgram, rememberProgram, vao, fbo;

    private GpuLensBlur() {}

    public static void register() {
        GpuBlur.setBackend(INSTANCE);
    }

    /** A render-thread step longer than this makes the game stutter: the card is too slow, Java does it instead. */
    private static final long SLOW_STEP_MS = 250;

    /** GL objects of one job, kept between the render-thread steps. */
    private static final class Ctx {
        int w, h, img, signed, behind;
        int[] bg, fg;
        int bgCur, fgCur;
        boolean anyNear, anyBehind;
    }

    @FunctionalInterface
    private interface Step<T> {
        T run();
    }

    @Override
    @Nullable
    public GpuBlur.Result layers(GpuBlur.Job job) {
        if (broken) return null;
        Ctx ctx = new Ctx();
        renderMs = 0;
        try {
            if (!step(() -> begin(ctx, job))) return null;
            // one layer per render-thread step: the game keeps drawing frames between them
            for (int k = job.levels() - 1; k >= 0; k--) {
                if (!job.farUsed()[k]) continue;
                GpuBlur.LayerKernels ker = GpuBlur.kernels(job, k, false);
                int kk = k;
                step(() -> {
                    ctx.bgCur = layer(ctx, kk, false, ker, ctx.bg, ctx.bgCur);
                    return true;
                });
            }
            for (int k = 0; k < job.levels(); k++) {
                if (!job.nearUsed()[k]) continue;
                GpuBlur.LayerKernels ker = GpuBlur.kernels(job, k, true);
                int kk = k;
                step(() -> {
                    if (ctx.anyNear) {
                        remember(ctx.fg[ctx.fgCur], ctx.signed, ctx.behind, kk, ctx.w, ctx.h);
                        ctx.anyBehind = true;
                    }
                    ctx.anyNear = true;
                    ctx.fgCur = layer(ctx, kk, true, ker, ctx.fg, ctx.fgCur);
                    return true;
                });
            }
            float[] bgOut = step(() -> read(ctx.bg[ctx.bgCur], ctx.w, ctx.h));
            float[] fgOut = ctx.anyNear ? step(() -> read(ctx.fg[ctx.fgCur], ctx.w, ctx.h)) : null;
            float[] behindOut = ctx.anyBehind ? step(() -> read(ctx.behind, ctx.w, ctx.h)) : null;
            boolean ok = step(() -> GL11.glGetError() == GL11.GL_NO_ERROR);
            if (!ok) {
                fail("OpenGL error");
                return null;
            }
            RealCamera.LOGGER.debug("GPU depth of field: {} layers, {}x{}, {} ms on the render thread", job.levels(),
                    ctx.w, ctx.h, renderMs);
            return new GpuBlur.Result(bgOut, fgOut, behindOut, ctx.anyNear);
        } catch (Exception e) {
            if (!broken) fail(e.toString());
            return null;
        } finally {
            try {
                step(() -> {
                    free(ctx);
                    return true;
                });
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
    }

    /** Runs a step on the render thread (directly when already on it) with the GL state saved and restored. */
    private <T> T step(Step<T> body) throws Exception {
        java.util.function.Supplier<T> wrapped = () -> {
            if (broken) throw new IllegalStateException("disabled");
            long t0 = System.nanoTime();
            int[] saved = saveState();
            try {
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBindVertexArray(vao);
                T out = body.run();
                GL11.glFinish();
                return out;
            } finally {
                restoreState(saved);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                renderMs += ms;
                if (ms > SLOW_STEP_MS) fail("a step took " + ms + " ms: the graphics card is slower than the CPU here");
            }
        };
        if (RenderSystem.isOnRenderThread()) return wrapped.get();
        return Minecraft.getInstance().submit(wrapped).get(30, TimeUnit.SECONDS);
    }

    private boolean begin(Ctx ctx, GpuBlur.Job job) {
        if (!ensurePrograms()) return false;
        int w = job.w(), h = job.h();
        int max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (w > max || h > max) return false;
        ctx.w = w;
        ctx.h = h;
        ctx.img = texture(w, h, GL30.GL_RGB32F, GL11.GL_RGB, job.img());
        ctx.signed = texture(w, h, GL30.GL_R32F, GL11.GL_RED, job.signed());
        ctx.bg = new int[]{texture(w, h, GL30.GL_RGBA32F, GL11.GL_RGBA, null), texture(w, h, GL30.GL_RGBA32F, GL11.GL_RGBA, null)};
        ctx.fg = new int[]{texture(w, h, GL30.GL_RGBA32F, GL11.GL_RGBA, null), texture(w, h, GL30.GL_RGBA32F, GL11.GL_RGBA, null)};
        ctx.behind = texture(w, h, GL30.GL_RGBA32F, GL11.GL_RGBA, null);
        for (int t : new int[]{ctx.bg[0], ctx.bg[1], ctx.fg[0], ctx.fg[1], ctx.behind}) clear(t, w, h);
        return true;
    }

    private static void free(Ctx ctx) {
        for (int t : new int[]{ctx.img, ctx.signed, ctx.behind}) if (t != 0) GL11.glDeleteTextures(t);
        if (ctx.bg != null) GL11.glDeleteTextures(ctx.bg);
        if (ctx.fg != null) GL11.glDeleteTextures(ctx.fg);
        ctx.img = ctx.signed = ctx.behind = 0;
        ctx.bg = ctx.fg = null;
    }

    /** One layer: downsample, convolve, composite over {@code acc[cur]} into the other one; returns the new current. */
    private int layer(Ctx ctx, int k, boolean near, GpuBlur.LayerKernels ker, int[] acc, int cur) {
        int w = ctx.w, h = ctx.h, scale = ker.scale(), lw = ker.lw(), lh = ker.lh();
        int c0 = texture(lw, lh, GL30.GL_RGBA32F, GL11.GL_RGBA, null), c1 = texture(lw, lh, GL30.GL_R32F, GL11.GL_RED, null);
        int d0 = texture(lw, lh, GL30.GL_RGBA32F, GL11.GL_RGBA, null), d1 = texture(lw, lh, GL30.GL_R32F, GL11.GL_RED, null);
        // kernel tables: taps (x, y, w) in rows of 1024, per variant (start, count), per tile its variant
        int nTaps = ker.taps().length / 3, tapRows = (nTaps + 1023) / 1024;
        float[] taps = new float[tapRows * 1024 * 3];
        System.arraycopy(ker.taps(), 0, taps, 0, ker.taps().length);
        float[] vinfo = new float[ker.start().length * 2];
        for (int i = 0; i < ker.start().length; i++) {
            vinfo[i * 2] = ker.start()[i];
            vinfo[i * 2 + 1] = ker.count()[i];
        }
        float[] tiles = new float[ker.tileVariant().length];
        for (int i = 0; i < tiles.length; i++) tiles[i] = ker.tileVariant()[i];
        int tapTex = texture(1024, tapRows, GL30.GL_RGB32F, GL11.GL_RGB, taps);
        int varTex = texture(ker.start().length, 1, GL30.GL_RG32F, GL30.GL_RG, vinfo);
        int tileTex = texture(ker.tw(), ker.th(), GL30.GL_R32F, GL11.GL_RED, tiles);
        try {
            // downsample with the layer weights
            target(c0, c1, lw, lh);
            GL20.glUseProgram(prepProgram);
            bind(prepProgram, "Img", 0, ctx.img);
            bind(prepProgram, "Signed", 1, ctx.signed);
            GL20.glUniform1i(loc(prepProgram, "Scale"), scale);
            GL20.glUniform1i(loc(prepProgram, "K"), k);
            GL20.glUniform1i(loc(prepProgram, "Near"), near ? 1 : 0);
            GL20.glUniform2i(loc(prepProgram, "Size"), w, h);
            draw();

            // the aperture disc
            target(d0, d1, lw, lh);
            GL20.glUseProgram(convProgram);
            bind(convProgram, "L0", 0, c0);
            bind(convProgram, "L1", 1, c1);
            bind(convProgram, "Taps", 2, tapTex);
            bind(convProgram, "Variants", 3, varTex);
            bind(convProgram, "Tiles", 4, tileTex);
            GL20.glUniform2i(loc(convProgram, "LSize"), lw, lh);
            draw();

            // over what is there
            int next = 1 - cur;
            target(acc[next], 0, w, h);
            GL20.glUseProgram(compositeProgram);
            bind(compositeProgram, "L0", 0, d0);
            bind(compositeProgram, "L1", 1, d1);
            bind(compositeProgram, "Acc", 2, acc[cur]);
            GL20.glUniform1i(loc(compositeProgram, "Scale"), scale);
            GL20.glUniform2i(loc(compositeProgram, "LSize"), lw, lh);
            draw();
            return next;
        } finally {
            GL11.glDeleteTextures(new int[]{c0, c1, d0, d1, tapTex, varTex, tileTex});
        }
    }

    private void remember(int fgTex, int signed, int behind, int k, int w, int h) {
        target(behind, 0, w, h);
        GL20.glUseProgram(rememberProgram);
        bind(rememberProgram, "Fg", 0, fgTex);
        bind(rememberProgram, "Signed", 1, signed);
        GL20.glUniform1i(loc(rememberProgram, "K"), k);
        draw();
    }

    // ------------------------------------------------------------------ GL plumbing

    private static void fail(String why) {
        broken = true;
        RealCamera.LOGGER.warn("GPU depth of field disabled ({}), developing on the CPU", why);
    }

    private static int loc(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    private static void bind(int program, String name, int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL20.glUniform1i(loc(program, name), unit);
    }

    private void target(int tex0, int tex1, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, tex1, 0);
        if (tex1 != 0) {
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        } else {
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
        }
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("incomplete framebuffer");
        }
        GL11.glViewport(0, 0, w, h);
    }

    private void clear(int tex, int w, int h) {
        target(tex, 0, w, h);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static void draw() {
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static int texture(int w, int h, int internal, int format, @Nullable float[] data) {
        int t = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        if (data == null) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL11.GL_FLOAT, (FloatBuffer) null);
        } else {
            FloatBuffer buf = MemoryUtil.memAllocFloat(data.length);
            try {
                buf.put(data).flip();
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL11.GL_FLOAT, buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
        return t;
    }

    private static float[] read(int tex, int w, int h) {
        FloatBuffer buf = MemoryUtil.memAllocFloat(w * h * 4);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
            float[] out = new float[w * h * 4];
            buf.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /** Everything the passes touch, to put back afterwards. */
    private static int[] saveState() {
        int[] s = new int[22];
        s[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        s[1] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        s[2] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        s[3] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        s[4] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        System.arraycopy(vp, 0, s, 5, 4);
        for (int u = 0; u < 5; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            s[u < 3 ? 9 + u : 17 + u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        s[12] = GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0;
        s[13] = GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0;
        s[14] = GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0;
        s[15] = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0;
        float[] cc = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, cc);
        for (int i = 0; i < 4; i++) s[16 + i] = Float.floatToRawIntBits(cc[i]);
        return s;
    }

    private static void restoreState(int[] s) {
        GL20.glUseProgram(s[0]);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, s[1]);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s[2]);
        GL30.glBindVertexArray(s[3]);
        for (int u = 0; u < 5; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[u < 3 ? 9 + u : 17 + u]);
        }
        GL13.glActiveTexture(s[4]);
        GL11.glViewport(s[5], s[6], s[7], s[8]);
        enable(GL11.GL_BLEND, s[12]);
        enable(GL11.GL_DEPTH_TEST, s[13]);
        enable(GL11.GL_CULL_FACE, s[14]);
        enable(GL11.GL_SCISSOR_TEST, s[15]);
        GL11.glClearColor(Float.intBitsToFloat(s[16]), Float.intBitsToFloat(s[17]), Float.intBitsToFloat(s[18]),
                Float.intBitsToFloat(s[19]));
    }

    private static void enable(int cap, int on) {
        if (on != 0) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }

    private boolean ensurePrograms() {
        if (prepProgram != 0) return true;
        try {
            prepProgram = program(PREP, true);
            convProgram = program(CONV, true);
            compositeProgram = program(COMPOSITE, false);
            rememberProgram = program(REMEMBER, false);
            vao = GL30.glGenVertexArrays();
            fbo = GL30.glGenFramebuffers();
            return true;
        } catch (RuntimeException e) {
            fail(e.getMessage());
            return false;
        }
    }

    private static int program(String fragment, boolean twoOutputs) {
        int vs = shader(GL20.GL_VERTEX_SHADER, VERTEX), fs = shader(GL20.GL_FRAGMENT_SHADER, COMMON + fragment);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL30.glBindFragDataLocation(p, 0, "o0");
        if (twoOutputs) GL30.glBindFragDataLocation(p, 1, "o1");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    // ------------------------------------------------------------------ shaders (LensBlur / BokehKernel in GLSL)

    private static final String VERTEX = """
            #version 150
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** LensBlur's layer weights. */
    private static final String COMMON = """
            #version 150
            const float LOG_STEP = 0.4054651081; // ln 1.5
            float focusWeight(float a) {
                if (a <= 0.5) return 1.0;
                if (a >= 1.0) return 0.0;
                return (1.0 - a) * 2.0;
            }
            float layerPos(float a) { return log(max(a, 1.0e-3)) / LOG_STEP; }
            float layerWeight(float a, int k) {
                if (a <= 0.5) return 0.0;
                float rest = 1.0 - focusWeight(a);
                if (a < 1.0) return k == 0 ? rest : 0.0;
                float d = abs(layerPos(a) - float(k));
                return d >= 1.0 ? 0.0 : (1.0 - d) * rest;
            }
            float nearOcc(float a, int k) {
                if (a < 1.0) return k == 0 ? min(1.0, 2.0 * layerWeight(a, 0)) : 0.0;
                return int(floor(layerPos(a))) == k ? 1.0 : 0.0;
            }
            """;

    private static final String PREP = """
            uniform sampler2D Img;
            uniform sampler2D Signed;
            uniform int Scale;
            uniform int K;
            uniform int Near;
            uniform ivec2 Size;
            out vec4 o0;
            out vec4 o1;
            void main() {
                ivec2 c = ivec2(gl_FragCoord.xy);
                vec4 acc = vec4(0.0);
                float occ = 0.0;
                float inv = 1.0 / float(Scale * Scale);
                for (int dy = 0; dy < Scale; dy++) {
                    int y = c.y * Scale + dy;
                    if (y >= Size.y) break;
                    for (int dx = 0; dx < Scale; dx++) {
                        int x = c.x * Scale + dx;
                        if (x >= Size.x) break;
                        float s = texelFetch(Signed, ivec2(x, y), 0).r;
                        if (s == 0.0 || (s > 0.0) != (Near == 1)) continue;
                        float a = abs(s);
                        float wt = layerWeight(a, K);
                        if (wt <= 0.0) continue;
                        vec3 col = texelFetch(Img, ivec2(x, y), 0).rgb;
                        acc += vec4(col * wt, wt) * inv;
                        occ += (Near == 1 ? nearOcc(a, K) : min(1.0, 2.0 * wt)) * inv;
                    }
                }
                o0 = acc;
                o1 = vec4(occ, 0.0, 0.0, 0.0);
            }
            """;

    private static final String CONV = """
            uniform sampler2D L0;
            uniform sampler2D L1;
            uniform sampler2D Taps;     // (dx, dy, weight), rows of 1024
            uniform sampler2D Variants; // (first tap, tap count) per kernel variant
            uniform sampler2D Tiles;    // kernel variant per 16-cell tile
            uniform ivec2 LSize;
            out vec4 o0;
            out vec4 o1;
            void main() {
                ivec2 l = ivec2(gl_FragCoord.xy);
                int v = int(texelFetch(Tiles, l / 16, 0).r + 0.5);
                vec2 info = texelFetch(Variants, ivec2(v, 0), 0).rg;
                int first = int(info.x + 0.5), count = int(info.y + 0.5);
                vec4 a0 = vec4(0.0);
                float a1 = 0.0;
                for (int t = first; t < first + count; t++) {
                    vec3 tap = texelFetch(Taps, ivec2(t % 1024, t / 1024), 0).rgb;
                    ivec2 s = l - ivec2(tap.xy);
                    if (s.x < 0 || s.y < 0 || s.x >= LSize.x || s.y >= LSize.y) continue;
                    a0 += texelFetch(L0, s, 0) * tap.z;
                    a1 += texelFetch(L1, s, 0).r * tap.z;
                }
                o0 = a0;
                o1 = vec4(a1, 0.0, 0.0, 0.0);
            }
            """;

    private static final String COMPOSITE = """
            uniform sampler2D L0;
            uniform sampler2D L1;
            uniform sampler2D Acc;
            uniform int Scale;
            uniform ivec2 LSize;
            out vec4 o0;
            vec4 fetch0(int x, int y) { return texelFetch(L0, ivec2(x, y), 0); }
            float fetch1(int x, int y) { return texelFetch(L1, ivec2(x, y), 0).r; }
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                vec4 acc = texelFetch(Acc, p, 0);
                vec4 v;
                float occ;
                if (Scale == 1) {
                    v = fetch0(p.x, p.y);
                    occ = fetch1(p.x, p.y);
                } else {
                    float s = float(Scale);
                    float fy = (float(p.y) + 0.5) / s - 0.5, fx = (float(p.x) + 0.5) / s - 0.5;
                    int y0 = clamp(int(floor(fy)), 0, LSize.y - 1), y1 = min(LSize.y - 1, y0 + 1);
                    int x0 = clamp(int(floor(fx)), 0, LSize.x - 1), x1 = min(LSize.x - 1, x0 + 1);
                    float ty = fy < 0.0 ? 0.0 : clamp(fy - floor(fy), 0.0, 1.0);
                    float tx = fx < 0.0 ? 0.0 : clamp(fx - floor(fx), 0.0, 1.0);
                    v = mix(mix(fetch0(x0, y0), fetch0(x1, y0), tx), mix(fetch0(x0, y1), fetch0(x1, y1), tx), ty);
                    occ = mix(mix(fetch1(x0, y0), fetch1(x1, y0), tx), mix(fetch1(x0, y1), fetch1(x1, y1), tx), ty);
                }
                if (occ <= 0.0 && v.a <= 0.0) {
                    o0 = acc;
                    return;
                }
                float keep = 1.0 - min(1.0, occ);
                o0 = v + keep * acc;
            }
            """;

    private static final String REMEMBER = """
            uniform sampler2D Fg;
            uniform sampler2D Signed;
            uniform int K;
            out vec4 o0;
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                float s = texelFetch(Signed, p, 0).r;
                if (s <= 0.5 || nearOcc(s, K) < 1.0) discard;
                o0 = texelFetch(Fg, p, 0);
            }
            """;
}
