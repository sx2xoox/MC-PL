#!/usr/bin/env python3
"""Prints success rate, IGT statistics, failure reasons and split medians of one or more result CSVs.

  python3 tools/summarize.py results/baseline.csv [results/tuned.csv ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srb_common as C  # noqa: E402

if __name__ == "__main__":
    for p in sys.argv[1:]:
        C.print_summary(C.read_csv(p), p)
        print()
