#version 150

#moj_import <realcamera:dof_common.glsl>
#moj_import <realcamera:dof_bokeh.glsl>

// Full resolution: the sharp picture where it is in focus, the blurred reduced-resolution one where the blur
// (this pixel's own, or a blurred foreground spilling over it) is wide. The reduced pass is 1/2..1/8 of the screen:
// for blur only a few of its pixels wide it would add its own blockiness, so small blur is gathered here at full
// resolution (same disc and occlusion rules as the blur pass).
uniform sampler2D DiffuseSampler;
uniform sampler2D CocSampler;
uniform sampler2D BlurSampler;
uniform float BlurMaxRadius; // full-resolution pixels
uniform vec2 InSize;         // full resolution
uniform float Scale;         // reduction of the blur pass

in vec2 texCoord;
out vec4 fragColor;

const float GOLDEN_ANGLE = 2.39996323;

void main() {
    vec3 sharp = texture(DiffuseSampler, texCoord).rgb;
    vec4 blurred = texture(BlurSampler, texCoord);
    float centreSigned = rcDecodeRadius(texture(CocSampler, texCoord).rg, BlurMaxRadius);
    float own = abs(centreSigned);
    float reach = max(own, blurred.a * BlurMaxRadius);
    float w = smoothstep(0.6, 2.0, reach);
    if (w <= 0.0) {
        fragColor = vec4(sharp, 1.0);
        return;
    }
    float lowT = smoothstep(Scale * 0.75, Scale * 2.0, reach);
    vec3 small = sharp;
    if (lowT < 1.0) {
        float limit = min(reach, Scale * 2.0) + 1.0;
        float stride = max(0.5, limit * limit / 96.0); // about 48 samples at most
        vec3 colour = sharp;
        float total = 1.0;
        float radius = stride;
        float angle = 0.0;
        vec2 px = 1.0 / InSize;
        for (int i = 0; i < 64 && radius < limit; i++) {
            vec2 tc = texCoord + vec2(cos(angle), sin(angle)) * px * radius;
            float sSigned = rcDecodeRadius(texture(CocSampler, tc).rg, BlurMaxRadius);
            float sSize = abs(sSigned);
            if (sSigned > centreSigned) {
                sSize = min(sSize, own * 2.0); // behind this pixel
            }
            float edge = sSize * rcEdgeFactor(sSigned < 0.0 ? angle : angle + 3.14159265);
            float m = smoothstep(radius - 0.5, radius + 0.5, edge);
            float wt = m * rcProfile(clamp(radius / max(edge, 0.5), 0.0, 1.0), sSigned < 0.0);
            colour += texture(DiffuseSampler, tc).rgb * wt + (colour / total) * (1.0 - m);
            total += wt + (1.0 - m);
            radius += stride / radius;
            angle += GOLDEN_ANGLE;
        }
        small = colour / total;
    }
    fragColor = vec4(mix(sharp, mix(small, blurred.rgb, lowT), w), 1.0);
}
