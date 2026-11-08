#version 150

// Exposure simulation of an electronic viewfinder: the per-pixel part of the photo's development applied to the
// game's picture (processing/LiveView is the reference and documents the order; keep the two in step).

uniform sampler2D DiffuseSampler;
uniform sampler2D LightMap;  // per-region light correction (processing/ShadeMap), log2 encoded: r * 8 - 6;
                             // g: the cell's distance, log2(m) = g * 17 - 5 (sky: 1)
uniform sampler2D DepthSampler;
uniform float LightMapOn;
uniform vec3 MapRotX;        // rows of the rotation from this frame's view axes into the light map's
uniform vec3 MapRotY;
uniform vec3 MapRotZ;
uniform vec3 MapShift;       // camera move since the map was cast, in the map's axes (metres)
uniform vec4 MapTan;         // map half-angle tangents (x, y), map size in cells
uniform vec2 ScreenTan;      // half-angle tangents of the screen (x, y)
uniform vec4 InvProj;        // inverse projection m22, m32, m23, m33: view z from NDC depth
uniform sampler2D LightVolume; // client/render/LightVolume: per voxel r = sky / 15, g = block / 15, b = open (not
                               // a full opaque cube); horizontal slices tiled VolumeSize.w per row
uniform float VolumeOn;
uniform vec3 CamRight;       // this frame's camera axes in the world
uniform vec3 CamUp;
uniform vec3 CamFwd;
uniform vec3 CamToVolume;    // camera position minus the volume's minimum corner (blocks)
uniform vec4 VolumeSize;     // x, y, z size in blocks, slices per texture row
uniform vec4 LightModel;     // SceneMeter: daylight EV, ambient EV, the sky's darkening (light curve), unused
uniform vec2 DevelopRef;     // the light the picture is developed against: EV, game light curve (ShadeMap.factor)
uniform vec4 FrameRect;     // photo frame on the screen: x, y, width, height (texture coordinates)
uniform float FrameAspect;  // width / height of the photo
uniform float Zoom;         // magnifier: the screen shows 1/Zoom of the frame around its centre
uniform vec3 Filter;        // colour filter transmission, normalised to its luminance
uniform float Gain;         // brightening of the game's picture x 2^exposure error
uniform float Gnd;          // graduated ND, stops at the top
uniform vec2 Vignette;      // lens vignetting at the corner (EV), in-camera correction at the corner (EV)
uniform float Saturation;   // sensor clipping level
uniform vec3 WhiteBalance;
uniform float Knee;         // start of the highlight roll-off
uniform vec3 Tint;
uniform vec4 Profile;       // saturation, contrast, shadow lift, monochrome (0/1)
uniform vec4 Noise;         // shot noise per unit of light, read noise variance, chroma share, averaging
uniform vec2 NoiseRow;      // row banding sigma, frame number (seed)

in vec2 texCoord;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 toSrgb(vec3 v) {
    v = clamp(v, 0.0, 1.0);
    return mix(v * 12.92, 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), v));
}

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// standard normal deviate (Box-Muller)
float gauss(vec2 p) {
    float u1 = max(hash(p), 1.0e-6);
    float u2 = hash(p + vec2(17.31, 41.77));
    return sqrt(-2.0 * log(u1)) * cos(6.2831853 * u2);
}

float vignette(float ev, float r2) {
    return exp2(-ev * (r2 * r2 * 0.5 + r2 * 0.5));
}

// ImagePipeline.curve: contrast as an S-curve around middle grey, fixed end points, shadow lift
float curve(float v, float contrast, float lift) {
    v = max(v, 0.0);
    if (contrast != 1.0 && v > 0.0) {
        float x = log2(max(v, 1.0e-6) / 0.18);
        float top = log2(1.0 / 0.18);
        float c = clamp(contrast, 0.5, 1.45);
        float y;
        if (x <= 0.0) {
            y = x * c;
        } else {
            float u = min(x, top) / top;
            y = x * (c + (1.0 - c) * u * u);
            if (x > top) y = top + (x - top) * 0.5;
        }
        v = min(1.0, 0.18 * exp2(y));
    }
    return min(1.0, v + lift * (1.0 - v) * (1.0 - v));
}

// The light map's correction for this pixel (stops). The pixel is re-projected into the map (cast a moment ago
// from a slightly different view) by its depth, and only cells at about its own distance count: a cell's light
// does not spill over an edge onto a nearer or farther surface. A pixel no nearby cell matches takes the closest.
float lightMapStops(vec3 p, float d) {
    vec3 q = vec3(dot(MapRotX, p), dot(MapRotY, p), dot(MapRotZ, p)) + MapShift;
    if (q.z <= 0.01) return 0.0;
    vec2 cell = ((q.xy / q.z) / MapTan.xy * 0.5 + 0.5) * MapTan.zw - 0.5;
    float own = d >= 1.0 ? 12.0 : log2(max(q.z, 0.03));
    ivec2 base = ivec2(floor(cell));
    ivec2 last = ivec2(MapTan.zw) - 1;
    float sum = 0.0, wsum = 0.0, bestDiff = 1.0e9, best = 0.0;
    for (int j = -1; j <= 2; j++) {
        for (int i = -1; i <= 2; i++) {
            ivec2 c = clamp(base + ivec2(i, j), ivec2(0), last);
            vec2 t = texelFetch(LightMap, c, 0).rg;
            float stops = t.r * 8.0 - 6.0;
            float diff = abs(t.g * 17.0 - 5.0 - own);
            vec2 off = cell - vec2(base + ivec2(i, j));
            float w = exp(-dot(off, off) * 1.4) * exp(-diff * diff * 12.5);
            sum += stops * w;
            wsum += w;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = stops;
            }
        }
    }
    return wsum > 1.0e-4 ? sum / wsum : best;
}

// ShadeMap.factor for light levels (SceneMeter.pointEv / gameLight), in stops
float levelStops(float sky, float block) {
    // interpolated levels can land a hair above 15: pow of a negative number is NaN (black and white specks)
    sky = clamp(sky, 0.0, 15.0);
    block = clamp(block, 0.0, 15.0);
    float b = block / 15.0;
    float falloff = sky <= 0.0 ? -40.0 : -2.6 * pow(max(15.0 - sky, 0.0), 0.85);
    float lum = exp2(LightModel.x + falloff) + 128.0 * b * b * b + exp2(LightModel.y) + exp2(-10.0);
    float f = max(sky * LightModel.z, block) / 15.0;
    float game = 0.05 + 0.95 * f / (4.0 - 3.0 * f);
    return clamp(log2(lum) - DevelopRef.x + log2(max(0.01, DevelopRef.y) / max(0.01, game)), -6.0, 2.0);
}

vec3 voxel(ivec3 v) {
    int tiles = int(VolumeSize.w);
    ivec2 t = ivec2((v.y % tiles) * int(VolumeSize.x) + v.x, (v.y / tiles) * int(VolumeSize.z) + v.z);
    return texelFetch(LightVolume, t, 0).rgb;
}

// The correction from the world's light levels at a surface point (relative to the volume's corner) with its normal:
// the light of the air half a block in front, interpolated between voxel centres over the open voxels only (as the
// game's smooth lighting). `inside`: 1 well within the volume, fading to 0 over its last blocks.
float volumeStops(vec3 wp, vec3 n, out float inside) {
    inside = 0.0;
    vec3 s = wp + n * 0.5 - 0.5;
    vec3 fl = floor(s);
    ivec3 b = ivec3(fl);
    ivec3 size = ivec3(VolumeSize.xyz);
    if (any(lessThan(b, ivec3(0))) || any(greaterThanEqual(b + 1, size))) return 0.0;
    vec3 f = s - fl;
    float sky = 0.0, block = 0.0, ws = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 c = voxel(b + o);
        vec3 t = mix(1.0 - f, f, vec3(o));
        float w = t.x * t.y * t.z * c.b;
        sky += c.r * w;
        block += c.g * w;
        ws += w;
    }
    // only solid blocks around (a crevice, or a normal gone wrong at an edge): leave it to the light map
    if (ws < 1.0e-3) return 0.0;
    vec2 lv = vec2(sky, block) / ws;
    vec3 edge = min(vec3(b), vec3(size - 2 - b));
    inside = smoothstep(0.0, 4.0, min(edge.x, min(edge.y, edge.z)));
    return levelStops(lv.x * 15.0, lv.y * 15.0);
}

void main() {
    vec3 c = toLinear(texture(DiffuseSampler, texCoord).rgb);
    vec2 f = 0.5 + ((texCoord - FrameRect.xy) / FrameRect.zw - 0.5) / Zoom;  // 0..1 in the photo frame, y up
    float t = 1.0 - f.y;                                // 0 at the top of the photo
    vec2 d = f * 2.0 - 1.0;
    float r2 = (d.x * d.x * FrameAspect * FrameAspect + d.y * d.y) / (FrameAspect * FrameAspect + 1.0);

    // the surface under this pixel: view-space point from the depth, world point relative to the light volume, and
    // its normal from the screen-space derivatives (computed for every pixel: derivatives need uniform control flow)
    float depth = texture(DepthSampler, texCoord).r;
    vec3 ray = vec3((texCoord * 2.0 - 1.0) * ScreenTan, 1.0);
    float z = depth >= 1.0 ? 4096.0 : min(4096.0, -(InvProj.x * (depth * 2.0 - 1.0) + InvProj.y)
            / (InvProj.z * (depth * 2.0 - 1.0) + InvProj.w));
    vec3 p = ray * z;
    vec3 off = CamRight * p.x + CamUp * p.y + CamFwd * p.z;
    vec3 wp = off + CamToVolume;
    vec3 n = cross(dFdx(wp), dFdy(wp));
    n = dot(n, n) > 1.0e-12 ? normalize(n) : -normalize(off);
    if (dot(n, off) > 0.0) n = -n;
    // blocks are boxes: snap to the dominant axis (the derivatives of a grazing surface are noisy and sent the
    // lookup into the ground, black specks)
    vec3 an = abs(n);
    n = an.x > an.y && an.x > an.z ? vec3(sign(n.x), 0.0, 0.0)
            : an.y > an.z ? vec3(0.0, sign(n.y), 0.0) : vec3(0.0, 0.0, sign(n.z));

    // light on the sensor
    float k = Gain;
    // each part of the picture at its real light (shade darker than the game's flat light curve draws it): per pixel
    // from the world's light levels near the camera, from the cast light map farther out; the sky keeps 1
    if (LightMapOn > 0.5 && depth < 1.0) {
        float stops = lightMapStops(p, depth);
        if (VolumeOn > 0.5) {
            float inside;
            float v = volumeStops(wp, n, inside);
            stops = mix(stops, v, inside);
        }
        k *= exp2(stops);
    }
    if (Gnd > 0.0) {
        float s = clamp((0.6 - t) / 0.2, 0.0, 1.0);
        k *= exp2(-Gnd * s * s * (3.0 - 2.0 * s));
    }
    if (Vignette.x > 0.0) k *= vignette(Vignette.x, r2);
    c *= Filter * k;

    // sensor noise (ImagePipeline.noise), new every frame like a live sensor readout
    if (Noise.w > 0.0 && (Noise.x > 0.0 || Noise.y > 0.0)) {
        vec2 seed = gl_FragCoord.xy + vec2(NoiseRow.y * 13.17, NoiseRow.y * 7.31);
        float sigma = sqrt(max(dot(c, LUMA), 0.0) * Noise.x + Noise.y) * Noise.w;
        float row = gauss(vec2(NoiseRow.y * 3.7, gl_FragCoord.y)) * NoiseRow.x;
        float grain = gauss(seed) * sigma + row;
        vec3 chroma = vec3(gauss(seed + vec2(5.1, 0.0)), gauss(seed + vec2(0.0, 9.3)), gauss(seed + vec2(3.3, 2.9)));
        c = max(c + grain + chroma * sigma * Noise.z, 0.0);
    }

    // sensor clipping, then the in-camera vignetting correction
    c = min(c, vec3(Saturation));
    if (Vignette.y > 0.0) c *= vignette(-Vignette.y, r2);

    // white balance: clipped highlights lose their colour and go neutral
    float pre = max(c.r, max(c.g, c.b));
    c *= WhiteBalance;
    float lo = Saturation * 0.8;
    if (pre > lo) {
        float s = clamp((pre - lo) / max(1.0e-6, Saturation - lo), 0.0, 1.0);
        s = s * s * (3.0 - 2.0 * s);
        float top = max(c.r, max(c.g, c.b));
        c += (vec3(top) - c) * s;
    }

    // highlight roll-off
    float span = 1.0 - Knee;
    c = mix(c, Knee + span * (1.0 - exp(-max(c - Knee, 0.0) / span)), step(vec3(Knee), c));

    // colour profile: tint (fading out towards white), saturation, monochrome, contrast curve, shadow lift
    if (Tint != vec3(1.0)) {
        float kt = 1.0 - clamp((dot(c, LUMA) - 0.35) / 0.6, 0.0, 1.0);
        c *= 1.0 + (Tint - 1.0) * kt;
    }
    float luma = dot(c, LUMA);
    c = Profile.w > 0.5 ? vec3(luma) : luma + (c - luma) * Profile.x;
    c = vec3(curve(c.r, Profile.y, Profile.z), curve(c.g, Profile.y, Profile.z), curve(c.b, Profile.y, Profile.z));

    fragColor = vec4(toSrgb(c), 1.0);
}
