"""Split rustedwax_mark.png into a spinning disc and a static overlay.

The badge is a circle, so anything rotationally symmetric about its centre --
the grooves, the rings, the flat of the pink label -- looks identical at every
angle. Rotating that alone would be an animation nobody can see. So the split
is not "symmetric vs not"; it is **record vs furniture**:

  spins   the vinyl: grooves, rings, the pink label and the gloss on it, the
          sparkles printed on the record
  static  the furniture sitting on top of it: the RustedWax lettering and its
          pink stripes, the tonearm, and the wax drips

`sym(r)` -- the per-radius median of the source -- is used to fill the hole the
furniture leaves in the disc layer. It is a median rather than a mean because
the lettering covers a wide arc at some radii and a mean would drag the groove
colour toward it; a median needs a decoration to own more than half the
circumference before it wins, and none of them do.

Composited at 0 degrees the two layers reproduce the source exactly, which is
the check at the bottom of this file.
"""

import numpy as np
from PIL import Image

SRC = "<redacted-local-path>/Desktop/git/rustedwax/app/src/main/res/drawable-nodpi/rustedwax_mark.png"
OUT = "<redacted-local-path>/Desktop/git/rustedwax/app/src/main/res/drawable-nodpi"

src = np.asarray(Image.open(SRC).convert("RGBA")).astype(np.float32)
h, w, _ = src.shape
cy = cx = (h - 1) / 2.0
Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
R = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)

# ── the symmetric disc, used only to fill what the furniture hides ────────
rmax = int(np.ceil(R.max())) + 1
bins = np.clip(R.astype(np.int32), 0, rmax - 1)
prof = np.zeros((rmax, 4), np.float32)
for r in range(rmax):
    m = bins == r
    if m.any():
        prof[r] = np.median(src[m], axis=0)
k = np.array([1.0, 2, 3, 2, 1])
k /= k.sum()
smooth = np.stack([np.convolve(prof[:, c], k, mode="same") for c in range(4)], axis=1)
smooth[138:] = prof[138:]  # don't smooth across the rim's alpha cliff
sym = smooth[bins]


def premul(x):
    return x[..., :3] * (x[..., 3:4] / 255.0)


# How far each pixel is from "plain record at this radius".
diff = np.abs(premul(src) - premul(sym)).max(axis=2)
diff = np.maximum(diff, np.abs(src[..., 3] - sym[..., 3]))

# ── where the furniture is ───────────────────────────────────────────────
def band(x_at, y_top, y_bot, slope):
    """The lettering runs as a straight diagonal ribbon across the badge."""
    top = y_top + slope * (X - x_at)
    bot = y_bot + slope * (X - x_at)
    return (Y >= top) & (Y <= bot)


def near_segment(x0, y0, x1, y1, width):
    dx, dy = x1 - x0, y1 - y0
    t = np.clip(((X - x0) * dx + (Y - y0) * dy) / (dx * dx + dy * dy), 0.0, 1.0)
    return np.hypot(X - (x0 + t * dx), Y - (y0 + t * dy)) <= width


def grow(m, n=2):
    out = m.copy()
    for _ in range(n):
        p = np.pad(out, 1, constant_values=False)
        out = p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return out


r_, g_, b_ = src[..., 0], src[..., 1], src[..., 2]
cream = (r_ > 190) & (g_ > 165) & (b_ > 110)
inked = np.maximum(np.maximum(r_, g_), b_) < 70
stripe = (r_ < 190) & (g_ < 100) & (b_ > 40)

LETTERING = band(200, 84, 154, -0.20)
# The band's top edge clips the gloss on the pink label, and that gloss is most
# of what makes the spin legible — a groove pattern is a solid of revolution
# and turning it is an animation nobody can see. No lettering reaches this far
# up, so cutting the corner out costs nothing.
GLOSS = (X >= 116) & (X <= 156) & (Y >= 84) & (Y <= 118)
LETTERING = LETTERING & ~GLOSS

# The wax is the only cream that isn't the rim ring, and the ring is symmetric,
# so it never registers as a difference in the first place. Boxes just say
# which cream to take; grown a little to carry each drip's dark outline with it.
WAX_ZONES = (
    near_segment(30, 152, 102, 40, 36)
    | near_segment(58, 40, 100, 96, 32)
    | ((X >= 44) & (X <= 88) & (Y >= 162) & (Y <= 226))
    | ((Y >= 190) & (X >= 82) & (X <= 272))
    | ((X >= 206) & (X <= 276) & (Y >= 164) & (Y <= 250))
)
WAX = grow(cream & WAX_ZONES & (diff > 14), 2) & WAX_ZONES

# The rim — both rings, the drips hanging over them, and the badge edge — is
# static in one piece. Not a design decision: those rings are drawn a hair
# off-centre, so some of each ring reads as a difference and some does not.
# Split that way the same ring ends up in both layers and tears itself apart as
# the disc turns. A circle looks identical stationary anyway, so the whole
# annulus goes to the overlay and the argument disappears.
RIM = R >= 122

furniture = (LETTERING | WAX | RIM) & (R <= 146)

# Soft edge so letter antialiasing doesn't come out as a hard stair-step.
LO, HI = 14.0, 40.0
S = np.clip((diff - LO) / (HI - LO), 0.0, 1.0) * furniture
S = S[..., None]

# ── filling the hole the furniture leaves ────────────────────────────────
# The record is not actually a solid of revolution: it is drawn in slight
# perspective and lit from one side, so `sym` alone fills the hole with grooves
# of the right shape but the wrong brightness, and the patch reads as a bar
# across the disc. Split the difference instead:
#
#   grooves   sym(r), which is exactly right and must survive untouched
#   shading   src - sym, a smooth field with no groove content left in it
#
# Only the shading needs inventing, and a smooth field is the one thing
# diffusion from the surrounding pixels reconstructs well.
hole = np.asarray(grow(furniture, 3) & (R <= 143))
shade = src[..., :3] - sym[..., :3]
known = ~hole & (R <= 145)
fill = np.where(known[..., None], shade, 0.0)
weight = known.astype(np.float32)
for _ in range(2500):
    fs = (
        np.roll(fill, 1, 0) + np.roll(fill, -1, 0) + np.roll(fill, 1, 1) + np.roll(fill, -1, 1)
    )
    ws = (
        np.roll(weight, 1, 0)
        + np.roll(weight, -1, 0)
        + np.roll(weight, 1, 1)
        + np.roll(weight, -1, 1)
    )
    avg = fs / np.maximum(ws, 1e-6)[..., None]
    grew = ws > 0
    fill = np.where(known[..., None], shade, np.where(grew[..., None], avg, fill))
    weight = np.where(known, 1.0, np.where(grew, 1.0, weight))
patch = np.clip(sym[..., :3] + fill, 0, 255)

# ── the two layers ───────────────────────────────────────────────────────
# disc: the source with the furniture lifted out and the hole filled in.
disc_rgb = src[..., :3] * (1 - S) + patch * S
disc_a = src[..., 3:4] * (1 - S) + sym[..., 3:4] * S
disc = np.concatenate([disc_rgb, disc_a], axis=2)

# overlay: solve S*c + (1-S)*disc == src, so the pair recomposites exactly.
safe = np.maximum(S, 1e-3)
c = np.where(S > 0.02, (src[..., :3] - (1 - S) * disc_rgb) / safe, src[..., :3])
overlay = np.concatenate([np.clip(c, 0, 255), (S[..., 0] * src[..., 3])[..., None]], axis=2)

Image.fromarray(disc.round().clip(0, 255).astype(np.uint8)).save(f"{OUT}/rustedwax_disc.png")
Image.fromarray(overlay.round().clip(0, 255).astype(np.uint8)).save(
    f"{OUT}/rustedwax_lettering.png"
)

# ── the check ────────────────────────────────────────────────────────────
oa = overlay[..., 3:4] / 255.0
recomposed = overlay[..., :3] * oa + disc_rgb * (1 - oa)
visible = src[..., 3] > 8
err = np.abs(recomposed - src[..., :3])[visible]
print("recomposite max channel error:", round(float(err.max()), 2), "mean:", round(float(err.mean()), 3))
print("static share of the badge:", round(float((S[..., 0] > 0.5).sum() / visible.sum()) * 100, 1), "%")
