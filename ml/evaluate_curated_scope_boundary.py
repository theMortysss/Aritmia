#!/usr/bin/env python3
"""Evaluate scope-gate retention on positive and hard-boundary Russian complaints.

Complaint text is parsed only by the real Kotlin FreeTextSymptomExtractor. This script
consumes its exported concept IDs, trains the same five-fold logistic scope detector,
and reports acceptance at thresholds derived only from held-out in-domain examples.

Boundary cases are intentionally ambiguous at concept level. Their acceptance is a probe
of representation limits, not a claim that a particular complaint has a non-cardiac cause.
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


def load_cases(path: Path) -> tuple[list[dict], np.ndarray]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("Boundary extraction JSON must be a non-empty list")

    concept_ids = list(CONCEPT_COLUMNS.keys())
    concept_index = {concept_id: i for i, concept_id in enumerate(concept_ids)}
    vectors = np.zeros((len(rows), len(concept_ids)), dtype=np.float64)
    seen: set[str] = set()

    for row_index, row in enumerate(rows):
        case_id = row.get("id")
        group = row.get("group")
        ids = row.get("conceptIds")
        if not isinstance(case_id, str) or not case_id:
            raise ValueError(f"Row {row_index} has invalid id")
        if case_id in seen:
            raise ValueError(f"Duplicate case id: {case_id}")
        seen.add(case_id)
        if group not in {"positive", "boundary"}:
            raise ValueError(f"Case {case_id} has invalid group: {group}")
        if not isinstance(ids, list):
            raise ValueError(f"Case {case_id} has invalid conceptIds")
        unknown = sorted(set(ids) - set(concept_index))
        if unknown:
            raise ValueError(f"Case {case_id} has unknown concepts: {unknown}")
        for concept_id in ids:
            vectors[row_index, concept_index[concept_id]] = 1.0

    return rows, vectors


def summarize_group(rows: list[dict], acceptance: dict[float, list[list[bool]]], group: str) -> dict:
    indices = [i for i, row in enumerate(rows) if row["group"] == group]
    result: dict[str, dict] = {}
    for target in TARGET_ID_COVERAGES:
        per_case = np.asarray([np.mean(acceptance[target][i]) for i in indices], dtype=np.float64)
        result[f"idCoverage{int(target * 100)}"] = {
            "caseCount": len(indices),
            "meanAcceptanceAcrossCasesAndFolds": float(per_case.mean()),
            "casesAcceptedInAnyFold": int(np.sum(per_case > 0.0)),
            "casesAcceptedInAllFolds": int(np.sum(per_case == 1.0)),
            "casesRejectedInAllFolds": int(np.sum(per_case == 0.0)),
        }
    return result


def evaluate(csv_path: Path, cases_path: Path) -> dict:
    id_x, _, ood_x, meta = load_id_and_ood(csv_path)
    rows, case_x = load_cases(cases_path)

    counts = case_x.sum(axis=1).astype(int)
    if np.any(counts < MIN_CONCEPTS):
        bad = [rows[i]["id"] for i in np.where(counts < MIN_CONCEPTS)[0]]
        raise ValueError(f"Cases do not pass minConcepts={MIN_CONCEPTS}: {bad}")

    x = np.vstack([id_x, ood_x])
    y = np.concatenate([
        np.ones(len(id_x), dtype=np.int64),
        np.zeros(len(ood_x), dtype=np.int64),
    ])

    scores: list[list[float]] = [[] for _ in rows]
    acceptance = {target: [[] for _ in rows] for target in TARGET_ID_COVERAGES}
    thresholds = {target: [] for target in TARGET_ID_COVERAGES}

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
        id_gate = id_x[id_test_idx].sum(axis=1) >= MIN_CONCEPTS
        heldout_id_scores = model.predict_proba(id_x[id_test_idx][id_gate])[:, 1]
        case_scores = model.predict_proba(case_x)[:, 1]

        for i, score in enumerate(case_scores):
            scores[i].append(float(score))

        for target in TARGET_ID_COVERAGES:
            threshold = float(np.quantile(heldout_id_scores, 1.0 - target, method="higher"))
            thresholds[target].append(threshold)
            for i, score in enumerate(case_scores):
                acceptance[target][i].append(bool(score >= threshold))

        print(f"scope boundary fold {fold}/5 complete")

    cases: list[dict] = []
    for i, row in enumerate(rows):
        fold_scores = np.asarray(scores[i], dtype=np.float64)
        cases.append({
            "group": row["group"],
            "id": row["id"],
            "text": row.get("text", ""),
            "expectedDiseaseId": row.get("expectedDiseaseId"),
            "conceptIds": row["conceptIds"],
            "conceptCount": int(counts[i]),
            "scopeScoreAcrossFolds": {
                "mean": float(fold_scores.mean()),
                "std": float(fold_scores.std()),
                "min": float(fold_scores.min()),
                "max": float(fold_scores.max()),
            },
            "acceptedAcrossFolds": {
                f"idCoverage{int(target * 100)}": float(np.mean(acceptance[target][i]))
                for target in TARGET_ID_COVERAGES
            },
        })

    return {
        "notes": [
            "Complaint text is converted to concepts only by the real Kotlin FreeTextSymptomExtractor.",
            "Positive cases cover all 14 supported cardiovascular classes and test false rejection of plausible in-domain complaint patterns.",
            "Boundary cases deliberately contain concept-level overlap and are not ground-truth non-cardiac diagnoses.",
            "All scope inputs are the same 47 complaint-derived concepts used by Android.",
            "Scope scores are not disease probabilities or clinical probabilities.",
            "This curated challenge is small and cannot establish real-world safety or calibration.",
        ],
        "sourceDataset": meta,
        "minConcepts": MIN_CONCEPTS,
        "thresholdsAcrossFolds": {
            f"idCoverage{int(target * 100)}": {
                "mean": float(np.mean(values)),
                "min": float(np.min(values)),
                "max": float(np.max(values)),
            }
            for target, values in thresholds.items()
        },
        "positiveSummary": summarize_group(rows, acceptance, "positive"),
        "boundarySummary": summarize_group(rows, acceptance, "boundary"),
        "cases": cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--cases-json", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("curated_scope_boundary_report.json"))
    args = parser.parse_args()

    report = evaluate(args.csv, args.cases_json)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
