"""Generate every contexTO brand asset from one geometry source.

    python tools/brand/build_brand.py            # writes app/src/main/res/... and docs/brand/*.svg

Needs fontTools and Noto Serif SC / Noto Sans SC (see tools/brand/README.md). PNG exports are
rendered from the SVGs afterwards; every shape here is outlined, so any SVG renderer gives the same result.
"""
import os
import sys
from brand import LIGHT, DARK, ROOT, text_path, rrect, sentence_contour
from layout import typeset, words_path, sentence_outline

OUT = sys.argv[1] if len(sys.argv) > 1 else ROOT
BR = f"{OUT}/docs/brand"
RES = f"{OUT}/app/src/main/res"
for d in [BR, f"{BR}/glyphs", f"{RES}/drawable", f"{RES}/mipmap-anydpi", f"{RES}/values", f"{RES}/values-night"]:
    os.makedirs(d, exist_ok=True)

PINE, PAPER, INK, CLAY = LIGHT["word"], LIGHT["paper"], LIGHT["ink"], LIGHT["paragraph"]


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text.strip() + "\n")


# ---------------------------------------------------------------- icon
# 108×108 adaptive canvas; the 66dp safe circle is centred at 54,54. Three lines of "words" (bars),
# the bookmark contour wrapping the sentence that starts mid-line 1 and ends mid-line 3, one word looked up.
ROWS = [[14, 17], [10, 13, 12], [19, 11]]   # relative word widths per line, justified
LEFT, RIGHT, TOP, LINE = 27.0, 81.0, 33.0, 14.0
BAR, GAP, PAD, BAR_R = 5.6, 3.4, 3.8, 1.4
SENTENCE_FROM, SENTENCE_TO, QUERIED = (0, 1), (2, 0), (1, 1)   # (line, word)
SQUIRCLE = "M54 18C84 18 90 24 90 54C90 84 84 90 54 90C24 90 18 84 18 54C18 24 24 18 54 18Z"


def icon_geometry():
    geo = []
    for r, widths in enumerate(ROWS):
        inner = RIGHT - LEFT - 2 * PAD
        k = (inner - GAP * (len(widths) - 1)) / sum(widths)
        x, cy = LEFT + PAD, TOP + LINE * r + LINE / 2 + 0.6
        row = []
        for w in widths:
            row.append((x, cy - BAR / 2, w * k, BAR))
            x += w * k + GAP
        geo.append(row)
    (r0, w0), (r1, w1) = SENTENCE_FROM, SENTENCE_TO
    start = geo[r0][w0][0] - PAD
    end = geo[r1][w1][0] + geo[r1][w1][2] + PAD
    contour = sentence_contour(start, LEFT, RIGHT, end, TOP, LINE, 3, r_out=4.4, r_in=2.4)
    inside, outside, queried = [], [], None
    for r, row in enumerate(geo):
        for i, (x, y, w, h) in enumerate(row):
            d = rrect(x, y, w, h, BAR_R)
            if (r, i) == QUERIED:
                queried = d
            elif (r, i) >= SENTENCE_FROM and (r, i) <= SENTENCE_TO:
                inside.append(d)
            else:
                outside.append(d)
    return contour, "".join(inside), "".join(outside), queried


contour, inside, outside, queried = icon_geometry()


def icon_layers():
    return (f'<rect width="108" height="108" fill="{PINE}"/>'
            f'<path d="{contour}" fill="{PAPER}"/>'
            f'<path d="{outside}" fill="{PAPER}" fill-opacity="0.45"/>'
            f'<path d="{inside}" fill="{INK}"/>'
            f'<path d="{queried}" fill="{CLAY}"/>')


def icon_at(x, y, px, uid):
    return (f'<svg x="{x}" y="{y}" width="{px}" height="{px}" viewBox="18 18 72 72">'
            f'<defs><clipPath id="{uid}"><path d="{SQUIRCLE}"/></clipPath></defs><g clip-path="url(#{uid})">{icon_layers()}</g></svg>')


def svg(w, h, body, vb=None):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb or f"0 0 {w} {h}"}" width="{w}" height="{h}">{body}</svg>'


# full-bleed master (108 canvas, as the launcher sees it before masking)
write(f"{BR}/icon-adaptive-108.svg", svg(108, 108, icon_layers()))
# masked presentational versions (visible 72dp viewport)
write(f"{BR}/icon.svg", svg(512, 512, f'<defs><clipPath id="m"><path d="{SQUIRCLE}"/></clipPath></defs><g clip-path="url(#m)">{icon_layers()}</g>', "18 18 72 72"))
write(f"{BR}/icon-round.svg", svg(512, 512, f'<defs><clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#m)">{icon_layers()}</g>', "18 18 72 72"))
# Play Store wants a 512 square, unmasked (Play applies its own mask)
write(f"{BR}/icon-play-512.svg", svg(512, 512, icon_layers(), "18 18 72 72"))


def vd(paths, w=108, h=108):
    body = "\n".join(paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{w}"
    android:viewportHeight="{h}">
{body}
</vector>'''


def vpath(d, color, alpha=None, even_odd=False):
    extra = f'\n        android:fillAlpha="{alpha}"' if alpha is not None else ""
    if even_odd:
        extra += '\n        android:fillType="evenOdd"'
    return f'''    <path
        android:fillColor="{color}"{extra}
        android:pathData="{d}" />'''


write(f"{RES}/drawable/ic_launcher_foreground.xml", vd([
    "    <!-- The reading-page bookmark: one sentence wrapped across three lines, one word looked up. -->",
    vpath(contour, PAPER), vpath(outside, PAPER, "0.45"), vpath(inside, INK), vpath(queried, CLAY)]))
write(f"{RES}/drawable/ic_launcher_monochrome.xml", vd([
    "    <!-- Themed-icon layer: the system tints alpha only, so words are cut out of the contour. -->",
    vpath(contour + inside + queried, "#FFFFFFFF", even_odd=True), vpath(outside, "#FFFFFFFF", "0.5")]))
write(f"{RES}/values/ic_launcher_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{PINE}</color>
</resources>''')
adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>'''
write(f"{RES}/mipmap-anydpi/ic_launcher.xml", adaptive)
write(f"{RES}/mipmap-anydpi/ic_launcher_round.xml", adaptive)

# splash: matches the app's own paper / dark paper so there is no white flash before Compose draws
theme = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ContextoTO" parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/contexto_paper</item>
        <item name="android:windowSplashScreenBackground">@color/contexto_paper</item>
    </style>
</resources>'''
write(f"{RES}/values/themes.xml", theme)
write(f"{RES}/values/colors.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Same values as palette(dark = false) in MainActivity.kt; used before Compose draws. -->
    <color name="contexto_paper">{LIGHT["paper"]}</color>
</resources>''')
write(f"{RES}/values-night/colors.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Same values as palette(dark = true) in MainActivity.kt. -->
    <color name="contexto_paper">{DARK["paper"]}</color>
</resources>''')

# ---------------------------------------------------------------- text
# There is no wordmark: the icon is the only logo. Where the name appears it is plain Tinos Regular, as text.
NAME = "contexTO"
SC = 0.78  # uiSmallCaps ratio used across the app


def zh(text, size, x, y, color, face="cjk", tracking=0.0, opacity=None):
    d, w = text_path(text, size, x, y, face, tracking)
    op = f' fill-opacity="{opacity}"' if opacity else ""
    return f'<path d="{d}" fill="{color}"{op}/>', w


# ---------------------------------------------------------------- article excerpt (real N01 text)
N01 = ("The attack on a merchant vessel in Omani waters revived a conflict that a fragile memorandum of "
       "understanding had been meant to end. Within days the United States resumed its naval blockade of "
       "Iranian ports and promised to increase economic pressure. The sixty-day period that the memorandum "
       "allowed for a final agreement on the nuclear question then expired without result.")
S2 = (N01.index("Within"), N01.index("pressure.") + len("pressure."))
Q = N01.index("blockade")


def excerpt(pal, x, y, width, size, leading, lines_max=None, contour_fill=None, contour_alpha=None,
            outside_color=None, outside_alpha=None, label=True):
    lines = typeset(N01, size, width, x, y, leading, bold_at=(Q,))
    if lines_max:
        lines = lines[:lines_max]
    c = sentence_outline(lines, S2, size, leading, pad_x=size * 0.2, r=leading * 0.22)
    in_s = lambda w, s: S2[0] <= s < S2[1] and s != Q
    out_s = lambda w, s: not (S2[0] <= s < S2[1])
    parts = [f'<path d="{c}" fill="{contour_fill or pal["word"]}" fill-opacity="{contour_alpha if contour_alpha is not None else 0.16}"/>',
             f'<path d="{words_path(lines, size, pick=out_s)}" fill="{outside_color or pal["ink"]}" fill-opacity="{outside_alpha if outside_alpha is not None else 0.5}"/>',
             f'<path d="{words_path(lines, size, pick=in_s)}" fill="{pal["ink"]}"/>',
             f'<path d="{words_path(lines, size, "bold", pick=lambda w, s: s == Q)}" fill="{pal["paragraph"]}"/>']
    if label:
        lp, _ = text_path("Reading / 1 of 48", size * 0.5, x, y - leading * 1.15, "bold", 0.05, SC)
        parts.append(f'<path d="{lp}" fill="{pal["muted"]}"/>')
    return "".join(parts), lines


# README banners (1280×400); the README heading right below carries the name
for name, pal in [("light", LIGHT), ("dark", DARK)]:
    W, H = 1280, 400
    parts = [f'<rect width="{W}" height="{H}" fill="{pal["paper"]}"/>', icon_at(72, 92, 128, "bm")]
    t, _ = zh("词在句中，句在篇中。", 22, 74, 290, pal["muted"], "cjk", 0.04)
    parts.append(t)
    ex, _ = excerpt(pal, 560, 150, 640, 27, 46, lines_max=5)
    parts.append(ex)
    write(f"{BR}/banner-{name}.svg", svg(W, H, "".join(parts)))


# Social preview (GitHub 1280×640) and Play feature graphic (1024×500): the icon, zoomed until the bars are words.
def hero(W, H, x, y, width, size, leading, lines_max, name_size, name_x, name_y):
    pal = dict(LIGHT)
    parts = [f'<rect width="{W}" height="{H}" fill="{PINE}"/>']
    ex, _ = excerpt(pal, x, y, width, size, leading, lines_max, contour_fill=PAPER, contour_alpha=1,
                    outside_color=PAPER, outside_alpha=0.5, label=False)
    parts.append(ex)
    d, _ = text_path(NAME, name_size, name_x, name_y, "regular")
    parts.append(f'<path d="{d}" fill="{PAPER}"/>')
    z, _ = zh("词在句中，句在篇中", name_size * 0.4, name_x + 2, name_y + name_size * 0.72, PAPER, "cjk", 0.06, opacity=".8")
    parts.append(z)
    return svg(W, H, "".join(parts))


write(f"{BR}/social-preview.svg", hero(1280, 640, 96, 118, 1090, 40, 66, 5, 60, 98, 528))
write(f"{BR}/feature-graphic.svg", hero(1024, 500, 72, 84, 850, 32, 52, 5, 46, 74, 420))


# ---------------------------------------------------------------- previews for the docs
def masked(inner, shape, px):
    # crop to the visible 72dp viewport (18..90) and mask
    clip = {"circle": '<circle cx="54" cy="54" r="36"/>', "squircle": f'<path d="{SQUIRCLE}"/>',
            "rounded": '<rect x="18" y="18" width="72" height="72" rx="16"/>'}[shape]
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{px}" height="{px}"><defs><clipPath id="m{shape}{px}">{clip}</clipPath></defs><g clip-path="url(#m{shape}{px})">{inner}</g></svg>'


inner = icon_layers()
cells = []
for wall in ["#D9D6CF", "#1B1D1C"]:
    row = ""
    for x, shape, px in [(32, "circle", 192), (256, "squircle", 192), (480, "rounded", 120), (632, "circle", 72), (736, "squircle", 48), (816, "circle", 36)]:
        cell = masked(inner, shape, px).replace("<svg ", '<svg y="%d" ' % ((192 - px) // 2 + 24), 1)
        row += f'<g transform="translate({x} 0)">{cell}</g>'
    cells.append(f'<rect width="896" height="240" fill="{wall}"/>{row}')
write(f"{BR}/icon-masks.svg", svg(896, 480, f'<g>{cells[0]}</g><g transform="translate(0 240)">{cells[1]}</g>'))

# Android 12+ splash: adaptive icon inside a 160dp circle on the window background, shown at 1:1 dp on a 412dp-wide phone
for name, pal in [("light", LIGHT), ("dark", DARK)]:
    W, H = 412, 892
    ic = (f'<svg x="{W / 2 - 80}" y="{H / 2 - 80}" width="160" height="160" viewBox="18 18 72 72">'
          f'<defs><clipPath id="sp{name}"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#sp{name})">{inner}</g></svg>')
    write(f"{BR}/splash-{name}.svg", svg(W, H, f'<rect width="{W}" height="{H}" rx="36" fill="{pal["paper"]}"/>{ic}'))


# ================================================================ palette, type scale, glyphs
def is_cjk(ch):
    return ord(ch) > 0x2E7F


def mixed(text, size, x, y, color, latin="regular", cjk="cjk", opacity=None, small_caps=None, tracking=0.0):
    """Outline text whose Latin runs use Tinos and CJK runs use Noto Serif/Sans SC."""
    runs, cur, cur_cjk = [], "", None
    for ch in text:
        c = is_cjk(ch) if ch != " " else (cur_cjk if cur_cjk is not None else False)
        if cur and c != cur_cjk:
            runs.append((cur, cur_cjk)); cur = ""
        cur += ch; cur_cjk = c
    if cur:
        runs.append((cur, cur_cjk))
    ds, cx = [], x
    for run, c in runs:
        d, w = text_path(run, size, cx, y, cjk if c else latin, 0 if c else tracking, None if c else small_caps)
        ds.append(d); cx += w
    op = f' fill-opacity="{opacity}"' if opacity else ""
    return f'<path d="{"".join(ds)}" fill="{color}"{op}/>', cx - x


def lum(hexc):
    r, g, b = (int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5))
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def contrast(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True)
    return (la + 0.05) / (lb + 0.05)


# ---------------------------------------------------------------- palette
ROLES = [("paper", "纸", "Paper"), ("ink", "墨", "Ink"), ("muted", "次", "Muted"), ("word", "松", "Pine · word"),
         ("paragraph", "陶", "Clay · sentence"), ("purple", "藤", "Wisteria · clause"), ("gold", "赭", "Ochre · clause")]


def palette_sheet():
    W, H = 1280, 560
    parts = [f'<rect width="{W}" height="{H}" fill="#DCD9D2"/>']
    for row, (name, pal) in enumerate([("LIGHT", LIGHT), ("DARK", DARK)]):
        y0 = 40 + row * 260
        parts.append(f'<rect x="32" y="{y0}" width="{W - 64}" height="236" rx="18" fill="{pal["paper"]}"/>')
        p, _ = mixed(f"{'Light' if row == 0 else 'Dark'} · {'明' if row == 0 else '暗'}", 15, 60, y0 + 38, pal["muted"], "bold", "cjk-bold", small_caps=.78, tracking=.05)
        parts.append(p)
        x = 60
        for key, zh, en in ROLES:
            c = pal[key]
            stroke = f' stroke="{pal["muted"]}" stroke-opacity=".5"' if key == "paper" else ""
            parts.append(f'<rect x="{x}" y="{y0 + 58}" width="150" height="88" rx="10" fill="{c}"{stroke}/>')
            g, gw = mixed(zh, 30, x + 14, y0 + 116, pal["paper"] if key not in ("paper",) else pal["ink"], cjk="cjk-bold")
            parts.append(g)
            t, _ = mixed(en, 14, x, y0 + 172, pal["ink"], "bold", small_caps=.78, tracking=.04)
            parts.append(t)
            h, _ = mixed(c.upper(), 14, x, y0 + 194, pal["muted"], "regular")
            parts.append(h)
            if key != "paper":
                r = contrast(c, pal["paper"])
                k, _ = mixed(f"{r:.1f}:1 on paper", 12, x, y0 + 214, pal["muted"], "sans", "sans")
                parts.append(k)
            x += 170
    return svg(W, H, "".join(parts))


# ---------------------------------------------------------------- type scale
SCALE = [  # token, sp, latin face, cjk face, small caps, sample, role
    ("Display", 42, "regular", "cjk", None, "100%  ·  The", "大数字、单词浮层的原词"),
    ("Headword", 36, "bold", "cjk-bold", None, "memorandum", "词条标题（≤ 1 行）"),
    ("Title", 30, "bold", "cjk-bold", .78, "Strait of Hormuz", "文章标题，small caps"),
    ("Heading", 25, "bold", "cjk-bold", .78, "In Context", "页 / 分区标题 UiHeading"),
    ("Body", 21, "regular", "cjk", None, "a fragile memorandum of understanding", "正文（用户 16–28 可调）"),
    ("Gloss", 18, "regular", "cjk", None, "谅解备忘录 · 本句释义", "中文释义、引文"),
    ("Label", 15, "sans", "sans", None, "接口与外观设置", "设置项、按钮文字（无衬线）"),
    ("Meta", 13, "sans", "sans", None, "55 / 55 句已缓存 · 不消耗额度", "说明、状态"),
    ("Caption", 11, "bold", "cjk-bold", .78, "Reading / 1 of 48", "眉题、词性缩写，small caps"),
]


def type_sheet(pal):
    W = 1280
    y = 70
    parts = []
    for token, sp, lf, cf, sc, sample, role in SCALE:
        y += max(sp * 1.55, 44)
        t, _ = mixed(token, 13, 48, y, pal["word"], "bold", small_caps=.78, tracking=.06)
        n, _ = mixed(f"{sp} sp", 13, 160, y, pal["muted"], "regular")
        s, sw = mixed(sample, sp, 250, y, pal["ink"], lf, cf, small_caps=sc, tracking=.03 if sc else 0)
        r, _ = mixed(role, 13, 940, y, pal["muted"], "sans", "sans")
        parts += [t, n, s, r]
        parts.append(f'<rect x="48" y="{y + 12}" width="{W - 96}" height="1" fill="{pal["muted"]}" fill-opacity=".18"/>')
    H = int(y + 56)
    head, _ = mixed("Type Scale · 字号层级  —  ratio 1.18 · Body 21", 15, 48, 44, pal["muted"], "bold", "cjk-bold", small_caps=.78, tracking=.05)
    return svg(W, H, f'<rect width="{W}" height="{H}" fill="{pal["paper"]}"/>' + head + "".join(parts))


# ---------------------------------------------------------------- glyphs (24 grid, 1.5 stroke, butt caps, half-feather arrows)
FEATHER = "M{x1} {y1}L{x2} {y2}"
G = {
    "menu": "M3 7H21M3 12H21M3 17H14",
    "back": "M4 12H20M4 12L9 8",
    "forward": "M4 12H20M20 12L15 8",
    "refresh": "M18.06 15.5A7 7 0 1 1 18.06 8.5M18.06 8.5L18.5 3.6",
    "bookmark": "M6.75 3.75H17.25V20.25L12 16.1L6.75 20.25Z",
    "flag": "M6 3V21M6 4H18L15 8.5L18 13H6",
    "check": "M5 12.5L10 17L19 7",
    "close": "M6 6L18 18M18 6L6 18",
    "settings": "M3 8H21M3 16H21M15 5V11M8 13V19",
    "download": "M12 4V17M12 17L8 13M5 20H19",
}
FILLED = {"radio-on": ('<circle cx="12" cy="12" r="6.25" fill="none" stroke="C" stroke-width="1.5"/><circle cx="12" cy="12" r="3" fill="C"/>'),
          "review": ('<path d="M3.75 7.75H16.25V20.25H3.75ZM7.75 3.75H20.25V16.25" fill="none" stroke="C" stroke-width="1.5"/><rect x="6.5" y="13" width="7" height="2" fill="C"/>'),
          "radio-off": '<circle cx="12" cy="12" r="6.25" fill="none" stroke="C" stroke-width="1.5"/>'}
ZH = {"menu": "目录", "back": "返回", "forward": "前往", "refresh": "重新生成", "bookmark": "书签", "flag": "纠错",
      "check": "完成", "close": "关闭", "settings": "设置", "download": "下载更新", "review": "复习",
      "radio-on": "选中", "radio-off": "未选"}


def glyph_body(name, color):
    if name in G:
        return f'<path d="{G[name]}" fill="none" stroke="{color}" stroke-width="1.5" stroke-linecap="butt" stroke-linejoin="miter"/>'
    return FILLED[name].replace('"C"', f'"{color}"')


def glyph_files():
    for name in list(G) + list(FILLED):
        write(f"{BR}/glyphs/{name}.svg",
              f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">{glyph_body(name, "currentColor")}</svg>')


def glyph_sheet():
    names = list(G) + list(FILLED)
    W = 1280
    parts = []
    for row, pal in enumerate([LIGHT, DARK]):
        y0 = row * 300
        parts.append(f'<rect y="{y0}" width="{W}" height="300" fill="{pal["paper"]}"/>')
        for i, n in enumerate(names):
            x = 40 + i * 92
            parts.append(f'<g transform="translate({x} {y0 + 40}) scale(2.5)">'
                         f'<rect width="24" height="24" fill="{pal["word"]}" fill-opacity=".08"/>{glyph_body(n, pal["ink"])}</g>')
            parts.append(f'<g transform="translate({x + 18} {y0 + 140})">{glyph_body(n, pal["ink"])}</g>')
            parts.append(f'<g transform="translate({x + 22} {y0 + 186}) scale(.66667)">{glyph_body(n, pal["word"])}</g>')
            z, zw = mixed(ZH[n], 13, x + 30 - len(ZH[n]) * 6.5, y0 + 238, pal["muted"], "sans", "sans")
            parts.append(z)
            e, ew = mixed(n, 11, x + 30 - len(n) * 2.6, y0 + 260, pal["muted"], "regular")
            parts.append(e)
    return svg(W, 600, "".join(parts))


write(f"{BR}/palette.svg", palette_sheet())
write(f"{BR}/type-scale-light.svg", type_sheet(LIGHT))
write(f"{BR}/type-scale-dark.svg", type_sheet(DARK))
glyph_files()
write(f"{BR}/glyphs.svg", glyph_sheet())

print("brand assets written to", OUT)
