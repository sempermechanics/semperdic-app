"""Code-quality metrics for the Semper Android app, for before/after comparison.

Usage: python scripts/quality_metrics.py <repo_root> <out_prefix>
Writes <out_prefix>.json and <out_prefix>.md. Measures app/src only; counts are
rough (regex and brace counting), so compare runs of this script with each
other, never with another tool.
"""
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = sys.argv[1]
OUT = sys.argv[2]
MAIN = os.path.join(ROOT, "app", "src", "main", "java", "com", "sempermechanics", "semper")
TEST_DIRS = {
    "test": os.path.join(ROOT, "app", "src", "test"),
    "androidTest": os.path.join(ROOT, "app", "src", "androidTest"),
}
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout")
INVARIANT = {"VisualizationEngine.kt", "ReportBuilder.kt", "GifEncoder.kt", "ScrubFrameCache.kt", "PointSpatialIndex.kt"}
RULES = ["LongParameterList", "ReturnCount", "CyclomaticComplexMethod", "LongMethod", "LargeClass",
         "TooManyFunctions", "MagicNumber", "TooGenericExceptionCaught", "NestedBlockDepth", "ComplexCondition"]


def kt_files(base):
    for d, _, fs in os.walk(base):
        for f in fs:
            if f.endswith(".kt"):
                yield os.path.join(d, f)


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def rel(p):
    return os.path.relpath(p, MAIN).replace("\\", "/")


def strip_strings(line):
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    return re.sub(r"'(?:\\.|[^'\\])'", "''", line)


FUN_RE = re.compile(r"^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|open|abstract|final)\s+)*fun\s+(?:<[^>]*>\s*)?([\w.`<>,? ]+?)\s*\(")


def functions(path, text):
    """Rough function extents by brace counting; returns (name, start, length, params)."""
    lines = text.splitlines()
    out = []
    i = 0
    in_block_comment = False
    while i < len(lines):
        raw = lines[i]
        if "/*" in raw and "*/" not in raw:
            in_block_comment = True
        if in_block_comment:
            if "*/" in raw:
                in_block_comment = False
            i += 1
            continue
        m = FUN_RE.match(raw)
        if not m:
            i += 1
            continue
        name = m.group(1).split(".")[-1].strip()
        # signature: collect until parens balance
        sig = ""
        j = i
        depth = 0
        started = False
        while j < len(lines):
            s = strip_strings(lines[j])
            for ch in s:
                if ch == "(":
                    depth += 1
                    started = True
                elif ch == ")":
                    depth -= 1
            sig += s + "\n"
            if started and depth <= 0:
                break
            j += 1
        params_txt = sig[sig.find("(") + 1: sig.rfind(")")] if "(" in sig else ""
        # count top-level commas
        d = 0
        n = 0
        has = bool(params_txt.strip())
        for ch in params_txt:
            if ch in "(<[{":
                d += 1
            elif ch in ")>]}":
                d -= 1
            elif ch == "," and d == 0:
                n += 1
        params = (n + 1 if has else 0)
        if has and params_txt.strip().endswith(","):
            params -= 1
        # body
        k = j
        body_depth = 0
        opened = False
        end = j
        expr_body = False
        while k < len(lines):
            s = strip_strings(lines[k])
            if k == j and "=" in s.split(")")[-1] and "{" not in s.split(")")[-1]:
                expr_body = True
            for ch in s:
                if ch == "{":
                    body_depth += 1
                    opened = True
                elif ch == "}":
                    body_depth -= 1
            if expr_body and not opened and k > j and (s.strip() == "" or FUN_RE.match(lines[k])):
                end = k - 1
                break
            if opened and body_depth <= 0:
                end = k
                break
            if not opened and not expr_body and k > j + 1:
                end = j
                break
            k += 1
        else:
            end = len(lines) - 1
        out.append((name, i + 1, end - i + 1, params))
        i += 1
    return out


def norm_code_lines(text):
    res = []
    in_bc = False
    for ln in text.splitlines():
        s = ln.strip()
        if in_bc:
            if "*/" in s:
                in_bc = False
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                in_bc = True
            continue
        if (not s or s.startswith("//") or s.startswith("*") or s.startswith("import ")
                or s.startswith("package ") or s.startswith("@") or s in {"{", "}", ")", "})", "),", "},", "]"}):
            continue
        res.append(re.sub(r"\s+", " ", s))
    return res


def main():
    m = {}
    files = list(kt_files(MAIN))
    sizes = {}
    pkg_files = Counter()
    pkg_lines = Counter()
    supp_file = Counter()
    supp_inline = Counter()
    files_with_file_suppress = 0
    counts = Counter()
    funs = []
    dup_windows = defaultdict(list)
    total_code_lines = 0
    for p in files:
        t = read(p)
        n = t.count("\n") + (0 if t.endswith("\n") else 1)
        r = rel(p)
        sizes[r] = n
        pkg = os.path.dirname(r) or "(root)"
        pkg_files[pkg] += 1
        pkg_lines[pkg] += n
        file_sup = re.findall(r"@file:Suppress\(([^)]*)\)", t)
        if file_sup:
            files_with_file_suppress += 1
        for blob in file_sup:
            for rule in re.findall(r'"(\w+)"', blob):
                supp_file[rule] += 1
        for blob in re.findall(r"(?<!file:)@Suppress\(([^)]*)\)", t):
            for rule in re.findall(r'"(\w+)"', blob):
                supp_inline[rule] += 1
        counts["findViewById"] += len(re.findall(r"\bfindViewById\b", t))
        counts["lateinit var"] += len(re.findall(r"\blateinit var\b", t))
        counts["Toast.makeText"] += len(re.findall(r"Toast\.makeText", t))
        counts["CrispToast.show"] += len(re.findall(r"CrispToast\.show", t))
        counts["generic catch (Exception/Throwable)"] += len(re.findall(r"catch \((?:@Suppress\([^)]*\) )?\w+: (?:Exception|Throwable)\)", t))
        counts["CancellationException mentions"] += len(re.findall(r"CancellationException", t))
        counts["Dispatchers.IO"] += len(re.findall(r"Dispatchers\.IO", t))
        counts["!! (non-null assertions)"] += len(re.findall(r"!!", t))
        counts["MaterialAlertDialogBuilder/AlertDialog.Builder"] += len(re.findall(r"(?:MaterialAlertDialogBuilder|AlertDialog\.Builder)\(", t))
        counts["Pair</Triple< types"] += len(re.findall(r"\b(?:Pair|Triple)<", t))
        counts["Timber.e/w"] += len(re.findall(r"Timber\.[ew]\(", t))
        counts["String.format(Locale"] += len(re.findall(r"String\.format\(Locale", t))
        for f in funs_of(p, t):
            funs.append((r,) + f)
        code = norm_code_lines(t)
        total_code_lines += len(code)
        W = 6
        for i in range(len(code) - W + 1):
            h = hashlib.md5("\n".join(code[i:i + W]).encode()).hexdigest()
            dup_windows[h].append((r, i))
    # duplication: lines covered by windows that occur in >=2 places
    dup_lines = set()
    dup_blocks = 0
    for h, occ in dup_windows.items():
        if len(occ) >= 2:
            dup_blocks += 1
            for (r, i) in occ:
                for k in range(i, i + 6):
                    dup_lines.add((r, k))
    tests = {}
    for name, d in TEST_DIRS.items():
        tf = list(kt_files(d))
        tests[name] = {
            "files": len(tf),
            "lines": sum(read(p).count("\n") for p in tf),
            "@Test": sum(len(re.findall(r"@Test\b", read(p))) for p in tf),
            "Thread.sleep": sum(len(re.findall(r"Thread\.sleep", read(p))) for p in tf),
            "@Config(sdk": sum(len(re.findall(r"@Config\(sdk", read(p))) for p in tf),
        }
    layout_lines = 0
    layout_files = 0
    if os.path.isdir(LAYOUT):
        for f in os.listdir(LAYOUT):
            if f.endswith(".xml"):
                layout_files += 1
                layout_lines += read(os.path.join(LAYOUT, f)).count("\n")
    big = {k: v for k, v in sizes.items() if v > 500}
    big_noninv = {k: v for k, v in big.items() if os.path.basename(k) not in INVARIANT}
    long_funs = sorted(funs, key=lambda x: -x[3])
    m["main"] = {
        "kotlin_files": len(files),
        "kotlin_lines": sum(sizes.values()),
        "code_lines_normalised": total_code_lines,
        "files_over_300": sum(1 for v in sizes.values() if v > 300),
        "files_over_500": len(big),
        "files_over_500_non_invariant": len(big_noninv),
        "largest_file": max(sizes.items(), key=lambda kv: kv[1]),
        "mean_file_lines": round(sum(sizes.values()) / len(sizes), 1),
        "top25_files": sorted(sizes.items(), key=lambda kv: -kv[1])[:25],
        "packages": {k: {"files": pkg_files[k], "lines": pkg_lines[k]} for k in sorted(pkg_files)},
        "max_files_in_package": max(pkg_files.values()),
        "files_with_@file:Suppress": files_with_file_suppress,
        "suppress_file_level": {r: supp_file[r] for r in RULES},
        "suppress_inline": {r: supp_inline[r] for r in RULES},
        "suppress_total": {r: supp_file[r] + supp_inline[r] for r in RULES},
        "counts": dict(counts),
        "functions": len(funs),
        "functions_over_60_lines": sum(1 for f in funs if f[3] > 60),
        "functions_over_100_lines": sum(1 for f in funs if f[3] > 100),
        "functions_with_7plus_params": sum(1 for f in funs if f[4] >= 7),
        "top20_longest_functions": [(f[1], f[0], f[2], f[3], f[4]) for f in long_funs[:20]],
        "duplicated_6line_blocks": dup_blocks,
        "duplicated_lines": len(dup_lines),
        "duplication_pct": round(100.0 * len(dup_lines) / max(1, total_code_lines), 2),
        "layout_xml_files": layout_files,
        "layout_xml_lines": layout_lines,
    }
    m["tests"] = tests
    with open(OUT + ".json", "w", encoding="utf-8") as fh:
        json.dump(m, fh, indent=1)
    mm = m["main"]
    md = ["# Quality metrics", "", f"Root: `{ROOT}`", "", "| Metric | Value |", "|---|---|"]
    for k in ["kotlin_files", "kotlin_lines", "code_lines_normalised", "mean_file_lines", "files_over_300",
              "files_over_500", "files_over_500_non_invariant", "largest_file", "max_files_in_package",
              "files_with_@file:Suppress", "functions", "functions_over_60_lines", "functions_over_100_lines",
              "functions_with_7plus_params", "duplicated_6line_blocks", "duplicated_lines", "duplication_pct",
              "layout_xml_files", "layout_xml_lines"]:
        md.append(f"| {k} | {mm[k]} |")
    for k, v in mm["counts"].items():
        md.append(f"| {k} | {v} |")
    for r in RULES:
        md.append(f"| @Suppress {r} (file / inline) | {mm['suppress_file_level'][r]} / {mm['suppress_inline'][r]} |")
    for name, t in tests.items():
        md.append(f"| {name}: files / lines / @Test / Thread.sleep / @Config(sdk | {t['files']} / {t['lines']} / {t['@Test']} / {t['Thread.sleep']} / {t['@Config(sdk']} |")
    md += ["", "## Top 25 files", "", "| File | Lines |", "|---|---|"]
    md += [f"| {k} | {v} |" for k, v in mm["top25_files"]]
    md += ["", "## Top 20 longest functions", "", "| Function | File:line | Lines | Params |", "|---|---|---|---|"]
    md += [f"| {f[0]} | {f[1]}:{f[2]} | {f[3]} | {f[4]} |" for f in mm["top20_longest_functions"]]
    md += ["", "## Packages", "", "| Package | Files | Lines |", "|---|---|---|"]
    md += [f"| {k} | {v['files']} | {v['lines']} |" for k, v in mm["packages"].items()]
    with open(OUT + ".md", "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")
    print("\n".join(md[:60]))


def funs_of(p, t):
    try:
        return functions(p, t)
    except Exception:  # noqa: BLE001 - metrics are best effort
        return []


if __name__ == "__main__":
    main()
