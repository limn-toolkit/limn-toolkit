// What CLDR says a count's grammatical form is, dumped from the ICU that ships inside Node.
//
// WHY THIS EXISTS. limn.i18n.PluralRules carries CLDR's cardinal rules transcribed by hand,
// because the toolkit has no runtime dependency and the JDK exposes no plural data. A
// transcription nobody can check is a standing risk, and this is the check: Node's
// Intl.PluralRules IS the CLDR data, read through ICU, so its answers are the source this
// repository's rules are supposed to agree with.
//
// HOW TO USE IT. Run it and write its output over the file the test reads:
//
//   node scripts/i18n/dump-cldr-plurals.mjs > \
//       limn-toolkit/src/test/resources/limn/i18n/cldr-cardinal.txt
//
// PluralRulesTest.theTranscriptionAgreesWithCldr reads that file and compares every answer. Run
// this again when a language is added to the toolkit (add its tag below first), or to move to a
// newer CLDR; the header records which ICU produced the file, so a diff is readable as "CLDR
// changed" rather than as "someone edited a golden".
//
// Node and nothing else: no package, no install, no entry in any manifest. Intl is built in.

const LANGUAGES = [
    // Every language limn-toolkit ships a catalog for, plus English, which is the fallback.
    "en", "pt-BR", "pt", "es", "fr", "de", "it", "nl", "tr", "id",
    "cs", "pl", "ru", "uk", "he", "ar", "hi", "ja", "ko", "vi", "zh-Hans", "zh-Hant",
    // Named by PluralRules although no catalog ships for them, so the rules it guesses at are
    // checked too: three that share a rule with a language above, and one that takes the
    // English-shaped fallback.
    "sk", "be", "th", "sw",
];

// Beyond the dense range: the places a rule that reads only the last two digits could still be
// wrong, and the magnitudes a hand-written rule is least likely to have been tried against.
const SPOTS = [
    1001, 1002, 1011, 1021, 1022, 1101, 1111, 1121,
    10_000, 10_001, 10_011, 10_021,
    100_000, 100_001, 100_011, 100_021,
    1_000_000, 1_000_001, 1_000_011, 1_000_021, 2_000_000, 3_000_000, 2_000_001,
    123_456_788, 123_456_789, 123_456_790, 123_456_791,
];

const DENSE_MAX = 1000;
const CODE = { zero: "z", one: "1", two: "2", few: "f", many: "m", other: "o" };

const out = [];
out.push("# CLDR cardinal plural categories, dumped from ICU.");
out.push(`# node ${process.version}, ICU ${process.versions.icu}, Unicode ${process.versions.unicode}`);
out.push(`# produced by scripts/i18n/dump-cldr-plurals.mjs on ${new Date().toISOString().slice(0, 10)}`);
out.push("#");
out.push("# dense <tag> <one character per integer, 0 through " + DENSE_MAX + ">");
out.push("# spots <n> <n> ...   the integers the spot lines answer, in order");
out.push("# spot  <tag> <one character per integer of the spots line>");
out.push("#");
out.push("# z=zero 1=one 2=two f=few m=many o=other");

for (const tag of LANGUAGES) {
    const rules = new Intl.PluralRules(tag, { type: "cardinal" });
    let dense = "";
    for (let n = 0; n <= DENSE_MAX; n++) {
        dense += CODE[rules.select(n)];
    }
    out.push(`dense ${tag} ${dense}`);
}

out.push(`spots ${SPOTS.join(" ")}`);
for (const tag of LANGUAGES) {
    const rules = new Intl.PluralRules(tag, { type: "cardinal" });
    out.push(`spot ${tag} ${SPOTS.map(n => CODE[rules.select(n)]).join("")}`);
}

process.stdout.write(out.join("\n") + "\n");
