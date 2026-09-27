// What CLDR says about a locale, for the tables this toolkit writes out by hand: which digits a
// language writes numbers in, which direction its script runs, and which region a language named
// alone is taken to mean.
//
// The sibling of dump-cldr-plurals.mjs and the same argument: Node's Intl is CLDR through ICU,
// so it is the source limn.i18n.NumberingSystem, limn.scene.LayoutDirection and the likely
// regions in limn.components.date.CalendarChronology are supposed to agree with, and a table
// nobody checks is a table that drifts. Run it and write its output over the file the test reads:
//
//   node scripts/i18n/dump-cldr-locale-facts.mjs > \
//       limn-toolkit/src/test/resources/limn/i18n/cldr-locale-facts.txt
//
// CldrLocaleFactsTest compares the first two tables against it and LikelyRegionsTest the third.
// Nothing in the build runs Node.
//
// The direction search is exhaustive over the shape of a language subtag rather than over a list
// somebody curated: every two-letter code ISO 639-1 has and every three-letter combination there
// is, 17,576 of them, asked one by one. That is how the 269 right-to-left languages the first
// hand-written table missed were found; a list of "the ones we thought of" would have missed them
// again.
//
// The digits search covers the same languages, and for each the scripts ICU holds it in, found
// the same way: every four-letter code CLDR has a script name for, tried on every language. A
// script ICU holds no data for is not searched, because Intl's locale negotiation then drops the
// region along with the script and answers for the language alone: ar-Arab-EG answers latn where
// ar-EG answers arab. That is an artifact of the lookup, not a fact about how Arabic is written in
// Egypt, and a row carrying it would teach the Java table something false.

const TWO_LETTER = [];
for (let a = 97; a < 123; a++) {
    for (let b = 97; b < 123; b++) {
        TWO_LETTER.push(String.fromCharCode(a, b));
    }
}
const THREE_LETTER = [];
for (let a = 97; a < 123; a++) {
    for (let b = 97; b < 123; b++) {
        for (let c = 97; c < 123; c++) {
            THREE_LETTER.push(String.fromCharCode(a, b, c));
        }
    }
}

// Every two-letter region code, asked of every language whose digits are asked about at all, which
// is every language code there is: a language with non-Latin digits is rare enough that the whole
// space is cheaper than a guess.
const REGIONS = [];
for (let a = 65; a < 91; a++) {
    for (let b = 65; b < 91; b++) {
        REGIONS.push(String.fromCharCode(a, b));
    }
}

// Every four-letter code CLDR names as a script, found by asking for the English name of each of
// the 456,976 there could be. Unicode's own script list would miss the ISO codes that are not a
// Unicode script value, Hans and Hant among them.
const SCRIPTS = [];
const scriptNames = new Intl.DisplayNames(["en"], { type: "script", fallback: "none" });
for (let a = 65; a < 91; a++) {
    for (let b = 97; b < 123; b++) {
        for (let c = 97; c < 123; c++) {
            for (let d = 97; d < 123; d++) {
                const code = String.fromCharCode(a, b, c, d);
                if (scriptNames.of(code) !== undefined) {
                    SCRIPTS.push(code);
                }
            }
        }
    }
}

function direction(tag) {
    try {
        return new Intl.Locale(tag).getTextInfo().direction;
    } catch {
        return null;
    }
}

// The numbering system ICU resolves for a tag, or null where ICU has no data for that language
// and fell back to the default locale. Without that guard a tag ICU does not know answers "latn"
// exactly as a language that really writes Latin digits does — and worse: the deprecated code
// "bh" falls back to en-US on its own and canonicalizes to "bho" the moment a region is attached,
// so it reported a difference in all 249 regions and buried the twenty-odd real ones. A tag that
// names a script gets the same guard for its script: ICU must have kept it, or the answer is the
// language's and not the script's.
function numbering(tag, script) {
    try {
        const resolved = new Intl.NumberFormat(tag).resolvedOptions();
        const language = tag.split("-")[0];
        const kept = resolved.locale.split("-");
        if (kept[0] !== language || (script !== undefined && kept[1] !== script)) {
            return null;
        }
        return resolved.numberingSystem;
    } catch {
        return null;
    }
}

const out = [];
out.push("# CLDR locale facts, dumped from ICU.");
out.push(`# node ${process.version}, ICU ${process.versions.icu}, Unicode ${process.versions.unicode}`);
out.push(`# produced by scripts/i18n/dump-cldr-locale-facts.mjs on ${new Date().toISOString().slice(0, 10)}`);
out.push("#");
out.push("# rtl <language subtag> ...   every subtag ICU writes right to left, searched over all");
out.push("#                             two- and three-letter codes");
out.push("# digits <language> <system>  a language, of two letters or three, whose default digits");
out.push("#                             are not latn");
out.push("# digits <language>-<REGION> <system>   a region that differs from its language");
out.push("# digits <language>-<Script> <system>   a script ICU holds the language in that differs");
out.push("#                             from the language");
out.push("# digits <language>-<Script>-<REGION> <system>   a region that differs from its language");
out.push("#                             in that script, or from a region row of the language");
out.push("# likely <language>-<REGION> ...   the region ICU's likely subtags give a two-letter");
out.push("#                             language named alone");

const rtl = [...TWO_LETTER, ...THREE_LETTER].filter(tag => direction(tag) === "rtl");
for (let i = 0; i < rtl.length; i += 16) {
    out.push("rtl " + rtl.slice(i, i + 16).join(" "));
}

// A language's rows: its own system, the regions that differ from it, then each script ICU holds
// it in that differs from it, and each region that differs from the script. A script-and-region
// row is also written wherever the script answers otherwise than a region row of the language,
// since that is where a table that read the region first would go wrong: pa-PK is Punjabi in
// Arabic script and writes Extended Arabic-Indic digits, and pa-Guru-PK, Gurmukhi in Pakistan,
// writes Latin ones.
for (const language of [...TWO_LETTER, ...THREE_LETTER]) {
    const base = numbering(language);
    if (base === null) {
        continue;
    }
    const rows = [];
    if (base !== "latn") {
        rows.push(`digits ${language} ${base}`);
    }
    const byRegion = new Map();
    for (const region of REGIONS) {
        const here = numbering(`${language}-${region}`);
        if (here !== null && here !== base) {
            byRegion.set(region, here);
            rows.push(`digits ${language}-${region} ${here}`);
        }
    }
    for (const script of SCRIPTS) {
        const written = numbering(`${language}-${script}`, script);
        if (written === null) {
            continue;
        }
        if (written !== base) {
            rows.push(`digits ${language}-${script} ${written}`);
        }
        for (const region of REGIONS) {
            const here = numbering(`${language}-${script}-${region}`, script);
            if (here === null) {
                continue;
            }
            const regionRow = byRegion.get(region);
            if (here !== written || (regionRow !== undefined && here !== regionRow)) {
                rows.push(`digits ${language}-${script}-${region} ${here}`);
            }
        }
    }
    out.push(...rows);
}

// The region a language named alone stands for: what the week of "de" or "fr" is, since a week's
// first day and numbering are a region's and Java's Locale has no maximize() to find one. A code
// ICU does not know maximizes to itself with no region and is left out, and so is an old code
// that maximizes to another language ("iw" is "he"), which Locale normalizes on its own.
const likely = [];
for (const language of TWO_LETTER) {
    try {
        const maximized = new Intl.Locale(language).maximize();
        if (maximized.language === language && maximized.region) {
            likely.push(`${language}-${maximized.region}`);
        }
    } catch {
        // not a language subtag ICU accepts
    }
}
for (let i = 0; i < likely.length; i += 12) {
    out.push("likely " + likely.slice(i, i + 12).join(" "));
}

process.stdout.write(out.join("\n") + "\n");
