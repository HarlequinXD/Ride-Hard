// Mode 7 world: a textured ground plane and an inverted cloud plane, both ray-cast per pixel
// (the per-scanline affine trick generalised to any pitch), plus sky, sun, moon, stars,
// distant mesas, rain and lightning. Rendered to a low-res buffer and dithered to 15-bit.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUv;

uniform sampler2D uGround;
uniform sampler2D uCloud;

uniform vec2 uTanHalf;      // tan(fov/2) for x and y
uniform float uYaw;
uniform float uPitch;
uniform vec2 uCamG;         // camera xz, wrapped to the 256 m ground tile
uniform float uCamH;        // eye height
uniform vec2 uCloudO;       // camera xz + wind drift, wrapped to the 2000 m cloud tile

uniform vec3 uSunDir;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform vec3 uSunCol;
uniform vec3 uAmbient;
uniform vec3 uFogCol;
uniform vec3 uMtnCol;
uniform float uFog;
uniform float uStars;
uniform float uCover;
uniform float uCloudDark;
uniform float uRain;
uniform float uWet;
uniform float uFlash;
uniform float uTime;
uniform float uSunVis;
uniform float uDust;

const float CLOUD_H = 420.0;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fog0(float t) { return 1.0 - exp(-t * 0.02); }

float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }

float cloudField(vec2 c) {
    return texture2D(uCloud, c / 2000.0).r * 0.62 + texture2D(uCloud, c / 666.6667 + 0.37).r * 0.38;
}

float coverage(float n) {
    float thr = 1.0 - uCover * 0.95;
    return smoothstep(thr - 0.06, thr + 0.22, n);
}

void main() {
    // ---- view ray
    vec2 ndc = vUv * 2.0 - 1.0;
    vec3 d = normalize(vec3(ndc.x * uTanHalf.x, ndc.y * uTanHalf.y, -1.0));
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy - d.z * sy, d.y, d.x * sy + d.z * cy);

    // ---- ground plane (sampled unconditionally so mip selection stays well defined)
    float gy = max(-d.y, 0.0006);
    float t = uCamH / gy;
    vec2 gp = d.xz * t;
    vec3 g = texture2D(uGround, (uCamG + gp) / 256.0).rgb;
    vec2 sOff = uSunDir.xz / max(uSunDir.y, 0.2) * CLOUD_H;
    float shadow = coverage(cloudField(uCloudO + gp + sOff));
    vec3 light = uAmbient * (1.0 - 0.42 * shadow * uSunVis);
    g *= light;
    g *= mix(1.0, 0.6, uWet);                                     // wet earth darkens
    g += uSkyHor * uAmbient * 0.10 * uWet * pow(1.0 - min(gy * 4.0, 1.0), 3.0) * (1.0 - fog0(t)); // grazing sheen
    float fog = 1.0 - exp(-t * uFog);
    vec3 ground = mix(g, uFogCol, fog);

    // ---- sky gradient, sun and glow
    float h = max(d.y, 0.0);
    vec3 sky = mix(uSkyHor, uSkyTop, pow(h, 0.55));
    float sd = dot(d, uSunDir);
    sky += uSunCol * (pow(max(sd, 0.0), 10.0) * 0.30 + pow(max(sd, 0.0), 180.0) * 0.55) * uSunVis;
    sky = mix(sky, vec3(1.0, 0.97, 0.88) * 1.4, smoothstep(0.99905, 0.99935, sd) * uSunVis);

    // ---- night: moon opposite the sun, stars
    vec3 md = normalize(-uSunDir + vec3(0.0, 0.12, 0.0));
    float mdot = dot(d, md);
    float moon = smoothstep(0.99935, 0.99955, mdot);
    float crater = 0.85 + 0.15 * vnoise(d.xz * 900.0);
    sky += vec3(0.75, 0.80, 0.92) * pow(max(mdot, 0.0), 60.0) * 0.12 * uStars;
    sky = mix(sky, vec3(0.88, 0.90, 0.96) * crater, moon * uStars);
    vec2 sg = floor(d.xz / (d.y + 0.25) * 140.0);
    float star = step(0.9935, hash(sg)) * smoothstep(0.02, 0.25, d.y) * (1.0 - moon);
    sky += vec3(star * uStars * (0.55 + 0.45 * hash(sg + 7.0)));

    // ---- Mode 7 clouds (inverted plane)
    float ct = CLOUD_H / max(d.y, 0.015);
    float n = cloudField(uCloudO + d.xz * ct);
    float cd = coverage(n) * smoothstep(-0.01, 0.05, d.y);
    float thick = clamp((n - (1.0 - uCover * 0.95)) / 0.35, 0.0, 1.0);
    float toward = pow(max(sd, 0.0), 3.0);
    vec3 cLit = uAmbient * 0.92 + uSunCol * (0.18 + 0.45 * toward) * uSunVis;
    vec3 cCore = uAmbient * mix(0.70, 0.35, uCloudDark);
    vec3 ccol = mix(cLit, cCore, thick * (0.45 + 0.55 * uCloudDark));
    ccol = mix(ccol, uFogCol, 0.30 * (1.0 - smoothstep(0.0, 0.35, d.y)));   // aerial perspective
    sky = mix(sky, ccol, cd * 0.95);

    // ---- distant mesas on the horizon
    vec2 az = normalize(d.xz + vec2(1e-5));
    float m1 = vnoise(az * 3.0 + 10.0);
    float m2 = vnoise(az * 11.0 + 3.0);
    float m3 = vnoise(az * 45.0 + 1.0);
    float mesa = smoothstep(0.53, 0.565, m1);
    float mh = 0.006 + mesa * (0.040 + 0.006 * m2) + m2 * 0.010 * (1.0 - mesa) + m3 * 0.0025;
    float isM = step(d.y, mh) * step(0.0, d.y);
    float face = max(dot(-az, normalize(uSunDir.xz + vec2(1e-5))), 0.0);
    vec3 mcol = uMtnCol * (0.62 + 0.45 * face * uSunVis);
    mcol *= 0.88 + 0.12 * vnoise(vec2(atan(az.x, az.y) * 60.0, d.y * 900.0));   // strata / erosion
    mcol *= 0.92 + 0.08 * step(0.5, fract(d.y * 260.0 + m3 * 0.6));
    mcol = mix(mcol, uFogCol, 0.50 + 0.45 * uDust + 0.3 * clamp(uFog * 40.0 - 0.15, 0.0, 0.5));
    sky = mix(sky, mcol, isM);

    sky = mix(sky, uFogCol, uDust * 0.80 * (1.0 - smoothstep(0.0, 0.6, h)));

    vec3 col = mix(sky, ground, step(d.y, 0.0));
    col += uFlash * vec3(0.55, 0.60, 0.75) * (0.4 + 0.6 * step(0.0, d.y));

    // ---- rain streaks (screen space, slanted by wind)
    if (uRain > 0.001) {
        vec2 fc = gl_FragCoord.xy;
        float lane = floor(fc.x + fc.y * 0.18);
        float r1 = hash(vec2(lane, 3.1));
        float yy = fc.y + uTime * (190.0 + 140.0 * r1) + r1 * 977.0;
        float cell = floor(yy / 17.0);
        float drop = step(0.82, fract(yy / 17.0)) * step(1.0 - uRain * 0.09, hash(vec2(lane, cell)));
        col = mix(col, uSkyHor * 0.9 + 0.06, drop * 0.30);
    }

    // ---- 15-bit ordered dither
    col = floor(clamp(col, 0.0, 1.0) * 31.0 + bayer4(gl_FragCoord.xy)) / 31.0;
    gl_FragColor = vec4(col, 1.0);
}
