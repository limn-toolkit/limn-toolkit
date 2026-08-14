import type { APIRoute } from "astro";

/**
 * `robots.txt`, and the one line in it that earns its place: where the sitemap is.
 *
 * The sitemap URL has to be absolute, so it is emitted only when the real hostname is
 * configured, the same rule the canonical and Open Graph tags follow. A `Sitemap:` line
 * pointing at `http://localhost:4321` is worse than no line at all.
 *
 * Nothing is disallowed. Every route here is meant to be found, including the generated
 * ones: `/api/` is the reference people search for by class name.
 */
export const GET: APIRoute = ({ site }) => {
  const configured = Boolean(process.env.SITE_URL) && site !== undefined;
  const lines = ["User-agent: *", "Allow: /"];
  if (configured) {
    lines.push("", `Sitemap: ${new URL("sitemap-index.xml", site).href}`);
  }
  return new Response(`${lines.join("\n")}\n`, {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
};
