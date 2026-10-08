#!/usr/bin/env node
// Line coverage of the console's own JavaScript, merged by file.
//
// The console tests open each page afresh by importing it as `page.js?load=N`
// (firebase-hosting/tests/firebase-hooks.mjs), so a page's modules load many
// times per run. Node's --experimental-test-coverage keeps each URL apart: a
// file's row shows one copy, and the "all files" figure falls as tests open
// pages more often (TD-203). This reads the raw V8 coverage instead
// (NODE_V8_COVERAGE), drops the query, and counts a line covered when any
// copy of its file ran it.
//
// Every .js file under firebase-hosting/public counts, loaded or not, except
// the vendored QR encoder: a module no test imports shows 0 %, not nothing.
//
// Usage:
//   NODE_V8_COVERAGE=<dir> node --test "firebase-hosting/tests/*.test.mjs"
//   node scripts/console_coverage.mjs <dir> [--lines=<min %>]
// Exits 1 when the total is under --lines.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const PUBLIC = join(ROOT, "firebase-hosting", "public");

/** Start and end offset of each line of `source`, and whether it has code. */
export function linesOf(source) {
  const lines = [];
  let start = 0;
  for (const text of source.split("\n")) {
    lines.push({ start, end: start + text.length, blank: text.trim() === "" });
    start += text.length + 1;
  }
  return lines;
}

/**
 * Which lines one loaded copy ran: each line takes the count of the smallest
 * V8 range that wholly contains it (the innermost block), so a skipped branch
 * marks its own lines and nothing around it.
 */
export function coveredLines(source, functions) {
  const lines = linesOf(source);
  const counts = new Array(lines.length).fill(0);
  const ranges = functions.flatMap((fn) => fn.ranges)
    .sort((a, b) => (b.endOffset - b.startOffset) - (a.endOffset - a.startOffset));
  for (const range of ranges) {
    lines.forEach((line, i) => {
      if (line.start >= range.startOffset && line.end <= range.endOffset) counts[i] = range.count;
    });
  }
  return new Set(lines.flatMap((line, i) => (!line.blank && counts[i] > 0 ? [i] : [])));
}

function consoleFiles(dir = PUBLIC) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "vendor" ? [] : consoleFiles(path);
    return name.endsWith(".js") ? [path] : [];
  });
}

/** Per file: lines with code, and those any copy ran. */
export function merge(results, files) {
  const byPath = new Map(files.map((path) => {
    const source = readFileSync(path, "utf8");
    return [path, { source, code: linesOf(source).filter((l) => !l.blank).length, covered: new Set() }];
  }));
  for (const script of results) {
    // Modules report a file: URL (with ?load=N for a page's own copy); a VM
    // script run with a filename reports that path as it was given.
    const url = script.url.replace(/\?.*$/, "");
    const path = url.startsWith("file:") ? fileURLToPath(url) : url;
    const entry = byPath.get(path);
    if (!entry) continue;
    for (const line of coveredLines(entry.source, script.functions)) entry.covered.add(line);
  }
  return byPath;
}

function readCoverage(dir) {
  return readdirSync(dir)
    .filter((name) => name.endsWith(".json"))
    .flatMap((name) => JSON.parse(readFileSync(join(dir, name), "utf8")).result ?? []);
}

function main(argv) {
  const dir = argv.find((a) => !a.startsWith("--"));
  const min = Number((argv.find((a) => a.startsWith("--lines=")) ?? "--lines=0").slice(8));
  if (!dir) {
    console.error("usage: console_coverage.mjs <NODE_V8_COVERAGE dir> [--lines=<min %>]");
    return 2;
  }
  const merged = merge(readCoverage(dir), consoleFiles());
  let code = 0;
  let covered = 0;
  const rows = [...merged].sort(([a], [b]) => a.localeCompare(b)).map(([path, entry]) => {
    code += entry.code;
    covered += entry.covered.size;
    const pct = entry.code ? (100 * entry.covered.size) / entry.code : 100;
    return `${pct.toFixed(2).padStart(7)} %  ${String(entry.covered.size).padStart(5)} / ${String(entry.code).padEnd(5)} ` +
      relative(PUBLIC, path).split(sep).join("/");
  });
  const total = code ? (100 * covered) / code : 100;
  console.log(rows.join("\n"));
  console.log(`${total.toFixed(2).padStart(7)} %  ${String(covered).padStart(5)} / ${String(code).padEnd(5)} all files (merged by path)`);
  if (total < min) {
    console.error(`Console line coverage ${total.toFixed(2)} % is under the floor of ${min} %.`);
    code = 1;
  } else {
    code = 0;
  }
  return code;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exit(main(process.argv.slice(2)));
