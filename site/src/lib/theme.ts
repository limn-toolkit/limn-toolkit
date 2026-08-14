/**
 * The theme contract. The two values the pre-paint script needs live in
 * `theme-script.mjs`, because the Javadoc injector is a plain Node script and has to read
 * exactly the same definition; see the note there. This file adds the types and the
 * mapping the toggle needs.
 */
export { NO_FLASH_SCRIPT, THEME_COLOR_SCRIPT, THEME_STORAGE_KEY } from "./theme-script.mjs";

/** What the reader chose. `system` follows `prefers-color-scheme` and is the default. */
export type ThemeChoice = "system" | "light" | "dark";

/** What ends up on `<html data-theme>`: the resolved value, never `system`. */
export type ResolvedTheme = "light" | "dark";

/** `system` is stored as the empty string, which is what Starlight writes for auto. */
export function toStored(choice: ThemeChoice): string {
  return choice === "system" ? "" : choice;
}

export function fromStored(raw: string | null): ThemeChoice {
  return raw === "light" || raw === "dark" ? raw : "system";
}
