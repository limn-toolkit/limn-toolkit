import { defineCollection } from "astro:content";
import { docsLoader } from "@astrojs/starlight/loaders";
import { docsSchema } from "@astrojs/starlight/schema";

export const collections = {
  // Populated by scripts/sync-docs.mjs from src/guides/, with the code samples and the
  // screenshots expanded into it. Nothing in this directory is authored by hand, and it is
  // gitignored for that reason; edit src/guides/ instead.
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
};
