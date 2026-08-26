#!/usr/bin/env python3
"""Evaluate a complaint-concept scope detector before the 14-class disease MLP.

The detector uses only the same 47 complaint-derived binary concepts available to the
Android disease pipeline. Its target is not a disease and not a clinical probability:
it estimates whether a concept pattern resembles one of the 14 supported
cardiovascular classes (ID) versus another disease in the 820-disease source matrix
(OOD).

This is an internal pattern-level OOF experiment. A production gate still requires an
external free-text OOD set because diseases from the same source dataset are not fully
independent of the cardiovascular training data.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score
from sklearn.model_selection import StratifiedKFold

from evaluate_abstention import CONCEPT_COLUMNS, load_id_and_ood

CV_SEED = 20260826
MIN_CONCEPT_GATES = (1, 2, 3, 4)
TARGET_ID_COVERAGES = (0.50, 0.60, 0.70, 0.80, 0.90, 0.95)


def oof_scope_scores(id_x: np.ndarray, ood_x: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x = np.vstack([id_x, ood_x])
    y = np.concatenate([
        np.ones(len(id_x), dtype=np.int64),
        np.zeros(len(ood_x), dtype=np.int64),
    ])
    probabilities = np.zeros(len(x), dtype=np.float64)
    coefficients: list[np.ndarray] = []

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
        probabilities[test_idx] = model.predict_proba(x[test_idx])[:, 1]
        coefficients.append(model.coef_[0].copy())
        print(f"scope fold {fold}/5 complete")

    return y, probabilities, np.vstack(coefficients)


def operating_points(
    id_scores: np.ndarray,
    ood_scores: np.ndarray,
    id_gate: np.ndarray,
    ood_gate: np.ndarray,
) -> list[dict]:
    gated_id_scores = id_scores[id_gate]
    rows: list[dict] = []
    for target in TARGET_ID_COVERAGES:
        threshold = float(np.quantile(gated_id_scores, 1.0 - target, method="higher"))
        id_accepted = id_gate & (id_scores >= threshold)
        ood_accepted = ood_gate & (ood_scores >= threshold)
        rows.append({
            "targetIdCoverageWithinEvidenceGate": target,
            "scopeThreshold": threshold,
            "actualIdCoverageWithinEvidenceGate": float(id_accepted.sum() / id_gate.sum()),
            "effectiveIdCoverageOfAllRows": float(id_accepted.mean()),
            "oodAcceptanceConditionalOnEvidenceGate": (
                float(ood_accepted.sum() / ood_gate.sum()) if ood_gate.any() else float("nan")
            ),
            "oodAcceptanceEndToEnd": float(ood_accepted.mean()),
        })
    return rows


def evaluate(csv_path: Path) -> dict:
    id_x, _, ood_x, meta = load_id_and_ood(csv_path)
    y, all_scores, fold_coefficients = oof_scope_scores(id_x, ood_x)
    id_scores = all_scores[:len(id_x)]
    ood_scores = all_scores[len(id_x):]

    concept_ids = list(CONCEPT_COLUMNS.keys())
    mean_coef = fold_coefficients.mean(axis=0)
    std_coef = fold_coefficients.std(axis=0)
    coefficient_rows = [
        {
            "concept": concept_ids[i],
            "meanCoefficient": float(mean_coef[i]),
            "stdAcrossFolds": float(std_coef[i]),
        }
        for i in range(len(concept_ids))
    ]

    gates: list[dict] = []
    id_counts = id_x.sum(axis=1).astype(int)
    ood_counts = ood_x.sum(axis=1).astype(int)
    for min_concepts in MIN_CONCEPT_GATES:
        id_gate = id_counts >= min_concepts
        ood_gate = ood_counts >= min_concepts
        gated_scores = np.concatenate([id_scores[id_gate], ood_scores[ood_gate]])
        gated_y = np.concatenate([
            np.ones(id_gate.sum(), dtype=np.int64),
            np.zeros(ood_gate.sum(), dtype=np.int64),
        ])
        gates.append({
            "minConcepts": min_concepts,
            "idEvidenceGateCoverage": float(id_gate.mean()),
            "oodEvidenceGatePassRate": float(ood_gate.mean()),
            "scopeAucWithinEvidenceGate": float(roc_auc_score(gated_y, gated_scores)),
            "operatingPoints": operating_points(id_scores, ood_scores, id_gate, ood_gate),
        })

    return {
        "notes": [
            "Scope detector inputs are only the same 47 complaint-derived concepts used by Android.",
            "The scope score is not a disease probability or clinical probability.",
            "OOF splitting is at deduplicated concept-pattern level inside the same 820-disease dataset.",
            "Use this report only to decide whether a dedicated scope model is promising; validate on external free-text OOD before production use.",
        ],
        "dataset": meta,
        "overall": {
            "rocAuc": float(roc_auc_score(y, all_scores)),
            "averagePrecision": float(average_precision_score(y, all_scores)),
            "brierScore": float(brier_score_loss(y, all_scores)),
        },
        "mostCardiovascularWeightedConcepts": sorted(
            coefficient_rows, key=lambda row: row["meanCoefficient"], reverse=True
        )[:15],
        "mostOodWeightedConcepts": sorted(
            coefficient_rows, key=lambda row: row["meanCoefficient"]
        )[:15],
        "evidenceGates": gates,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("scope_detector_report.json"))
    args = parser.parse_args()

    report = evaluate(args.csv)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
