#version 300 es
// Chronicles of Eryndor: Hollowspire
// Pixel-voxel terrain: every screen pixel marches a ray across a layered heightmap (floor, cave
// ceiling, rock top) so hills, cliffs, overhangs and caves all come from one texture read per
// step. Colour comes from the painted ground texture (nearest texels, pixel perfect), steep faces
// and cave rock get procedural stone. The sky is a Mode 7 cloud plane with sun, moon, stars,
// distant mountains and the Hollowspire. Rendered to a low-res buffer, dithered to 15-bit.
precision highp float;
precision highp int;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uGround;   // rgb = ground colour, a = 0 open water, 0.5..1 land (lit fraction: tree shadows)
uniform sampler2D uCloud;
uniform sampler2D uHeight;   // r = open-air ground surface (the volume below holds tunnels and caves)
uniform highp sampler3D uVol; // terrain volume, 0.5 m, 64 m tall: r = signed distance to the ground (-4..4 m), g = sky openness
uniform sampler2D uDetail;   // fine relief on the floor: grass, pebbles, shrubs, rocks, walls (0..3 m)
uniform sampler2D uMat;      // material id per texel: 0 grass, 1 earth, 2 stone, 3 wood, 4 water, 5 plant
uniform sampler2D uFerns;    // one optional fern per 4 m cell: offset xz, size, presence + rotation
uniform sampler2D uTrees;    // one optional tree per 16 m cell: offset xz, species * 25, presence + size
uniform sampler2D uTreePrims; // RGBA32F: 3 texels per primitive (branch segment or leaf)
uniform sampler2D uTreeCells; // RG32F: per zone (first index, count)
uniform sampler2D uTreeIdx;   // R32F: primitive ids listed by zone
uniform sampler2D uLeafAtlas; // 8 leaf shapes, rgb detail + alpha outline
uniform vec4 uTreeMeta[60];   // 6 per species: min+gx, max+gy, cellBase gz bark halfWidth, leafA+trunkR, leafB, bark+height
uniform vec4 uPreview;        // Tree Builder: xyz base position, w = species slot (-1 = off)
uniform float uPreviewYaw;
uniform highp sampler3D uHandA;  // voxel hand (tools/build_hands.py): palm+arm, thumb, index, middle distances
uniform highp sampler3D uHandB;  //   ring, little finger distances
uniform highp sampler3D uHandC;  //   colour, material
uniform vec3 uHandO;             //   grid origin (arm units, half a cell before the first centre)
uniform vec3 uHandS;             //   grid size (arm units)
uniform mat3 uArmRot[2];      // arm-local -> camera space (0 right arm, 1 left arm)
uniform vec3 uArmPos[2];      // arm-local origin (the shoulder end) in camera space, metres
uniform vec4 uFing[10];       // per finger (right arm then left, thumb first): knuckle u, z, direction u, z
uniform float uFingK[10];     // per finger curl: curvature (radians per grid unit)
uniform float uArmShow;
uniform vec3 uSwordO;         // the pickaxe in right-arm grid space: grip centre,
uniform vec3 uSwordB;         //   haft direction (out of the thumb side),
uniform vec3 uSwordE;         //   head direction
uniform vec4 uClods[48];      // dirt clods knocked loose by the pickaxe: xyz, radius
uniform int uClodN;
uniform float uSwordShow;
uniform highp sampler2DArray uSurfCol;  // ground texture sets, one layer each: 0 grass, 1 cobblestone
uniform highp sampler2DArray uSurfNH;   // their normal maps (r, g) and height maps (b)

uniform vec2 uTanHalf;
uniform float uYaw;
uniform float uPitch;
uniform vec3 uCam;           // camera x, eye height, z (x/z shifted by a multiple of 4096 m)
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
const float HMAX = 64.0;
const float FAR = 224.0;      // the ground window reaches 224 m or more each way
const float WIN = 512.0;      // metres of ground, heights, materials and trees held around you (wraps)
const float VOL_NEAR = 88.0;  // the volume window holds at least 96 m each way; beyond, plain heights
const float DMAX = 3.0;
const float DFAR = 70.0;      // detail relief fades out by here

float detailFade(float t) { return smoothstep(DFAR, DFAR * 0.4, t); }

// ---- the terrain volume: smooth signed distance, traced per pixel (no cubes: the 0.5 m grid is
//      only data, blended trilinearly)
const vec3 VOL_SCALE = vec3(1.0 / 256.0, 1.0 / 64.0, 1.0 / 256.0);
float volDist(vec3 p) {
    if (p.y > 63.5) return p.y - 63.0;                          // above the volume: open air
    vec2 off = abs(p.xz - uCam.xz);
    float far = smoothstep(VOL_NEAR - 8.0, VOL_NEAR, max(off.x, off.y));
    float d = 0.0;
    if (far < 1.0) d = textureLod(uVol, p * VOL_SCALE, 0.0).r * 8.0 - 4.0;
    if (far > 0.0) {                                             // past the volume window: the heightfield
        float hd = clamp((p.y - textureLod(uHeight, p.xz / WIN, 0.0).r * HMAX) * 0.7, -4.0, 4.0);
        d = mix(d, hd, far);
    }
    return min(d, p.y - 0.3);                                    // bedrock
}
float volOpen(vec3 p) { return textureLod(uVol, p * VOL_SCALE, 0.0).g; }
vec3 volNormal(vec3 p, float e) {
    const vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * volDist(p + k.xyy * e) + k.yyx * volDist(p + k.yyx * e)
                   + k.yxy * volDist(p + k.yxy * e) + k.xxx * volDist(p + k.xxx * e));
}
// how much the 2D surface detail applies here: on the open-air ground surface, not in tunnels
float topWeight(vec3 p) {
    vec4 hm = texture(uHeight, p.xz / WIN);      // r = ground height, g = dug or heaped (bare dirt)
    return smoothstep(-0.7, -0.3, p.y - hm.r * HMAX) * (1.0 - smoothstep(0.25, 0.7, hm.g));
}

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
    return texture(uCloud, c / 2000.0).r * 0.62 + texture(uCloud, c / 666.6667 + 0.37).r * 0.38;
}

float coverage(float n) {
    float thr = 1.0 - uCover * 0.95;
    return smoothstep(thr - 0.06, thr + 0.22, n);
}

// Sky for an upward direction d (d.y >= 0). Also used, mirrored, for water reflections.
float gOpenSky = 1.0;    // set by skyColor: how much open sky (not cloud, peak or tower) that direction shows

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
    gOpenSky = 1.0 - cd * (0.55 + 0.45 * thick);

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
        gOpenSky = 0.0;
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
        gOpenSky = 0.0;
    }

    float hh = 0.003 + 0.013 * vnoise(az * 7.0 + 20.0) + 0.004 * vnoise(az * 40.0) + 0.002 * hash(floor(az * 400.0));
    if (h < hh) {
        vec3 fc = vec3(0.13, 0.19, 0.11) * uAmbient * (0.75 + 0.35 * face * uSunVis);
        sky = mix(fc, uFogCol, haze * 0.65);
        gOpenSky = 0.0;
    }

    sky = mix(sky, uFogCol, uMist * 0.75 * (1.0 - smoothstep(0.0, 0.5, h)));
    gOpenSky *= 1.0 - uMist * 0.75 * (1.0 - smoothstep(0.0, 0.5, h));
    return sky;
}

// ---------------------------------------------------------------------------------------------
// Physically based shading. Lambert diffuse + Cook-Torrance specular (GGX distribution, Smith /
// Schlick-GGX visibility, Schlick Fresnel) for the sun; the sky as hemisphere ambient light and as
// a rough reflection (split-sum with an analytic environment BRDF); the player's lantern as a
// light at the eye. Colours come in and go out in display space; the maths runs in linear light
// and is tone mapped at the end. metal = 1 makes the albedo the specular colour (F0).
const float PI = 3.14159265;
const float SUN_E = 2.4;          // sun irradiance (linear), so full sun on flat ground ~ albedo
const float SKY_E = 0.45;         // sky ambient irradiance factor
vec3 toLin(vec3 c) { return c * c; }
vec3 gGI = vec3(-1.0);            // ambient light from the GI rays (linear); < 0 = use sky + ground bounce
vec3 toDisp(vec3 c) { return sqrt(max(c, 0.0)); }

vec3 tonemap(vec3 c) {            // linear up to 0.8, then a soft shoulder (no hard clipping)
    vec3 over = max(c - 0.8, 0.0);
    return min(c, 0.8) + 0.2 * (1.0 - exp(-over / 0.2));
}

// alb display albedo; n normal; v toward the eye; sunLit 0..1 (cloud, cast and self shadows);
// open 0..1 sky openness (caves); ao ambient occlusion; lamp lantern strength at this point;
// trans light passing through thin foliage from behind
vec3 pbr(vec3 albD, vec3 n, vec3 v, float rough, float metal, float sunLit, float open, float ao, float lamp, float trans) {
    vec3 alb = toLin(albD);
    vec3 F0 = mix(vec3(0.04), alb, metal);
    float r = clamp(rough, 0.04, 1.0);
    float a = r * r, a2 = a * a;
    float NdV = max(dot(n, v), 1e-3);
    vec3 L = uSunDir;
    vec3 H = normalize(L + v);
    float NdL = max(dot(n, L), 0.0), NdH = max(dot(n, H), 0.0), VdH = max(dot(v, H), 0.0);
    float dn = NdH * NdH * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * dn * dn);
    float k = (r + 1.0) * (r + 1.0) / 8.0;
    float G = NdV / (NdV * (1.0 - k) + k) * (NdL / (NdL * (1.0 - k) + k));
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - VdH, 5.0);
    vec3 spec = D * G * F / (4.0 * NdV * max(NdL, 1e-3));
    vec3 kd = (1.0 - F) * (1.0 - metal);
    vec3 sunRad = toLin(uSunCol) * SUN_E * uSunVis * sunLit * open;
    vec3 col = (kd * alb / PI + spec) * sunRad * NdL;
    col += trans * alb * sunRad * max(dot(-n, L), 0.0) * 0.35;                // through leaves and blades
    // ambient: traced global illumination where available, otherwise sky from above plus light
    // bounced up off the sunlit meadow below; specular from the sky in the mirror direction
    vec3 amb;
    if (gGI.x >= 0.0) {
        amb = gGI * ao;
    } else {
        amb = toLin(uAmbient) * SKY_E * mix(0.05, 1.0, open) * (0.75 + 0.25 * n.y);
        vec3 meadow = toLin(vec3(0.30, 0.34, 0.15));
        amb += meadow * (toLin(uSunCol) * SUN_E * uSunVis * 0.8 / PI + toLin(uAmbient) * SKY_E) * open * (0.5 - 0.5 * n.y) * 0.8;
        amb *= ao;
    }
    col += kd * alb * amb;
    vec3 rd = reflect(-v, n);
    vec3 env = toLin(skyColor(normalize(vec3(rd.x, max(rd.y, 0.02), rd.z))));
    env = mix(env, amb * 1.6, r * r) * (rd.y < 0.0 ? 0.45 : 1.0) * ao * mix(0.03, 1.0, open);
    vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022), c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 rr = r * c0 + c1;
    float a004 = min(rr.x * rr.x, exp2(-9.28 * NdV)) * rr.x + rr.y;
    vec2 AB = vec2(-1.04, 1.04) * a004 + rr.zw;
    col += env * (F0 * AB.x + AB.y);
    // lantern at the eye: light arrives along v
    if (lamp > 0.0) {
        vec3 lampRad = toLin(vec3(1.0, 0.70, 0.40)) * lamp;
        float dl = NdV * NdV * (a2 - 1.0) + 1.0;
        vec3 Fl = F0 + (1.0 - F0) * pow(1.0 - NdV, 5.0);
        float Gl = NdV / (NdV * (1.0 - k) + k);
        col += ((1.0 - Fl) * (1.0 - metal) * alb * max(NdV, 0.25) + a2 / (PI * dl * dl) * Gl * Gl * Fl / 4.0 * NdV) * lampRad;
    }
    return toDisp(tonemap(col));
}


// ---- per-pixel grass: individual blades computed at the exact point each ray reaches
const float GFAR = 12.0;
// ---- ground texture sets (picked by the terrain mask), tiling and point sampled. Layers:
//      0 grass, 1 cobblestone, 2 dirt, 3 cave rock, 4 forest floor, 5 gravel, 6 sand, 7 snow,
//      8 moss, 9 pine needles, 10 roots, 11 mud, 12 forest grass, 13 cliff, 14 mossy rock.
//      Most use two placements (the second turned 90 degrees and larger) picked per pixel by a
//      dithered noise so the tile doesn't repeat visibly; cobblestones use one, so rows stay straight.
const float SURF_TILE[15] = float[15](1.25, 1.6, 1.5, 2.5, 1.6, 1.0, 2.0, 2.5,
                                      1.4, 1.3, 2.2, 1.8, 1.3, 4.0, 2.2);           // metres per tile
const float SURF_DEPTH[15] = float[15](0.035, 0.06, 0.02, 0.05, 0.03, 0.03, 0.015, 0.02,
                                       0.03, 0.025, 0.06, 0.03, 0.035, 0.08, 0.05);  // relief (m)
const float LINES = 240.0;              // screen lines rendered (PS1 resolution)
const float SURF_FAR = 14.0;            // texture height is traced this far
const float MAT_STEP = 16.0;            // material ids are stored as id * 16 in uMat

// material id (terrain mask) -> texture layer, or -1 for the made-up surfaces (wood, water)
int surfLayer(float mid) {
    int m = int(mid + 0.5);
    if (m == 0) return 0;              // grass
    if (m == 1) return 2;              // earth -> dirt
    if (m == 2) return 1;              // stone -> cobblestone
    if (m == 5 || m == 7) return 4;    // plants, forest floor -> leaf litter
    if (m == 6) return 3;              // rock
    if (m == 8) return 5;              // gravel
    if (m == 9) return 6;              // sand
    if (m == 10) return 7;             // snow
    if (m >= 11 && m <= 15) return m - 3;   // moss, pine needles, roots, mud, forest grass
    return -1;
}

float matAt(vec2 xz) { return floor(texture(uMat, xz / WIN).r * 255.0 / MAT_STEP + 0.5); }

vec2 surfUV(int layer, vec2 xz, out float turned) {
    turned = 0.0;
    float tile = SURF_TILE[layer];
    if (layer == 1) return xz / tile;
    float k = vnoise(xz * 0.31 + 11.0 + float(layer) * 3.7) + (bayer4(gl_FragCoord.xy) - 0.5) * 0.22;
    turned = step(0.5, k);
    return turned > 0.5 ? vec2(-xz.y, xz.x) / (tile * 1.36) + vec2(0.37, 0.61) : xz / tile;
}

float surfLod(float t, float tile) {   // texture level for one screen pixel at distance t
    return log2(max(1.0, t * 2.0 * uTanHalf.y / LINES / (tile / 512.0)));
}

// per-pixel relief from the texture set's height map (cobbles bulge out of the mortar, grass clumps
// rise), only where the terrain mask picked a textured surface
float surfHeight(vec2 xz, float t) {
    int layer = surfLayer(matAt(xz));
    if (layer < 0) return 0.0;
    float turned;
    vec2 uv = surfUV(layer, xz, turned);
    float h = textureLod(uSurfNH, vec3(uv, float(layer)), surfLod(t, SURF_TILE[layer])).b;
    return h * SURF_DEPTH[layer] * smoothstep(SURF_FAR, SURF_FAR * 0.5, t);
}

// Self-shadowing of the texture relief (as in parallax occlusion mapping with shadows): march a
// short way toward the sun over the height map; anything taller than the ray shades this pixel.
// Returns 1 lit .. 0 in the shadow of a neighbouring stone or clump.
float surfShadow(vec2 xz, float t) {
    if (t > SURF_FAR || uSunDir.y < 0.03) return 1.0;
    float h0 = surfHeight(xz, t);
    vec2 dir = normalize(uSunDir.xz + vec2(1e-5));
    float rise = uSunDir.y / max(length(uSunDir.xz), 1e-3);   // ray height gained per metre
    float occ = 0.0;
    float j = bayer4(gl_FragCoord.xy) * 0.012;                  // dithered start: no banding
    for (int i = 1; i <= 10; i++) {
        float d = float(i) * 0.018 + j;
        float hs = surfHeight(xz + dir * d, t);
        occ = max(occ, clamp((hs - (h0 + d * rise)) / 0.008, 0.0, 1.0));
    }
    return 1.0 - occ;
}


// ---- 3D ferns, evaluated per pixel as an implicit shape (no geometry, no voxel grid)
const float FERN_CELL = 4.0;
const float FERN_FAR = 38.0;
bool fernAt(vec3 p, out vec3 col, out float near) {
    col = vec3(0.0);
    near = 0.0;
    vec2 cell = floor(p.xz / FERN_CELL);
    vec4 f = texture(uFerns, (cell + 0.5) / 64.0);
    if (f.a < 0.5) return false;
    vec2 c = (cell + 0.25 + f.rg * 0.5) * FERN_CELL;
    float L = 0.8 + 0.7 * f.b;
    vec2 q = p.xz - c;
    float r = length(q);
    if (r > L + 0.3) return false;
    float base = texture(uHeight, c / WIN).r * HMAX + texture(uDetail, c / WIN).r * DMAX - 0.04;
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

// ---- spline trees from the Tree Builder: tapered branch segments and textured leaves, traced
//      per pixel through each species' 3D zone grid (no meshes)
const float TC = 16.0;          // world tree cell size; every tree fits inside its own cell
const float TREE_FAR = 222.0;
const float PIX = 0.0085;       // radians per low-res pixel (for leaf texture detail level)

vec4 tfetch(sampler2D s, int i) { return texelFetch(s, ivec2(i % 1024, i / 1024), 0); }

float capsuleHit(vec3 ro0, vec3 rd, vec3 pa, vec3 pb, float ra) {
    // start the test just in front of the segment: keeps the maths precise for thin, distant branches
    float ts = max(0.0, dot(0.5 * (pa + pb) - ro0, rd) - (0.5 * length(pb - pa) + ra + 0.05));
    vec3 ro = ro0 + rd * ts;
    vec3 ba = pb - pa, oa = ro - pa;
    float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa), oaoa = dot(oa, oa);
    float a = baba - bard * bard, b = baba * rdoa - baoa * bard, c = baba * oaoa - baoa * baoa - ra * ra * baba;
    float h = b * b - a * c;
    if (h >= 0.0) {
        float t = (-b - sqrt(h)) / a;
        float y = baoa + t * bard;
        if (y > 0.0 && y < baba) return t + ts;
        vec3 oc = (y <= 0.0) ? oa : ro - pb;
        b = dot(rd, oc);
        c = dot(oc, oc) - ra * ra;
        h = b * b - c;
        if (h > 0.0) return -b - sqrt(h) + ts;
    }
    return -1.0;
}

float leafAspect(float shape) {        // leaf cards (sprigs) are near-square; willow and needles narrower
    if (shape > 3.5 && shape < 4.5) return 0.55;
    if (shape > 4.5 && shape < 5.5) return 0.6;
    return 0.85;
}

// trace one species in its local space (rd unit length); returns local t of the nearest hit or -1
float traceSpecies(int sp, vec3 ro, vec3 rd, float tmax, float tScale, out int hitPrim, out vec2 leafUv) {
    hitPrim = -1;
    leafUv = vec2(0.0);
    vec4 m0 = uTreeMeta[sp * 6], m1 = uTreeMeta[sp * 6 + 1], m2 = uTreeMeta[sp * 6 + 2];
    vec3 bmin = m0.xyz, bmax = m1.xyz;
    vec3 inv = 1.0 / (abs(rd) + 1e-6) * sign(rd + 1e-9);
    vec3 t0s = (bmin - ro) * inv, t1s = (bmax - ro) * inv;
    vec3 tsm = min(t0s, t1s), tbg = max(t0s, t1s);
    float tin = max(max(tsm.x, tsm.y), max(tsm.z, 0.0)), tout = min(min(tbg.x, tbg.y), min(tbg.z, tmax));
    if (tin >= tout) return -1.0;
    ivec3 dims = ivec3(int(m0.w), int(m1.w), int(m2.y));
    vec3 cs = (bmax - bmin) / vec3(dims);
    vec3 p = ro + rd * (tin + 1e-4);
    ivec3 cell = clamp(ivec3(floor((p - bmin) / cs)), ivec3(0), dims - 1);
    ivec3 stp = ivec3(sign(rd));
    vec3 nextB = bmin + (vec3(cell) + max(vec3(stp), 0.0)) * cs;
    vec3 tMax = (nextB - ro) * inv;
    vec3 tDel = cs * abs(inv);
    float best = tout + 1e-3;
    int cellBase = int(m2.x);
    for (int i = 0; i < 72; i++) {
        vec2 ce = texelFetch(uTreeCells, ivec2((cellBase + (cell.z * dims.y + cell.y) * dims.x + cell.x) % 1024,
                                               (cellBase + (cell.z * dims.y + cell.y) * dims.x + cell.x) / 1024), 0).xy;
        int start = int(ce.x), count = min(int(ce.y), 200);
        float cellExit = min(min(tMax.x, tMax.y), tMax.z);
        for (int k = 0; k < 200; k++) {
            if (k >= count) break;
            int pid = int(texelFetch(uTreeIdx, ivec2((start + k) % 1024, (start + k) / 1024), 0).r);
            vec4 A = tfetch(uTreePrims, pid * 3), B = tfetch(uTreePrims, pid * 3 + 1), C = tfetch(uTreePrims, pid * 3 + 2);
            if (C.x < 0.5) {
                float t = capsuleHit(ro, rd, A.xyz, B.xyz, 0.5 * (A.w + B.w));
                if (t > 0.0 && t < best) { best = t; hitPrim = pid; }
            } else {
                vec3 n = vec3(C.y, (C.x < 1.5 ? 1.0 : -1.0) * sqrt(max(0.0, 1.0 - C.y * C.y - C.z * C.z)), C.z);
                float den = dot(rd, n);
                if (abs(den) < 1e-4) continue;
                float t = dot(A.xyz - ro, n) / den;
                if (t <= 0.0 || t >= best) continue;
                vec3 q = ro + rd * t - A.xyz;
                float along = dot(q, B.xyz) / A.w;
                if (along < 0.0 || along > 1.0) continue;
                float asp = leafAspect(B.w);
                float across = dot(q, normalize(cross(n, B.xyz))) / (A.w * asp);
                if (abs(across) > 0.5) continue;
                vec2 uv = vec2((B.w + along) / 8.0, across + 0.5);
                float lod = log2(max(1.0, t * tScale * PIX * 128.0 / A.w));
                // dithered cut-out: keeps a sprig's coverage at every texture level (no bare trees far off)
                if (textureLod(uLeafAtlas, uv, lod).a * (1.0 + 0.55 * lod) < 0.18 + 0.64 * bayer4(gl_FragCoord.xy)) continue;
                best = t; hitPrim = pid; leafUv = uv;
            }
        }
        if (best <= cellExit) break;
        if (cellExit > tout) break;
        if (tMax.x < tMax.y && tMax.x < tMax.z) { cell.x += stp.x; tMax.x += tDel.x; if (cell.x < 0 || cell.x >= dims.x) break; }
        else if (tMax.y < tMax.z) { cell.y += stp.y; tMax.y += tDel.y; if (cell.y < 0 || cell.y >= dims.y) break; }
        else { cell.z += stp.z; tMax.z += tDel.z; if (cell.z < 0 || cell.z >= dims.z) break; }
    }
    return hitPrim >= 0 ? best : -1.0;
}

float barkTex(float type, float ang, float v) {
    if (type < 0.5) {                               // rough: deep vertical furrows
        float f = vnoise(vec2(ang * 5.0, v * 1.2)) * 0.6 + vnoise(vec2(ang * 13.0, v * 4.0)) * 0.4;
        return 0.55 + 0.6 * smoothstep(0.25, 0.75, f);
    } else if (type < 1.5) {                        // birch: white with dark lenticels and patches
        float mark = step(0.82, hash(floor(vec2(ang * 3.0, v * 7.0)))) * step(0.5, fract(v * 7.0));
        float blot = smoothstep(0.62, 0.7, vnoise(vec2(ang * 2.0, v * 0.8)));
        return mix(1.0, 0.15, max(mark, blot * 0.8));
    } else if (type < 2.5) {                        // pine: plates with dark cracks
        vec2 g = vec2(ang * 2.4, v * 2.2 + floor(ang * 2.4) * 0.37);
        vec2 f = fract(g);
        float crack = step(f.x, 0.08) + step(f.y, 0.1);
        return mix(0.85 + 0.3 * hash(floor(g)), 0.35, min(crack, 1.0));
    }
    return 0.9 + 0.1 * vnoise(vec2(ang * 6.0, v * 3.0));    // smooth
}

// shade a tree hit: albedo and normal in world space
void shadeTree(int sp, int pid, vec2 leafUv, vec3 hl, vec3 rdl, float yaw, out vec3 alb, out vec3 nrm, out float leaf) {
    vec4 A = tfetch(uTreePrims, pid * 3), B = tfetch(uTreePrims, pid * 3 + 1), C = tfetch(uTreePrims, pid * 3 + 2);
    vec4 m2 = uTreeMeta[sp * 6 + 2], ma = uTreeMeta[sp * 6 + 3], mb = uTreeMeta[sp * 6 + 4], mk = uTreeMeta[sp * 6 + 5];
    vec3 nl;
    if (C.x < 0.5) {
        vec3 ba = B.xyz - A.xyz;
        float h = clamp(dot(hl - A.xyz, ba) / dot(ba, ba), 0.0, 1.0);
        vec3 axis = normalize(ba);
        nl = normalize(hl - (A.xyz + ba * h));
        vec3 b1 = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 b2 = cross(axis, b1);
        float ang = atan(dot(nl, b2), dot(nl, b1));
        float v = mix(C.z, C.w, h);
        // bark texels: 32 around the trunk, 6 cm along it
        float bt = barkTex(m2.z, (floor(ang * 5.093) + 0.5) / 5.093, (floor(v * 16.0) + 0.5) / 16.0);
        alb = mk.rgb * bt;
        if (C.y > 1.5) alb = mix(alb, mk.rgb * 0.8, 0.5);         // young twigs are smoother
        leaf = 0.0;
    } else {
        nl = vec3(C.y, (C.x < 1.5 ? 1.0 : -1.0) * sqrt(max(0.0, 1.0 - C.y * C.y - C.z * C.z)), C.z);
        if (dot(nl, rdl) > 0.0) nl = -nl;                          // two-sided
        vec3 tex = textureLod(uLeafAtlas, leafUv, 0.0).rgb;
        float tint = C.w;
        vec3 base = tint >= 10.0 ? mb.rgb : ma.rgb;
        alb = base * tex * (0.82 + 0.36 * fract(tint));
        leaf = 1.0;
    }
    // back to world space (undo the tree's yaw)
    float c = cos(yaw), s = sin(yaw);
    nrm = vec3(nl.x * c + nl.z * s, nl.y, -nl.x * s + nl.z * c);
}



// ---------------------------------------------------------------------------------------------
// Real-time global illumination over the heightmap. Three short rays per pixel across the
// cosine-weighted hemisphere (turned per pixel by the dither, so the noise reads as the game's
// dither): a ray that escapes brings sky light, one that hits terrain brings the light bouncing
// off that spot (its colour, lit by the sun and the sky there). Gives colour bleeding, ambient
// occlusion in hollows and caves, and cave mouths lighting the cave inside.
// Returns the ambient light for pbr() (linear), already including openness.
const float GI_FAR = 45.0;
vec3 giAmbient(vec3 p, vec3 n, float t) {
    vec3 skyL = toLin(uAmbient) * SKY_E;
    vec3 sunL = toLin(uSunCol) * SUN_E * uSunVis;
    vec3 tng = normalize(abs(n.y) < 0.95 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 btg = cross(n, tng);
    float rot = bayer4(gl_FragCoord.xy) * 6.2832;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float a = rot + float(i) * 2.0944;
        float r2 = (float(i) + 0.5) / 3.0;                        // cosine-weighted rings
        float rr = sqrt(r2);
        vec3 dir = normalize(tng * cos(a) * rr + btg * sin(a) * rr + n * sqrt(1.0 - r2));
        vec3 q = p + n * 0.15;
        float dist = 0.0, len = 0.35;
        bool hitT = false;
        for (int k = 0; k < 6; k++) {
            dist += len;
            len *= 1.6;
            q = p + n * 0.15 + dir * dist;
            if (volDist(q) < 0.0) { hitT = true; break; }
        }
        if (hitT) {
            // light bounced off the spot the ray hit: its colour (ground texture on the open-air
            // surface, rock underground), lit by the sun and sky it sees
            float d0 = volDist(q);
            vec3 nq = normalize(vec3(volDist(q + vec3(0.4, 0.0, 0.0)) - d0, volDist(q + vec3(0.0, 0.4, 0.0)) - d0,
                                     volDist(q + vec3(0.0, 0.0, 0.4)) - d0) + 1e-4);
            float oq = volOpen(q + nq * 0.6);
            vec3 albq = mix(vec3(0.20, 0.19, 0.17), toLin(texture(uGround, q.xz / WIN).rgb), topWeight(q));
            sum += albq * (sunL * max(dot(nq, uSunDir), 0.0) * oq / PI + skyL * mix(0.05, 1.0, oq));
        } else {
            sum += skyL * volOpen(q) * (0.85 + 0.3 * max(dir.y, 0.0));
        }
    }
    return sum / 3.0;
}
// ---------------------------------------------------------------------------------------------
// First-person arms, per pixel: smooth distance-field shapes in each arm's grid space (x along the
// arm toward the fingertips, y out of the back of the hand, z across it; 1.25 mm units). A leather
// bracer with straps over the forearm, a linen wrap at the wrist, then a bare hand: a padded palm,
// knuckles, and five fingers that each curl on their own (a sample point is unbent around the
// finger's knuckle axis and tested against the straight finger). No image, so no ragged edges.
const float ARM_S = 0.00125;      // metres per grid unit
const float ARM_X0 = 150.0;       // nearest part of the arm drawn (the rest is behind the camera)

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

// tapered capsule from a (radius ra) to b (radius rb)
float sdCone(vec3 p, vec3 a, vec3 b, float ra, float rb, out float h) {
    vec3 pa = p - a, ba = b - a;
    h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - mix(ra, rb, h);
}

// Unbend p around finger f of arm i; returns the rest-pose point, th = bend angle there.
vec3 fingerRest(vec3 p, int i, int f, out float th) {
    vec4 F = uFing[i * 5 + f];
    float k = uFingK[i * 5 + f];
    vec2 rel = p.xz - F.xy;
    vec2 perp = vec2(-F.w, F.z);
    float a = dot(rel, F.zw), c = dot(rel, perp), y = p.y;
    th = 0.0;
    if (a > 0.0 && k > 1e-5) {
        vec2 v = vec2(a, y + 1.0 / k);
        th = atan(v.x, v.y);
        a = th / k;
        y = length(v) - 1.0 / k;
    }
    vec2 xz = F.xy + F.zw * a + perp * c;
    return vec3(xz.x, y, xz.y);
}

// The hand and forearm as voxels (tools/build_hands.py): signed distances on a 1.9 mm grid, one
// channel for the palm and forearm and one per finger (straight), plus colour and material. A
// sample point is tested against the palm channel as it is, and against each finger's channel
// after being unbent around that finger's knuckle, so every finger curls on its own. The left hand
// is the same data mirrored.
vec3 handTex(vec3 q, int i) {
    if (i == 1) q.z = -q.z;
    return (q - uHandO) / uHandS;
}

// distance (grid units) to arm i, the part that is nearest (0 palm and arm, 1..5 finger) and the
// rest-pose point on it
float handDist(vec3 p, int i, out int part, out vec3 rq) {
    vec3 tc = handTex(p, i);
    float outside = length(max(abs(tc - 0.5) - 0.5, 0.0) * uHandS);
    float d = outside > 0.0 ? outside + 16.0 : textureLod(uHandA, tc, 0.0).r * 32.0 - 16.0;
    float best = d;
    part = 0;
    rq = p;
    for (int f = 0; f < 5; f++) {
        float th;
        vec3 q = fingerRest(p, i, f, th);
        vec3 tq = handTex(q, i);
        if (any(greaterThan(abs(tq - 0.5), vec3(0.5)))) continue;
        float df = (f < 3 ? textureLod(uHandA, tq, 0.0)[f + 1] : textureLod(uHandB, tq, 0.0)[f - 3]) * 32.0 - 16.0;
        if (df < best) { best = df; part = f + 1; rq = q; }
        d = smin(d, df, 1.5);
    }
    return d;
}

// returns distance in metres along the camera ray (or -1), the part hit, the hit point (arm grid)
float traceArm(int i, vec3 dc, out int part, out vec3 hq, out float hth) {
    mat3 Rt = transpose(uArmRot[i]);
    vec3 ro = Rt * (-uArmPos[i]) / ARM_S;
    vec3 rd = Rt * dc / ARM_S;
    vec3 bmin = vec3(ARM_X0, -50.0, -98.0), bmax = vec3(560.0, 50.0, 98.0);
    vec3 inv = 1.0 / rd;
    vec3 ta = (bmin - ro) * inv, tb = (bmax - ro) * inv;
    vec3 tn = min(ta, tb), tf = max(ta, tb);
    float t = max(max(tn.x, tn.y), max(tn.z, 0.02)), t1 = min(min(tf.x, tf.y), tf.z);
    part = -1;
    hth = 0.0;
    if (t >= t1) return -1.0;
    float vl = length(rd);
    for (int s = 0; s < 110; s++) {
        vec3 q = ro + rd * t;
        int pt;
        vec3 rq;
        float d = handDist(q, i, pt, rq);
        if (d < 0.2) {
            hq = q;
            part = pt;
            return t;
        }
        t += max(d * 0.75, 0.25) / vl;
        if (t > t1) break;
    }
    return -1.0;
}

float gArmMetal = 0.0, gArmRough = 0.6, gArmTrans = 0.0;

void shadeArm(int i, int part0, vec3 q, float unused, float t, out vec3 alb, out vec3 nc) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.5;
    int pt;
    vec3 rq;
    vec3 n = normalize(k.xyy * handDist(q + k.xyy * e, i, pt, rq) + k.yyx * handDist(q + k.yyx * e, i, pt, rq)
                     + k.yxy * handDist(q + k.yxy * e, i, pt, rq) + k.xxx * handDist(q + k.xxx * e, i, pt, rq));
    handDist(q, i, pt, rq);
    vec4 c = textureLod(uHandC, handTex(rq, i), 0.0);
    alb = c.rgb;
    int mat = int(c.a * 255.0 / 50.0 + 0.5);
    gArmMetal = mat == 4 ? 1.0 : 0.0;
    gArmTrans = mat == 0 ? 0.25 : 0.0;              // light through skin
    gArmRough = mat == 1 ? 0.62 : (mat == 2 ? 0.9 : (mat == 3 ? 0.32 : (mat == 4 ? 0.38 : 0.52)));
    nc = normalize(uArmRot[i] * n);
}

// ---------------------------------------------------------------------------------------------
// The pickaxe in the right hand, per pixel: distance-field shapes in tool space (x along the head,
// y across it, z up the haft from the grip), all in arm-grid units (1.25 mm). An iron head, a pick
// on one side and a broad adze blade on the other, on an ash haft; both caked with dirt.
const float PK_HAFT_R = 14.0;     // haft radius
const float PK_BUTT = -88.0;      // haft's end below the hand
const float PK_HEAD_Z = 400.0;    // head centre up the haft (50 cm)
const float PK_PICK_L = 245.0;    // pick arm length (31 cm)
const float PK_ADZE_L = 175.0;    // adze arm length

float sdBox(vec3 p, vec3 b) {
    vec3 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

// x = distance, y = material (0 iron head, 2 wooden haft)
vec2 swordSdf(vec3 q) {
    // the haft: slightly oval and tapering toward the head, a swelled knob at the butt
    float rr = PK_HAFT_R * (1.0 + 0.10 * clamp(-q.z / 90.0, 0.0, 1.0)) - 0.0035 * max(q.z, 0.0);
    float haft = max(length(q.xy * vec2(1.0, 1.15)) - rr, max(PK_BUTT - q.z, q.z - PK_HEAD_Z - 30.0)) * 0.85;
    haft = min(haft, length((q - vec3(0.0, 0.0, PK_BUTT + 6.0)) * vec3(1.0, 1.1, 1.5)) / 1.5 - 13.0);
    vec3 h = q - vec3(0.0, 0.0, PK_HEAD_Z);
    // the head: one forged crescent bar through the top of the haft, thick in the middle and
    // curving down to a sharp point on one side and a narrow chisel on the other
    float L = h.x > 0.0 ? PK_PICK_L : PK_ADZE_L;
    float sx = clamp(h.x, -PK_ADZE_L, PK_PICK_L), u = abs(sx) / L;
    float zc = -0.0012 * sx * sx;                      // the curve, both arms dropping toward the hand
    float ht = mix(17.0, h.x > 0.0 ? 1.5 : 2.5, pow(u, 0.9));      // half height
    float wd = mix(12.0, h.x > 0.0 ? 1.5 : 9.0, pow(u, 0.9));      // half width: point vs chisel
    vec2 e = vec2(abs(h.y) - wd, abs(h.z - zc) - ht);
    float head = (length(max(e, 0.0)) + min(max(e.x, e.y), 0.0)) * 0.7;
    head = max(head, abs(h.x - (PK_PICK_L - PK_ADZE_L) * 0.5) - (PK_PICK_L + PK_ADZE_L) * 0.5);
    // the eye: a slim swelling where the haft passes through, not a block
    head = min(head, (length(h * vec3(1.0, 1.0, 0.85)) - 21.0));
    return head < haft ? vec2(head, 0.0) : vec2(haft, 2.0);
}

// ray in right-arm grid space (t in metres, as traceArm); returns t or -1, hit in tool space
float traceSword(vec3 ro, vec3 rd, out vec3 hq, out float mat) {
    vec3 F = cross(uSwordB, uSwordE);
    mat3 W = mat3(uSwordE.x, F.x, uSwordB.x, uSwordE.y, F.y, uSwordB.y, uSwordE.z, F.z, uSwordB.z);
    vec3 o = W * (ro - uSwordO), v = W * rd;
    vec3 bmin = vec3(-PK_ADZE_L - 30.0, -45.0, PK_BUTT - 25.0), bmax = vec3(PK_PICK_L + 10.0, 45.0, PK_HEAD_Z + 50.0);
    vec3 inv = 1.0 / v;
    vec3 ta = (bmin - o) * inv, tb = (bmax - o) * inv;
    vec3 tn = min(ta, tb), tf = max(ta, tb);
    float t = max(max(tn.x, tn.y), max(tn.z, 0.02)), t1 = min(min(tf.x, tf.y), tf.z);
    if (t >= t1) return -1.0;
    float vl = length(v);
    for (int i = 0; i < 90; i++) {
        vec3 q = o + v * t;
        vec2 d = swordSdf(q);
        if (d.x < 0.3) { hq = q; mat = d.y; return t; }
        t += max(d.x, 0.25) / vl;
        if (t > t1) break;
    }
    return -1.0;
}

vec3 swordNormal(vec3 q) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.5;
    return normalize(k.xyy * swordSdf(q + k.xyy * e).x + k.yyx * swordSdf(q + k.yyx * e).x
                   + k.yxy * swordSdf(q + k.yxy * e).x + k.xxx * swordSdf(q + k.xxx * e).x);
}

// Rock texture set wrapped onto any slope (walls, ceilings, tunnels, boulders): three planar
// projections blended by the normal, with the normal maps combined the same way ("whiteout").
vec3 rockTriplanar(vec3 p, vec3 n, float t, float layer, out vec3 nOut) {
    vec3 w = pow(abs(n), vec3(4.0));
    w /= (w.x + w.y + w.z);
    float tile = SURF_TILE[int(layer)];
    float lod = surfLod(t, tile);
    vec3 cx = textureLod(uSurfCol, vec3(p.zy / tile, layer), lod).rgb;
    vec3 cy = textureLod(uSurfCol, vec3(p.xz / tile, layer), lod).rgb;
    vec3 cz = textureLod(uSurfCol, vec3(p.xy / tile, layer), lod).rgb;
    vec2 ax = textureLod(uSurfNH, vec3(p.zy / tile, layer), lod).rg * 2.0 - 1.0;
    vec2 ay = textureLod(uSurfNH, vec3(p.xz / tile, layer), lod).rg * 2.0 - 1.0;
    vec2 az = textureLod(uSurfNH, vec3(p.xy / tile, layer), lod).rg * 2.0 - 1.0;
    vec3 tx = vec3(ax + n.zy, abs(n.x) * sqrt(max(0.0, 1.0 - dot(ax, ax))));
    vec3 ty = vec3(ay + n.xz, abs(n.y) * sqrt(max(0.0, 1.0 - dot(ay, ay))));
    vec3 tz = vec3(az + n.xy, abs(n.z) * sqrt(max(0.0, 1.0 - dot(az, az))));
    tx.z *= sign(n.x); ty.z *= sign(n.y); tz.z *= sign(n.z);
    nOut = normalize(tx.zyx * w.x + ty.xzy * w.y + tz.xyz * w.z);
    return cx * w.x + cy * w.y + cz * w.z;
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
    vec3 dCam = d;
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy - d.z * sy, d.y, d.x * sy + d.z * cy);

    // ---- march the terrain volume: sphere steps through open air, fine steps near the surface,
    //      where the 2D relief (rocks, texture height) rides on the open-air ground
    vec3 ro = uCam;
    float t = 0.05 + bayer4(gl_FragCoord.xy) * 0.012, prevT = 0.0, hitT = -1.0;   // dithered start
    float isFern = 0.0;
    vec3 fcol = vec3(0.0);
    for (int i = 0; i < 260; i++) {
        vec3 p = ro + d * t;
        float dd = volDist(p);
        float tw = 0.0;
        if (dd < 4.0 && t < DFAR) {
            tw = topWeight(p);
            if (tw > 0.0) {
                float det = texture(uDetail, p.xz / WIN).r * DMAX * detailFade(t);
                if (t < SURF_FAR) det += surfHeight(p.xz, t);
                dd -= det * tw;
            }
        }
        if (dd < 0.0) { hitT = t; break; }
        if (d.y > 0.0 && p.y > HMAX + DMAX) break;
        if (t > FAR) break;
        prevT = t;
        float fine = 0.035 + t * 0.026;
        if (t < 4.0) fine = min(fine, 0.012 + t * 0.012);     // small texture relief near you
        // never slower than a plain march (so grazing rays still reach the far hills); leap
        // further when the volume says the air is clear
        float st = tw > 0.0 ? min(max(dd * 0.85, 0.01 + t * 0.004), fine) : max(dd * 0.85, 0.035 + t * 0.026);
        t += st;
    }
    float kind = 0.0;                         // 0 ground or tunnel floor, 1 ceiling / overhang underside
    if (hitT > 0.0) {
        float a = prevT, b = hitT;
        for (int k = 0; k < 7; k++) {
            float m = 0.5 * (a + b);
            vec3 p = ro + d * m;
            float dd = volDist(p);
            float tw = topWeight(p);
            if (tw > 0.0 && m < DFAR) {
                float det = texture(uDetail, p.xz / WIN).r * DMAX * detailFade(m);
                if (m < SURF_FAR) det += surfHeight(p.xz, m);
                dd -= det * tw;
            }
            if (dd < 0.0) b = m; else a = m;
        }
        hitT = b;
    }
    float hit = step(0.0, hitT);
    float T = hit > 0.5 ? hitT : FAR;
    vec3 hp = ro + d * T;
    vec2 wp = hp.xz;

    // ---- surface data (sampled outside the loop so texture LOD stays well defined)
    vec4 gt = texture(uGround, wp / WIN);
    vec3 nMacro = volNormal(hp, 0.3);                       // the volume's own surface normal
    float onTop = hit * topWeight(hp - nMacro * 0.05);      // open-air ground (not a tunnel or overhang)
    float useTop = 1.0 - onTop;                             // underground: rock, no 2D textures
    // fine relief normal (0.125 m texels) on the open-air ground, faded with distance
    float e2 = 0.125;
    vec2 dx2 = vec2(e2, 0.0), dz2 = vec2(0.0, e2);
    float dfx = (texture(uDetail, (wp + dx2) / WIN).r - texture(uDetail, (wp - dx2) / WIN).r) * DMAX;
    float dfz = (texture(uDetail, (wp + dz2) / WIN).r - texture(uDetail, (wp - dz2) / WIN).r) * DMAX;
    float dk = detailFade(T) * onTop;
    vec3 n = normalize(nMacro + vec3(-dfx, 0.0, -dfz) / (2.0 * e2) * dk * max(nMacro.y, 0.0));
    if (nMacro.y < -0.2) kind = 1.0;
    float steep = smoothstep(0.75, 0.45, nMacro.y);          // rock on big slopes, walls and ceilings
    steep = max(steep, useTop);                              // and everywhere underground

    // per-pixel material texture and bump
    float mid = matAt(wp);
    vec2 bump;
    // point-sampled look: the fine surface detail is snapped to 3 cm texels like a texture would be
    vec2 wpT = (floor(wp * 32.0) + 0.5) / 32.0;
    vec3 micro = microAlbedo(wpT, mid, bump);
    float mf = smoothstep(30.0, 5.0, T) * (1.0 - useTop * 0.5);
    micro = mix(vec3(1.0), micro, mf);
    // per-pixel normal map: finite differences sized to this pixel's footprint on the ground
    float eps = clamp(T * 0.0085, 0.006, 0.08);
    float m0 = microHeight(wp, mid);
    float msx = (microHeight(wp + vec2(eps, 0.0), mid) - m0) / eps;
    float msz = (microHeight(wp + vec2(0.0, eps), mid) - m0) / eps;
    float mk = mf * (1.0 - steep * 0.7);
    // textured ground (the terrain mask picked grass or cobblestone here): the texture set's own
    // colour and normal map replace the made-up detail
    int layer = surfLayer(mid);
    float isGrass = (layer >= 0 ? 1.0 : 0.0) * (1.0 - step(0.5, kind)) * (1.0 - useTop) * (1.0 - steep);
    vec3 grassAlb = vec3(0.0);
    if (isGrass > 0.5) {
        float turned, glod = surfLod(T, SURF_TILE[layer]);
        vec2 guv = surfUV(layer, wp, turned);
        vec3 gc = textureLod(uSurfCol, vec3(guv, float(layer)), glod).rgb;
        vec2 tn = textureLod(uSurfNH, vec3(guv, float(layer)), glod).rg * 2.0 - 1.0;
        // the photo's own colour (the terrain mask picked grass here); tree shadows from the ground alpha
        grassAlb = gc * 0.92;
        vec2 wxz = turned > 0.5 ? vec2(-tn.y, -tn.x) : vec2(tn.x, -tn.y);
        float nz = sqrt(max(0.0, 1.0 - dot(tn, tn)));
        float gk = 1.0 - steep * 0.7;
        n = normalize(vec3(n.x * nz + wxz.x * gk, n.y * nz, n.z * nz + wxz.y * gk));
        mk = 0.0;                                   // no procedural bumps on top
    }
    if (kind < 0.5) n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 1.6);
    else n = normalize(n + vec3(-msx, 0.0, -msz) * mk * 0.8);
    if (isFern > 0.5) {
        n = normalize(vec3(0.0, 1.0, 0.0) + vec3(hash(floor(wp * 30.0)) - 0.5, 0.0, hash(floor(wp * 30.0) + 3.0) - 0.5) * 0.6);
        steep = 0.0;
    }

    // sky openness: test a point nudged off the surface; if it sits under rock, use that cell's value
    float open = mix(1.0, volOpen(hp + n * 0.5), hit);     // sky openness from the volume

    float water = (1.0 - clamp(gt.a * 2.0, 0.0, 1.0)) * hit * (1.0 - steep) * (1.0 - isFern) * onTop;

    // ---- one sky evaluation: the sky above, its mirror image in water below
    vec2 rip = vec2(vnoise(wp * 1.6 + uTime * 0.7), vnoise(wp * 1.6 - uTime * 0.55 + 7.0)) - 0.5;
    rip *= 1.0 + 2.5 * uRain;
    vec3 dd = vec3(d.x, abs(d.y), d.z);
    dd.xz += rip * 0.045 * water;
    dd.y = max(dd.y, 0.0005);
    gOpenSky = 1.0;
    vec3 sky = skyColor(normalize(dd));
    // sun shafts: alpha carries how much open sky this pixel shows (0 for anything solid)
    float shaftMask = (1.0 - hit) * step(0.0, d.y) * gOpenSky;

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

    vec3 albedo = isFern > 0.5 ? fcol * (0.85 + 0.3 * hash(floor(wp * 60.0))) : (isGrass > 0.5 ? grassAlb : gt.rgb * micro);
    // cliffs, walls, ceilings, tunnels: the rock texture set wrapped from three sides
    vec3 nRock;
    float dug = hit * smoothstep(0.25, 0.7, texture(uHeight, wp / WIN).g);      // dug by the pickaxe: bare earth
    vec3 rock = rockTriplanar(hp, nMacro, T, dug > 0.5 ? 2.0 : (onTop > 0.5 ? 13.0 : 3.0), nRock) * 0.95;   // cliff outside, cave rock below
    albedo = mix(albedo, rock, steep);
    n = normalize(mix(n, nRock, steep));

    // ---- physically based ground: roughness per material, wet ground gets darker and glossier
    float rough = mid < 0.5 ? 0.90 : (mid < 1.5 ? 0.92 : (mid < 2.5 ? 0.62 : (mid < 3.5 ? 0.80 : 0.85)));
    rough = mix(rough, 0.75, steep);                                   // rock faces
    if (isFern > 0.5) rough = 0.70;
    float wetK = uWet * open * (1.0 - isFern);
    rough = mix(rough, rough * 0.35, wetK);
    float trans = (mid < 0.5 || mid > 4.5 || isFern > 0.5) ? 0.25 + 0.25 * isFern : 0.0;
    float sunLit = 1.0 - 0.85 * shadow;                                // cloud shadow
    if (isGrass > 0.5) {
        sunLit *= clamp(gt.a * 2.0 - 1.0, 0.0, 1.0);                     // tree shadows on textured ground
        if (T < SURF_FAR) sunLit *= surfShadow(wp, T);                   // self-shadowed relief
    }
    float lamp = 1.4 / (1.0 + T * T * 0.09) * dark;
    if (T < GI_FAR && hit > 0.5) {
        vec3 gi = giAmbient(hp, n, T);
        float far = smoothstep(GI_FAR * 0.6, GI_FAR, T);                // fade to the plain sky term
        vec3 plain = toLin(uAmbient) * SKY_E * mix(0.05, 1.0, open) * (0.75 + 0.25 * n.y);
        gGI = mix(gi, plain, far);
    }
    vec3 land = pbr(albedo * mix(1.0, 0.70, wetK), n, -d, rough, 0.0, sunLit, open, 1.0, lamp, trans);
    gGI = vec3(-1.0);
    float fres = 0.25 + 0.75 * pow(1.0 - min(max(-d.y, 0.0), 1.0), 5.0);
    vec3 wcol = mix(gt.rgb * light * 0.8, sky * 0.92 * mix(0.05, 1.0, open), fres);
    vec3 g = mix(land, wcol, water);
    float camOpen = volOpen(ro);
    float fog = 1.0 - exp(-T * uFog * mix(0.4, 1.0, camOpen));
    vec3 fogc = mix(vec3(0.0), uFogCol, max(open, camOpen));   // fog only goes black when you are in the dark
    vec3 ground = mix(g, fogc, fog);
    {   // the land fades into the far tree line painted at the horizon, hiding the window's edge
        float keep = gOpenSky;
        vec3 horizon = skyColor(normalize(vec3(d.x, 0.004, d.z)));
        gOpenSky = keep;
        ground = mix(ground, mix(horizon, fogc, fog), smoothstep(FAR * 0.62, FAR - 2.0, T) * max(open, camOpen));
    }

    vec3 missCol = d.y > 0.0 ? sky : uFogCol;
    vec3 col = mix(missCol, ground, hit);
    col += uFlash * vec3(0.55, 0.60, 0.75) * (0.4 + 0.6 * (1.0 - hit)) * open;

    // ---- clods of dirt knocked loose by the pickaxe: small lumpy balls, flying, rolling, settling
    for (int i = 0; i < 48; i++) {
        if (i >= uClodN) break;
        vec4 c = uClods[i];
        vec3 oc = ro - c.xyz;
        float b = dot(oc, d), h = b * b - (dot(oc, oc) - c.w * c.w);
        if (h < 0.0) continue;
        float tc = -b - sqrt(h);
        if (tc < 0.05 || tc > (hit > 0.5 ? T : FAR)) continue;
        vec3 cpos = ro + d * tc;
        vec3 cn = normalize(cpos - c.xyz);
        vec3 lq = floor(cn * 3.0 + float(i) * 7.0);                         // a few flat facets: lumpy
        cn = normalize(cn + (vec3(hash(lq.xy), hash(lq.yz + 3.1), hash(lq.zx + 7.3)) - 0.5) * 0.9);
        float co = volOpen(c.xyz);
        float cdark = clamp(1.0 - co * (1.0 - uStars * 0.85), 0.0, 1.0);
        vec3 soil = vec3(0.30, 0.21, 0.14) * (0.70 + 0.45 * hash(vec2(float(i), 3.0))) * (0.85 + 0.3 * hash(floor(cpos.xz * 60.0)));
        col = pbr(soil, cn, -d, 0.95, 0.0, 1.0, co, 1.0, 1.4 / (1.0 + tc * tc * 0.09) * cdark, 0.0);
        col = mix(col, mix(vec3(0.0), uFogCol, co), 1.0 - exp(-tc * uFog));
        T = tc;
        hit = 1.0;
        shaftMask = 0.0;
    }

    // ---- trees: walk the 16 m cells the ray crosses (nearest first), trace each cell's tree
    {
        float bestT = -1.0, bestYaw = 0.0, bestScale = 1.0;
        int bestSp = 0, bestPid = -1;
        vec2 bestUv = vec2(0.0);
        vec3 bestHl = vec3(0.0), bestRdl = vec3(0.0);
        float tLimit = min(hit > 0.5 ? T : FAR, TREE_FAR);
        if (uPreview.w >= 0.0) {                     // Tree Builder: a single tree, no world trees
            int sp = int(uPreview.w);
            float yaw = uPreviewYaw, cyw = cos(yaw), syw = sin(yaw);
            vec3 o = ro - uPreview.xyz;
            vec3 rol = vec3(o.x * cyw - o.z * syw, o.y, o.x * syw + o.z * cyw);
            vec3 rdl = vec3(d.x * cyw - d.z * syw, d.y, d.x * syw + d.z * cyw);
            int pid;
            vec2 uv;
            float th = traceSpecies(sp, rol, rdl, tLimit, 1.0, pid, uv);
            if (th > 0.0) { bestT = th; bestSp = sp; bestPid = pid; bestUv = uv; bestHl = rol + rdl * th; bestRdl = rdl; bestYaw = yaw; }
        } else {
            vec2 dir = d.xz;
            vec2 cell = floor(ro.xz / TC);
            vec2 sgn = vec2(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0);
            vec2 inv = 1.0 / max(abs(dir), vec2(1e-5));
            vec2 tMax = ((cell + max(sgn, 0.0)) * TC - ro.xz) * sgn * inv;
            vec2 tDel = TC * inv;
            float tCell = 0.0;
            for (int i = 0; i < 26; i++) {
                if (tCell > tLimit) break;
                vec4 f = texture(uTrees, (mod(cell, 32.0) + 0.5) / 32.0);
                if (f.a > 0.5) {
                    vec2 c = (cell + 0.5) * TC + (f.rg - 0.5) * 3.0;
                    float bv = floor(f.b * 255.0 + 0.5);
                    int sp = int(floor(bv / 25.0));
                    float halfW = uTreeMeta[sp * 6 + 2].w;
                    float sz = (0.85 + (f.a - 0.5) * 0.7);
                    sz = min(sz, 6.3 / max(halfW, 0.1));
                    vec4 hm = texture(uHeight, c / WIN) * HMAX;
                    float baseY = (hm.b - hm.g > 0.4) ? hm.b : hm.r;
                    float yaw = (bv - float(sp) * 25.0) / 25.0 * 6.2831853;
                    float cyw = cos(yaw), syw = sin(yaw);
                    vec3 o = (ro - vec3(c.x, baseY - 0.1, c.y)) / sz;
                    vec3 rol = vec3(o.x * cyw - o.z * syw, o.y, o.x * syw + o.z * cyw);
                    vec3 rdl = vec3(d.x * cyw - d.z * syw, d.y, d.x * syw + d.z * cyw);
                    int pid;
                    vec2 uv;
                    float th = traceSpecies(sp, rol, rdl, tLimit / sz, sz, pid, uv);
                    if (th > 0.0) {
                        bestT = th * sz; bestSp = sp; bestPid = pid; bestUv = uv;
                        bestHl = rol + rdl * th; bestRdl = rdl; bestYaw = yaw; bestScale = sz;
                        break;
                    }
                }
                if (tMax.x < tMax.y) { tCell = tMax.x; tMax.x += tDel.x; cell.x += sgn.x; }
                else { tCell = tMax.y; tMax.y += tDel.y; cell.y += sgn.y; }
            }
        }
        if (bestT > 0.0) {
            vec3 alb, tn;
            float isLeaf;
            shadeTree(bestSp, bestPid, bestUv, bestHl, bestRdl, bestYaw, alb, tn, isLeaf);
            // physically based: bark rough, leaves a little glossy and translucent; darker low down
            float tao = 0.70 + 0.30 * clamp(bestHl.y / max(uTreeMeta[bestSp * 6 + 5].w, 1.0), 0.0, 1.0);
            float tdark = clamp(uStars * 0.85, 0.0, 1.0);
            float tlamp = 1.4 / (1.0 + bestT * bestT * 0.09) * tdark;
            vec3 tnf = dot(tn, -d) < 0.0 && isLeaf > 0.5 ? -tn : tn;          // leaves are two-sided
            vec3 tc = pbr(alb, tnf, -d, isLeaf > 0.5 ? 0.55 : 0.88, 0.0, tao, 1.0, tao, tlamp, isLeaf * 0.9);
            tc = mix(tc, uFogCol, 1.0 - exp(-bestT * uFog));
            col = tc + uFlash * vec3(0.55, 0.60, 0.75) * 0.4;
            shaftMask = 0.0;
        }
    }

    // ---- first-person arms (in front of everything)
    if (uArmShow > 0.5) {
        vec3 aq0, aq1, aq;
        float ath0, ath1, ath;
        int ap0, ap1, ap;
        float at0 = traceArm(0, dCam, ap0, aq0, ath0);
        float at1 = traceArm(1, dCam, ap1, aq1, ath1);
        int ai = -1;
        float at = 1e9;
        if (at0 > 0.0) { ai = 0; at = at0; ap = ap0; aq = aq0; ath = ath0; }
        if (at1 > 0.0 && at1 < at) { ai = 1; at = at1; ap = ap1; aq = aq1; ath = ath1; }
        // the pickaxe, in the right arm's space
        vec3 sq;
        float smat = 0.0, st = -1.0;
        if (uSwordShow > 0.5) {
            mat3 Rt0 = transpose(uArmRot[0]);
            st = traceSword(Rt0 * (-uArmPos[0]) / ARM_S, Rt0 * dCam / ARM_S, sq, smat);
        }
        float swordOpen = volOpen(uCam);
        if (st > 0.0 && st < at) {
            ai = -1;
            vec3 F = cross(uSwordB, uSwordE);
            vec3 ns = swordNormal(sq);
            vec3 nc = normalize(uArmRot[0] * (uSwordE * ns.x + F * ns.y + uSwordB * ns.z));
            vec3 nw = vec3(nc.x, nc.y * cp - nc.z * sp, nc.y * sp + nc.z * cp);
            nw = vec3(nw.x * cy - nw.z * sy, nw.y, nw.x * sy + nw.z * cy);
            float sdark = clamp(1.0 - swordOpen * (1.0 - uStars * 0.85), 0.0, 1.0);
            float slamp = 1.0 * sdark;
            // point-sampled: pits, scratches and grime on 2.5 mm texels
            vec2 tq = floor(vec2(sq.x * 0.5 + sq.y, sq.z * 0.5));
            float wear = hash(tq) * 0.5 + hash(floor(tq / 6.0)) * 0.5;
            // dirt caked on from digging: thickest on the points and the lower head, smears on the haft
            float grime = vnoise(sq.xz * 0.045 + sq.y * 0.03) * 0.65 + hash(floor(tq / 3.0)) * 0.35;
            vec3 sc;
            if (smat < 0.5) {                       // forged iron, dark and dull, bright where it bites
                float tipK = smoothstep(110.0, 230.0, abs(sq.x - 30.0));
                float below = smoothstep(10.0, -25.0, sq.z - PK_HEAD_Z);
                float dirt = smoothstep(0.62, 0.80, grime + 0.22 * tipK + 0.10 * below);
                float shine = smoothstep(185.0, 240.0, sq.x) + smoothstep(135.0, 172.0, -sq.x);   // worn bright edges
                vec3 iron = vec3(0.46, 0.46, 0.47) * (0.80 + 0.30 * wear) + vec3(0.25) * shine;
                vec3 soil = vec3(0.30, 0.21, 0.13) * (0.70 + 0.45 * wear);
                vec3 alb = mix(iron, soil, dirt);
                sc = pbr(alb, nw, -d, mix(mix(0.48, 0.28, shine), 0.95, dirt), mix(1.0, 0.0, dirt), 1.0, swordOpen, 1.0, slamp, 0.0);
            } else {                                // ash haft: grain along it, polished by the hand, muddy at the butt
                float grain = 0.5 + 0.5 * sin(sq.x * 0.9 + vnoise(vec2(sq.z * 0.02, sq.x * 0.1)) * 6.0 + sq.z * 0.01);
                vec3 wood = vec3(0.47, 0.33, 0.19) * (0.78 + 0.22 * grain) * (0.85 + 0.25 * wear);
                float dirt = smoothstep(0.66, 0.84, grime + 0.30 * smoothstep(-30.0, -80.0, sq.z) + 0.15 * smoothstep(250.0, 370.0, sq.z));
                dirt *= 1.0 - smoothstep(60.0, 0.0, abs(sq.z - 10.0)) * 0.8;          // the grip is rubbed clean
                vec3 soil = vec3(0.29, 0.21, 0.14) * (0.75 + 0.4 * wear);
                sc = pbr(mix(wood, soil, dirt), nw, -d, mix(0.55, 0.95, dirt), 0.0, 1.0, swordOpen, 1.0, slamp, 0.0);
            }
            col = sc;
            shaftMask = 0.0;
        }
        if (ai >= 0) {
            vec3 alb, nc;
            shadeArm(ai, ap, aq, ath, at, alb, nc);
            // camera -> world, same rotations as the view ray
            vec3 nw = vec3(nc.x, nc.y * cp - nc.z * sp, nc.y * sp + nc.z * cp);
            nw = vec3(nw.x * cy - nw.z * sy, nw.y, nw.x * sy + nw.z * cy);
            float armOpen = volOpen(uCam);
            float adark = clamp(1.0 - armOpen * (1.0 - uStars * 0.85), 0.0, 1.0);
            // physically based: skin with a little light through it, leather, linen, iron buckles
            col = pbr(alb, nw, -d, gArmRough, gArmMetal, 1.0, armOpen, 1.0, 1.0 * adark, gArmTrans);
            shaftMask = 0.0;
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
    fragColor = vec4(col, shaftMask);
}
