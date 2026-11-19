"""contexTO brand toolkit: palette, glyph outlines, sentence-contour geometry."""
import math
import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONTS = os.path.join(ROOT, "app", "src", "main", "res", "font")
# Noto Serif SC / Noto Sans SC (OFL) are only needed to outline Chinese text; see README.md here.
CJK = os.environ.get("NOTO_CJK_DIR", os.path.join(ROOT, "build", "brand-fonts"))

# Palette — taken from the app's own Palette(dark/light) in MainActivity.kt
LIGHT = dict(paper="#F2F0EA", ink="#262B29", muted="#66716E", word="#5D7B72",
             paragraph="#A87562", purple="#897999", gold="#A48656")
DARK = dict(paper="#252525", ink="#E8E7E1", muted="#ACAEAA", word="#9FBEAF",
            paragraph="#D2A18C", purple="#B9A7CA", gold="#D6BF91")

_fonts = {}


def font(name):
    if name not in _fonts:
        path = {"regular": f"{FONTS}/tinos_regular.ttf", "bold": f"{FONTS}/tinos_bold.ttf",
                "cjk": f"{CJK}/NotoSerifSC-Regular.otf", "cjk-bold": f"{CJK}/NotoSerifSC.otf", "sans": f"{CJK}/NotoSansSC-Regular.otf"}[name]
        _fonts[name] = TTFont(path)
    return _fonts[name]


def text_path(text, size, x=0.0, y=0.0, face="regular", tracking=0.0, small_caps=None, true_case=False):
    """SVG path data for `text` with baseline at y. small_caps: ratio for uiSmallCaps-style
    letters (uppercase everything, non-initial letters scaled). Returns (d, advance)."""
    f = font(face)
    gs = f.getGlyphSet()
    cmap = f.getBestCmap()
    upm = f["head"].unitsPerEm
    pen = SVGPathPen(gs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    cursor = x
    prev = " "
    src = text.upper() if small_caps else text
    for idx, ch in enumerate(src):
        s = size
        if small_caps and true_case and text[idx].islower():
            s = size * small_caps
        elif small_caps and not true_case and ch.isalpha() and (prev.isalpha() or prev in "'’"):
            s = size * small_caps
        prev = ch
        gname = cmap.get(ord(ch))
        if gname is None:
            raise KeyError(f"glyph missing for {ch!r} in {face}")
        k = s / upm
        tp = TransformPen(pen, (k, 0, 0, -k, cursor, y))
        gs[gname].draw(tp)
        cursor += gs[gname].width * k + tracking * size
    return pen.getCommands(), cursor - x - tracking * size


def text_width(text, size, face="regular", tracking=0.0, small_caps=None, true_case=False):
    return text_path(text, size, 0, 0, face, tracking, small_caps, true_case)[1]


def rounded_polygon(points, radii, fmt="{:.2f}"):
    """Closed polygon with each corner rounded by a circular-ish cubic of radius r."""
    n = len(points)
    if isinstance(radii, (int, float)):
        radii = [radii] * n
    f = lambda v: fmt.format(v).rstrip("0").rstrip(".")
    parts = []
    k = 0.5523
    for i in range(n):
        p0, p1, p2 = points[i - 1], points[i], points[(i + 1) % n]
        r = radii[i]
        d1 = math.dist(p0, p1); d2 = math.dist(p1, p2)
        r = min(r, d1 / 2, d2 / 2)
        u1 = ((p0[0] - p1[0]) / d1, (p0[1] - p1[1]) / d1)
        u2 = ((p2[0] - p1[0]) / d2, (p2[1] - p1[1]) / d2)
        a = (p1[0] + u1[0] * r, p1[1] + u1[1] * r)
        b = (p1[0] + u2[0] * r, p1[1] + u2[1] * r)
        c1 = (a[0] - u1[0] * r * k, a[1] - u1[1] * r * k)
        c2 = (b[0] - u2[0] * r * k, b[1] - u2[1] * r * k)
        parts.append(("M" if i == 0 else "L") + f"{f(a[0])} {f(a[1])}")
        parts.append(f"C{f(c1[0])} {f(c1[1])} {f(c2[0])} {f(c2[1])} {f(b[0])} {f(b[1])}")
    return "".join(parts) + "Z"


def sentence_contour(start, left, right, end, top, line_h, lines=3, r_out=3.0, r_in=2.0):
    """The app's bookmark shape: a sentence starting at x=start on the first line and ending
    at x=end on the last line, wrapping full lines [left, right] between."""
    y0 = top
    y1 = top + line_h
    yl = top + line_h * (lines - 1)
    yb = top + line_h * lines
    pts = [(start, y0), (right, y0), (right, yl), (end, yl), (end, yb), (left, yb), (left, y1), (start, y1)]
    rad = [r_out, r_out, r_out, r_in, r_out, r_out, r_out, r_in]
    return rounded_polygon(pts, rad)


def rrect(x, y, w, h, r):
    return rounded_polygon([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], r)
