#!/usr/bin/env python3
"""
Turn a rigged FBX character (Mixamo-style skeleton) into the per-pixel character the game traces:
no polygons at runtime, just distance fields.

    python3 tools/build_character.py Ch02_nonPBR.fbx app/src/main/assets/character

What it makes (in the output folder):
  * parts.bin.gz - the body split into 16 rigid parts that follow the bones (hips, spine, chest, head,
                 upper arms, forearms, hands, thighs, shins, feet). Each part is a 1 cm grid in its
                 bone's own frame holding the signed distance to the surface (+-4 cm) and, for every
                 cell, the texture coordinate of the nearest surface point. All parts are packed
                 side by side into one 3D atlas. Hair cards become a thin solid shell.
  * rig.txt    - per part: bone, parent part, bind matrix (metres), atlas placement and size.
  * atlas.png  - 1536 x 3072 RGB in three bands: colour (body 1024 + hair 512), the normal map
                 turned into each part's own frame (so the shader needs no tangents), and the height
                 map (grey).
The height map is integrated from the normal map (Frankot-Chellappa: the surface whose slopes best
match the normals, solved with FFTs), keeping the fine detail: pores, folds, seams, stitching.
"""
import io, os, sys, struct
import numpy as np
from collections import defaultdict
from PIL import Image
from scipy.spatial import cKDTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fbxparse

VOX = 0.01          # metres per cell
RANGE = 0.04        # distances stored +-4 cm
HAIR_SHELL = 0.006  # hair cards become a 6 mm shell
SAMPLE = 0.0025     # surface sample spacing
MARGIN = 0.03
BODY_RES, HAIR_RES = 1024, 512

PARTS = [
    ('Hips', None, ['Hips']),
    ('Spine', 'Hips', ['Spine', 'Spine1']),
    ('Spine2', 'Spine', ['Spine2', 'LeftShoulder', 'RightShoulder']),
    ('Neck', 'Spine2', ['Neck', 'Head', 'HeadTop_End']),
]
for s in ('Left', 'Right'):
    PARTS += [(s + 'Arm', 'Spine2', [s + 'Arm']), (s + 'ForeArm', s + 'Arm', [s + 'ForeArm']),
              (s + 'Hand', s + 'ForeArm', None)]          # None: the hand and every finger bone
for s in ('Left', 'Right'):
    PARTS += [(s + 'UpLeg', 'Hips', [s + 'UpLeg']), (s + 'Leg', s + 'UpLeg', [s + 'Leg']),
              (s + 'Foot', s + 'Leg', [s + 'Foot', s + 'ToeBase', s + 'Toe_End'])]
PART_OF = {}


def clean(n):
    return n.split('\x00')[0].replace('mixamorig:', '')


def part_of(bone):
    if bone in PART_OF: return PART_OF[bone]
    for i, (name, par, bones) in enumerate(PARTS):
        if bones is None and bone.startswith(name): return i
        if bones and bone in bones: return i
    return 2 if 'Shoulder' in bone else 0


def load(path):
    ver, root = fbxparse.load(path)
    obj = root.first('Objects'); con = root.first('Connections')
    byid = {c.props[0]: c for c in obj.children}
    kids = defaultdict(list)
    for c in con.children: kids[c.props[2]].append(c.props[1])
    bones, meshes, images = {}, [], {}
    for c in obj.children:
        if c.name == 'Video':
            fn = os.path.basename(c.first('RelativeFilename').props[0].replace('\\', '/'))
            images[fn] = c.first('Content').props[0]
    for c in obj.children:
        if c.name != 'Model' or c.props[2] != 'Mesh': continue
        name = clean(c.props[1])
        geo = [byid[k] for k in kids[c.props[0]] if k in byid and byid[k].name == 'Geometry'][0]
        mat = [clean(byid[k].props[1]) for k in kids[c.props[0]] if k in byid and byid[k].name == 'Material'][0]
        V = geo.first('Vertices').props[0].reshape(-1, 3) * 0.01
        PI = geo.first('PolygonVertexIndex').props[0]
        uvn = geo.first('LayerElementUV')
        UV = uvn.first('UV').props[0].reshape(-1, 2); UVI = uvn.first('UVIndex').props[0]
        NRM = geo.first('LayerElementNormal').first('Normals').props[0].reshape(-1, 3)
        tris, start = [], 0
        for i, v in enumerate(PI):
            if v < 0:
                for k in range(1, i - start):
                    tris.append((start, start + k, start + k + 1))
                start = i + 1
        tris = np.array(tris)
        vidx = PI.copy(); vidx[vidx < 0] = -vidx[vidx < 0] - 1
        W = np.zeros((len(V), len(PARTS)))
        for sk in [byid[k] for k in kids[geo.props[0]] if k in byid and byid[k].name == 'Deformer']:
            for cid in kids[sk.props[0]]:
                cl = byid[cid]
                bone = [clean(byid[k].props[1]) for k in kids[cid] if k in byid and byid[k].name == 'Model'][0]
                TL = cl.first('TransformLink').props[0].reshape(4, 4).T.copy()
                TL[:3, 3] *= 0.01
                bones[bone] = TL
                ix = cl.first('Indexes')
                if ix is not None: np.add.at(W[:, part_of(bone)], ix.props[0], cl.first('Weights').props[0])
        meshes.append(dict(name=name, mat=mat, V=V, P=vidx[tris], UV=UV[UVI[tris]], N=NRM[tris], W=W))
    return meshes, bones, images


def to_atlas_uv(uv, hair):
    """Mesh UV (v up) -> atlas texture coordinate (t down from the top), top half of the atlas."""
    u, v = uv[..., 0] % 1.0, uv[..., 1] % 1.0
    if hair:
        return np.stack([(1024 + u * 512) / 1536.0, (1 - v) * 512 / 3072.0], -1)
    return np.stack([u * 1024 / 1536.0, (1 - v) * 1024 / 3072.0], -1)


def height_from_normals(nrm):
    """Frankot-Chellappa: integrate a tangent-space normal map into the height it describes."""
    n = nrm.astype(np.float64) / 127.5 - 1.0
    nz = np.clip(n[..., 2], 0.25, 1.0)
    p, q = -n[..., 0] / nz, n[..., 1] / nz         # dh/dx, dh/dy (green up)
    H, W = p.shape
    wx = np.fft.fftfreq(W) * 2 * np.pi; wy = np.fft.fftfreq(H) * 2 * np.pi
    WX, WY = np.meshgrid(wx, wy)
    den = WX ** 2 + WY ** 2; den[0, 0] = 1
    Z = (-1j * WX * np.fft.fft2(p) - 1j * WY * np.fft.fft2(-q)) / den
    Z[0, 0] = 0
    Z[den < (2 * np.pi / 48) ** 2] = 0              # keep the fine relief, drop the broad shape
    h = np.real(np.fft.ifft2(Z))
    lo, hi = np.percentile(h, 1), np.percentile(h, 99)
    return np.clip((h - lo) / (hi - lo + 1e-9), 0, 1)


def main(src, out):
    os.makedirs(out, exist_ok=True)
    meshes, bones, images = load(src)
    bind = []
    for name, par, _ in PARTS: bind.append(bones[name])
    inv = [np.linalg.inv(b) for b in bind]

    # ---- textures: colour + height (top), normals in part frames (bottom, filled below)
    def img(name, size, mode):
        return np.asarray(Image.open(io.BytesIO(images[name])).convert(mode).resize((size, size), Image.LANCZOS))
    body_col, body_nrm = img('Ch02_1001_Diffuse.png', BODY_RES, 'RGB'), img('Ch02_1001_Normal.png', BODY_RES, 'RGB')
    hair_rgba = img('Ch02_1002_Diffuse.png', HAIR_RES, 'RGBA').astype(np.float64)
    hair_nrm = img('Ch02_1002_Normal.png', HAIR_RES, 'RGB')
    # hair cards are strands on a transparent card; the shell shows the strands over the hair's own
    # mean colour, never the black of the empty card
    a = hair_rgba[..., 3:4] / 255.0
    mean = (hair_rgba[..., :3] * a).sum((0, 1)) / max(a.sum(), 1.0)
    hair_col = np.clip(hair_rgba[..., :3] * a + mean * 0.85 * (1 - a), 0, 255).astype(np.uint8)
    atlas = np.zeros((3072, 1536, 3), np.uint8)
    atlas[:1024, :1024] = body_col
    atlas[:512, 1024:] = hair_col
    atlas[1024:2048] = 128
    atlas[2048:3072] = 128
    atlas[2048:3072, :1024] = (height_from_normals(body_nrm) * 255).astype(np.uint8)[..., None]
    atlas[2048:2560, 1024:] = (height_from_normals(hair_nrm) * 255).astype(np.uint8)[..., None]

    # ---- triangles per part (overlapping a little at the joints), surface samples
    per = [defaultdict(list) for _ in PARTS]
    allp, allown = [], []                                   # every solid surface sample, and its part
    rng = np.random.default_rng(7)
    for m in meshes:
        if m['name'] == 'Eyelashes': continue
        hair = 'hair' in m['mat'].lower()
        P, V = m['P'], m['V']
        tw = m['W'][P].max(axis=1)                          # (tris, parts)
        dom = m['W'][P].sum(axis=1).argmax(axis=1)          # dominant part per triangle
        if hair:
            tw[:] = 0; tw[:, 3] = 1; dom[:] = 3
        A, B, C = V[P[:, 0]], V[P[:, 1]], V[P[:, 2]]
        fn = np.cross(B - A, C - A)
        area = np.linalg.norm(fn, axis=1) * 0.5
        fn = fn / np.maximum(np.linalg.norm(fn, axis=1, keepdims=True), 1e-12)
        nsmp = np.maximum(1, np.ceil(area / SAMPLE ** 2)).astype(int)
        ti = np.repeat(np.arange(len(P)), nsmp)
        r1, r2 = rng.random(len(ti)), rng.random(len(ti))
        s = np.sqrt(r1)
        b0, b1, b2 = 1 - s, s * (1 - r2), s * r2
        pts = A[ti] * b0[:, None] + B[ti] * b1[:, None] + C[ti] * b2[:, None]
        uv = m['UV'][ti, 0] * b0[:, None] + m['UV'][ti, 1] * b1[:, None] + m['UV'][ti, 2] * b2[:, None]
        auv = to_atlas_uv(uv, hair)
        if not hair:
            allp.append(pts); allown.append(dom[ti])
        for pi in range(len(PARTS)):
            # a little overlap at the joints (filled by the smooth union), less where a limb swings
            # far from its T-pose, so a lowered arm leaves no sleeve behind on the chest
            sel = tw[ti, pi] >= (0.55 if pi in (1, 2) else 0.25)
            if not sel.any(): continue
            per[pi]['pts'].append(pts[sel]); per[pi]['n'].append(fn[ti[sel]])
            per[pi]['uv'].append(auv[sel]); per[pi]['hair'].append(np.full(sel.sum(), hair))
        # bake the normal map into each triangle's dominant part frame (bottom half of the atlas)
        bake_normals(atlas, m, hair, dom, inv)

    # ---- grow the baked normals a few texels past their charts (filtering never reaches the grey)
    band = atlas[1024:2048].astype(np.float64)
    done = np.abs(band - 128).sum(-1) > 3
    for _ in range(6):
        acc = np.zeros_like(band); cnt = np.zeros(done.shape)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            acc += np.roll(band * done[..., None], (dy, dx), (0, 1)); cnt += np.roll(done, (dy, dx), (0, 1))
        grow = (~done) & (cnt > 0)
        band[grow] = acc[grow] / cnt[grow][:, None]
        done |= grow
    atlas[1024:2048] = np.clip(band, 0, 255).astype(np.uint8)

    # ---- inside / outside for the whole body: mark the surface on a 1 cm grid, flood the outside
    from scipy import ndimage
    allp = np.concatenate(allp); allown = np.concatenate(allown)
    glo = allp.min(0) - 0.05
    gdim = np.ceil((allp.max(0) + 0.05 - glo) / VOX).astype(int) + 1
    surf = np.zeros(gdim, bool)
    gi = np.floor((allp - glo) / VOX).astype(int)
    surf[gi[:, 0], gi[:, 1], gi[:, 2]] = True
    solid = ndimage.binary_fill_holes(ndimage.binary_closing(surf, iterations=1))
    owner_tree = cKDTree(allp)

    def inside_body(wp):
        k = np.floor((wp - glo) / VOX + 0.5).astype(int)
        ok = np.all((k >= 0) & (k < gdim), axis=1)
        out = np.zeros(len(wp), bool)
        kk = k[ok]
        out[ok] = solid[kk[:, 0], kk[:, 1], kk[:, 2]]
        return out

    # ---- distance grids
    grids = []
    for pi, (name, par, _) in enumerate(PARTS):
        d = per[pi]
        pts = np.concatenate(d['pts']); n = np.concatenate(d['n'])
        uv = np.concatenate(d['uv']); hair = np.concatenate(d['hair'])
        R, t = inv[pi][:3, :3], inv[pi][:3, 3]
        lp = pts @ R.T + t; ln = n @ R.T
        lo = lp.min(0) - MARGIN; hi = lp.max(0) + MARGIN
        dims = np.ceil((hi - lo) / VOX).astype(int) + 1
        gx, gy, gz = [lo[k] + (np.arange(dims[k]) + 0.0) * VOX for k in range(3)]
        G = np.stack(np.meshgrid(gx, gy, gz, indexing='ij'), -1).reshape(-1, 3)
        dist = np.full(len(G), RANGE)
        best_uv = np.zeros((len(G), 2))
        for want_hair in (False, True):
            sel = hair == want_hair
            if not sel.any(): continue
            tree = cKDTree(lp[sel])
            dd, ii = tree.query(G, k=1, distance_upper_bound=RANGE * 1.5)
            ok = np.isfinite(dd)
            ii2 = np.where(ok, ii, 0)
            if want_hair:
                sd = dd - HAIR_SHELL
            else:
                side = np.einsum('ij,ij->i', G - lp[sel][ii2], ln[sel][ii2])
                sd = np.where(side < 0, -dd, dd)
                # away from the surface: solid only inside the body, and only the part of the
                # body this part owns (so a raised arm leaves nothing behind in the chest)
                far = ~ok | (dd > 0.03)
                Gw = G[far] @ bind[pi][:3, :3].T + bind[pi][:3, 3]
                ins = inside_body(Gw)
                _, oi = owner_tree.query(Gw, k=1)
                mine = allown[oi] == pi
                near_ok = ok[far]
                sd_far = np.where(ins & (mine | (near_ok & (sd[far] < 0))), -np.minimum(np.where(near_ok, dd[far], RANGE), RANGE), RANGE)
                sd[far] = np.where(ins & ~mine & near_ok, np.minimum(sd[far], sd_far), sd_far)
                if (~ok).any():
                    _, far_i = tree.query(G[~ok], k=1)
                    ii2[~ok] = far_i
            closer = np.where(ok | (not want_hair), sd < dist, False)
            dist = np.where(closer, sd, dist)
            best_uv[closer] = uv[sel][ii2[closer]]
        dist = np.clip(dist, -RANGE, RANGE).reshape(dims)
        best_uv = best_uv.reshape(tuple(dims) + (2,))
        grids.append(dict(name=name, par=par, lo=lo, dims=dims, dist=dist, uv=best_uv))
        print('%-12s %3d x %3d x %3d   %d samples' % (name, dims[0], dims[1], dims[2], len(lp)))

    # ---- pack side by side along x (1 cell gap)
    AY = max(g['dims'][1] for g in grids); AZ = max(g['dims'][2] for g in grids)
    AX = sum(g['dims'][0] + 1 for g in grids)
    D = np.full((AZ, AY, AX), 255, np.uint8)               # z, y, x (x fastest when flattened)
    U = np.zeros((AZ, AY, AX, 4), np.uint8)
    x0 = 0
    rig = ['# part parent bind(16, row-major, metres) atlasX0 nx ny nz loX loY loZ', 'atlas %d %d %d' % (AX, AY, AZ)]
    for pi, g in enumerate(grids):
        nx, ny, nz = g['dims']
        q = np.round((g['dist'] + RANGE) / (2 * RANGE) * 255).astype(np.uint8)
        D[:nz, :ny, x0:x0 + nx] = q.transpose(2, 1, 0)
        uu = np.clip(np.round(g['uv'][..., 0] * 65535), 0, 65535).astype(np.int32)
        vv = np.clip(np.round(g['uv'][..., 1] * 65535), 0, 65535).astype(np.int32)
        pk = np.stack([uu >> 8, uu & 255, vv >> 8, vv & 255], -1).astype(np.uint8)
        U[:nz, :ny, x0:x0 + nx] = pk.transpose(2, 1, 0, 3)
        parent = -1 if g['par'] is None else [p[0] for p in PARTS].index(g['par'])
        m = bind[pi]
        rig.append('%s %d %s %d %d %d %d %.5f %.5f %.5f' % (g['name'], parent, ' '.join('%.6f' % v for v in m.reshape(-1)),
                                                        x0, nx, ny, nz, g['lo'][0], g['lo'][1], g['lo'][2]))
        x0 += nx + 1
    import gzip
    with gzip.open(os.path.join(out, 'parts.bin.gz'), 'wb', 9) as f:
        f.write(struct.pack('<iii', AX, AY, AZ))
        f.write(D.tobytes()); f.write(U.tobytes())
    open(os.path.join(out, 'rig.txt'), 'w').write('\n'.join(rig) + '\n')
    Image.fromarray(atlas, 'RGB').save(os.path.join(out, 'atlas.png'), optimize=True)
    print('atlas %d x %d x %d, %.1f MB' % (AX, AY, AZ, (D.nbytes + U.nbytes) / 1e6))


def bake_normals(atlas, m, hair, dom, inv):
    """Rasterise each triangle in texture space: tangent-space normal map -> the part's own frame."""
    res = HAIR_RES if hair else BODY_RES
    ox = 1024 if hair else 0
    src = atlas  # colour already in place; normal maps read from the original images below
    nm = NRM_CACHE[hair]
    V, P, UV, N = m['V'], m['P'], m['UV'], m['N']
    for t in range(len(P)):
        uv = UV[t] % 1.0
        px = uv[:, 0] * res; py = (1 - uv[:, 1]) * res
        x0, x1 = int(max(0, np.floor(px.min()))), int(min(res - 1, np.ceil(px.max())))
        y0, y1 = int(max(0, np.floor(py.min()))), int(min(res - 1, np.ceil(py.max())))
        if x1 < x0 or y1 < y0: continue
        a, b, c = V[P[t]]
        e1, e2 = b - a, c - a
        du1, dv1 = uv[1] - uv[0]; du2, dv2 = uv[2] - uv[0]
        det = du1 * dv2 - du2 * dv1
        if abs(det) < 1e-12: continue
        T = (e1 * dv2 - e2 * dv1) / det
        B = (e2 * du1 - e1 * du2) / det
        X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        d = (px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0])
        if abs(d) < 1e-9: continue
        w1 = ((X - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (Y - py[0])) / d
        w2 = ((px[1] - px[0]) * (Y - py[0]) - (X - px[0]) * (py[1] - py[0])) / d
        w0 = 1 - w1 - w2
        inside = (w0 >= -0.02) & (w1 >= -0.02) & (w2 >= -0.02)
        if not inside.any(): continue
        ys, xs = Y[inside].astype(int), X[inside].astype(int)
        n = N[t, 0] * w0[inside, None] + N[t, 1] * w1[inside, None] + N[t, 2] * w2[inside, None]
        n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-9
        Tn = T - n * (n @ T)[:, None]; Tn /= np.linalg.norm(Tn, axis=1, keepdims=True) + 1e-9
        Bs = np.sign(np.dot(np.cross(n[0], Tn[0]), B)) or 1.0
        Bn = np.cross(n, Tn) * Bs
        tsn = nm[ys, xs].astype(np.float64) / 127.5 - 1.0
        wn = Tn * tsn[:, :1] + Bn * tsn[:, 1:2] + n * tsn[:, 2:3]
        R = inv[dom[t]][:3, :3]
        ln = wn @ R.T
        ln /= np.linalg.norm(ln, axis=1, keepdims=True) + 1e-9
        atlas[1024 + ys, ox + xs] = np.clip(np.round((ln * 0.5 + 0.5) * 255), 0, 255).astype(np.uint8)


NRM_CACHE = {}

if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    _, _, imgs = load(src)
    NRM_CACHE[False] = np.asarray(Image.open(io.BytesIO(imgs['Ch02_1001_Normal.png'])).convert('RGB').resize((BODY_RES, BODY_RES), Image.LANCZOS))
    NRM_CACHE[True] = np.asarray(Image.open(io.BytesIO(imgs['Ch02_1002_Normal.png'])).convert('RGB').resize((HAIR_RES, HAIR_RES), Image.LANCZOS))
    main(src, out)
