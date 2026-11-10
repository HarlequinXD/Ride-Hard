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
uniform sampler2D uDetail;   // fine relief on the floor: grass, pebbles, shrubs, rocks, walls (0..3 m)
uniform sampler2D uMat;      // material id per texel: 0 grass, 1 earth, 2 stone, 3 wood, 4 water, 5 plant
uniform sampler2D uFerns;    // one optional fern per 4 m cell: offset xz, size, presence + rotation
uniform sampler2D uTrees;    // one optional tree per 16 m cell: offset xz, species * 25, presence + size

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
const float DMAX = 3.0;
const float DFAR = 70.0;      // detail relief fades out by here

float detailFade(float t) { return smoothstep(DFAR, DFAR * 0.4, t); }

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

// ---- per-pixel grass: individual blades computed at the exact point each ray reaches
const float GFAR = 12.0;
float grassBlades(vec2 xz) {
    return 0.13 * pow(hash(floor(xz * 40.0)), 3.0) + 0.05 * vnoise(xz * 12.0);
}

// ---- 3D ferns, evaluated per pixel as an implicit shape (no geometry, no voxel grid)
const float FERN_CELL = 4.0;
const float FERN_FAR = 38.0;
bool fernAt(vec3 p, out vec3 col, out float near) {
    col = vec3(0.0);
    near = 0.0;
    vec2 cell = floor(p.xz / FERN_CELL);
    vec4 f = texture2D(uFerns, (cell + 0.5) / 64.0);
    if (f.a < 0.5) return false;
    vec2 c = (cell + 0.25 + f.rg * 0.5) * FERN_CELL;
    float L = 0.8 + 0.7 * f.b;
    vec2 q = p.xz - c;
    float r = length(q);
    if (r > L + 0.3) return false;
    float base = texture2D(uHeight, c / 256.0).r * HMAX + texture2D(uDetail, c / 256.0).r * DMAX - 0.04;
    float H = 0.45 + 0.35 * f.b;
    float y = p.y - base;
    if (y < -0.1 || y > H + 0.25) return false;
    near = 1.0;                                    // inside the fern's bounds: march finely here
    if (r > L) return false;
    const float N = 9.0;
    float sector = 6.2831853 / N;
    float ang = atan(q.y, q.x) + (f.a - 0.5) * 12.566;
    float k = floor(ang / sector + 0.5);
    float kk = mod(k, N);
    float jit = (hash(vec2(kk, f.r * 97.0 + f.g * 31.0)) - 0.5) * 0.45;
    float da = ang - (k + jit) * sector;
    float lat = r * sin(da);
    float lenK = 0.70 + 0.30 * hash(vec2(kk + 3.0, f.g * 53.0));
    float s = r / (L * lenK);
    if (s > 1.0) return false;
    float al = abs(lat);
    float w = 0.03 + 0.17 * sin(3.14159 * min(s * 1.1, 1.0));
    if (al > w) return false;
    float pin = fract(s * L * lenK * 10.0);
    if (al > 0.025 && pin < 0.28) return false;    // gaps between the leaflets
    float spine = H * (3.0 * s - 2.6 * s * s) * (0.85 + 0.3 * hash(vec2(kk, 9.0)));
    float ly = spine - al * 0.7;                   // leaflets droop to the sides
    if (abs(y - ly) > 0.035 + 0.025 * s) return false;
    float leaf = hash(vec2(floor(s * L * lenK * 10.0), kk + f.r * 17.0));
    col = vec3(0.19, 0.39, 0.11) * (0.70 + 0.45 * s) * (0.85 + 0.30 * leaf);
    if (al < 0.022) col = vec3(0.30, 0.34, 0.13) * (0.75 + 0.40 * s);   // midrib
    col *= 0.65 + 0.35 * clamp(y / H, 0.0, 1.0);
    return true;
}

// ---- per-pixel surface texture: albedo multiplier (rgb) and a bump (xz) for each material
vec3 microAlbedo(vec2 wp, float mid, out vec2 bump) {
    vec3 m = vec3(1.0);
    bump = vec2(0.0);
    if (mid < 0.5 || mid > 4.5) {                  // grass and leafy plants
        float b1 = vnoise(wp * vec2(34.0, 30.0)), b2 = hash(floor(wp * 48.0)), b3 = vnoise(wp * 7.0);
        float l = 0.80 + 0.30 * (b1 - 0.5) * 2.0 * 0.6 + 0.20 * (b2 - 0.5) + 0.10 * (b3 - 0.5);
        m = vec3(l * (1.0 + 0.14 * (b2 - 0.5)), l, l * (1.0 - 0.18 * (b2 - 0.5)));
        bump = (vec2(vnoise(wp * 30.0 + 3.1), vnoise(wp * 30.0 + 7.7)) - 0.5) * 1.1;
    } else if (mid < 1.5) {                        // earth: grain and tiny grit
        float n1 = vnoise(wp * 22.0), sp = hash(floor(wp * 40.0));
        float l = 0.88 + 0.22 * (n1 - 0.5) + (sp > 0.93 ? 0.22 : (sp < 0.05 ? -0.22 : 0.0));
        m = vec3(l);
        bump = (vec2(vnoise(wp * 25.0 + 1.3), vnoise(wp * 25.0 + 5.9)) - 0.5) * 0.7;
    } else if (mid < 2.5) {                        // stone: fine grain and speckle
        float n1 = vnoise(wp * 40.0), sp = hash(floor(wp * 70.0));
        float l = 0.90 + 0.16 * (n1 - 0.5) + 0.10 * (sp - 0.5);
        m = vec3(l * 0.99, l, l * 1.02);
        bump = (vec2(vnoise(wp * 36.0 + 2.2), vnoise(wp * 36.0 + 8.8)) - 0.5) * 0.4;
    } else if (mid < 3.5) {                        // wood: grain
        float g1 = vnoise(wp * vec2(10.0, 70.0)) * 0.5 + vnoise(wp * vec2(70.0, 10.0)) * 0.5;
        float l = 0.80 + 0.30 * (g1 - 0.5) * 2.0 * 0.6;
        m = vec3(l);
        bump = (vec2(vnoise(wp * 20.0), vnoise(wp * 20.0 + 4.0)) - 0.5) * 0.5;
    }
    return m;
}

// ---- per-pixel normal mapping: a tiny height field (metres) for each material
float microHeight(vec2 wp, float mid) {
    if (mid < 0.5) {                               // grass: streaky blades leaning with a slow swirl
        float a = vnoise(wp * 0.7) * 6.2831;
        vec2 r = vec2(cos(a) * wp.x + sin(a) * wp.y, -sin(a) * wp.x + cos(a) * wp.y);
        return 0.012 * vnoise(r * vec2(70.0, 12.0)) + 0.008 * vnoise(wp * 35.0);
    } else if (mid < 1.5) {                        // earth: grains and little pebbles
        vec2 c = floor(wp * 22.0);
        vec2 f = fract(wp * 22.0) - 0.5;
        float peb = step(0.80, hash(c)) * max(0.0, 0.16 - dot(f, f)) * 0.08;
        return 0.006 * vnoise(wp * 28.0) + 0.003 * vnoise(wp * 90.0) + peb;
    } else if (mid < 2.5) {                        // stone: pitting and hairline cracks
        float crack = smoothstep(0.03, 0.0, abs(vnoise(wp * 6.0) - 0.5)) * 0.004;
        return 0.0025 * vnoise(wp * 60.0) + 0.0015 * vnoise(wp * 160.0) - crack;
    } else if (mid < 3.5) {                        // wood: grain ridges
        return 0.003 * sin(wp.x * 90.0 + vnoise(wp * 8.0) * 6.0) + 0.002 * vnoise(wp * 50.0);
    } else if (mid < 4.5) {                        // water: wind ripples
        return 0.006 * vnoise(wp * 7.0 + uTime * vec2(0.6, 0.3)) + 0.003 * vnoise(wp * 19.0 - uTime * vec2(0.4, 0.7));
    }
    return 0.010 * vnoise(wp * 40.0) + 0.006 * vnoise(wp * 13.0);   // leafy plants
}

// ---- trees: ten species, each a per-pixel implicit shape (no meshes, no voxel grid)
const float TC = 16.0;          // tree cell size; every tree fits inside its own cell
const float TREE_FAR = 300.0;

float hash3(vec3 p) { return hash(p.xy + p.z * vec2(17.13, 31.71)); }

// A: trunk height, trunk radius, crown radius, crown centre height
// B: crown type (0 clusters, 1 tiered cone, 2 ellipsoid, 3 willow, 4 bare), leaf gaps, top, squash/stretch
void treeParams(float v, out vec4 A, out vec4 B, out vec3 leaf, out vec3 bark) {
    if (v < 0.5)      { A = vec4(3.0, 0.38, 4.2, 5.8); B = vec4(0.0, 0.22, 10.0, 0.85); leaf = vec3(0.19, 0.31, 0.11); bark = vec3(0.30, 0.25, 0.20); }  // oak
    else if (v < 1.5) { A = vec4(4.5, 0.16, 2.0, 7.0); B = vec4(2.0, 0.40, 10.4, 1.6);  leaf = vec3(0.42, 0.52, 0.20); bark = vec3(0.86, 0.85, 0.80); }  // birch
    else if (v < 2.5) { A = vec4(3.2, 0.26, 2.9, 0.0); B = vec4(1.0, 0.12, 11.5, 1.0);  leaf = vec3(0.11, 0.23, 0.13); bark = vec3(0.40, 0.26, 0.17); }  // scots pine
    else if (v < 3.5) { A = vec4(1.2, 0.24, 2.3, 0.0); B = vec4(1.0, 0.10, 13.5, 1.0);  leaf = vec3(0.09, 0.19, 0.14); bark = vec3(0.30, 0.22, 0.17); }  // spruce
    else if (v < 4.5) { A = vec4(2.6, 0.38, 3.9, 6.4); B = vec4(3.0, 0.30, 9.5, 1.0);   leaf = vec3(0.40, 0.52, 0.20); bark = vec3(0.32, 0.27, 0.21); }  // willow
    else if (v < 5.5) { A = vec4(5.5, 0.26, 3.2, 0.0); B = vec4(4.0, 0.0, 9.0, 1.0);    leaf = vec3(0.0);              bark = vec3(0.44, 0.41, 0.38); }  // dead
    else if (v < 6.5) { A = vec4(3.0, 0.30, 3.4, 5.6); B = vec4(0.0, 0.25, 9.2, 0.9);   leaf = vec3(0.72, 0.30, 0.08); bark = vec3(0.28, 0.22, 0.18); }  // autumn maple
    else if (v < 7.5) { A = vec4(2.2, 0.22, 2.9, 4.3); B = vec4(0.0, 0.30, 7.4, 0.85);  leaf = vec3(0.93, 0.63, 0.72); bark = vec3(0.24, 0.17, 0.15); }  // blossom
    else if (v < 8.5) { A = vec4(1.6, 0.60, 4.0, 3.5); B = vec4(0.0, 0.08, 6.4, 0.6);   leaf = vec3(0.09, 0.17, 0.10); bark = vec3(0.36, 0.25, 0.20); }  // ancient yew
    else              { A = vec4(1.4, 0.20, 1.5, 8.0); B = vec4(2.0, 0.15, 14.0, 3.6);  leaf = vec3(0.24, 0.35, 0.14); bark = vec3(0.35, 0.30, 0.24); }  // poplar
}

float segDist(vec3 p, vec3 a, vec3 b) {
    vec3 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// signed distance (local metres, unscaled tree) ; part = 0 trunk/branch, 1 crown
float treeSDF(vec3 q, vec4 A, vec4 B, float seed, out float part) {
    float trunkH = A.x, rT = A.y, R = A.z, cY = A.w, type = B.x;
    vec2 lean = (vec2(hash(vec2(seed, 1.0)), hash(vec2(seed, 2.0))) - 0.5) * 0.12;
    float top = trunkH + 1.2;
    float ty = clamp(q.y, 0.0, top);
    float rad = rT * (1.0 - 0.35 * ty / top) * (1.0 + 0.7 * exp(-max(q.y, 0.0) * 3.0));   // root flare
    float dT = max(length(q.xz - lean * ty) - rad, q.y - top);
    float dC = 1e3;
    if (type < 0.5) {                                   // clustered crown
        vec3 qq = vec3(q.x, cY + (q.y - cY) / B.w, q.z);
        dC = length(qq - vec3(0.0, cY, 0.0)) - R;
        for (int i = 0; i < 3; i++) {
            float fi = float(i);
            float a = hash(vec2(seed, fi + 3.0)) * 6.2831 + fi * 2.094;
            vec3 ci = vec3(cos(a) * R * 0.55, cY + (hash(vec2(seed, fi + 7.0)) - 0.3) * R * 0.5, sin(a) * R * 0.55);
            dC = min(dC, length(qq - ci) - R * 0.62);
        }
        dC *= min(B.w, 1.0);
    } else if (type < 1.5) {                            // tiered cone
        float yy = B.z - q.y, hgt = B.z - trunkH;
        float rc = R * clamp(yy / hgt, 0.0, 1.0);
        rc *= 0.68 + 0.32 * fract(yy / (hgt / 5.0));    // layered skirts
        dC = max(max((length(q.xz) - rc) * 0.7, -yy), trunkH - q.y);
    } else if (type < 2.5) {                            // ellipsoid / column
        dC = length(vec3(q.x, (q.y - cY) / B.w, q.z)) - R;
    } else if (type < 3.5) {                            // willow: dome + hanging curtains
        float dome = (length(vec3(q.x, (q.y - cY) / 0.6, q.z)) - R) * 0.6;   // flattened crown
        float ang = atan(q.z, q.x);
        float sid = floor(ang * 1.4 * R);
        float shell = abs(length(q.xz) - R * (0.85 + 0.12 * hash(vec2(sid, seed + 2.0)))) - 0.25;
        float hang = 0.3 + hash(vec2(sid, seed + 1.0)) * 1.8;   // strands sweep almost to the ground
        float curtain = max(shell, max(q.y - cY, hang - q.y));
        if (hash(vec2(sid, seed)) < 0.30) curtain = 1e3;
        dC = min(dome, curtain);
    } else {                                            // bare branches
        vec3 o = vec3(lean.x * trunkH, trunkH * 0.65, lean.y * trunkH);
        for (int i = 0; i < 5; i++) {
            float fi = float(i);
            float a = hash(vec2(seed, fi + 11.0)) * 6.2831 + fi * 1.256;
            vec3 dir = normalize(vec3(cos(a), 0.5 + 0.7 * hash(vec2(seed, fi + 17.0)), sin(a)));
            vec3 st = o + vec3(0.0, fi * 0.45, 0.0);
            vec3 en = st + dir * (2.0 + 1.5 * hash(vec2(seed, fi + 23.0)));
            dC = min(dC, segDist(q, st, en) - 0.11 * (1.0 - fi * 0.12));
        }
    }
    part = dC < dT ? 1.0 : 0.0;
    return min(dT, dC);
}

// crown foliage: lumpy outline plus leaf-sized gaps, all per pixel
bool foliage(vec3 q, float s, vec4 A, vec4 B, float seed) {
    float R = A.z;
    float lump = (vnoise(q.xz * 1.4 + q.y * 0.8 + seed) * 0.6 + vnoise(q.xy * 2.7 + q.z + seed) * 0.4) * R * (B.x > 2.5 ? 0.05 : 0.32);
    if (s + lump > 0.0) return false;
    float shellK = smoothstep(-1.4, 0.0, s + lump);
    return hash3(floor(q * 6.0 + seed)) >= B.y * shellK;
}

// march one tree inside [t0, t1]; returns hit distance or -1
float traceTree(vec3 ro, vec3 d, vec3 base, float sz, vec4 A, vec4 B, float seed, float t0, float t1, out float part) {
    part = 0.0;
    float t = t0;
    for (int i = 0; i < 56; i++) {
        vec3 q = (ro + d * t - base) / sz;
        float pt;
        float s = treeSDF(q, A, B, seed, pt) * sz;
        if (s < 0.0) {
            if (pt < 0.5 || B.x > 3.5) { part = 0.0; return t; }
            if (foliage(q, s / sz, A, B, seed)) { part = 1.0; return t; }
        }
        float st = max(abs(s) * 0.75, 0.03 + t * 0.0025);
        if (s < 1.0 * sz && pt > 0.5) st = min(st, 0.05 + t * 0.003);
        t += st;
        if (t > t1) break;
    }
    return -1.0;
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
    float isFern = 0.0;
    vec3 fcol = vec3(0.0);
    for (int i = 0; i < 260; i++) {
        vec3 p = ro + d * t;
        vec4 hm = texture2D(uHeight, p.xz / 256.0) * HMAX;
        float det = 0.0;
        if (t < DFAR) det = texture2D(uDetail, p.xz / 256.0).r * DMAX * detailFade(t);
        if (t < GFAR && texture2D(uMat, p.xz / 256.0).r < 0.08)
            det += grassBlades(p.xz) * smoothstep(GFAR, GFAR * 0.35, t);
        float near = 0.0;
        vec3 fc;
        if (t < FERN_FAR && fernAt(p, fc, near)) { hitT = t; isFern = 1.0; fcol = fc; break; }
        if (p.y < hm.r + det || (p.y > hm.g && p.y < hm.b)) { hitT = t; break; }
        if (d.y > 0.0 && p.y > HMAX + DMAX) break;
        if (t > FAR) break;
        prevT = t;
        float st = 0.035 + t * 0.026;
        if (near > 0.5) st = min(st, 0.015 + t * 0.004);   // thin fronds need fine steps
        t += st;
    }
    float kind = 0.0;                         // 0 ground/top surface, 1 cave ceiling, 2 rock face
    if (hitT > 0.0) {
        float a = prevT, b = hitT;
        for (int k = 0; k < 6; k++) {
            float m = 0.5 * (a + b);
            vec3 p = ro + d * m;
            bool solid;
            if (isFern > 0.5) {
                float nr;
                vec3 fc;
                solid = fernAt(p, fc, nr);
                if (solid) fcol = fc;
            } else {
                vec4 hm = texture2D(uHeight, p.xz / 256.0) * HMAX;
                float det = texture2D(uDetail, p.xz / 256.0).r * DMAX * detailFade(m);
                if (m < GFAR && texture2D(uMat, p.xz / 256.0).r < 0.08)
                    det += grassBlades(p.xz) * smoothstep(GFAR, GFAR * 0.35, m);
                solid = p.y < hm.r + det || (p.y > hm.g && p.y < hm.b);
            }
            if (solid) b = m; else a = m;
        }
        hitT = b;
        vec3 pb = ro + d * b;                 // blocked by the underside of the rock over a cave?
        vec4 hb = texture2D(uHeight, pb.xz / 256.0) * HMAX;
        float detb = texture2D(uDetail, pb.xz / 256.0).r * DMAX * detailFade(b);
        if (isFern < 0.5 && pb.y >= hb.r + detb && pb.y > hb.g && pb.y < hb.b && (pb.y - hb.g) < (hb.b - pb.y)) kind = 1.0;
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
    vec3 nMacro = normalize(vec3(-gx, 2.0 * e, -gz));
    // fine relief normal (0.125 m texels), faded with distance and ignored on the rock-top layer
    float e2 = 0.125;
    vec2 dx2 = vec2(e2, 0.0), dz2 = vec2(0.0, e2);
    float dfx = (texture2D(uDetail, (wp + dx2) / 256.0).r - texture2D(uDetail, (wp - dx2) / 256.0).r) * DMAX;
    float dfz = (texture2D(uDetail, (wp + dz2) / 256.0).r - texture2D(uDetail, (wp - dz2) / 256.0).r) * DMAX;
    float dk = detailFade(T) * (1.0 - useTop);
    vec3 n = normalize(vec3(-gx / (2.0 * e) - dfx / (2.0 * e2) * dk, 1.0, -gz / (2.0 * e) - dfz / (2.0 * e2) * dk));
    if (kind > 0.5) {                          // underside of the vault: normal from the ceiling layer
        float cx = (h1.g - h2.g) * HMAX, cz = (h3.g - h4.g) * HMAX;
        n = normalize(vec3(cx, -2.0 * e, cz));
    }
    float steep = smoothstep(0.75, 0.45, nMacro.y);          // rock only on big slopes, not on shrubs
    if (kind > 0.5) steep = 1.0;

    // per-pixel material texture and bump
    float mid = floor(texture2D(uMat, wp / 256.0).r * 255.0 / 40.0 + 0.5);
    vec2 bump;
    vec3 micro = microAlbedo(wp, mid, bump);
    float mf = smoothstep(30.0, 5.0, T) * (1.0 - useTop * 0.5);
    micro = mix(vec3(1.0), micro, mf);
    // per-pixel normal map: finite differences sized to this pixel's footprint on the ground
    float eps = clamp(T * 0.0085, 0.006, 0.08);
    float m0 = microHeight(wp, mid);
    float msx = (microHeight(wp + vec2(eps, 0.0), mid) - m0) / eps;
    float msz = (microHeight(wp + vec2(0.0, eps), mid) - m0) / eps;
    float mk = mf * (1.0 - steep * 0.7);
    if (kind < 0.5) n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 1.6);
    else n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 0.8);
    if (isFern > 0.5) {
        n = normalize(vec3(0.0, 1.0, 0.0) + vec3(hash(floor(wp * 30.0)) - 0.5, 0.0, hash(floor(wp * 30.0) + 3.0) - 0.5) * 0.6);
        steep = 0.0;
    }

    // sky openness: test a point nudged off the surface; if it sits under rock, use that cell's value
    vec3 q = hp + n * 0.35;
    vec4 hq = texture2D(uHeight, q.xz / 256.0);
    float under = step(q.y, hq.b * HMAX - 0.5);   // below the top of the rock here: cave, wall or cutting
    float open = mix(1.0, hq.a, under * hit);

    float water = (1.0 - gt.a) * hit * (1.0 - steep) * (1.0 - isFern);

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
    float sunRel = clamp(0.78 + 1.15 * (dot(n, uSunDir) - max(uSunDir.y, 0.05)), 0.25, 1.45);
    vec3 light = uAmbient * mix(1.0, sunRel, uSunVis) * (1.0 - 0.42 * shadow * uSunVis);
    light *= mix(0.05, 1.0, open);
    float dark = clamp(1.0 - open * (1.0 - uStars * 0.85), 0.0, 1.0);   // caves, and night outside
    vec3 lantern = vec3(1.0, 0.70, 0.40) * 1.6 / (1.0 + T * T * 0.09) * max(dot(n, -d), 0.25) * dark;
    light += lantern;

    vec3 albedo = isFern > 0.5 ? fcol * (0.85 + 0.3 * hash(floor(wp * 60.0))) : gt.rgb * micro;
    vec3 rock = vec3(0.47, 0.45, 0.42) * rockTex(hp);
    rock = mix(rock, vec3(0.30, 0.36, 0.20) * rockTex(hp * 1.7), smoothstep(0.55, 0.85, n.y) * 0.5 * (1.0 - kind));
    albedo = mix(albedo, rock, steep);

    vec3 land = albedo * light * mix(1.0, 0.62, uWet * open);
    // soft specular glints: stone and wet ground catch the sun along the normal-mapped surface
    vec3 hv = normalize(uSunDir - d);
    float specK = (mid > 1.5 && mid < 2.5 ? 0.10 : 0.02) + 0.45 * uWet * open * (1.0 - isFern);
    float spec = pow(max(dot(n, hv), 0.0), mid > 1.5 && mid < 2.5 ? 30.0 : 60.0) * specK;
    land += uSunCol * spec * uSunVis * (1.0 - 0.42 * shadow) * open;
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

    // ---- trees: walk the 16 m cells the ray crosses (nearest first), test each cell's tree
    {
        vec2 dir = d.xz;
        vec2 cell = floor(ro.xz / TC);
        vec2 sgn = vec2(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0);
        vec2 inv = 1.0 / max(abs(dir), vec2(1e-5));
        vec2 tMax = ((cell + max(sgn, 0.0)) * TC - ro.xz) * sgn * inv;
        vec2 tDel = TC * inv;
        float tLimit = min(hit > 0.5 ? T : FAR, TREE_FAR);
        float tCell = 0.0;
        for (int i = 0; i < 30; i++) {
            if (tCell > tLimit) break;
            vec4 f = texture2D(uTrees, (mod(cell, 16.0) + 0.5) / 16.0);
            if (f.a > 0.5) {
                vec2 c = (cell + 0.5) * TC + (f.rg - 0.5) * 3.0;
                float v = floor(f.b * 255.0 / 25.0 + 0.5);
                float sz = 0.85 + (f.a - 0.5) * 0.7;
                vec4 A, B;
                vec3 leaf, bark;
                treeParams(v, A, B, leaf, bark);
                vec4 hm = texture2D(uHeight, c / 256.0) * HMAX;
                float baseY = (hm.b - hm.g > 0.4) ? hm.b : hm.r;
                float Rb = A.z * 1.3 * sz;
                vec2 oc = ro.xz - c;
                float a2 = dot(dir, dir), b2 = dot(oc, dir), c2 = dot(oc, oc) - Rb * Rb;
                float disc = b2 * b2 - a2 * c2;
                if (disc > 0.0 && a2 > 1e-8) {
                    float sq = sqrt(disc);
                    float ta = (-b2 - sq) / a2, tb = (-b2 + sq) / a2;
                    float y0 = baseY - 0.3, y1 = baseY + B.z * sz + 0.6;
                    if (abs(d.y) > 1e-5) {
                        float s0 = (y0 - ro.y) / d.y, s1 = (y1 - ro.y) / d.y;
                        ta = max(ta, min(s0, s1));
                        tb = min(tb, max(s0, s1));
                    }
                    ta = max(ta, 0.02);
                    tb = min(tb, tLimit);
                    if (tb > ta) {
                        float seed = floor(mod(cell.x, 16.0)) * 16.0 + floor(mod(cell.y, 16.0));
                        vec3 base = vec3(c.x, baseY, c.y);
                        float part;
                        float th = traceTree(ro, d, base, sz, A, B, seed, ta, tb, part);
                        if (th > 0.0) {
                            vec3 q = (ro + d * th - base) / sz;
                            float pp;
                            float e3 = 0.06;
                            float s0 = treeSDF(q, A, B, seed, pp);
                            vec3 tn = normalize(vec3(treeSDF(q + vec3(e3, 0.0, 0.0), A, B, seed, pp) - s0,
                                                     treeSDF(q + vec3(0.0, e3, 0.0), A, B, seed, pp) - s0,
                                                     treeSDF(q + vec3(0.0, 0.0, e3), A, B, seed, pp) - s0));
                            vec3 alb;
                            if (part > 0.5) {
                                float lh = hash3(floor(q * 6.0 + seed));
                                float topK = clamp((q.y - (A.w - A.z)) / (2.0 * A.z + 0.01), 0.0, 1.0);
                                if (B.x > 0.5 && B.x < 1.5) topK = clamp(q.y / B.z, 0.0, 1.0);
                                alb = leaf * (0.65 + 0.55 * topK) * (0.80 + 0.40 * lh);
                                alb *= 0.75 + 0.25 * smoothstep(-1.5, 0.0, s0);              // darker deep inside
                                if (v > 6.5 && v < 7.5 && lh > 0.85) alb = vec3(0.98, 0.92, 0.95);   // white blossom
                                if (v > 5.5 && v < 6.5 && lh > 0.7) alb = vec3(0.85, 0.55, 0.12);    // golden leaves
                                tn = normalize(tn + (vec3(hash3(floor(q * 6.0) + 1.0), hash3(floor(q * 6.0) + 2.0), hash3(floor(q * 6.0) + 3.0)) - 0.5) * 0.9);
                            } else {
                                float ang = atan(q.z, q.x);
                                float g = vnoise(vec2(ang * 3.0, q.y * 8.0) + seed);
                                alb = bark * (0.75 + 0.35 * g);
                                if (v > 0.5 && v < 1.5 && hash(floor(vec2(ang * 2.0, q.y * 4.0))) > 0.72) alb = vec3(0.12, 0.11, 0.10);  // birch marks
                            }
                            float sr = clamp(0.78 + 1.15 * (dot(tn, uSunDir) - max(uSunDir.y, 0.05)), 0.25, 1.45);
                            vec3 tl = uAmbient * mix(1.0, sr, uSunVis);
                            float tdark = clamp(1.0 - (1.0 - uStars * 0.85), 0.0, 1.0);
                            tl += vec3(1.0, 0.70, 0.40) * 1.6 / (1.0 + th * th * 0.09) * max(dot(tn, -d), 0.25) * tdark;
                            vec3 tc = alb * tl;
                            tc = mix(tc, uFogCol, 1.0 - exp(-th * uFog));
                            col = tc + uFlash * vec3(0.55, 0.60, 0.75) * 0.4;
                            break;
                        }
                    }
                }
            }
            if (tMax.x < tMax.y) { tCell = tMax.x; tMax.x += tDel.x; cell.x += sgn.x; }
            else { tCell = tMax.y; tMax.y += tDel.y; cell.y += sgn.y; }
        }
    }

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
