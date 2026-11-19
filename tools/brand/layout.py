"""Tiny typesetter: greedy line breaking with Tinos metrics, emitting outlined SVG paths."""
from brand import text_path, text_width, rounded_polygon


def typeset(text, size, width, x0, y0, leading, face="regular", bold_at=()):
    """Returns lines: [(baseline_y, [(word, x, w, char_start), ...]), ...]"""
    space = text_width(" ", size, face)
    lines, cur, x, pos = [], [], 0.0, 0
    for word in text.split(" "):
        w = text_width(word, size, "bold" if pos in bold_at else face)
        if cur and x + w > width:
            lines.append(cur); cur, x = [], 0.0
        cur.append((word, x0 + x, w, pos))
        x += w + space
        pos += len(word) + 1
    if cur:
        lines.append(cur)
    return [(y0 + i * leading, ln) for i, ln in enumerate(lines)]


def words_path(lines, size, face="regular", pick=None):
    """Outline every word (or those where pick(word, start) is true)."""
    out = []
    for base, ln in lines:
        for word, x, w, start in ln:
            if pick is None or pick(word, start):
                out.append(text_path(word, size, x, base, face)[0])
    return "".join(out)


def sentence_outline(lines, span, size, leading, pad_x, r=None, asc=0.80, desc=0.34):
    """Contour wrapping each line's own extent of characters [span[0], span[1]) — the app's bookmark."""
    a, b = span
    rows = []
    for base, ln in lines:
        hit = [(x, w) for word, x, w, s in ln if s < b and s + len(word) > a]
        if hit:
            band_top = base - size * asc - (leading - size * (asc + desc)) / 2
            rows.append((min(x for x, _ in hit) - pad_x, max(x + w for x, w in hit) + pad_x, band_top, band_top + leading))
    pts = []
    for l, rr, t, bt in rows:
        pts += [(rr, t), (rr, bt)]
    for l, rr, t, bt in reversed(rows):
        pts += [(l, bt), (l, t)]
    # drop collinear duplicates where consecutive rows share an edge
    clean = []
    for p in pts:
        if clean and abs(clean[-1][0] - p[0]) < 1e-6 and abs(clean[-1][1] - p[1]) < 1e-6:
            continue
        clean.append(p)
    return rounded_polygon(clean, r if r is not None else leading * 0.22)
