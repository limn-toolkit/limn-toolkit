// What CLDR says about a locale, for the tables this toolkit writes out by hand: which digits a
// language writes numbers in, which direction it runs in and where a region or a script turns it,
// which region a language named alone is taken to mean, and which codes are another language's.
//
// The sibling of dump-cldr-plurals.mjs and the same argument: Node's Intl is CLDR through ICU,
// so it is the source limn.i18n.NumberingSystem, limn.scene.LayoutDirection and the likely
// regions in limn.components.date.CalendarChronology are supposed to agree with, and a table
// nobody checks is a table that drifts. Run it and write its output over the file the test reads:
//
//   node scripts/i18n/dump-cldr-locale-facts.mjs > \
//       limn-toolkit/src/test/resources/limn/i18n/cldr-locale-facts.txt
//
// CldrLocaleFactsTest compares the first two tables and the aliases against it, and
// LikelyRegionsTest the third.
// Nothing in the build runs Node.
//
// The direction search is exhaustive over the shape of a language subtag rather than over a list
// somebody curated: every two-letter code ISO 639-1 has and every three-letter combination there
// is, 17,576 of them, asked one by one. That is how the 269 right-to-left languages the first
// hand-written table missed were found; a list of "the ones we thought of" would have missed them
// again.
//
// A language alone is half of it. ICU reads a direction off the locale's script, and a locale that
// names none off the script its likely subtags give the language in the locale's region: pa-PK,
// Punjabi in Pakistan, is Punjabi in Arabic script and runs right to left where pa runs left to
// right, and sd-IN, Sindhi in India, is Devanagari and runs left to right where sd does not. So the
// direction search also asks every one of those codes in every two-letter region, some 12 million
// tags and nearly three minutes where the rest of the file takes seconds, and writes the regions
// that turn a language the other way. It asks the codes ICU reads as another language too, as the
// rtl search did, since pan-PK is pa-PK, and und, which names no language, since ICU gives a region
// alone its likely language: und-SA is Arabic. Then it asks each language in the scripts ICU holds
// it in and writes those that run the other way, where holding it means a script its likely
// subtags give it in some region (az-IR is az-Arab) or one ICU has locale data for it in (ks-Deva,
// ff-Adlm). A script ICU does not hold a language in is not asked: sd-Latn runs left to right as
// every Latin tag does, and a row for it would say nothing about Sindhi.
//
// The digits search covers the same languages, and for each the scripts ICU holds it in, found
// the same way: every four-letter code CLDR has a script name for, tried on every language. A
// script ICU holds no data for is not searched, because Intl's locale negotiation then drops the
// region along with the script and answers for the language alone: ar-Arab-EG answers latn where
// ar-EG answers arab. That is an artifact of the lookup, not a fact about how Arabic is written in
// Egypt, and a row carrying it would teach the Java table something false.
//
// The alias search asks the same space which codes ICU reads as another language before it looks
// anything up: pes, Iranian Persian, is fa; prs, Dari, is fa-AF; arb, Standard Arabic, is ar. The
// digits search cannot see them, since its guard drops a tag ICU answers for under another
// language, and Java's Locale replaces only three of them itself (iw, ji and in), so a table keyed
// on the language subtag answers every other one as a language nobody listed. The replacement is
// new Intl.Locale(code).baseName, ICU's canonicalization with CLDR's alias data, and not
// Intl.getCanonicalLocales, which leaves bh and tw alone when they are named alone and replaces
// them the moment a region is attached (bh-IN is bho-IN); Intl.NumberFormat then falls back to the
// default locale for the bare code, which is the same artifact of the lookup the digits search
// refuses, not a fact about Bhojpuri. A replacement can carry a script or a region, sh is sr-Latn,
// and fills in only what the tag leaves out: prs-IR is fa-IR and sh-Cyrl is sr-Cyrl.

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

// The scripts ICU holds a language in, in SCRIPTS' order: each one its likely subtags give the
// language, alone or in some region, and each one ICU keeps locale data for it in. Empty for a
// code ICU reads as another language, whose likely subtags are its replacement's, and for und.
function heldScripts(language) {
    const likely = tag => {
        try {
            const maximized = new Intl.Locale(tag).maximize();
            return maximized.language === language ? maximized.script : undefined;
        } catch {
            return undefined;
        }
    };
    const held = new Set();
    if (likely(language) !== undefined) {
        for (const tag of [language, ...REGIONS.map(region => `${language}-${region}`)]) {
            const script = likely(tag);
            if (script !== undefined) {
                held.add(script);
            }
        }
    }
    if (numbering(language) !== null) {
        for (const script of SCRIPTS) {
            if (numbering(`${language}-${script}`, script) !== null) {
                held.add(script);
            }
        }
    }
    return SCRIPTS.filter(script => held.has(script));
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
out.push("# direction <language>-<REGION> <ltr|rtl>   a region that differs from its language, of");
out.push("#                             every code the rtl rows searched, und (no language) included");
out.push("# direction <language>-<Script> <ltr|rtl>   a script ICU holds the language in that differs");
out.push("#                             from the language");
out.push("# direction <language>-<Script>-<REGION> <ltr|rtl>   a region that differs from its");
out.push("#                             language in that script, or from a region row of the language");
out.push("# digits <language> <system>  a language, of two letters or three, whose default digits");
out.push("#                             are not latn");
out.push("# digits <language>-<REGION> <system>   a region that differs from its language");
out.push("# digits <language>-<Script> <system>   a script ICU holds the language in that differs");
out.push("#                             from the language");
out.push("# digits <language>-<Script>-<REGION> <system>   a region that differs from its language");
out.push("#                             in that script, or from a region row of the language");
out.push("# likely <language>-<REGION> ...   the region ICU's likely subtags give a two-letter");
out.push("#                             language named alone");
out.push("# alias <code> <replacement>   a two- or three-letter code ICU reads as another language,");
out.push("#                             and the script or region the replacement carries");

const rtl = [...TWO_LETTER, ...THREE_LETTER].filter(tag => direction(tag) === "rtl");
for (let i = 0; i < rtl.length; i += 16) {
    out.push("rtl " + rtl.slice(i, i + 16).join(" "));
}

// A language's direction rows, in the digits rows' shape: the regions that turn it, then each
// script ICU holds it in that runs the other way, and each region that differs from the script.
// ICU 78.3 writes none of the last kind, since a named script decides alone, and the search asks
// anyway so that the day one does the file says so. A script-and-region row is also written
// wherever the script answers otherwise than a region row of the language, since that is where a
// table that read the region first would go wrong: pa-PK runs right to left and pa-Guru-PK,
// Gurmukhi in Pakistan, left to right.
for (const language of [...TWO_LETTER, ...THREE_LETTER]) {
    const base = direction(language);
    if (base === null) {
        continue;
    }
    const rows = [];
    const byRegion = new Map();
    for (const region of REGIONS) {
        const here = direction(`${language}-${region}`);
        if (here !== null && here !== base) {
            byRegion.set(region, here);
            rows.push(`direction ${language}-${region} ${here}`);
        }
    }
    for (const script of heldScripts(language)) {
        const written = direction(`${language}-${script}`);
        if (written !== base) {
            rows.push(`direction ${language}-${script} ${written}`);
        }
        for (const region of REGIONS) {
            const here = direction(`${language}-${script}-${region}`);
            const regionRow = byRegion.get(region);
            if (here !== written || (regionRow !== undefined && here !== regionRow)) {
                rows.push(`direction ${language}-${script}-${region} ${here}`);
            }
        }
    }
    out.push(...rows);
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

// Every code ICU replaces with another language, one to a row since each carries its replacement.
for (const code of [...TWO_LETTER, ...THREE_LETTER]) {
    let replacement;
    try {
        replacement = new Intl.Locale(code).baseName;
    } catch {
        continue;
    }
    if (replacement.split("-")[0] !== code) {
        out.push(`alias ${code} ${replacement}`);
    }
}

process.stdout.write(out.join("\n") + "\n");
