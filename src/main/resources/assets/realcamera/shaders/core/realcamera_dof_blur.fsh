#version 150

#moj_import <realcamera:dof_bokeh.glsl>

// Scatter-as-gather disc blur on the half-resolution prep image (rgb + signed blur radius in alpha), after
// Dennis Gustafsson's single-pass bokeh: samples on a golden-angle spiral; a sample contributes where its own
// blur circle reaches this pixel, and a sample behind this pixel cannot blur more than twice this pixel's
// blur (so a sharp subject does not smear over its background, while a blurred foreground spreads over it).
// A sample's disc has the shape of the aperture (polygon from the blade count, inverted in front of the focus plane)
// and the lens's brightness profile, like ImagePipeline's BokehKernel.
uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float MaxRadius; // pixels of this pass (decodes the radius in alpha)
uniform float Reach;     // largest blur in this frame, pixels of this pass: the spiral stops there
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
    float limit = min(MaxRadius, Reach);
    for (int i = 0; i < 400 && radius < limit; i++) {
        vec2 dir = vec2(cos(angle), sin(angle));
        vec2 tc = texCoord + dir * px * radius;
        vec4 s = texture(DiffuseSampler, tc);
        float sSigned = radiusOf(s.a);
        float sSize = abs(sSigned);
        if (sSigned > centreSigned) {
            sSize = min(sSize, centreSize * 2.0); // behind this pixel
        }
        // this pixel seen from the sample lies opposite; a disc in front of the focus plane is inverted
        float edge = sSize * rcEdgeFactor(sSigned < 0.0 ? angle : angle + 3.14159265);
        float rr = radius;
        float cat = rcVignetteShape(tc * OutSize, OutSize, -dir * radius, sSize, rr, edge);
        float m = smoothstep(rr - 0.5, rr + 0.5, edge) * cat;
        float wt = m * rcProfile(clamp(rr / max(edge, 0.5), 0.0, 1.0), sSigned < 0.0);
        colour += s.rgb * wt + (colour / total) * (1.0 - m);
        total += wt + (1.0 - m);
        if (sSigned < centreSigned && m > 0.5) {
            spread = max(spread, sSize);
        }
        radius += RadScale / radius;
        angle += GOLDEN_ANGLE;
    }
    fragColor = vec4(colour / total, clamp(spread / MaxRadius, 0.0, 1.0));
}
