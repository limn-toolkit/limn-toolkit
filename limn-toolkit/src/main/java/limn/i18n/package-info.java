/**
 * Internationalization: a localizable string as a value ({@link limn.i18n.I18nString}),
 * the process-wide language it resolves against ({@link limn.i18n.I18n}), and the
 * translation sources it reads ({@link limn.i18n.StringBundle},
 * {@link limn.i18n.PropertyBundle}).
 *
 * <p>A localizable string is a value the component stores, not a lookup it performs:
 * capturing it is then the correct thing to do rather than the bug. The text pipeline
 * draws Latin, Greek, Cyrillic and CJK; scripts needing shaping or bidi do not render
 * correctly yet, whatever translations are registered.
 */
package limn.i18n;
