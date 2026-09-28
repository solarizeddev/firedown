// Firedown accent presets — generator + contrast checker.
//
//   cd scripts/theme-palettes && npm ci
//   npm run generate   # rewrites the three generated resource files
//   npm run check      # verifies every palette against the floors below
//
// WHY THIS EXISTS. Firedown's coral palette was tuned by hand, one measured
// contrast at a time (see the notes in values/colors.xml and CLAUDE.md's
// "UI conventions"). An accent preset cannot be tuned that way per hue, so
// the presets are GENERATED from one tone map that encodes the coral
// palette's SHAPE — which tone each role sits at — and then CHECKED against
// the same floors the coral values were tuned to. HCT tone is CIE L*, and
// WCAG contrast is a function of luminance alone, so a role placed at the
// same tone has (to within gamut rounding) the same contrast in every hue:
// that is what makes one map safe for arbitrary hues, and why the System
// (Material You) overlay — which only knows the wallpaper's hue at run time
// — can be checked here by sweeping every hue.
//
// The DEFAULT coral palette is NOT generated. It stays hand-tuned in
// values/colors.xml; this script only reads it, to prove the floors below
// are the ones it already meets.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
    Hct,
    TonalPalette,
    argbFromHex,
    hexFromArgb,
} from '@material/material-color-utilities';

const HERE = dirname(fileURLToPath(import.meta.url));
const RES = join(HERE, '..', '..', 'app', 'src', 'main', 'res');

// ── The presets ───────────────────────────────────────────────────────────
// id = the stored preference value (ThemeAccent.java) and the style suffix.
// hue/chroma are HCT. Chroma is a REQUEST: TonalPalette clamps it to what
// the sRGB gamut allows at each tone, so a cool hue at tone 65 comes out
// less saturated than coral does. Adding a preset = one line here, then
// `npm run generate`, the ThemeAccent constant, the settings row and its
// string.
const PRESETS = [
    { id: 'ocean', name: 'Ocean', hue: 255, chroma: 60 },
    { id: 'teal', name: 'Teal', hue: 190, chroma: 50 },
    { id: 'forest', name: 'Forest', hue: 140, chroma: 50 },
    { id: 'violet', name: 'Violet', hue: 300, chroma: 60 },
];

// The logo triad's geometry (values/colors.xml): the peach arm sits +27°
// from the brand hue, the magenta arm −31°. Presets keep the same geometry
// around THEIR primary so the three roles stay three distinguishable hues.
const SECONDARY_HUE_OFFSET = 27;
const TERTIARY_HUE_OFFSET = -31;

// ── The tone map ─────────────────────────────────────────────────────────
// [palette, tone]. Palettes: p = primary hue at the preset chroma;
// s = secondary arm, low chroma (a SUPPORTING tonal ground); sd = the same
// arm at more chroma for dark theme's light fill (the #D8804A lesson: a
// light warm fill needs chroma or it reads muddy); t = tertiary arm.
// Tones are the coral palette's own, measured with HCT (rounded):
//   primary #ff716c T65 · onPrimary #460005 T11 · light container #FF857F
//   T69 · dark container #F66A66 T62 · checked chip #EC7E78 T65 / #DE615E
//   T57 · progress #CC524A T51 · peach #FFBF9B T82 / #D8804A T62 ·
//   magenta #C8417B T49 / #FFB0C9 T80 · dark tertiary container T23.
// Both themes share ONE primary (as coral does): every filled control is a
// light fill under a dark ink in both themes — the app-wide shape.
const MAP = {
    light: {
        colorPrimary: ['p', 65],
        colorOnPrimary: ['p', 11],
        colorPrimaryContainer: ['p', 69],
        colorOnPrimaryContainer: ['p', 11],
        colorPrimaryInverse: ['p', 80],
        colorPrimaryFixed: ['p', 90],
        colorPrimaryFixedDim: ['p', 80],
        colorOnPrimaryFixed: ['p', 10],
        colorOnPrimaryFixedVariant: ['p', 30],
        // colorSecondary is the CONTROL ACCENT (bare platform widgets tint
        // through it) — the acting hue, so it stays the primary. See the
        // md_theme_secondary note in values/colors.xml.
        colorSecondary: ['p', 65],
        colorOnSecondary: ['p', 11],
        colorSecondaryContainer: ['s', 82],
        colorOnSecondaryContainer: ['s', 25],
        colorSecondaryFixed: ['s', 90],
        colorSecondaryFixedDim: ['s', 80],
        colorOnSecondaryFixed: ['s', 10],
        colorOnSecondaryFixedVariant: ['s', 30],
        colorTertiary: ['t', 49],
        colorOnTertiary: ['t', 100],
        colorTertiaryContainer: ['t', 90],
        colorOnTertiaryContainer: ['t', 10],
        colorTertiaryFixed: ['t', 90],
        colorTertiaryFixedDim: ['t', 80],
        colorOnTertiaryFixed: ['t', 10],
        colorOnTertiaryFixedVariant: ['t', 30],
        fdColorProgressIndicator: ['p', 51],
        fdColorChipChecked: ['p', 65],
        fdColorOnChipChecked: ['p', 11],
    },
    dark: {
        colorPrimary: ['p', 65],
        colorOnPrimary: ['p', 11],
        colorPrimaryContainer: ['p', 62],
        colorOnPrimaryContainer: ['p', 4],
        colorPrimaryInverse: ['p', 40],
        colorPrimaryFixed: ['p', 90],
        colorPrimaryFixedDim: ['p', 80],
        colorOnPrimaryFixed: ['p', 10],
        colorOnPrimaryFixedVariant: ['p', 30],
        colorSecondary: ['p', 65],
        colorOnSecondary: ['p', 11],
        colorSecondaryContainer: ['sd', 62],
        colorOnSecondaryContainer: ['p', 11],
        colorSecondaryFixed: ['s', 90],
        colorSecondaryFixedDim: ['s', 80],
        colorOnSecondaryFixed: ['s', 10],
        colorOnSecondaryFixedVariant: ['s', 30],
        colorTertiary: ['t', 80],
        colorOnTertiary: ['t', 20],
        colorTertiaryContainer: ['t', 23],
        colorOnTertiaryContainer: ['t', 90],
        colorTertiaryFixed: ['t', 90],
        colorTertiaryFixedDim: ['t', 80],
        colorOnTertiaryFixed: ['t', 10],
        colorOnTertiaryFixedVariant: ['t', 30],
        fdColorProgressIndicator: ['p', 65],
        fdColorChipChecked: ['p', 57],
        fdColorOnChipChecked: ['p', 11],
    },
};

// ── System (Material You) snapping ────────────────────────────────────────
// @android:color/system_accentN_<step> is a tonal palette of the wallpaper
// seed at fixed tones: step 0 = T100, 10 = T99, 50 = T95, then every 100
// steps is 10 tones darker (100 = T90 … 900 = T10, 1000 = T0). A role maps
// to the step whose tone is nearest the map's; the few ties are resolved
// explicitly below. Palettes: p → accent1, s/sd → accent2, t → accent3
// (accent2/3 are the system's own secondary/tertiary hues — not the triad
// offsets, which a static resource can't express).
const SYSTEM_TONE_OVERRIDES = {
    // T65 is a tie between T70 (step 300) and T60 (step 400). T70 keeps the
    // light-fill shape (ink contrast 7.3:1); T60 would be 5.4:1 and darker.
    colorPrimary: 70,
    colorSecondary: 70,
    // The container fills every Material primary-container surface: FABs
    // other than the hero one (the tabs-screen new-tab button), extended
    // FABs, the 20% selection wash. It must stay a SATURATED fill beside the
    // primary, as coral's T69 is: step 200 (T80) was the first choice and
    // made those buttons pale pastel discs in light theme — the regression
    // CLAUDE.md records for this token. T70 = step 300. (The HERO download
    // FAB no longer takes it: it is pinned to coral, @color/hero_fab_fill.)
    colorPrimaryContainer: { light: 70, dark: 60 },
    // Chip steps one tone below the primary, like the coral chip does.
    fdColorChipChecked: 60,
    // T49 snaps to T50, where the WHITE onTertiary ink measures 4.45-4.50:1
    // across wallpaper hues — under the floor. One step darker clears it.
    colorTertiary: { light: 40, dark: 80 },
};
const SYSTEM_PALETTE = { p: 'accent1', s: 'accent2', sd: 'accent2', t: 'accent3' };
const TONE_TO_STEP = [
    [100, 0], [99, 10], [95, 50], [90, 100], [80, 200], [70, 300], [60, 400],
    [50, 500], [40, 600], [30, 700], [20, 800], [10, 900], [0, 1000],
];

function systemTone(role, mode, tone) {
    const o = SYSTEM_TONE_OVERRIDES[role];
    if (typeof o === 'number') return o;
    if (o && o[mode] !== undefined) return o[mode];
    let best = TONE_TO_STEP[0][0];
    for (const [t] of TONE_TO_STEP) {
        if (Math.abs(t - tone) < Math.abs(best - tone)) best = t;
    }
    return best;
}

function stepForTone(tone) {
    for (const [t, step] of TONE_TO_STEP) {
        if (t === tone) return step;
    }
    throw new Error('no system step for tone ' + tone);
}

// ── Colour math ───────────────────────────────────────────────────────────
function channels(argb) {
    return [(argb >> 16) & 255, (argb >> 8) & 255, argb & 255];
}

function luminance(argb) {
    const lin = channels(argb).map((c) => {
        const v = c / 255;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}

function contrast(a, b) {
    const la = luminance(a);
    const lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

function over(fg, alpha, bg) {
    const f = channels(fg);
    const g = channels(bg);
    const out = f.map((c, i) => Math.round(c * alpha + g[i] * (1 - alpha)));
    return (0xff << 24) | (out[0] << 16) | (out[1] << 8) | out[2];
}

function hex(argb) {
    return hexFromArgb(argb).toUpperCase();
}

// ── Palette construction ─────────────────────────────────────────────────
function palettesFor(hue, chroma) {
    return {
        p: TonalPalette.fromHueAndChroma(hue, chroma),
        s: TonalPalette.fromHueAndChroma(hue + SECONDARY_HUE_OFFSET, 30),
        sd: TonalPalette.fromHueAndChroma(hue + SECONDARY_HUE_OFFSET, 48),
        t: TonalPalette.fromHueAndChroma(hue + TERTIARY_HUE_OFFSET, chroma),
    };
}

// System palettes as Android builds them from a wallpaper seed (tonal spot:
// accent1 chroma 36 — 48 on Android 12 — accent2 16, accent3 24, tertiary
// rotated +60°). Only used to SIMULATE the System overlay for the check.
function systemPalettesFor(hue, a1Chroma) {
    const a1 = TonalPalette.fromHueAndChroma(hue, a1Chroma);
    const a2 = TonalPalette.fromHueAndChroma(hue, 16);
    const a3 = TonalPalette.fromHueAndChroma(hue + 60, 24);
    return { p: a1, s: a2, sd: a2, t: a3 };
}

function buildScheme(pals, mode, snap) {
    const out = {};
    for (const [role, [pal, tone]] of Object.entries(MAP[mode])) {
        const t = snap ? systemTone(role, mode, tone) : tone;
        out[role] = pals[pal].tone(t);
    }
    return out;
}

// ── Floors ────────────────────────────────────────────────────────────────
// Each is a rule the hand-tuned coral palette already meets; the message
// names where the rule comes from.
const GROUND = {
    light: { page: argbFromHex('#FBF9FB'), surfaceVariant: argbFromHex('#E1E2E9') },
    dark: { page: argbFromHex('#131315'), surfaceVariant: argbFromHex('#44474C') },
    oled: { page: argbFromHex('#000000'), surfaceVariant: argbFromHex('#1A1A1C') },
};
const BLACK = argbFromHex('#000000');
// MimeTypeThumbnail.COLOR_FALLBACK_GROUND — the Captured grid tile the CC
// tag (?attr/colorPrimaryFixedDim) sits on.
const TILE_GROUND = argbFromHex('#4A2120');

function checkScheme(label, c, groundKey) {
    const g = GROUND[groundKey];
    const fails = [];
    const need = (name, value, floor) => {
        if (value < floor) {
            fails.push(`${label}: ${name} ${value.toFixed(2)} < ${floor}`);
        }
    };
    need('onPrimary/primary (filled buttons)', contrast(c.colorOnPrimary, c.colorPrimary), 4.5);
    // A primary container (FAB fills) must read as the ACTING hue, not a pastel.
    // Coral's light container is T69 beside a T65 primary (a 4-tone lift);
    // the shipped-then-caught System value was +10 (T80 over T70), so the
    // bound sits between them.
    const fabLift = Hct.fromInt(c.colorPrimaryContainer).tone - Hct.fromInt(c.colorPrimary).tone;
    if (fabLift > 6) {
        fails.push(`${label}: primaryContainer (FAB fill) ${fabLift.toFixed(1)} tones lighter than primary — reads pastel`);
    }
    need('onPrimaryContainer/primaryContainer (FAB icon)',
        contrast(c.colorOnPrimaryContainer, c.colorPrimaryContainer), 4.5);
    need('onSecondaryContainer/secondaryContainer (segments, code box)',
        contrast(c.colorOnSecondaryContainer, c.colorSecondaryContainer), 4.5);
    need('onTertiary/tertiary', contrast(c.colorOnTertiary, c.colorTertiary), 4.5);
    need('onTertiaryContainer/tertiaryContainer',
        contrast(c.colorOnTertiaryContainer, c.colorTertiaryContainer), 4.5);
    need('checked chip label', contrast(c.fdColorOnChipChecked, c.fdColorChipChecked), 4.5);
    // @color/progress_indicator: 3:1 against BOTH tracks it meets.
    need('progress vs primary@20% track',
        contrast(c.fdColorProgressIndicator, over(c.colorPrimary, 0.2, g.page)), 3.0);
    need('progress vs surfaceVariant track (credit meter)',
        contrast(c.fdColorProgressIndicator, g.surfaceVariant), 3.0);
    // md_theme_secondaryContainer (night): the segmented control must stay
    // quieter than the CTA it feeds.
    const segment = contrast(c.colorSecondaryContainer, g.page);
    const cta = contrast(c.colorPrimary, g.page);
    if (!(segment < cta)) {
        fails.push(`${label}: secondaryContainer vs page ${segment.toFixed(2)} not quieter than primary ${cta.toFixed(2)}`);
    }
    need('CC tag (primaryFixedDim) on the grid fallback tile',
        contrast(c.colorPrimaryFixedDim, TILE_GROUND), 4.5);
    return fails;
}

// Player time bar, on the player's opaque black window in either theme.
// TimeBarColors.java derives buffered/unplayed as primary at 0x80/0x26.
function checkScrubber(label, primary) {
    const fails = [];
    const buffered = over(primary, 0x80 / 255, BLACK);
    const unplayed = over(primary, 0x26 / 255, BLACK);
    if (contrast(primary, unplayed) < 3.0) {
        fails.push(`${label}: scrubber played/unplayed ${contrast(primary, unplayed).toFixed(2)} < 3`);
    }
    if (contrast(primary, buffered) < 2.9) {
        // Coral itself measures 3.08 here; the floor keeps a hair of slack
        // for gamut rounding in other hues rather than failing on it.
        fails.push(`${label}: scrubber played/buffered ${contrast(primary, buffered).toFixed(2)} < 2.9`);
    }
    return fails;
}

// ── Reading the hand-tuned coral palette back ─────────────────────────────
function readColors(file) {
    const xml = readFileSync(join(RES, file), 'utf8');
    const out = {};
    for (const m of xml.matchAll(/<color name="([^"]+)">(#[0-9A-Fa-f]{6,8})<\/color>/g)) {
        out[m[1]] = argbFromHex('#' + m[2].slice(-6));
    }
    return out;
}

function coralScheme(mode) {
    const base = readColors('values/colors.xml');
    const night = mode === 'dark' ? readColors('values-night/colors.xml') : {};
    const v = (name) => {
        const value = night[name] ?? base[name];
        if (value === undefined) throw new Error('missing colour ' + name);
        return value;
    };
    return {
        colorPrimary: v('md_theme_primary'),
        colorOnPrimary: v('md_theme_onPrimary'),
        colorPrimaryContainer: v('md_theme_primaryContainer'),
        colorOnPrimaryContainer: v('md_theme_onPrimaryContainer'),
        colorSecondaryContainer: v('md_theme_secondaryContainer'),
        colorOnSecondaryContainer: v('md_theme_onSecondaryContainer'),
        colorTertiary: v('md_theme_tertiary'),
        colorOnTertiary: v('md_theme_onTertiary'),
        colorTertiaryContainer: v('md_theme_tertiaryContainer'),
        colorOnTertiaryContainer: v('md_theme_onTertiaryContainer'),
        colorPrimaryFixedDim: v('md_theme_primaryFixedDim'),
        fdColorProgressIndicator: v('progress_indicator'),
        fdColorChipChecked: v('chip_checked_container'),
        fdColorOnChipChecked: v('chip_checked_on_container'),
    };
}

// ── Generation ───────────────────────────────────────────────────────────
const HEADER = `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by scripts/theme-palettes/palettes.mjs — do not edit by hand.
     Change the preset table or the tone map there, then run
     \`npm run generate && npm run check\` in that directory. -->
`;

function item(name, value) {
    return `        <item name="${name}">${value}</item>`;
}

function presetStyle(preset, mode) {
    const pals = palettesFor(preset.hue, preset.chroma);
    const c = buildScheme(pals, mode, false);
    const lines = [];
    lines.push(`    <style name="ThemeOverlay.FireDown.Accent.${preset.name}" parent="">`);
    for (const [role, value] of Object.entries(c)) lines.push(item(role, hex(value)));
    // Platform accent: read by GeckoView's web-content ::selection colour
    // on the APPLICATION context (App.onCreate applies this overlay there).
    lines.push(item('android:colorAccent', hex(c.colorPrimary)));
    lines.push('    </style>');
    return { xml: lines.join('\n'), scheme: c };
}

function systemStyle(mode) {
    const lines = [];
    lines.push('    <style name="ThemeOverlay.FireDown.Accent.System" parent="">');
    let primaryRef = null;
    for (const [role, [pal, tone]] of Object.entries(MAP[mode])) {
        const ref = `@android:color/system_${SYSTEM_PALETTE[pal]}_${stepForTone(systemTone(role, mode, tone))}`;
        if (role === 'colorPrimary') primaryRef = ref;
        lines.push(item(role, ref));
    }
    lines.push(item('android:colorAccent', primaryRef));
    lines.push('    </style>');
    return lines.join('\n');
}

function generate() {
    for (const mode of ['light', 'dark']) {
        const dir = mode === 'light' ? 'values' : 'values-night';
        const parts = [HEADER + '<resources>'];
        if (mode === 'light') {
            parts.push(`    <!-- Swatch shown beside each preset in Settings → Theme (its primary). -->`);
            for (const p of PRESETS) {
                const c = buildScheme(palettesFor(p.hue, p.chroma), 'light', false);
                parts.push(`    <color name="accent_swatch_${p.id}">${hex(c.colorPrimary)}</color>`);
            }
            parts.push('', `    <!-- Placeholder so R.style.ThemeOverlay_FireDown_Accent_System exists
         below API 31. Never applied there: ThemeAccent gates System on S. -->`);
            parts.push('    <style name="ThemeOverlay.FireDown.Accent.System" parent="" />');
        }
        for (const p of PRESETS) parts.push('', presetStyle(p, mode).xml);
        parts.push('', '</resources>', '');
        writeFileSync(join(RES, dir, 'theme_accents.xml'), parts.join('\n'));
        const sysDir = mode === 'light' ? 'values-v31' : 'values-night-v31';
        writeFileSync(join(RES, sysDir, 'theme_accents_system.xml'),
            [HEADER + '<resources>', systemStyle(mode), '</resources>', ''].join('\n'));
    }
    console.log('generated', PRESETS.length, 'presets + System (light + dark)');
}

// ── Check ────────────────────────────────────────────────────────────────
function check() {
    const fails = [];
    let checked = 0;
    const run = (label, scheme, mode) => {
        fails.push(...checkScheme(label, scheme, mode));
        if (mode === 'dark') fails.push(...checkScheme(label + ' (OLED)', scheme, 'oled'));
        fails.push(...checkScrubber(label, scheme.colorPrimary));
        checked++;
    };
    for (const mode of ['light', 'dark']) {
        run(`firedown/${mode}`, coralScheme(mode), mode);
        for (const p of PRESETS) {
            run(`${p.id}/${mode}`, buildScheme(palettesFor(p.hue, p.chroma), mode, false), mode);
        }
        // System: every wallpaper hue, both the Android 12 and 13+ accent1 chroma.
        for (let hue = 0; hue < 360; hue += 5) {
            for (const chroma of [36, 48]) {
                run(`system/h${hue}c${chroma}/${mode}`,
                    buildScheme(systemPalettesFor(hue, chroma), mode, true), mode);
            }
        }
    }
    // The generated files must match what the table produces now.
    for (const mode of ['light', 'dark']) {
        const dir = mode === 'light' ? 'values' : 'values-night';
        const xml = readFileSync(join(RES, dir, 'theme_accents.xml'), 'utf8');
        for (const p of PRESETS) {
            if (!xml.includes(presetStyle(p, mode).xml)) {
                fails.push(`${dir}/theme_accents.xml is stale for ${p.id} — run npm run generate`);
            }
        }
    }
    if (fails.length) {
        console.error(fails.join('\n'));
        console.error(`\nFAIL: ${fails.length} violation(s) across ${checked} palettes`);
        process.exit(1);
    }
    console.log(`OK: ${checked} palettes meet every floor (firedown, ${PRESETS.length} presets, System swept over 72 hues x 2 chromas, light/dark/OLED)`);
}

const mode = process.argv[2];
if (mode === 'generate') generate();
else if (mode === 'check') check();
else {
    console.error('usage: palettes.mjs generate|check');
    process.exit(2);
}
