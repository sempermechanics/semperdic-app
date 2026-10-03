# Performance baseline — moved

This baseline predates the engine extraction: commit `bd44af0` exists only in
this repository's pre-extraction history, not in
[`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine).

**The maintained copy is [`engine/docs/PERF_BASELINE_bd44af0.md`](../../engine/docs/PERF_BASELINE_bd44af0.md).**

One caveat worth knowing before you cite it: the document states a
`≥ 4557 solves/s` non-regression gate, but no CI job enforces that number.
`engine/tests/perf/test_throughput.cpp` says in its own header that it is not a
benchmark gate — it only asserts a wall-clock ceiling so the suite cannot hang.
Treat the solve-rate figures as a manual reference measurement, not a check that
will fail for you.

The quality floors in that document (median displacement error, coverage
minimums, DICe RMS tolerance) *are* enforced, by the engine repo's test suite.
