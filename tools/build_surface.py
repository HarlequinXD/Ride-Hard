"""Ground texture set (colour, normal map, height map) -> game assets. Makes all three seamless with
the same cross-fade (each map blended with itself shifted half a tile, only near the borders, so they
stay aligned), then writes 512x512 <out_dir>/<name>_col.png (RGB) and <name>_nh.png
(R,G = normal x,y; B = height). Normal maps are OpenGL style (green = up in the image).
usage: build_surface.py name colour.png normal.png height.png out_dir
   e.g. build_surface.py grass Grass.png GrassNormal.png GrassHeight.png app/src/main/assets/surfaces"""
import sys, numpy as np
from PIL import Image

name = sys.argv[1]
col = np.array(Image.open(sys.argv[2]).convert('RGB')).astype(np.float64)
nrm = np.array(Image.open(sys.argv[3]).convert('RGB')).astype(np.float64)
hgt = np.array(Image.open(sys.argv[4]).convert('L')).astype(np.float64)
out = sys.argv[5]
N = min(col.shape[0], col.shape[1], nrm.shape[0], nrm.shape[1], hgt.shape[0], hgt.shape[1])
col, nrm, hgt = col[:N, :N], nrm[:N, :N], hgt[:N, :N]

# ---- seamless: S = the maps shifted half a tile, which is continuous across the wrap. Keep the
#      original A in the middle and S near the borders, switching along the cheapest cut through
#      each border band (where A and S agree, preferring low ground such as mortar between stones),
#      the same cut for all three maps so they stay aligned.
BAND = N // 6
def shifted(a):
    return np.roll(np.roll(a, N // 2, 0), N // 2, 1)

nA = nrm / 127.5 - 1.0
cS, nS, hS = shifted(col), shifted(nA), shifted(hgt)
diff = (np.abs(col - cS).mean(2) / 255.0 + np.abs(nA - nS).mean(2) * 0.5 + np.abs(hgt - hS) / 255.0)
low = (hgt + hS) / 510.0                                # prefer cutting through low ground
cost = diff + 0.35 * low

def cut(c):
    """cheapest top-to-bottom path through cost c (rows x cols); returns its column per row"""
    h, w = c.shape
    acc = c.copy()
    back = np.zeros((h, w), np.int64)
    for y in range(1, h):
        prev = acc[y - 1]
        left = np.concatenate(([np.inf], prev[:-1]))
        right = np.concatenate((prev[1:], [np.inf]))
        stack = np.stack([left, prev, right])
        k = np.argmin(stack, 0)
        acc[y] += stack[k, np.arange(w)]
        back[y] = np.arange(w) + k - 1
    path = np.zeros(h, np.int64)
    path[-1] = int(np.argmin(acc[-1]))
    for y in range(h - 1, 0, -1):
        path[y - 1] = back[y, path[y]]
    return path

lo, hi = 3, BAND                                        # never at the very edge: that stays S
xl = cut(cost[:, lo:hi]) + lo                           # left band
xr = cut(cost[:, N - hi:N - lo]) + N - hi               # right band
yt = cut(cost[lo:hi, :].T) + lo                         # top band
yb = cut(cost[N - hi:N - lo, :].T) + N - hi             # bottom band
yy, xx = np.mgrid[0:N, 0:N]
M = ((xx > xl[:, None]) & (xx < xr[:, None]) & (yy > yt[None, :]) & (yy < yb[None, :])).astype(np.float64)
from PIL import ImageFilter
M = np.array(Image.fromarray((M * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))).astype(np.float64) / 255

col = col * M[..., None] + cS * (1 - M[..., None])
n = nA * M[..., None] + nS * (1 - M[..., None])
n /= np.maximum(np.linalg.norm(n, axis=2, keepdims=True), 1e-6)
hgt = hgt * M + hS * (1 - M)

S = 512
def down(a):
    return np.array(Image.fromarray(a.astype(np.float32)).resize((S, S), Image.LANCZOS)) if a.ndim == 2 else \
        np.dstack([np.array(Image.fromarray(a[..., c].astype(np.float32)).resize((S, S), Image.LANCZOS)) for c in range(a.shape[2])])
c5 = np.clip(down(col), 0, 255)
n5 = down(n)
n5 /= np.maximum(np.linalg.norm(n5, axis=2, keepdims=True), 1e-6)
h5 = down(hgt)
h5 = (h5 - h5.min()) / (h5.max() - h5.min()) * 255

Image.fromarray(c5.astype(np.uint8), 'RGB').save(out + '/' + name + '_col.png', optimize=True)
nh = np.dstack([n5[..., 0] * 127.5 + 127.5, n5[..., 1] * 127.5 + 127.5, h5]).clip(0, 255).astype(np.uint8)
Image.fromarray(nh, 'RGB').save(out + '/' + name + '_nh.png', optimize=True)
avg = c5.reshape(-1, 3).mean(0) / 255
print('average colour %.4f %.4f %.4f   mean height %.3f' % (avg[0], avg[1], avg[2], h5.mean() / 255))
# seam check: wrap difference vs. ordinary neighbour difference
for name, a in (('col', c5), ('nh', nh.astype(float))):
    inner = np.abs(np.diff(a, axis=1)).mean()
    seam = np.abs(a[:, 0] - a[:, -1]).mean()
    print('%s seam %.1f vs neighbour %.1f' % (name, seam, inner))
