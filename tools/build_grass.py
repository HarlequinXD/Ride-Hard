"""Grass texture set -> game assets. Makes colour, normal map and height map seamless with the same
cross-fade (each map blended with itself shifted half a tile, only near the borders, so all three
stay aligned), then writes 512x512 assets/grass/grass_col.png (RGB) and grass_nh.png
(R,G = normal x,y; B = height).
usage: build_grass.py colour.png normal.png height.png out_dir"""
import sys, numpy as np
from PIL import Image

col = np.array(Image.open(sys.argv[1]).convert('RGB')).astype(np.float64)
nrm = np.array(Image.open(sys.argv[2]).convert('RGB')).astype(np.float64)
hgt = np.array(Image.open(sys.argv[3]).convert('L')).astype(np.float64)
out = sys.argv[4]
N = min(col.shape[0], col.shape[1], nrm.shape[0], nrm.shape[1], hgt.shape[0], hgt.shape[1])
col, nrm, hgt = col[:N, :N], nrm[:N, :N], hgt[:N, :N]

BAND = N // 8
x = np.arange(N, dtype=np.float64)
edge = np.minimum(x, N - 1 - x)                       # distance to the nearest border
w1 = np.clip(edge / BAND, 0, 1)
w1 = w1 * w1 * (3 - 2 * w1)                            # 1 inside, 0 at the border
W = np.minimum.outer(w1, w1)                           # rows x cols

def seamless(a):
    sh = np.roll(np.roll(a, N // 2, 0), N // 2, 1)     # continuous across the wrap
    if a.ndim == 3:
        return a * W[..., None] + sh * (1 - W[..., None])
    return a * W + sh * (1 - W)

col = seamless(col)
n = seamless(nrm / 127.5 - 1.0)
n /= np.maximum(np.linalg.norm(n, axis=2, keepdims=True), 1e-6)
hgt = seamless(hgt)

S = 512
def down(a):
    return np.array(Image.fromarray(a.astype(np.float32)).resize((S, S), Image.LANCZOS)) if a.ndim == 2 else \
        np.dstack([np.array(Image.fromarray(a[..., c].astype(np.float32)).resize((S, S), Image.LANCZOS)) for c in range(a.shape[2])])
c5 = np.clip(down(col), 0, 255)
n5 = down(n)
n5 /= np.maximum(np.linalg.norm(n5, axis=2, keepdims=True), 1e-6)
h5 = down(hgt)
h5 = (h5 - h5.min()) / (h5.max() - h5.min()) * 255

Image.fromarray(c5.astype(np.uint8), 'RGB').save(out + '/grass_col.png', optimize=True)
nh = np.dstack([n5[..., 0] * 127.5 + 127.5, n5[..., 1] * 127.5 + 127.5, h5]).clip(0, 255).astype(np.uint8)
Image.fromarray(nh, 'RGB').save(out + '/grass_nh.png', optimize=True)
avg = c5.reshape(-1, 3).mean(0) / 255
print('average colour %.4f %.4f %.4f   mean height %.3f' % (avg[0], avg[1], avg[2], h5.mean() / 255))
# seam check: wrap difference vs. ordinary neighbour difference
for name, a in (('col', c5), ('nh', nh.astype(float))):
    inner = np.abs(np.diff(a, axis=1)).mean()
    seam = np.abs(a[:, 0] - a[:, -1]).mean()
    print('%s seam %.1f vs neighbour %.1f' % (name, seam, inner))
