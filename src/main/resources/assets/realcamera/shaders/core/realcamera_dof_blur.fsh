#version 150

// Scatter-as-gather disc blur on the half-resolution prep image (rgb + signed blur radius in alpha), after
// Dennis Gustafsson's single-pass bokeh: samples on a golden-angle spiral; a sample contributes where its own
// blur circle reaches this pixel, and a sample behind this pixel cannot blur more than twice this pixel's
// blur (so a sharp subject does not smear over its background, while a blurred foreground spreads over it).
uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float MaxRadius; // pixels of this pass
uniform float RadScale;

in vec2 texCoord;
out vec4 fragColor;

const float GOLDEN_ANGLE = 2.39996323;

float radiusOf(float a) {
    return (a * 2.0 - 1.0) * MaxRadius;
}

void main() {
    vec4 centre = texture(DiffuseSampler, texCoord);
    float centreSigned = radiusOf(centre.a);
    float centreSize = abs(centreSigned);
    vec3 colour = centre.rgb;
    float total = 1.0;
    float spread = centreSize; // how far blur reaches this pixel (its own, or a foreground's spilling over it)
    float radius = RadScale;
    float angle = 0.0;
    vec2 px = 1.0 / OutSize;
    for (int i = 0; i < 400 && radius < MaxRadius; i++) {
        vec2 tc = texCoord + vec2(cos(angle), sin(angle)) * px * radius;
        vec4 s = texture(DiffuseSampler, tc);
        float sSigned = radiusOf(s.a);
        float sSize = abs(sSigned);
        if (sSigned > centreSigned) {
            sSize = min(sSize, centreSize * 2.0); // behind this pixel
        }
        float m = smoothstep(radius - 0.5, radius + 0.5, sSize);
        colour += mix(colour / total, s.rgb, m);
        total += 1.0;
        if (sSigned < centreSigned && m > 0.5) {
            spread = max(spread, sSize);
        }
        radius += RadScale / radius;
        angle += GOLDEN_ANGLE;
    }
    fragColor = vec4(colour / total, clamp(spread / MaxRadius, 0.0, 1.0));
}
