/**
 * A small build-time syntax highlighter for the code on the marketing pages.
 *
 * Deliberately tiny and deliberately not a parser. It runs once, at build time, over
 * samples this repository wrote (Java, Kotlin build files and shell), so the failure mode
 * that rules regex highlighting out for a general-purpose editor (a construct it mis-reads)
 * is a construct we can simply not write. It buys the pages colour without a runtime, a
 * dependency, or a stylesheet with two hundred token classes in it.
 *
 * The documentation pages do NOT use this: Starlight ships a real grammar-driven
 * highlighter, and /docs/ uses that one.
 */

export type Language = "java" | "kotlin" | "bash" | "text";

const JAVA_KEYWORDS = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
  "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
  "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
  "interface", "long", "native", "new", "package", "private", "protected", "public",
  "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
  "synchronized", "this", "throw", "throws", "transient", "try", "var", "void", "volatile",
  "while", "yield", "true", "false", "null",
]);

const KOTLIN_KEYWORDS = new Set([
  "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
  "interface", "internal", "is", "null", "object", "open", "override", "package", "private",
  "protected", "public", "return", "super", "this", "throw", "true", "try", "typealias",
  "val", "var", "when", "while", "by", "get", "set", "import",
]);

/** The one master pattern. Order is the precedence: a comment wins over a string in it. */
const PATTERN =
  /(?<comment>\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*)|(?<text>"""[\s\S]*?""")|(?<string>"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|(?<annotation>@\w+)|(?<number>\b\d[\d_]*(?:\.\d+)?[fFdDlL]?\b)|(?<word>[A-Za-z_$][\w$]*)/g;

const ESCAPES: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
};

function escape(text: string): string {
  return text.replace(/[&<>]/g, (character) => ESCAPES[character]);
}

function span(kind: string, text: string): string {
  return `<span class="tok-${kind}">${escape(text)}</span>`;
}

/**
 * @param code  the sample, already de-indented
 * @param language which keyword set to use; `text` returns escaped HTML and nothing else
 * @returns HTML for the inside of a `<code>` element
 */
export function highlight(code: string, language: Language): string {
  if (language === "text") return escape(code);
  const keywords =
    language === "kotlin" ? KOTLIN_KEYWORDS : language === "java" ? JAVA_KEYWORDS : new Set<string>();

  let out = "";
  let last = 0;
  PATTERN.lastIndex = 0;
  for (let match = PATTERN.exec(code); match !== null; match = PATTERN.exec(code)) {
    const groups = match.groups!;
    out += escape(code.slice(last, match.index));
    last = match.index + match[0].length;

    if (groups.comment !== undefined) {
      out += span("comment", groups.comment);
    } else if (groups.text !== undefined || groups.string !== undefined) {
      out += span("string", match[0]);
    } else if (groups.annotation !== undefined) {
      out += span("meta", groups.annotation);
    } else if (groups.number !== undefined) {
      out += span("number", groups.number);
    } else {
      const word = groups.word!;
      if (keywords.has(word)) {
        out += span("keyword", word);
      } else if (/^[A-Z]/.test(word)) {
        // A capitalised word is a type by convention here, and the convention holds in
        // every sample on this site because they are all this repository's own code.
        out += span("type", word);
      } else if (code[last] === "(") {
        out += span("call", word);
      } else {
        out += escape(word);
      }
    }
  }
  return out + escape(code.slice(last));
}
