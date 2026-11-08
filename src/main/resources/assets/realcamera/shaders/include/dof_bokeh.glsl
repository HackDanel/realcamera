// The shape of an out-of-focus disc, like ImagePipeline's BokehKernel: polygon from the blade count (or a circle) and
// the lens's brightness profile.

uniform vec4 Bokeh;      // blades, round (1) or polygon (0), style (BOKEH_*), unused
uniform vec4 Character;  // spherical aberration (faded by stopping down), apodisation, mirror obstruction, unused

// distance to the disc edge in direction theta, for a disc of radius 1
float rcEdgeFactor(float theta) {
    if (Bokeh.y > 0.5) return 1.0;
    float n = Bokeh.x;
    float sector = 6.28318531 / n;
    float t = theta - floor(theta / sector) * sector - sector * 0.5;
    return cos(3.14159265 / n) / cos(t);
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
