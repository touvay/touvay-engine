# Rewrite benchmark corpus v1

`corpus-v1.tsv` is the frozen synthetic evaluation corpus for `text.rewrite@1`.
It contains 105 cases: 15 each for grammar-shaped Rewrite inputs, formality,
shortening, expansion, professional writing, social writing, and multilingual
writing. The corpus contains no production or user data.

The tab-separated columns are:

1. stable case ID;
2. category;
3. BCP 47 output locale;
4. Rewrite tone;
5. Rewrite length;
6. source;
7. reference output;
8. pipe-separated required meaning terms; and
9. pipe-separated forbidden terms, or `-`.

Changes require a corpus version increment, a review of every changed case, a
new SHA-256 in the resulting benchmark report, and a fresh approved baseline.
Never edit v1 in place after its first baseline is promoted.

See `docs/benchmarks/rewrite-benchmark-suite.md` for execution and scoring.
