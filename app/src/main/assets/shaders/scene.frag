// Chronicles of Eryndor: Hollowspire
// Pixel-voxel terrain: every screen pixel marches a ray across a layered heightmap (floor, cave
// ceiling, rock top) so hills, cliffs, overhangs and caves all come from one texture read per
// step. Colour comes from the painted ground texture (nearest texels, pixel perfect), steep faces
// and cave rock get procedural stone. The sky is a Mode 7 cloud plane with sun, moon, stars,
// distant mountains and the Hollowspire. Rendered to a low-res buffer, dithered to 15-bit.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUv;

uniform sampler2D uGround;   // rgb = ground colour, a = 1 land / 0 open water
uniform sampler2D uCloud;
uniform sampler2D uHeight;   // r = floor, g = cave ceiling, b = rock top, a = sky openness

uniform vec2 uTanHalf;
uniform float uYaw;
uniform float uPitch;
uniform vec3 uCam;           // camera x, eye height, z (x/z wrapped to the 256 m tile)
uniform vec2 uCloudO;        // camera xz + wind drift wrapped to the 2000 m cloud tile

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
uniform float uMist;

const float CLOUD_H = 420.0;
const float HMAX = 48.0;
const float FAR = 700.0;

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

float ridge(float n) { return 1.0 - abs(2.0 * n - 1.0); }

float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }

float cloudField(vec2 c) {
    return texture2D(uCloud, c / 2000.0).r * 0.62 + texture2D(uCloud, c / 666.6667 + 0.37).r * 0.38;
}

float coverage(float n) {
    float thr = 1.0 - uCover * 0.95;
    return smoothstep(thr - 0.06, thr + 0.22, n);
}

// Sky for an upward direction d (d.y >= 0). Also used, mirrored, for water reflections.
vec3 skyColor(vec3 d) {
    float h = d.y;
    vec3 sky = mix(uSkyHor, uSkyTop, pow(h, 0.55));
    float sd = dot(d, uSunDir);
    sky += uSunCol * (pow(max(sd, 0.0), 10.0) * 0.30 + pow(max(sd, 0.0), 180.0) * 0.55) * uSunVis;
    sky = mix(sky, vec3(1.0, 0.97, 0.88) * 1.4, smoothstep(0.99905, 0.99935, sd) * uSunVis);

    // moon and stars
    vec3 md = normalize(-uSunDir + vec3(0.0, 0.12, 0.0));
    float mdot = dot(d, md);
    float moon = smoothstep(0.99935, 0.99955, mdot);
    float crater = 0.85 + 0.15 * vnoise(d.xz * 900.0);
    sky += vec3(0.75, 0.80, 0.92) * pow(max(mdot, 0.0), 60.0) * 0.12 * uStars;
    sky = mix(sky, vec3(0.88, 0.90, 0.96) * crater, moon * uStars);
    vec2 sg = floor(d.xz / (d.y + 0.25) * 140.0);
    float star = step(0.9935, hash(sg)) * smoothstep(0.02, 0.25, d.y) * (1.0 - moon);
    sky += vec3(star * uStars * (0.55 + 0.45 * hash(sg + 7.0)));

    // Mode 7 clouds
    float ct = CLOUD_H / max(d.y, 0.015);
    float n = cloudField(uCloudO + d.xz * ct);
    float cd = coverage(n) * smoothstep(-0.01, 0.05, d.y);
    float thick = clamp((n - (1.0 - uCover * 0.95)) / 0.35, 0.0, 1.0);
    float toward = pow(max(sd, 0.0), 3.0);
    vec3 cLit = uAmbient * 0.92 + uSunCol * (0.18 + 0.45 * toward) * uSunVis;
    vec3 cCore = uAmbient * mix(0.70, 0.35, uCloudDark);
    vec3 ccol = mix(cLit, cCore, thick * (0.45 + 0.55 * uCloudDark));
    ccol = mix(ccol, uFogCol, 0.30 * (1.0 - smoothstep(0.0, 0.35, d.y)));
    sky = mix(sky, ccol, cd * 0.95);

    // ---- horizon: far snow-capped range, the Hollowspire, then forested foothills
    vec2 az = normalize(d.xz + vec2(1e-5));
    float ang = atan(az.x, -az.y);                       // 0 = north
    vec2 lit2 = normalize(uSunDir.xz + vec2(1e-5));
    float face = max(dot(-az, lit2), 0.0);
    float haze = 0.45 + 0.4 * uMist + 0.3 * clamp(uFog * 40.0 - 0.15, 0.0, 0.5);

    float m = 0.50 * vnoise(az * 3.0 + 10.0) + 0.32 * ridge(vnoise(az * 9.0 + 3.0)) + 0.18 * ridge(vnoise(az * 31.0 + 1.0));
    float mh = 0.006 + 0.115 * smoothstep(0.25, 0.92, m);
    if (h < mh) {
        float slope = vnoise(vec2(ang * 140.0, h * 500.0));
        vec3 mc = uMtnCol * (0.60 + 0.50 * face * uSunVis) * (0.85 + 0.25 * slope);
        float snow = step(mh * 0.58 + 0.008 * slope, h) * step(0.045, mh);
        mc = mix(mc, uAmbient * (0.80 + 0.30 * face * uSunVis), snow);
        sky = mix(mc, uFogCol, haze);
    }

    // the Hollowspire: a lone, slightly leaning black tower to the north
    float sx = ang - 0.035 - h * 0.025;
    float H = 0.24;
    float w = 0.004 + 0.020 * pow(max(1.0 - h / H, 0.0), 1.8);
    w += 0.007 * step(0.175, h) * step(h, 0.188);        // balcony ring
    float top = H - 0.02 * step(0.0, sx);                // broken crown
    if (abs(sx) < w && h < top) {
        vec3 tc = vec3(0.09, 0.09, 0.12) * (0.7 + 0.5 * uAmbient) * (0.8 + 0.4 * step(0.0, -sx) * face);
        float glow = smoothstep(0.0045, 0.0015, length(vec2(sx, (h - 0.205) * 0.6))) * (0.25 + uStars);
        tc += vec3(0.35, 0.95, 0.85) * glow;
        sky = mix(tc, uFogCol, haze * 0.6);
        sky += vec3(0.35, 0.95, 0.85) * glow * 0.6;
    }

    float hh = 0.003 + 0.013 * vnoise(az * 7.0 + 20.0) + 0.004 * vnoise(az * 40.0) + 0.002 * hash(floor(az * 400.0));
    if (h < hh) {
        vec3 fc = vec3(0.13, 0.19, 0.11) * uAmbient * (0.75 + 0.35 * face * uSunVis);
        sky = mix(fc, uFogCol, haze * 0.65);
    }

    sky = mix(sky, uFogCol, uMist * 0.75 * (1.0 - smoothstep(0.0, 0.5, h)));
    return sky;
}

float rockTex(vec3 p) {
    float n = vnoise(vec2(p.x + p.z, p.y) * 2.2) * 0.55 + vnoise(vec2(p.x - p.z, p.y * 3.0) * 5.0) * 0.30
            + hash(floor(vec2(p.x * 7.0 + p.z * 5.0, p.y * 8.0))) * 0.15;
    float strata = 0.92 + 0.08 * step(0.5, fract(p.y * 1.3 + vnoise(p.xz * 0.5) * 0.6));
    return (0.62 + 0.45 * n) * strata;
}

void main() {
    // ---- view ray
    vec2 ndc = vUv * 2.0 - 1.0;
    vec3 d = normalize(vec3(ndc.x * uTanHalf.x, ndc.y * uTanHalf.y, -1.0));
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy - d.z * sy, d.y, d.x * sy + d.z * cy);

    // ---- march the layered heightmap: solid if below the floor, or inside the rock over a cave
    vec3 ro = uCam;
    float t = 0.05, prevT = 0.0, hitT = -1.0;
    for (int i = 0; i < 230; i++) {
        vec3 p = ro + d * t;
        vec4 hm = texture2D(uHeight, p.xz / 256.0) * HMAX;
        if (p.y < hm.r || (p.y > hm.g && p.y < hm.b)) { hitT = t; break; }
        if (d.y > 0.0 && p.y > HMAX) break;
        if (t > FAR) break;
        prevT = t;
        t += 0.035 + t * 0.026;
    }
    float kind = 0.0;                         // 0 ground/top surface, 1 cave ceiling, 2 rock face
    if (hitT > 0.0) {
        float a = prevT, b = hitT;
        for (int k = 0; k < 6; k++) {
            float m = 0.5 * (a + b);
            vec3 p = ro + d * m;
            vec4 hm = texture2D(uHeight, p.xz / 256.0) * HMAX;
            if (p.y < hm.r || (p.y > hm.g && p.y < hm.b)) b = m; else a = m;
        }
        hitT = b;
        vec3 pb = ro + d * b;                 // blocked by the underside of the rock over a cave?
        vec4 hb = texture2D(uHeight, pb.xz / 256.0) * HMAX;
        if (pb.y >= hb.r && pb.y > hb.g && pb.y < hb.b && (pb.y - hb.g) < (hb.b - pb.y)) kind = 1.0;
    }
    float hit = step(0.0, hitT);
    float T = hit > 0.5 ? hitT : FAR;
    vec3 hp = ro + d * T;
    vec2 wp = hp.xz;

    // ---- surface data (sampled outside the loop so texture LOD stays well defined)
    vec4 gt = texture2D(uGround, wp / 256.0);
    vec4 hc = texture2D(uHeight, wp / 256.0);
    float e = 0.5;
    float useTop = step(0.4, (hc.b - hc.g) * HMAX) * step(hc.g * HMAX + 0.3, hp.y);  // on the rock top
    vec2 ex = vec2(e, 0.0), ez = vec2(0.0, e);
    vec4 h1 = texture2D(uHeight, (wp + ex) / 256.0), h2 = texture2D(uHeight, (wp - ex) / 256.0);
    vec4 h3 = texture2D(uHeight, (wp + ez) / 256.0), h4 = texture2D(uHeight, (wp - ez) / 256.0);
    float gx = mix(h1.r - h2.r, h1.b - h2.b, useTop) * HMAX;
    float gz = mix(h3.r - h4.r, h3.b - h4.b, useTop) * HMAX;
    vec3 n = normalize(vec3(-gx, 2.0 * e, -gz));
    if (kind > 0.5) {                          // underside of the vault: normal from the ceiling layer
        float cx = (h1.g - h2.g) * HMAX, cz = (h3.g - h4.g) * HMAX;
        n = normalize(vec3(cx, -2.0 * e, cz));
    }
    float steep = smoothstep(0.75, 0.45, n.y);
    if (kind > 0.5) steep = 1.0;

    // sky openness: test a point nudged off the surface; if it sits under rock, use that cell's value
    vec3 q = hp + n * 0.35;
    vec4 hq = texture2D(uHeight, q.xz / 256.0);
    float under = step(q.y, hq.b * HMAX - 0.5);   // below the top of the rock here: cave, wall or cutting
    float open = mix(1.0, hq.a, under * hit);

    float water = (1.0 - gt.a) * hit * (1.0 - steep);

    // ---- one sky evaluation: the sky above, its mirror image in water below
    vec2 rip = vec2(vnoise(wp * 1.6 + uTime * 0.7), vnoise(wp * 1.6 - uTime * 0.55 + 7.0)) - 0.5;
    rip *= 1.0 + 2.5 * uRain;
    vec3 dd = vec3(d.x, abs(d.y), d.z);
    dd.xz += rip * 0.045 * water;
    dd.y = max(dd.y, 0.0005);
    vec3 sky = skyColor(normalize(dd));

    // ---- lighting: ambient + sun relative to flat ground (the texture already has baked light),
    //      cloud shadows, darkness under rock, and a lantern near the player
    vec2 sOff = uSunDir.xz / max(uSunDir.y, 0.2) * CLOUD_H;
    float shadow = coverage(cloudField(uCloudO + (wp - ro.xz) + sOff));
    float sunRel = clamp(0.80 + 0.9 * (dot(n, uSunDir) - max(uSunDir.y, 0.05)), 0.35, 1.35);
    vec3 light = uAmbient * mix(1.0, sunRel, uSunVis) * (1.0 - 0.42 * shadow * uSunVis);
    light *= mix(0.05, 1.0, open);
    float dark = clamp(1.0 - open * (1.0 - uStars * 0.85), 0.0, 1.0);   // caves, and night outside
    vec3 lantern = vec3(1.0, 0.70, 0.40) * 1.6 / (1.0 + T * T * 0.09) * max(dot(n, -d), 0.25) * dark;
    light += lantern;

    vec3 albedo = gt.rgb;
    vec3 rock = vec3(0.47, 0.45, 0.42) * rockTex(hp);
    rock = mix(rock, vec3(0.30, 0.36, 0.20) * rockTex(hp * 1.7), smoothstep(0.55, 0.85, n.y) * 0.5 * (1.0 - kind));
    albedo = mix(albedo, rock, steep);

    vec3 land = albedo * light * mix(1.0, 0.62, uWet * open);
    land += uSkyHor * uAmbient * 0.08 * uWet * open * pow(1.0 - min(-d.y * 4.0, 1.0), 3.0) * (1.0 - steep);
    float fres = 0.25 + 0.75 * pow(1.0 - min(max(-d.y, 0.0), 1.0), 5.0);
    vec3 wcol = mix(gt.rgb * light * 0.8, sky * 0.92 * mix(0.05, 1.0, open), fres);
    vec3 g = mix(land, wcol, water);
    float camOpen = texture2D(uHeight, ro.xz / 256.0).a;
    float fog = 1.0 - exp(-T * uFog * mix(0.4, 1.0, camOpen));
    vec3 fogc = mix(vec3(0.0), uFogCol, max(open, camOpen));   // fog only goes black when you are in the dark
    vec3 ground = mix(g, fogc, fog);

    vec3 missCol = d.y > 0.0 ? sky : uFogCol;
    vec3 col = mix(missCol, ground, hit);
    col += uFlash * vec3(0.55, 0.60, 0.75) * (0.4 + 0.6 * (1.0 - hit)) * open;

    // ---- rain streaks (screen space, slanted by wind); none inside caves
    if (uRain > 0.001) {
        vec2 fc = gl_FragCoord.xy;
        float lane = floor(fc.x + fc.y * 0.18);
        float r1 = hash(vec2(lane, 3.1));
        float yy = fc.y + uTime * (190.0 + 140.0 * r1) + r1 * 977.0;
        float cell = floor(yy / 17.0);
        float drop = step(0.82, fract(yy / 17.0)) * step(1.0 - uRain * 0.09, hash(vec2(lane, cell)));
        col = mix(col, uSkyHor * 0.9 + 0.06, drop * 0.30 * camOpen);
    }

    // ---- 15-bit ordered dither
    col = floor(clamp(col, 0.0, 1.0) * 31.0 + bayer4(gl_FragCoord.xy)) / 31.0;
    gl_FragColor = vec4(col, 1.0);
}
