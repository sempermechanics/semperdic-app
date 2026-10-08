// scripts/console_coverage.mjs: the gate on the console's line coverage. It
// must merge a file's copies (`page.js?load=N`) into one, take each line from
// the innermost V8 range, and count a file no test loads as 0 %.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { coveredLines, linesOf, merge } from "../../scripts/console_coverage.mjs";

// Line 1 always runs; the if body (lines 3-4) runs only when x is set.
const SOURCE = "const a = 1;\nif (x) {\n  b();\n  c();\n}\n\nexport { a };\n";
const BODY_START = SOURCE.indexOf("{");
const BODY_END = SOURCE.indexOf("}") + 1;

function ran(bodyCount) {
  return [{
    functionName: "",
    ranges: [
      { startOffset: 0, endOffset: SOURCE.length, count: 1 },
      { startOffset: BODY_START, endOffset: BODY_END, count: bodyCount },
    ],
  }];
}

test("blank lines are not code", () => {
  assert.deepEqual(linesOf(SOURCE).map((l) => l.blank), [false, false, false, false, false, true, false, true]);
});

test("a line takes the count of the innermost range that holds it", () => {
  // 0-based: the body's lines (2, 3) and its closing brace (4) did not run.
  assert.deepEqual([...coveredLines(SOURCE, ran(0))].sort(), [0, 1, 6]);
  assert.deepEqual([...coveredLines(SOURCE, ran(3))].sort(), [0, 1, 2, 3, 4, 6]);
});

test("copies of one file merge into one row, and an unloaded file counts as 0 %", () => {
  const dir = mkdtempSync(join(tmpdir(), "console-coverage-"));
  const page = join(dir, "page.js");
  const unloaded = join(dir, "unloaded.js");
  writeFileSync(page, SOURCE);
  writeFileSync(unloaded, "export const never = 1;\n");
  const url = pathToFileURL(page).href;
  const merged = merge([
    { url: `${url}?load=1`, functions: ran(0) },
    { url: `${url}?load=2`, functions: ran(2) },
    { url: "node:internal/whatever", functions: [] },
  ], [page, unloaded]);

  assert.equal(merged.get(page).code, 6);
  assert.equal(merged.get(page).covered.size, 6, "the second copy ran the body");
  assert.equal(merged.get(unloaded).code, 1);
  assert.equal(merged.get(unloaded).covered.size, 0);
});

test("a VM script named by its path counts for that file", () => {
  const dir = mkdtempSync(join(tmpdir(), "console-coverage-"));
  const script = join(dir, "reset.js");
  writeFileSync(script, SOURCE);
  const merged = merge([{ url: script, functions: ran(1) }], [script]);
  assert.equal(merged.get(script).covered.size, 6);
});
