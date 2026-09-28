/**
 * What the toolkit's language handling shares: the language a locale's code stands for, so that
 * everything that decides by language reads {@code pes} as Persian and {@code fra} as French, and
 * the region its UN number stands for ({@code 364} is Iran); the region a language named alone
 * most likely means; and the locale a number is written in, so that every widget that writes one
 * pairs the digits it shows with the separators that go with them.
 *
 * <p><b>Not API.</b> The module does not export this package, and an application should not name
 * it; it changes without notice.
 */
package limn.i18n.internal;
