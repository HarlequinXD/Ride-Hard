#version 300 es
precision highp float;
// Upscales the low-res scene to the screen and adds sun shafts and glare.
// The scene's alpha holds how much open sky each pixel shows (0 for anything solid), so the
// shafts are that mask blurred radially toward the sun: light streams through gaps in the
// branches, between hills and around the clouds, and the glare dims when the sun is hidden.
uniform sampler2D uTex;
uniform vec2 uSunUv;       // sun position on screen (may be off screen)
uniform float uSunOn;      // sun strength (0 = night, behind you, or no sun)
uniform vec3 uSunTint;
uniform float uAspect;     // width / height
uniform vec4 uGrade;       // colour grade: warmth (-1 cool .. 1 warm), saturation, contrast, vignette
in vec2 vUv;
out vec4 fragColor;

const int SHAFT_STEPS = 18;

float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }

float skyAt(vec2 uv) {          // open sky at uv; nothing outside the screen
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return texture(uTex, uv).a * inside.x * inside.y;
}

void main() {
    vec2 ts = vec2(textureSize(uTex, 0));
    vec2 texel = floor(vUv * ts);
    vec2 uv = (texel + 0.5) / ts;                     // work per low-res pixel: stays chunky
    vec3 c = texture(uTex, uv).rgb;
    if (uSunOn > 0.002) {
        vec2 dv = (uv - uSunUv) * vec2(uAspect, 1.0);
        float dist = length(dv);

        // ---- shafts: march from this pixel toward the sun, gathering open sky
        float shaft = 0.0, wsum = 0.0, w = 1.0;
        float j = bayer4(texel);                       // dithered steps, no banding
        vec2 stepUv = (uSunUv - uv) / float(SHAFT_STEPS);
        for (int i = 0; i < SHAFT_STEPS; i++) {
            shaft += skyAt(uv + stepUv * (float(i) + j)) * w;
            wsum += w;
            w *= 0.92;
        }
        shaft /= wsum;
        float fall = exp(-dist * 1.4);
        c += uSunTint * shaft * shaft * fall * 0.55 * uSunOn;

        // ---- glare: how much of the sun's disc is in view
        float vis = 0.0;
        for (int k = 0; k < 8; k++) {
            float a = float(k) * 0.785398;
            vis += skyAt(uSunUv + vec2(cos(a) / uAspect, sin(a)) * 0.012);
        }
        vis = vis / 8.0;
        float halo = exp(-dist * 10.0) * 0.55 + exp(-dist * 32.0) * 0.9;
        float streak = exp(-abs(dv.y) * 140.0) * exp(-abs(dv.x) * 2.5) * 0.22;   // horizontal glint
        vec3 glare = uSunTint * (halo + streak);
        // lens ghosts along the line from the sun through the middle of the screen
        vec2 axis = vec2(0.5) - uSunUv;
        for (int g = 0; g < 4; g++) {
            float t = g == 0 ? 0.55 : (g == 1 ? 0.95 : (g == 2 ? 1.35 : 1.75));
            float r = g == 0 ? 0.035 : (g == 1 ? 0.018 : (g == 2 ? 0.06 : 0.028));
            vec3 tint = g == 0 ? vec3(0.9, 0.6, 0.3) : (g == 1 ? vec3(0.4, 0.8, 0.6) : (g == 2 ? vec3(0.5, 0.5, 1.0) : vec3(1.0, 0.8, 0.5)));
            float gd = length((uv - (uSunUv + axis * t)) * vec2(uAspect, 1.0));
            glare += tint * smoothstep(r, r * 0.7, gd) * 0.07;
        }
        c += glare * vis * uSunOn;
    }

    // ---- colour grade (film-like): white balance, S-curve contrast, saturation, split toning
    //      (cool shadows, warm highlights), vignette; driven by the time of day and the weather
    float warm = uGrade.x;
    c *= warm >= 0.0 ? mix(vec3(1.0), vec3(1.06, 1.0, 0.90), warm) : mix(vec3(1.0), vec3(0.90, 0.97, 1.10), -warm);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), uGrade.z);                       // filmic S-curve
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(lum), c, uGrade.y);                                      // saturation
    float hi = smoothstep(0.15, 0.85, lum);
    c *= mix(vec3(0.95, 0.99, 1.07), vec3(1.04, 1.0, 0.94), hi);          // split toning
    c = max(c - 0.012, 0.0) * (1.0 / 0.988);                              // a touch of black point
    vec2 vq = (vUv - 0.5) * vec2(uAspect, 1.0) / sqrt(uAspect * uAspect + 1.0) * 2.0;
    c *= 1.0 - uGrade.w * pow(dot(vq, vq), 1.2);                          // vignette

    // back to 15-bit colour with the same ordered dither as the scene
    c = floor(clamp(c, 0.0, 1.0) * 31.0 + bayer4(texel)) / 31.0;
    fragColor = vec4(c, 1.0);
}
