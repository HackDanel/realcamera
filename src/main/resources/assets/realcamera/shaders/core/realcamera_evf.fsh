#version 150

// Exposure simulation of an electronic viewfinder: the per-pixel part of the photo's development applied to the
// game's picture (processing/LiveView is the reference and documents the order; keep the two in step).

uniform sampler2D DiffuseSampler;
uniform sampler2D LightMap;  // per-region light correction (processing/ShadeMap), log2 encoded: v * 8 - 6
uniform float LightMapOn;
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

void main() {
    vec3 c = toLinear(texture(DiffuseSampler, texCoord).rgb);
    vec2 f = 0.5 + ((texCoord - FrameRect.xy) / FrameRect.zw - 0.5) / Zoom;  // 0..1 in the photo frame, y up
    float t = 1.0 - f.y;                                // 0 at the top of the photo
    vec2 d = f * 2.0 - 1.0;
    float r2 = (d.x * d.x * FrameAspect * FrameAspect + d.y * d.y) / (FrameAspect * FrameAspect + 1.0);

    // light on the sensor
    float k = Gain;
    // each part of the picture at its real light (shade darker than the game's flat light curve draws it)
    if (LightMapOn > 0.5) k *= exp2(texture(LightMap, clamp(f, 0.0, 1.0)).r * 8.0 - 6.0);
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
