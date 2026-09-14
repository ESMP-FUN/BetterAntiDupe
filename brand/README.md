# Better Anti-Dupe brand assets

Everything needed to produce a Better Anti-Dupe image, and the reasoning behind
the choices so nobody has to guess later.

Every file here is generated. Edit `build.py`, then:

```bash
python brand/build.py && python brand/export.py
```

## The mark

A **Minecraft diamond**, with a **steel shield** turning slowly around it.

- The **diamond** is what dupers go after, and what the plugin keeps count of.
- The **shield** is the protection. It is an open steel rim with a thin amber
  line set inside it, rather than a solid plate, so the diamond is never
  covered up by the thing guarding it.
- It makes a **half turn and then rests** face on. A shield that spins without
  stopping reads as a loading spinner. One that turns and settles reads as a
  guard doing its rounds.

The rim is drawn as a real object with thickness, not a flat line. While it
turns you see its edge, the near side grows and the far side shrinks, the
steel darkens as it turns away from the light, and the far half fades a
little. The near half passes in front of the diamond and the far half behind
it. Face on, none of that shows and the icon is clean.

The diamond is our own drawing on a 12 by 13 pixel grid, in six flat tones with
no bevels or per-pixel shadows. It reads as a Minecraft diamond at 32px and
still looks clean at 512. It keeps the game's diamond colours whatever the rest
of the palette does.

### No text in the icon

Modrinth, Spigot and Discord always print the project name right next to the
icon, so letters inside it are duplicated information that costs legibility at
40px. Same rule as Better Trial Chambers and Community Chat.

## Colours

| Role | Hex | Notes |
|---|---|---|
| Amber | `#FFB000` | The accent. The tagline's ending and its progress rule. |
| Inlay | `#CE9108` | The amber line inside the shield: amber mixed 80% into the background. |
| Background | `#0B1729` | Midnight blue. |
| Light | `#27497A` | The soft glow behind the mark. |
| Wordmark | `#FFFFFF` | |
| Body text | `#9AABC2` | The fixed half of the tagline. |
| Steel | `#E8EEF2` to `#A3B0BB` | The rim, lighter at the top. `#C9D2DA` for its edge. |
| Diamond | `#4AEDD9` | Plus five lighter and darker tones in `GEM_TONES`. |

The inlay is a mixed colour rather than amber at 80% opacity on purpose. Its
two halves meet at the top and the tip, and two see-through lines on top of
each other come out brighter than one.

### Contrast

Against the background `#0B1729`:

| Colour | Ratio | |
|---|---|---|
| `#FFB000` amber | 9.8:1 | fine |
| `#9AABC2` body text | 7.7:1 | fine |
| `#CE9108` inlay | 6.6:1 | fine |
| `#FFFFFF` | 18.0:1 | fine |

Amber on white is **1.8:1**, unreadable. The images carry their own dark
background so this never matters for them. If you set it as a Discord role
colour, use `#B07800` instead (3.8:1 on light theme, 3.3:1 on dark).

## Type

| | Font | Weight | Tracking |
|---|---|---|---|
| Wordmark | **Space Grotesk** | 700 | `-2.2` at 74px |
| Tagline | **Inter** | 500 | normal |

The same pair as Better Trial Chambers and Community Chat, so the plugins look
like they came from the same person. Never a pixel or Minecraft-style font:
the diamond already says Minecraft, and pixel type says hobby plugin.

## Tagline

```
Stops item duplication and tells you who tried.
```

The ending rotates. The full set lives in `PHRASES` in `build.py`:

1. and tells you who tried.
2. before it reaches your economy.
3. and shows you where the items went.
4. inside chests, shulkers and bundles.
5. without changing how items stack.
6. across every server on your network.

The grey lead never moves. It sits where the first sentence comes out centred,
and every other ending runs on to the right from the same point. A faint rule
fills up under the ending, sits full for a moment, and only then does the
ending change, so the rule reads as a countdown and not a reaction.

### Changing a phrase

Static SVG can't measure text, so `PHRASE_W25` in `build.py` holds a measured
width for every ending. **Re-measure after editing one**, or its rule comes out
the wrong length:

```bash
python3 -c "from PIL import ImageFont as F; f = F.truetype('InterVariable.woff2', 25, layout_engine=F.Layout.RAQM); f.set_variation_by_axes([14, 500]); print(round(f.getlength('your new phrase.'), 1))"
```

That measures at Inter's default optical size, which is what `export.py`
renders. Browsers pick a slightly tighter one, so in a browser the live SVG's
rule can run a few pixels past the text. Nobody will notice.

## Files

| File | Size | Used for |
|---|---|---|
| `bad-icon-512.png` / `.svg` | 512x512 | Modrinth, Spigot and Discord icon, favicon |
| `bad-icon-192-animated.webp` | 192x192 | **the animated icon to upload.** 75 KB |
| `bad-icon-384-animated.webp` | 384x384 | same, for anywhere it's shown large. 159 KB |
| `bad-icon-512-animated.svg` | 512x512 | anywhere that renders SVG live. 104 KB |
| `bad-banner-animated.webp` | 1280x420 | **the animated banner to upload.** 142 KB |
| `bad-banner-1280x420-nomark.svg` | 1280x420 | its static fallback |
| `bad-banner-1280x420.png` / `.svg` | 1280x420 | static banner with the mark |
| `bad-banner-1200x600.png` / `.svg` | 1200x600 | BuiltByBit and Spigot resource banners |
| `bad-banner-1280x420-animated.svg` | 1280x420 | anywhere that renders SVG live |
| `bad-banner-1280x420-animated-withmark.svg` | 1280x420 | same, with the mark. 109 KB |

## Animation

**The `.svg` files animate themselves** in a browser, and in a GitHub README
when linked from `raw.githubusercontent.com`. Modrinth, Spigot and BuiltByBit
strip SVG, so upload the `.webp` files there.

### The icon

A 2 second half turn with a gentle start and stop, then 2.4 seconds at rest:
a 4.4 second loop. The settings in `build.py`:

| | |
|---|---|
| `TURN`, `REST` | how long the turn and the rest last, in seconds |
| `CAMERA` | strength of the perspective. Lower is stronger, but the near top corner then stretches up into a spike |
| `RIM_W`, `RIM_D` | the rim's width face on, and its thickness from front to back |
| `DIM` | how much the far half fades mid-turn. 0 is not at all |
| `LIGHT`, `AMBIENT` | where the light comes from, and how dark the steel gets facing away from it |

It runs at 30 fps rather than 20, because the middle of the turn is quick and at
20 fps you can see it step. The resting frames are identical, so the encoder
keeps them as one long frame and the file stays small.

The animated SVG moves the same shapes through the same turn, so it matches the
WebP. It is about 100 KB because it stores the whole rim once for each of its
25 keyframes. Raise `CURVE_STEPS` for smoother curves and it grows with it.

### How the rim is drawn

Two details matter if you ever edit the rim code:

- **The rim is filled shapes, not an outline with a stroke.** A stroke works
  out its sharp corners on screen, after the turn has squeezed them, so near
  edge on the corners shoot out as long spikes. The rim's corners are worked
  out once, flat, and then turned.
- **Each curve and straight of the rim is its own shape.** Near edge on, the
  top curve folds back over the side, and within one shape the overlap cancels
  itself out and leaves a notch in the corner.

### The banner

Six endings, 4.05 seconds each, a 24.3 second loop. Each beat is `FILL` (3.5s,
the rule travels), `FULL` (0.4s, the rule sits finished) and `FADE` (0.15s, the
ending changes).

The mark is left out of the animated banner. The wordmark and the changing
ending already carry it, and a second moving thing splits the attention; the
mark has its own icon to live in. `bad-banner-1280x420-animated-withmark.svg`
exists if you disagree.

## Exporting

`export.py` needs `rsvg-convert` (`sudo apt install librsvg2-bin`) and `ffmpeg`.

**The icon needs no fonts.** It is pure shapes and renders the same everywhere.

**The banners contain live text**, so they need Space Grotesk and Inter.
`export.py` refuses to render the banner if either is missing, rather than
quietly using a fallback font. Install both from Google Fonts, or point
fontconfig at a folder holding the font files without installing anything:

```bash
FONTCONFIG_FILE=/path/to/fonts.conf python brand/export.py banner
```

where `fonts.conf` includes `/etc/fonts/fonts.conf` and adds a `<dir>` for the
folder. Inter's variable release calls itself "Inter Variable"; the SVGs accept
that name too.

Before handing a banner SVG to someone else, convert the text to outlines
(Inkscape: Path, Object to Path) so it no longer depends on their fonts.

## Safe areas

Discord crops the icon to a **circle** and Modrinth rounds its corners. The
furthest point of the mark is a top corner of the rim, about 230px from the
centre, against a 256px radius, and the turn never takes it further out. If you
edit the shield, keep it within about 235px of the centre.
