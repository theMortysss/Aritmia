#!/usr/bin/env python3
"""Evaluate curated Russian free-text OOD examples after the real Kotlin extractor.

The Kotlin JVM test exports concept IDs produced by FreeTextSymptomExtractor. This
script never parses complaint text itself. It trains the same simple complaint-concept
scope detector used in evaluate_scope_detector.py and scores the exported vectors with
five independently trained folds.

The output is research-only: the scope score is not a disease probability and not a
clinical probability. Curated examples are intentionally small and adversarial, so the
report is a challenge set rather than a population estimate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold

from evaluate_abstention import CONCEPT_COLUMNS, load_id_and_ood

CV_SEED = 20260826
MIN_CONCEPTS = 2
TARGET_ID_COVERAGES = (0.70, 0.80, 0.90, 0.95)


def load_curated(path: Path) -> tuple[list[dict], np.ndarray]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("Curated extraction JSON must be a non-empty list")

    concept_ids = list(CONCEPT_COLUMNS.keys())
    concept_index = {concept_id: i for i, concept_id in enumerate(concept_ids)}
    vectors = np.zeros((len(rows), len(concept_ids)), dtype=np.float64)

    seen_ids: set[str] = set()
    for row_index, row in enumerate(rows):
        case_id = row.get("id")
        ids = row.get("conceptIds")
        if not isinstance(case_id, str) or not case_id:
            raise ValueError(f"Row {row_index} has invalid id")
        if case_id in seen_ids:
            raise ValueError(f"Duplicate curated case id: {case_id}")
        seen_ids.add(case_id)
        if not isinstance(ids, list):
            raise ValueError(f"Case {case_id} has invalid conceptIds")
        unknown = sorted(set(ids) - set(concept_index))
        if unknown:
            raise ValueError(f"Case {case_id} contains unknown concepts: {unknown}")
        for concept_id in ids:
            vectors[row_index, concept_index[concept_id]] = 1.0

    return rows, vectors


def evaluate(csv_path: Path, curated_path: Path) -> dict:
    id_x, _, ood_x, meta = load_id_and_ood(csv_path)
    curated_rows, curated_x = load_curated(curated_path)

    x = np.vstack([id_x, ood_x])
    y = np.concatenate([
        np.ones(len(id_x), dtype=np.int64),
        np.zeros(len(ood_x), dtype=np.int64),
    ])

    curated_counts = curated_x.sum(axis=1).astype(int)
    if np.any(curated_counts < MIN_CONCEPTS):
        bad = [curated_rows[i]["id"] for i in np.where(curated_counts < MIN_CONCEPTS)[0]]
        raise ValueError(f"Curated cases do not pass minConcepts={MIN_CONCEPTS}: {bad}")

    per_case_scores: list[list[float]] = [[] for _ in curated_rows]
    per_case_accepts = {
        target: [[] for _ in curated_rows] for target in TARGET_ID_COVERAGES
    }
    fold_thresholds: dict[float, list[float]] = {target: [] for target in TARGET_ID_COVERAGES}

    splitter = StratifiedKFold(n_splits=5, shuffle=True, random_state=CV_SEED)
    for fold, (train_idx, test_idx) in enumerate(splitter.split(x, y), start=1):
        model = LogisticRegression(
            C=1.0,
            class_weight="balanced",
            max_iter=2000,
            solver="lbfgs",
            random_state=CV_SEED + fold,
        )
        model.fit(x[train_idx], y[train_idx])

        id_test_idx = test_idx[y[test_idx] == 1]
        id_test_gate = id_x[id_test_idx].sum(axis=1) >= MIN_CONCEPTS
        heldout_id_scores = model.predict_proba(id_x[id_test_idx][id_test_gate])[:, 1]
        external_scores = model.predict_proba(curated_x)[:, 1]

        for i, score in enumerate(external_scores):
            per_case_scores[i].append(float(score))

        for target in TARGET_ID_COVERAGES:
            threshold = float(np.quantile(heldout_id_scores, 1.0 - target, method="higher"))
            fold_thresholds[target].append(threshold)
            for i, score in enumerate(external_scores):
                per_case_accepts[target][i].append(bool(score >= threshold))

        print(f"curated scope fold {fold}/5 complete")

    cases: list[dict] = []
    for i, row in enumerate(curated_rows):
        scores = np.asarray(per_case_scores[i], dtype=np.float64)
        cases.append({
            "id": row["id"],
            "text": row.get("text", ""),
            "conceptIds": row["conceptIds"],
            "conceptCount": int(curated_counts[i]),
            "scopeScoreAcrossFolds": {
                "mean": float(scores.mean()),
                "std": float(scores.std()),
                "min": float(scores.min()),
                "max": float(scores.max()),
            },
            "acceptedAcrossFolds": {
                f"idCoverage{int(target * 100)}": float(np.mean(per_case_accepts[target][i]))
                for target in TARGET_ID_COVERAGES
            },
        })

    summary = {}
    for target in TARGET_ID_COVERAGES:
        acceptance_rates = np.asarray([
            np.mean(per_case_accepts[target][i]) for i in range(len(curated_rows))
        ])
        summary[f"idCoverage{int(target * 100)}"] = {
            "meanFoldAcceptanceAcrossCases": float(acceptance_rates.mean()),
            "casesAcceptedInAnyFold": int(np.sum(acceptance_rates > 0.0)),
            "casesAcceptedInAllFolds": int(np.sum(acceptance_rates == 1.0)),
            "foldThresholdMean": float(np.mean(fold_thresholds[target])),
            "foldThresholdMin": float(np.min(fold_thresholds[target])),
            "foldThresholdMax": float(np.max(fold_thresholds[target])),
        }

    return {
        "notes": [
            "Complaint text is converted to concepts only by the real Kotlin FreeTextSymptomExtractor before this script runs.",
            "All scope-model inputs are the same 47 complaint-derived concepts used by Android.",
            "The scope score is not a disease probability or clinical probability.",
            "Thresholds are derived independently in each fold from held-out in-domain rows that pass minConcepts=2.",
            "This is a small curated adversarial challenge, not an estimate of real-world prevalence or safety.",
        ],
        "sourceDataset": meta,
        "curatedCaseCount": len(curated_rows),
        "minConcepts": MIN_CONCEPTS,
        "summary": summary,
        "cases": cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--curated-json", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("curated_text_scope_report.json"))
    args = parser.parse_args()

    report = evaluate(args.csv, args.curated_json)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
