"""Turn the two-view arm sheet (colour, normal map, height map) (palm row on top, back-of-hand row below, two arms meeting at the
shoulders in the middle) into: back.png (RGB + A = half-thickness), palm.png (RGB). Grid: 544 x 352,
rows 0-175 right arm, 176-351 left arm; u = shoulder -> fingertips, z across (thumb at -z for the
right arm, +z for the left, so both are true right-handed hands)."""
import sys, numpy as np, cv2
from PIL import Image

src = np.array(Image.open(sys.argv[1]).convert('RGBA')).astype(np.float32)
nrm_src = np.array(Image.open(sys.argv[2]).convert('RGB')).astype(np.float32)
hgt_src = np.array(Image.open(sys.argv[3]).convert('L')).astype(np.float32)
# height detail = height map minus its broad shape (the 3D body comes from the outline)
hgt_hp = hgt_src - cv2.GaussianBlur(hgt_src, (0, 0), 14)
out = sys.argv[4]
DETAIL = 0.028                     # grid units per height-map level of relief
H, W = src.shape[:2]
alpha = src[:, :, 3] > 128
SPLIT_Y = 335
MID_X = W // 2
GX, GZ, S = 544, 176, 2.0          # grid size, source px per grid px
HMAX = 56.0                        # grid units stored as alpha 255

nrm_mask = np.abs(nrm_src - nrm_src[2, 2]).sum(2) > 40
hgt_mask = hgt_src > 14

def view_fit(y0, y1, xs, m=alpha):
    """Centre line (linear fit) and full width of the forearm in one view of one map."""
    cs, ws = [], []
    for x in xs:
        ys = np.nonzero(m[y0:y1, x])[0]
        cs.append((ys.min() + ys.max()) / 2 + y0)
        ws.append(ys.max() - ys.min())
    k, b = np.polyfit(xs, cs, 1)
    return k, b, float(np.median(ws))

def sample(img, mapx, mapy):
    return cv2.remap(img, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

rgb_back = np.zeros((2 * GZ, GX, 3), np.float32)
n_back = np.zeros((2 * GZ, GX, 3), np.float32)
n_palm = np.zeros((2 * GZ, GX, 3), np.float32)
h_top = np.zeros((2 * GZ, GX), np.float32)
h_bot = np.zeros((2 * GZ, GX), np.float32)
rgb_palm = np.zeros((2 * GZ, GX, 3), np.float32)
height = np.zeros((2 * GZ, GX), np.float32)
finger_id = np.zeros((2 * GZ, GX), np.uint8)
fingers = []                       # per arm, 5 x (pivot u, pivot z, dir u, dir z, length), thumb first

for arm in (0, 1):                  # 0 right (fingertips at the right edge), 1 left
    sign = 1 if arm == 0 else -1
    # forearm columns (between elbow cloth and wrist) for the centre line fit
    fore = [MID_X + sign * d for d in range(520, 760, 10)]
    rows = {'palm': (0, SPLIT_Y), 'back': (SPLIT_Y, H)}
    fits = {name: view_fit(y0, y1, fore) for name, (y0, y1) in rows.items()}
    nfits = {name: view_fit(y0, y1, fore, nrm_mask) for name, (y0, y1) in rows.items()}
    hfits = {name: view_fit(y0, y1, fore, hgt_mask) for name, (y0, y1) in rows.items()}
    w_ref = fits['palm'][2]
    gu, gz = np.meshgrid(np.arange(GX, dtype=np.float32), np.arange(GZ, dtype=np.float32) - GZ / 2)
    xsrc = MID_X + sign * (gu * S) - (0 if arm == 0 else 1)
    views, nviews, hviews = {}, {}, {}
    for name in ('palm', 'back'):
        def ymap(f):
            k, b, w = f
            zs = gz * S * (w / w_ref) * (1 if arm == 0 else -1)   # left arm: thumb ends up at +z
            return ((k * xsrc + b) + zs).astype(np.float32)
        mx = xsrc.astype(np.float32)
        my = ymap(fits[name])
        views[name] = sample(src, mx, my)
        t = sample(nrm_src, mx, ymap(nfits[name])) / 127.5 - 1.0          # x image-right, y image-up, z to viewer
        sx = 1.0 if arm == 0 else -1.0                      # image right = +u (right arm) / -u (left)
        # local frame: x along the arm, y = back of hand, z across (image down = +z right arm, -z left)
        if name == 'back':     # seen from +y
            loc = np.dstack([sx * t[..., 0], t[..., 2], -sx * t[..., 1]])
        else:                  # seen from -y
            loc = np.dstack([sx * t[..., 0], -t[..., 2], -sx * t[..., 1]])
        nviews[name] = loc / np.maximum(np.linalg.norm(loc, axis=2, keepdims=True), 1e-4)
        hviews[name] = sample(hgt_hp, mx, ymap(hfits[name]))
    a_p = views['palm'][:, :, 3] > 128
    a_b = views['back'][:, :, 3] > 128
    mask = a_p | a_b
    # each view fills the other's missing rim
    cp = np.where(a_p[..., None], views['palm'][:, :, :3], views['back'][:, :, :3])
    cb = np.where(a_b[..., None], views['back'][:, :, :3], views['palm'][:, :, :3])

    # round 3D body from the outline: union of balls on the distance field
    m8 = mask.astype(np.uint8)
    m8 = cv2.morphologyEx(m8, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    D = cv2.distanceTransform(m8, cv2.DIST_L2, 5).astype(np.float64)
    g = D * D
    idx = np.arange(GX, dtype=np.float64)
    d2 = (idx[:, None] - idx[None, :]) ** 2
    rowmax = np.max(g[:, None, :] - d2[None, :, :], axis=2)          # along u
    idz = np.arange(GZ, dtype=np.float64)
    dz2 = (idz[:, None] - idz[None, :]) ** 2
    h2 = np.max(rowmax.T[:, None, :] - dz2[None, :, :], axis=2).T    # along z
    h = np.sqrt(np.clip(h2, 0, None)) * m8

    # find the wrist: narrowest point between forearm and knuckles
    width = m8.sum(0).astype(float)
    wrist = int(np.argmin(np.where((idx > 330) & (idx < 420), width, 1e9)))
    # forearm a bit oval, hand flat (palm about 3 cm thick), fingers stay round
    t = np.clip((idx - (wrist - 8)) / 24.0, 0, 1)
    t = t * t * (3 - 2 * t)
    cap = (1 - t) * 1e3 + t * 13.0
    flat = (1 - t) * 0.82 + t * 1.0
    hc = h * flat[None, :]
    hc = -np.log(np.exp(-hc / 3.0) + np.exp(-cap[None, :] / 3.0)) * 3.0   # smooth min
    hc = np.clip(hc, 0, None) * m8
    print('arm', arm, 'wrist u', wrist, 'max h', hc.max(), 'tip u', np.nonzero(m8.any(0))[0].max())

    # the photo's outline carries a red fringe: repaint a thin rim from the colour just inside
    inner = cv2.erode(m8, np.ones((5, 5), np.uint8))
    rim = ((m8 > 0) & (inner == 0)) | (m8 == 0)
    rim8 = rim.astype(np.uint8) * 255
    cp = cv2.inpaint(np.clip(cp, 0, 255).astype(np.uint8), rim8, 3, cv2.INPAINT_TELEA).astype(np.float32)
    cb = cv2.inpaint(np.clip(cb, 0, 255).astype(np.uint8), rim8, 3, cv2.INPAINT_TELEA).astype(np.float32)
    # ---- fingers: what an opening with a palm-sized disc removes, beyond the wrist
    hm = (hc > 0.5).astype(np.uint8)
    core = cv2.morphologyEx(hm, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (35, 35)))
    fing = (hm > 0) & (core == 0)
    fing[:, :wrist + 20] = False
    n, lab, st, _ = cv2.connectedComponentsWithStats(fing.astype(np.uint8))
    comps = [i for i in range(1, n) if st[i, 4] > 300]
    assert len(comps) == 5, 'expected 5 fingers, found %d' % len(comps)
    zc = {i: np.mean(np.nonzero(lab == i)[0]) for i in comps}
    order = sorted(comps, key=lambda i: zc[i], reverse=(arm == 1))   # thumb first on both arms
    ring = cv2.dilate(core, np.ones((5, 5), np.uint8)) > 0
    fid = np.zeros_like(hm)
    for f, i in enumerate(order):
        zz, uu = np.nonzero(lab == i)
        bz, bu = np.nonzero((lab == i) & ring)
        base = np.array([bu.mean(), bz.mean() - GZ / 2])
        pts = np.stack([uu, zz - GZ / 2], 1).astype(float)
        far = pts[np.argmax(((pts - base) ** 2).sum(1))]
        dvec = (far - base) / np.linalg.norm(far - base)
        pivot = base - dvec * 3.0
        length = float(np.linalg.norm(far - pivot))
        fingers.append([float(pivot[0]), float(pivot[1]), float(dvec[0]), float(dvec[1]), length])
        fid[lab == i] = f + 1
        print('  finger', f, 'pivot', pivot.round(1), 'dir', dvec.round(2), 'len', round(length, 1))
    finger_id[arm * GZ:(arm + 1) * GZ] = fid

    r0 = arm * GZ
    rgb_palm[r0:r0 + GZ] = cp
    rgb_back[r0:r0 + GZ] = cb
    height[r0:r0 + GZ] = hc
    # fill each view's normals/detail from the other where its own outline is missing
    nb = np.where(a_b[..., None], nviews['back'], nviews['palm'] * [1, -1, 1])
    npm = np.where(a_p[..., None], nviews['palm'], nviews['back'] * [1, -1, 1])
    n_back[r0:r0 + GZ] = nb
    n_palm[r0:r0 + GZ] = npm
    db = np.where(a_b, hviews['back'], 0) * DETAIL
    dp = np.where(a_p, hviews['palm'], 0) * DETAIL
    edge = np.clip(hc / 6.0, 0, 1)                      # no relief where the body is paper thin
    h_top[r0:r0 + GZ] = np.clip(hc + db * edge, 0, None) * m8
    h_bot[r0:r0 + GZ] = np.clip(hc + dp * edge, 0, None) * m8
    print('  detail range', db.min(), db.max())

def enc(v):
    return np.clip(v / HMAX * 255 + 0.5, 0, 255)
# assets: opaque PNGs (Android premultiplies alpha, so no data goes in an alpha channel)
col = np.vstack([rgb_back, rgb_palm]).clip(0, 255).astype(np.uint8)          # back view rows 0-351, palm 352-703
nrm = (np.vstack([n_back, n_palm]) * 127.5 + 127.5).clip(0, 255).astype(np.uint8)
hgt = np.dstack([enc(h_top), enc(h_bot), finger_id.astype(np.float32) * 40]).astype(np.uint8)
Image.fromarray(col, 'RGB').save(out + '/arm_col.png', optimize=True)
Image.fromarray(nrm, 'RGB').save(out + '/arm_nrm.png', optimize=True)
Image.fromarray(hgt, 'RGB').save(out + '/arm_hgt.png', optimize=True)

# finger table for the game (ArmFingers.java)
java = ['package com.harlequinxd.eryndor;', '',
        '/** Generated by tools/build_arms.py: each finger\'s knuckle pivot (u, z), direction (u, z) and',
        ' *  length in arm-grid units; right arm then left arm, thumb first. */',
        'final class ArmFingers {', '    private ArmFingers() { }', '',
        '    static final float[] PIVOT_DIR = {']
for f in fingers:
    java.append('            %.2ff, %.2ff, %.4ff, %.4ff,' % tuple(f[:4]))
java += ['    };', '', '    static final float[] LENGTH = {',
         '            ' + ', '.join('%.1ff' % f[4] for f in fingers), '    };', '}', '']
open(out + '/ArmFingers.java', 'w').write('\n'.join(java))
