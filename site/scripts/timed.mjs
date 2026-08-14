/**
 * Runs one of this package's scripts and prints what it cost.
 *
 * The site build is a chain of six generators and an Astro pass, and when it got slow there
 * was no way to tell which link was the problem short of running them one at a time by hand.
 * This wraps a step, prints its wall clock, and appends it to `build-timings.log` so a slow
 * build can be compared against a fast one rather than remembered.
 *
 * It reports rather than enforces: a build is allowed to be slow, and a threshold here would
 * be a number nobody chose failing a build for a machine that was busy.
 */
import { spawn } from "node:child_process";
import { appendFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const LOG = path.join(SITE_DIR, "build-timings.log");

const [label, ...command] = process.argv.slice(2);
if (!label || command.length === 0) {
  console.error("usage: node scripts/timed.mjs <label> <command> [args...]");
  process.exit(2);
}

const started = process.hrtime.bigint();
const child = spawn(command[0], command.slice(1), { stdio: "inherit", shell: false });

child.on("exit", async (code, signal) => {
  const ms = Number(process.hrtime.bigint() - started) / 1e6;
  const seconds = (ms / 1000).toFixed(1);
  // stderr, so a build whose stdout is being captured still shows its timings.
  console.error(`  ⏱  ${label}: ${seconds}s`);
  try {
    await appendFile(LOG, `${new Date().toISOString()}\t${label}\t${ms.toFixed(0)}\n`, "utf8");
  } catch {
    // A timing log that cannot be written must not fail a build that otherwise worked.
  }
  process.exit(signal ? 1 : (code ?? 0));
});
