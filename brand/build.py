#!/usr/bin/env python3
"""
Builds every Better Anti-Dupe brand asset from one description of the mark.

    python brand/build.py

Writes the SVGs into brand/. export.py turns them into PNG and animated WebP.
"""

import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- palette ---

BG     = "#0B1729"
LIFT   = "#27497A"   # the soft light behind the mark
AMBER  = "#FFB000"
INLAY  = "#CE9108"   # AMBER at 80% over BG, premixed so the two halves never stack up
TEXT   = "#FFFFFF"
MUTED  = "#9AABC2"

FACE_TOP    = "#E8EEF2"
FACE_BOTTOM = "#A3B0BB"
WALL        = "#C9D2DA"
SHADOW      = "#223149"   # what the steel darkens towards as it turns out of the light

WORD_FONT = "'Space Grotesk','Outfit','Segoe UI',system-ui,sans-serif"
BODY_FONT = "'Inter','Inter Variable','Segoe UI',system-ui,sans-serif"

# ------------------------------------------------------------- the tagline ---

LEAD = "Stops item duplication "

PHRASES = [
    "and tells you who tried.",
    "before it reaches your economy.",
    "and shows you where the items went.",
    "inside chests, shulkers and bundles.",
    "without changing how items stack.",
    "across every server on your network.",
]

# Inter 500 at 25px, default optical size. Static SVG can't measure text, so
# re-measure after editing a phrase (see README) or its rule comes out the wrong length.
LEAD_W25 = 272.3
PHRASE_W25 = {
    "and tells you who tried.":             277.7,
    "before it reaches your economy.":      386.2,
    "and shows you where the items went.":  449.8,
    "inside chests, shulkers and bundles.": 433.5,
    "without changing how items stack.":    414.7,
    "across every server on your network.": 446.5,
}

FILL = 3.50   # rule travels the width of the ending
FULL = 0.40   # rule sits finished before the swap, so it reads as a countdown
FADE = 0.15
BEAT = FILL + FULL + FADE

# ------------------------------------------------------------ the mark ---
# Drawn at 512x512 around (256, 256), then scaled into place.

C = 256.0

TURN = 2.0         # seconds for the shield's half turn
REST = 2.4         # seconds it then holds still, face on
LOOP = TURN + REST
DIM = 0.4          # how far the far half fades at the middle of the turn
CAMERA = 1300.0    # smaller is a stronger perspective, but the near corner then shoots up
LIGHT = (-0.2, 0.0, 1.0)
AMBIENT = 0.45

# Right half of the rim's centre line and of the amber inlay, x measured from
# the middle. The left half is the mirror image.
RIM = ((0, 110), [
    ("C", (58, 110), (102, 102), (140, 92)),
    ("L", (140, 206)),
    ("C", (140, 322), (74, 396), (0, 428)),
])
RIM_W = 13.0       # face-on width of the steel
RIM_D = 16.0       # front-to-back thickness, which only shows while it turns
INLAY_PATH = ((0, 134), [
    ("C", (48, 134), (86, 128), (118, 122)),
    ("L", (118, 210)),
    ("C", (118, 308), (62, 370), (0, 400)),
])
INLAY_W = 3.5
CURVE_STEPS = 12   # the animated SVG stores every point once per keyframe, so keep this low

GEM_MAP = """
....EEEE....
...EHHHLE...
..EHHLLLCE..
.EHLLCHCME..
.EHLCCCCCME.
EHLCCCCCCMME
EHLCCCCCCMME
EHLCCCCCCMDE
ELMDDDDDDDDE
.ELMMMMMMDE.
.EDMMMMMMDE.
..EDMMMMDE..
...EEEEEE...
""".strip().splitlines()

GEM_TONES = {
    "H": "#EFFFFC",
    "L": "#A8F5EA",
    "C": "#4AEDD9",
    "M": "#27BFAD",
    "D": "#168276",
    "E": "#0B4F49",
}
PIXEL = 13
GEM_X = C - PIXEL * len(GEM_MAP[0]) / 2
GEM_Y = 164


def _half(outline):
    """Right half of an outline as points from the top middle down to the tip, and where each segment ends."""
    start, segs = outline
    pts, ends = [start], [0]
    for seg in segs:
        if seg[0] == "L":
            pts.append(seg[1])
        else:
            p0, (p1, p2, p3) = pts[-1], seg[1:]
            for i in range(1, CURVE_STEPS + 1):
                t = i / CURVE_STEPS
                u = 1 - t
                pts.append(tuple(u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
                                 for a, b, c, d in zip(p0, p1, p2, p3)))
        ends.append(len(pts) - 1)
    return pts, ends


def _offset(loop, d):
    """The closed loop pushed d outwards (negative for inwards), mitred at the corners."""
    n = len(loop)
    out = []
    for i in range(n):
        normals = []
        for a, b in ((loop[i - 1], loop[i]), (loop[i], loop[(i + 1) % n])):
            dx, dy = b[0] - a[0], b[1] - a[1]
            ln = math.hypot(dx, dy)
            normals.append((dy / ln, -dx / ln))
        bx, by = normals[0][0] + normals[1][0], normals[0][1] + normals[1][1]
        bl = math.hypot(bx, by)
        bx, by = bx / bl, by / bl
        m = d / (bx * normals[0][0] + by * normals[0][1])
        out.append((loop[i][0] + bx * m, loop[i][1] + by * m))
    return out


# The rim is solid geometry offset in its own plane, not a stroke, so its
# corners stay put when the turn squeezes them instead of shooting out as mitre spikes.
_RIGHT, _ENDS = _half(RIM)
_LOOP = _RIGHT + [(-x, y) for x, y in reversed(_RIGHT[1:-1])]
RIM_OUT = _offset(_LOOP, RIM_W / 2)
RIM_IN = _offset(_LOOP, -RIM_W / 2)

# Each curve and straight of the rim is its own shape. Drawn as one shape, the
# top curve folds back over the side near edge on and the overlap cancels out,
# which punches a notch into the corner. Neighbouring shapes overlap by one step
# so no hairline of background shows through where they meet.
_N = len(_LOOP)
_SPANS = [(a - (k > 0), b + (k < len(_ENDS) - 2)) for k, (a, b) in enumerate(zip(_ENDS, _ENDS[1:]))]
RIGHT_SECTIONS = [list(range(a, b + 1)) for a, b in _SPANS]
LEFT_SECTIONS = [[i % _N for i in range(_N - b, _N - a + 1)] for a, b in _SPANS]


def _projector(angle):
    """
    Turns a point about the vertical centre line and projects it. Perspective is
    taken from the point's position on the shield only, not its thickness, so
    face on the rim shows no side walls at all.
    """
    a = math.radians(angle)
    sa, ca = math.sin(a), math.cos(a)

    def proj(p, z=0.0):
        x, y = p
        s = CAMERA / (CAMERA - x * sa)
        return C + (x * ca - z * sa) * s, C + (y - C) * s
    return proj


def _poly(points):
    return "M" + " ".join(f"{x:.1f},{y:.1f}" for x, y in points) + "Z"


def turn_angle(t):
    t %= LOOP
    if t >= TURN:
        return 180.0
    return 180.0 * (0.5 - 0.5 * math.cos(math.pi * t / TURN))


def _mix(c1, c2, t):
    a = [int(c1[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(c2[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(a, b))


def _lit(base, k):
    return _mix(SHADOW, base, k) if k <= 1 else _mix(base, "#FFFFFF", k - 1)


def _light(normal):
    return AMBIENT + (1 - AMBIENT) * max(0.0, sum(n * l for n, l in zip(normal, LIGHT))) / LIGHT[2]


def _state(angle, front=None):
    """
    Everything about the turned shield that changes with the angle. Between 0
    and 180 degrees the right half is always the near one, so the draw order
    never changes: far half, diamond, near half. Only the visible face swaps,
    front for back, at the moment it is edge on and has no width.
    """
    a = math.radians(angle)
    sa, ca = math.sin(a), math.cos(a)
    if front is None:
        front = ca >= 0
    proj = _projector(angle)
    fz = RIM_D / 2 if front else -RIM_D / 2

    def face(idx):
        return _poly([proj(RIM_OUT[i], fz) for i in idx]
                     + [proj(RIM_IN[i], fz) for i in reversed(idx)])

    def wall(edge, idx):
        return _poly([proj(edge[i], RIM_D / 2) for i in idx]
                     + [proj(edge[i], -RIM_D / 2) for i in reversed(idx)])

    k_face = _light((-sa, 0, ca) if front else (sa, 0, -ca))
    k_wall = _light((ca, 0, sa))
    inlay_r, inlay_l = _inlay(proj)
    parts = dict(
        back=f"{1 - DIM * abs(sa):.3f}",
        top=_lit(FACE_TOP, k_face), bottom=_lit(FACE_BOTTOM, k_face), wall=_lit(WALL, k_wall),
        inlay_r=inlay_r, inlay_l=inlay_l,
    )
    for i, (left, right) in enumerate(zip(LEFT_SECTIONS, RIGHT_SECTIONS)):
        parts[f"left_face{i}"] = face(left)
        parts[f"right_face{i}"] = face(right)
        parts[f"inner_wall{i}"] = wall(RIM_IN, left)
        parts[f"outer_wall{i}"] = wall(RIM_OUT, right)
    return parts


def _inlay(proj):
    start, right = INLAY_PATH
    starts = [start] + [seg[-1] for seg in right]

    def fmt(p):
        x, y = proj(p)
        return f"{x:.1f},{y:.1f}"

    def mirror(p):
        return (-p[0], p[1])

    r = "M" + fmt(start) + "".join(seg[0] + " ".join(fmt(p) for p in seg[1:]) for seg in right)
    l = "M" + fmt(mirror(right[-1][-1]))
    for i in range(len(right) - 1, -1, -1):
        seg = right[i]
        if seg[0] == "L":
            l += "L" + fmt(mirror(starts[i]))
        else:
            l += "C" + " ".join(fmt(mirror(p)) for p in (seg[2], seg[1], starts[i]))
    return r, l


def _turn_keys(n=24):
    """(keyTime, angle, front) for the animated SVG, with the face swap as an instant jump at 90 degrees."""
    keys = []
    for k in range(n + 1):
        t = k / n * TURN / LOOP
        if k == n // 2:
            keys += [(t, 90.0, True), (t, 90.0, False)]
        else:
            keys.append((t, turn_angle(k / n * TURN), None))
    keys.append((1.0, 180.0, None))
    return keys


def gem():
    """The diamond: a solid silhouette in the rim colour, with the facets laid over it."""
    right, left = [], []
    for r, row in enumerate(GEM_MAP):
        first = len(row) - len(row.lstrip("."))
        last = len(row.rstrip("."))
        right += [(last, r), (last, r + 1)]
        left.append([(first, r + 1), (first, r)])
    pts = right + [p for pair in reversed(left) for p in pair]
    d = "M" + " L".join(f"{GEM_X + x * PIXEL:g},{GEM_Y + y * PIXEL:g}" for x, y in pts) + "Z"

    rects = []
    for r, row in enumerate(GEM_MAP):
        c = 0
        while c < len(row):
            ch = row[c]
            run = 1
            while c + run < len(row) and row[c + run] == ch:
                run += 1
            if ch not in ".E":
                rects.append(
                    f'<rect x="{GEM_X + c * PIXEL:g}" y="{GEM_Y + r * PIXEL:g}" '
                    f'width="{run * PIXEL}" height="{PIXEL}" fill="{GEM_TONES[ch]}"/>')
            c += run
    return f'<path d="{d}" fill="{GEM_TONES["E"]}"/>\n    ' + "\n    ".join(rects)


def mark(idp, angle=0.0, animated=False):
    gem_cy = GEM_Y + PIXEL * len(GEM_MAP) / 2
    inlay_style = (f'fill="none" stroke="{INLAY}" stroke-width="{INLAY_W}" '
                   f'stroke-linecap="round" stroke-linejoin="round"')

    if animated:
        keys = _turn_keys()
        states = [_state(a, f) for _, a, f in keys]
        kt = ";".join(f"{t:.4f}" for t, _, _ in keys)
        s0 = states[0]

        def anim(attr, name):
            return (f'<animate attributeName="{attr}" dur="{LOOP:g}s" repeatCount="indefinite" '
                    f'keyTimes="{kt}" values="{";".join(s[name] for s in states)}"/>')
    else:
        s0 = _state(angle)

        def anim(attr, name):
            return ""

    steel = f"""
  <defs>
    <linearGradient id="{idp}steel" gradientUnits="userSpaceOnUse" x1="0" y1="90" x2="0" y2="432">
      <stop offset="0" stop-color="{s0['top']}">{anim('stop-color', 'top')}</stop>
      <stop offset="1" stop-color="{s0['bottom']}">{anim('stop-color', 'bottom')}</stop>
    </linearGradient>
  </defs>
  <circle cx="{C:g}" cy="{gem_cy:g}" r="170" fill="url(#{idp}gemglow)"/>"""

    if not animated and angle % 180 == 0:
        return steel + f"""
  <path d="{_poly([(C + x, y) for x, y in RIM_OUT])}{_poly([(C + x, y) for x, y in RIM_IN])}" fill="url(#{idp}steel)" fill-rule="evenodd"/>
  <path d="{s0['inlay_r']}" {inlay_style}/>
  <path d="{s0['inlay_l']}" {inlay_style}/>
  <g>
    {gem()}
  </g>"""

    def shapes(kind, fill, fill_anim=""):
        return "".join(f'\n  <path d="{s0[f"{kind}{i}"]}" fill="{fill}">{anim("d", f"{kind}{i}")}{fill_anim}</path>'
                       for i in range(len(RIGHT_SECTIONS)))

    wall_anim = anim("fill", "wall")
    steel_fill = f"url(#{idp}steel)"
    return steel + f"""
  <g opacity="{s0['back']}">{anim('opacity', 'back')}{shapes('inner_wall', s0['wall'], wall_anim)}{shapes('left_face', steel_fill)}
  <path d="{s0['inlay_l']}" {inlay_style}>{anim('d', 'inlay_l')}</path>
  </g>
  <g>
    {gem()}
  </g>
  <path d="{s0['inlay_r']}" {inlay_style}>{anim('d', 'inlay_r')}</path>{shapes('outer_wall', s0['wall'], wall_anim)}{shapes('right_face', steel_fill)}"""


def defs(idp):
    return f"""
  <defs>
    <radialGradient id="{idp}glow" cx="50%" cy="50%" r="58%">
      <stop offset="0%"   stop-color="{LIFT}" stop-opacity="0.35"/>
      <stop offset="55%"  stop-color="{LIFT}" stop-opacity="0.10"/>
      <stop offset="100%" stop-color="{LIFT}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="{idp}gemglow">
      <stop offset="0%"   stop-color="{GEM_TONES['C']}" stop-opacity="0.20"/>
      <stop offset="45%"  stop-color="{GEM_TONES['C']}" stop-opacity="0.06"/>
      <stop offset="100%" stop-color="{GEM_TONES['C']}" stop-opacity="0"/>
    </radialGradient>
  </defs>"""


# ------------------------------------------------------------------ assets ---

def icon(angle=0.0, animated=False):
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512" role="img" aria-labelledby="t d">
  <title id="t">Better Anti-Dupe icon</title>
  <desc id="d">A Minecraft diamond inside a turning steel shield.</desc>
{defs('i')}
  <rect width="512" height="512" fill="{BG}"/>
  <rect width="512" height="512" fill="url(#iglow)"/>
{mark('i', angle, animated)}
</svg>
"""


def _layout(w, h, mark_on):
    cx = w / 2
    if not mark_on:
        return dict(cx=cx, word_y=h * 0.505, word_size=w * 0.0755,
                    tag_y=h * 0.690, tag_size=w * 0.0219)
    if h >= 600:
        return dict(cx=cx, scale=0.42, mark_top=132,
                    word_y=434, word_size=88, tag_y=488, tag_size=27)
    return dict(cx=cx, scale=0.38, mark_top=40,
                word_y=308, word_size=74, tag_y=356, tag_size=25)


def _tagline_x(L, phrase_idx=0):
    """The lead is pinned where the first sentence comes out centred; every ending runs on from it."""
    k = L["tag_size"] / 25.0
    lead_w = LEAD_W25 * k
    first_w = PHRASE_W25[PHRASES[0]] * k
    x0 = L["cx"] - (lead_w + first_w) / 2
    return x0, x0 + lead_w, PHRASE_W25[PHRASES[phrase_idx]] * k


def _progress(L, x, width, frac, anim=""):
    y = L["tag_y"] + L["tag_size"] * 0.52
    hgt = max(1.0, L["tag_size"] * 0.055)
    return (
        f'<rect x="{x:.1f}" y="{y:.2f}" width="{width:.1f}" height="{hgt:.2f}" '
        f'rx="{hgt / 2:.2f}" fill="{AMBER}" opacity="0.10"/>'
        f'<rect x="{x:.1f}" y="{y:.2f}" width="{width * frac:.1f}" height="{hgt:.2f}" '
        f'rx="{hgt / 2:.2f}" fill="{AMBER}" opacity="0.45">{anim}</rect>'
    )


def _tag_text(L, x, text, fill, extra=""):
    return (f'<text x="{x:.1f}" y="{L["tag_y"]:.1f}" font-family="{BODY_FONT}" '
            f'font-size="{L["tag_size"]:.1f}" font-weight="500" fill="{fill}" '
            f'text-anchor="start"{extra}>{text}</text>')


def banner(w, h, phrase_idx=0, phrase_op=1.0, frac=0.0,
           angle=0.0, animated=False, mark_on=True):
    L = _layout(w, h, mark_on)
    cx = L["cx"]
    lead_x, phrase_x, phrase_w = _tagline_x(L, phrase_idx)
    lead = _tag_text(L, lead_x, LEAD.rstrip(), MUTED)

    if animated:
        n = len(PHRASES)
        total = n * BEAT
        parts = []
        for i, p in enumerate(PHRASES):
            _, px, pw = _tagline_x(L, i)
            bar_t, bar_v = _bar_anim(i, n, total, round(pw, 1))
            op_t, op_v = _phrase_anim(i, n)
            parts.append(
                f'<g opacity="{1 if i == 0 else 0}">'
                + _tag_text(L, px, p, AMBER)
                + _progress(L, px, pw, 0.0,
                            f'<animate attributeName="width" dur="{total:g}s" '
                            f'repeatCount="indefinite" values="{bar_v}" keyTimes="{bar_t}"/>')
                + f'<animate attributeName="opacity" dur="{total:g}s" '
                  f'repeatCount="indefinite" values="{op_v}" keyTimes="{op_t}"/></g>'
            )
        cycling = "".join(parts)
    else:
        cycling = (_tag_text(L, phrase_x, PHRASES[phrase_idx], AMBER,
                             f' opacity="{phrase_op:.3f}"')
                   + _progress(L, phrase_x, phrase_w, frac))

    if mark_on:
        size = 512 * L["scale"]
        mark_block = (f'<g transform="translate({cx - size / 2:.1f} {L["mark_top"]}) '
                      f'scale({L["scale"]})">{mark("b", angle, animated)}</g>')
        desc = "A diamond inside a turning steel shield, above the Better Anti-Dupe wordmark and its tagline."
    else:
        mark_block = ""
        desc = "The Better Anti-Dupe wordmark above its tagline."

    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-labelledby="t d">
  <title id="t">Better Anti-Dupe</title>
  <desc id="d">{desc}</desc>
{defs('b')}
  <rect width="{w}" height="{h}" fill="{BG}"/>
  <rect width="{w}" height="{h}" fill="url(#bglow)"/>
  {mark_block}
  <text x="{cx:g}" y="{L['word_y']:.1f}" font-family="{WORD_FONT}"
        font-size="{L['word_size']:.1f}" font-weight="700"
        letter-spacing="{-2.2 * L['word_size'] / 74:.2f}" fill="{TEXT}"
        text-anchor="middle">Better Anti-Dupe</text>

  {lead}
  {cycling}
</svg>
"""


def _keys(pairs, total):
    """(seconds, value) points as SMIL keyTimes and values, clamped and never going backwards."""
    times, vals, last = [], [], 0.0
    for t, v in pairs:
        t = max(last, min(1.0, max(0.0, t / total)))
        last = t
        times.append(f"{t:.5f}")
        vals.append(f"{v:g}")
    return ";".join(times), ";".join(vals)


def _phrase_anim(i, n):
    s, e = i * BEAT, (i + 1) * BEAT
    total = n * BEAT
    if i == 0:
        pts = [(0, 1), (e - FADE, 1), (e, 0), (total - FADE, 0), (total, 1)]
    else:
        pts = [(0, 0), (s - FADE, 0), (s, 1), (e - FADE, 1), (e, 0), (total, 0)]
    return _keys(pts, total)


def _bar_anim(i, n, total, w):
    s, e = i * BEAT, (i + 1) * BEAT
    pts = [(0, 0), (s, 0), (s + FILL, w), (e, w), (e + 0.0001, 0), (n * BEAT, 0)]
    return _keys(pts, total)


# ------------------------------------------------------------------ frames ---

ICON_FPS = 30     # the turn is fast enough mid-way that 20 fps visibly steps
BANNER_FPS = 20


def ease(t):
    return t * t * (3 - 2 * t)


def icon_frames():
    n = round(LOOP * ICON_FPS)
    for f in range(n):
        yield icon(angle=turn_angle(f / ICON_FPS))


def banner_frames():
    """Only the ending and its rule move, which is what keeps the WebP small."""
    per_beat = round(BEAT * BANNER_FPS)
    fill_f = round(FILL * BANNER_FPS)
    fade_f = max(1, round(FADE * BANNER_FPS))
    for i in range(len(PHRASES)):
        for k in range(per_beat):
            frac = min(1.0, k / fill_f)
            if k < fade_f:
                op = ease((k + 1) / (fade_f + 1))
            elif k >= per_beat - fade_f:
                op = ease((per_beat - k) / (fade_f + 1))
            else:
                op = 1.0
            yield banner(1280, 420, i, phrase_op=op, frac=frac, mark_on=False)


def write(name, text):
    with open(os.path.join(HERE, name), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def main():
    write("bad-icon-512.svg", icon())
    write("bad-icon-512-animated.svg", icon(animated=True))
    write("bad-banner-1200x600.svg", banner(1200, 600))
    write("bad-banner-1280x420.svg", banner(1280, 420))
    write("bad-banner-1280x420-nomark.svg", banner(1280, 420, mark_on=False))
    write("bad-banner-1280x420-animated.svg", banner(1280, 420, animated=True, mark_on=False))
    write("bad-banner-1280x420-animated-withmark.svg", banner(1280, 420, animated=True))
    print("wrote 7 SVGs into brand/")


if __name__ == "__main__":
    main()
