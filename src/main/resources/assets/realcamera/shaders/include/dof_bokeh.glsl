// The shape of an out-of-focus disc, like ImagePipeline's BokehKernel: polygon from the blade count (or a circle) and
// the lens's brightness profile.

uniform vec4 Bokeh;      // blades, round (1) or polygon (0), style (BOKEH_*), photo frame half-diagonal / screen height
uniform vec4 Character;  // spherical aberration (faded by stopping down), apodisation, mirror obstruction,
                         // barrel clipping (1 wide open .. 0 two stops down)

// distance to the disc edge in direction theta, for a disc of radius 1
float rcEdgeFactor(float theta) {
    if (Bokeh.y > 0.5) return 1.0;
    float n = Bokeh.x;
    float sector = 6.28318531 / n;
    float t = theta - floor(theta / sector) * sector - sector * 0.5;
    return cos(3.14159265 / n) / cos(t);
}

// The disc of a sample at sp (pixels of this pass, pass size `size`) seen at offset o from it (this pixel - sample,
// pixels): towards the frame edges the lens barrel clips it into a cat's eye (inside a circle shifted towards the
// frame centre) and a swirl lens squeezes it along the radius, both fading within two stops down, like
// BokehKernel.kernel. Returns the cat's-eye cover (0..1); for a swirl, rr and edge become the squeezed distance and
// the radius.
float rcVignetteShape(vec2 sp, vec2 size, vec2 o, float r, inout float rr, inout float edge) {
    float barrel = Character.w;
    if (barrel <= 0.02 || r < 0.5) return 1.0;
    vec2 d = sp - size * 0.5;
    float len = length(d);
    if (len < 1.0e-3) return 1.0;
    float dist = min(1.0, len / max(1.0, Bokeh.w * size.y));
    vec2 u = -d / len; // towards the frame centre
    if (Bokeh.z > 1.5 && Bokeh.z < 2.5) {
        float squeeze = 1.0 - 0.7 * min(1.0, dist * 1.2) * barrel;
        float rad = -dot(o, u);
        float tang = o.x * u.y - o.y * u.x;
        rr = length(vec2(rad / squeeze, tang));
        edge = r;
    }
    float shift = r * 1.2 * dist * barrel;
    return clamp(r * 1.05 + 0.5 - length(o - u * shift), 0.0, 1.0);
}

// relative brightness at e (0 centre .. 1 edge) of a disc in front of (near) or behind the focus plane
float rcProfile(float e, bool near) {
    if (e < Character.z) return 0.0;                                                          // mirror lens
    float v = 0.8 + 0.4 * e;
    if (Bokeh.z > 2.5) v = 0.3 + 2.6 * pow(e, 7.0);                                           // bubble
    else if (Bokeh.z > 0.5 && Bokeh.z < 1.5) v = (0.8 + 0.4 * e) * (1.0 + 0.3 * sin(e * 22.0)); // busy
    float k = (near ? -1.0 : 1.0) * Character.x * 0.7;
    v *= max(0.05, 1.0 + k * (0.6 - 1.2 * e));
    v = mix(v, 1.0, Character.y) * ((1.0 - Character.y) + Character.y * exp(-2.5 * e * e));
    return v;
}
