// Shared by the live depth-of-field passes: metres-from-the-sensor of a depth sample and its blur circle.

uniform vec4 InvProj;   // inverse projection m22, m32, m23, m33: view z from NDC depth
uniform vec4 Lens;      // focal length (mm), f-number, focus distance (mm), output pixels per sensor mm
uniform vec2 Tilt;      // tilted lens: sin(tilt) / focal length (1/mm), and 1 / projection m11 (screen y to tan)

// Focus distance (mm) for a screen row: a tilted lens turns the plane of focus (Optics.tiltedFocusM).
float rcFocusMm(vec2 uv) {
    if (Tilt.x == 0.0) return Lens.z;
    float tanPhi = (uv.y * 2.0 - 1.0) * Tilt.y;
    float denom = 1.0 - Lens.z * tanPhi * Tilt.x;
    return denom <= 1.0e-4 ? 1.0e9 : min(1.0e9, Lens.z / denom);
}

// Distance along the view axis in mm; 1e7 for the sky, -1 when unknown (a shader pack cleared the depth).
float rcDistanceMm(float z) {
    if (z >= 0.999999) return 1.0e7;
    if (z <= 1.0e-6) return -1.0;
    float n = z * 2.0 - 1.0;
    return max(50.0, -(InvProj.x * n + InvProj.y) / (InvProj.z * n + InvProj.w) * 1000.0);
}

// Blur circle diameter in output pixels (Optics.blurCircleMm: thin lens, distances from the sensor).
float rcBlurPx(float dMm, float focusMm) {
    if (dMm < 0.0) return 0.0;
    float f = Lens.x;
    float s = max(focusMm, f * 4.001);
    float v = 2.0 * f / (1.0 + sqrt(max(0.0, 1.0 - 4.0 * f / s))); // image distance at the focus
    float ud = dMm - v;
    float c;
    if (ud <= f * 1.0005) {
        c = f / Lens.y;
    } else {
        float vd = f * ud / (ud - f);
        c = (f / Lens.y) * abs(vd - v) / vd;
    }
    return c * Lens.w;
}

// Signed blur (negative in front of the focus plane): grows with the distance, so it orders depths too.
float rcSignedBlurPx(float dMm, float focusMm) {
    float c = rcBlurPx(dMm, focusMm);
    return dMm >= 0.0 && dMm < focusMm ? -c : c;
}

// Signed blur radius (pixels, |r| <= maxR) packed into two 8-bit channels, and back.
vec2 rcEncodeRadius(float r, float maxR) {
    float v = clamp(0.5 + 0.5 * r / maxR, 0.0, 1.0) * 255.0;
    float hi = floor(v);
    return vec2(hi / 255.0, fract(v));
}

float rcDecodeRadius(vec2 rg, float maxR) {
    float v = (rg.x * 255.0 + rg.y) / 255.0;
    return (v * 2.0 - 1.0) * maxR;
}
