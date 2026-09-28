/**
 * What the toolkit's language handling shares: the language a locale's code stands for, so that
 * everything that decides by language reads {@code pes} as Persian and {@code fra} as French; the
 * platform's language services asked in that language; and the locale a number is written in, so
 * that every widget that writes one pairs the digits it shows with the separators that go with
 * them.
 *
 * <p><b>Not API.</b> The module exports this package to the backend alone, whose accessibility
 * bridges tell a screen reader which language a node is in, and an application should not name
 * it; it changes without notice.
 */
package limn.i18n.internal;
