"""Draws the app icon in the iOS 26 style: soft gradient tile, a glassy white symbol
that sits slightly above the tile (soft shadow + inner light + rim highlight).
Outputs light, dark and tinted 1024px PNGs (iOS masks the corners itself)."""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 1024
K = 4            # supersampling
W = S * K
OUT = sys.argv[1] if len(sys.argv) > 1 else "."


def lerp(a, b, t):
    return a + (b - a) * t


def gradient(top, bottom, size=W):
    t = np.linspace(0, 1, size)[:, None, None]
    top = np.array(top, float)[None, None, :]
    bottom = np.array(bottom, float)[None, None, :]
    g = top + (bottom - top) * t
    g = np.repeat(g, size, axis=1)
    return g


def radial(cx, cy, r, size=W):
    y, x = np.mgrid[0:size, 0:size].astype(float)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r
    return np.clip(1 - d, 0, 1)


def symbol_mask():
    """Ring + download arrow, as one white shape (L mode)."""
    m = Image.new("L", (W, W), 0)
    d = ImageDraw.Draw(m)
    c = W / 2
    # ring
    R, th = 0.335 * W, 0.062 * W
    d.ellipse([c - R, c - R + 0.01 * W, c + R, c + R + 0.01 * W], fill=255)
    d.ellipse([c - R + th, c - R + th + 0.01 * W, c + R - th, c + R - th + 0.01 * W], fill=0)
    # arrow shaft
    sw = 0.085 * W
    top, mid = c - 0.17 * W, c + 0.03 * W
    d.rounded_rectangle([c - sw / 2, top, c + sw / 2, mid + 0.02 * W], radius=sw / 2, fill=255)
    # arrow head (rounded by blur+threshold below)
    hw, hy, tip = 0.165 * W, mid - 0.035 * W, c + 0.165 * W
    d.polygon([(c - hw, hy), (c + hw, hy), (c, tip)], fill=255)
    # soften the corners a little: blur then threshold
    m = m.filter(ImageFilter.GaussianBlur(0.012 * W))
    m = m.point(lambda v: 255 if v > 128 else 0)
    return m.filter(ImageFilter.GaussianBlur(1.2 * K))


def render(bg_top, bg_bottom, sym_top, sym_bottom, glow_rgb, tinted=False):
    # ---- tile
    bg = gradient(bg_top, bg_bottom)
    hl = radial(W * 0.28, W * 0.12, W * 0.9)[..., None]
    bg = bg + (255 - bg) * hl * 0.16                    # soft light from the top-left
    sh = radial(W * 0.5, W * 1.15, W * 0.75)[..., None]
    bg = bg * (1 - sh * 0.18)                           # a little depth at the bottom
    tile = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

    mask = symbol_mask()

    # ---- shadow under the symbol so it "pops out"
    shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sm = mask.filter(ImageFilter.GaussianBlur(0.025 * W))
    sm = ImageChops.offset(sm, 0, int(0.018 * W)).point(lambda v: int(v * 0.32))
    shadow.putalpha(sm)
    tile = Image.alpha_composite(tile, shadow)

    # coloured glow around the glass, like light passing through it
    glow = Image.new("RGBA", (W, W), glow_rgb + (0,))
    gm = mask.filter(ImageFilter.GaussianBlur(0.03 * W)).point(lambda v: int(v * 0.35))
    glow.putalpha(gm)
    tile = Image.alpha_composite(tile, glow)

    # ---- glass body: vertical gradient
    body = Image.fromarray(np.clip(gradient(sym_top, sym_bottom), 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    # inner shading: darker toward the inside edge of the shape, lighter in the middle
    inner = mask.filter(ImageFilter.GaussianBlur(0.02 * W))
    edge = ImageChops.subtract(mask, inner)                  # bright near the edges
    edge_arr = np.array(edge, float)[..., None] / 255
    b = np.array(body, float)
    tint_edge = np.array(glow_rgb, float)[None, None, :]
    b[..., :3] = b[..., :3] * (1 - edge_arr * 0.22) + tint_edge * edge_arr * 0.22
    body = Image.fromarray(np.clip(b, 0, 255).astype(np.uint8), "RGBA")

    # top highlight: a crisp white sheen on the upper part of each stroke
    up = ImageChops.offset(mask, 0, int(0.012 * W))
    sheen = ImageChops.subtract(mask, up).filter(ImageFilter.GaussianBlur(0.004 * W))
    sheen_layer = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    sheen_layer.putalpha(sheen.point(lambda v: int(v * 0.9)))

    # bottom rim: thin darker line so the edge reads as glass thickness
    down = ImageChops.offset(mask, 0, -int(0.008 * W))
    rim = ImageChops.subtract(mask, down).filter(ImageFilter.GaussianBlur(0.003 * W))
    rim_layer = Image.new("RGBA", (W, W), tuple(int(c * 0.75) for c in glow_rgb) + (0,))
    rim_layer.putalpha(rim.point(lambda v: int(v * 0.45)))

    body.putalpha(mask)
    tile = Image.alpha_composite(tile, body)
    tile = Image.alpha_composite(tile, rim_layer)
    tile = Image.alpha_composite(tile, sheen_layer)

    # specular: soft diagonal gloss on the top half of the symbol only
    y, x = np.mgrid[0:W, 0:W].astype(float)
    gloss = np.clip(1 - (y / W - 0.18) / 0.32, 0, 1) ** 1.6 * 0.35
    gl = Image.fromarray((gloss * 255).astype(np.uint8), "L")
    gl = ImageChops.multiply(gl, mask)
    gloss_layer = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    gloss_layer.putalpha(gl)
    tile = Image.alpha_composite(tile, gloss_layer)

    out = tile.resize((S, S), Image.LANCZOS).convert("RGB")
    if tinted:
        out = out.convert("L").convert("RGB")
    return out


if __name__ == "__main__":
    light = render((255, 78, 72), (214, 18, 30), (255, 255, 255), (255, 228, 228), (255, 120, 110))
    light.save(f"{OUT}/icon-light.png")
    dark = render((48, 18, 20), (18, 6, 8), (255, 112, 104), (226, 30, 40), (255, 70, 60))
    dark.save(f"{OUT}/icon-dark.png")
    tinted = render((40, 40, 40), (10, 10, 10), (255, 255, 255), (200, 200, 200), (160, 160, 160), tinted=True)
    tinted.save(f"{OUT}/icon-tinted.png")
    # preview with the iOS corner mask, side by side
    prev = Image.new("RGB", (3 * 360 + 80, 400), (242, 242, 247))
    for i, im in enumerate([light, dark, tinted]):
        sm = im.resize((360, 360), Image.LANCZOS)
        mk = Image.new("L", (360 * 4, 360 * 4), 0)
        ImageDraw.Draw(mk).rounded_rectangle([0, 0, 360 * 4 - 1, 360 * 4 - 1], radius=int(360 * 4 * 0.225), fill=255)
        mk = mk.resize((360, 360), Image.LANCZOS)
        prev.paste(sm, (20 + i * 380, 20), mk)
    prev.save(f"{OUT}/preview.png")
