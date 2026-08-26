#!/usr/bin/env python3
"""Evaluate nearest-support abstention for Aritmia's 47 binary complaint concepts.

Unlike softmax/scope thresholds, this experiment asks a direct representation question:
does the complaint-derived concept pattern resemble cardiovascular patterns actually seen
in the ID training distribution?

Two deployable scores are evaluated with 5-fold OOF separation:
  * global_jaccard: maximum Jaccard similarity to any ID training pattern;
  * predicted_class_jaccard: maximum Jaccard similarity to a training pattern whose
    true class equals the MLP's predicted class.

Thresholds are chosen only from OOF ID scores. Frozen Russian and curated boundary cases
are evaluation-only. Inputs remain only complaint-derived concepts.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.model_selection import StratifiedKFold

from evaluate_abstention import CV_SEED, DISEASES, load_id_and_ood, oof_predict
from evaluate_joint_abstention import load_case_vectors, load_shipped_model, shipped_predict

MIN_CONCEPTS = 2
TARGET_COVERAGES = (0.50, 0.60, 0.70, 0.80, 0.90, 0.95)
SCORES = ("global_jaccard", "predicted_class_jaccard")


def max_jaccard(query: np.ndarray, reference: np.ndarray, chunk_size: int = 256) -> np.ndarray:
    if len(query) == 0:
        return np.empty(0, dtype=np.float64)
    if len(reference) == 0:
        return np.zeros(len(query), dtype=np.float64)

    q = query.astype(np.float64, copy=False)
    r = reference.astype(np.float64, copy=False)
    r_sum = r.sum(axis=1)[None, :]
    out = np.zeros(len(q), dtype=np.float64)
    for start in range(0, len(q), chunk_size):
        block = q[start : start + chunk_size]
        intersection = block @ r.T
        union = block.sum(axis=1, keepdims=True) + r_sum - intersection
        similarity = np.divide(
            intersection,
            union,
            out=np.zeros_like(intersection),
            where=union > 0,
        )
        out[start : start + len(block)] = similarity.max(axis=1)
    return out


def predicted_class_support(
    query: np.ndarray,
    predicted: np.ndarray,
    train_x: np.ndarray,
    train_y: np.ndarray,
) -> np.ndarray:
    out = np.zeros(len(query), dtype=np.float64)
    for class_index in range(len(DISEASES)):
        mask = predicted == class_index
        if not mask.any():
            continue
        reference = train_x[train_y == class_index]
        out[mask] = max_jaccard(query[mask], reference)
    return out


def compute_oof_support(
    id_x: np.ndarray,
    id_y: np.ndarray,
    ood_x: np.ndarray,
    id_probs: np.ndarray,
    ood_fold_probs: list[np.ndarray],
) -> tuple[dict[str, np.ndarray], dict[str, list[np.ndarray]], list[tuple[np.ndarray, np.ndarray]]]:
    id_scores = {name: np.zeros(len(id_x), dtype=np.float64) for name in SCORES}
    ood_scores = {name: [] for name in SCORES}
    fold_train_sets: list[tuple[np.ndarray, np.ndarray]] = []

    splitter = StratifiedKFold(n_splits=5, shuffle=True, random_state=CV_SEED)
    for fold_index, (train_idx, test_idx) in enumerate(splitter.split(id_x, id_y)):
        train_x = id_x[train_idx]
        train_y = id_y[train_idx]
        fold_train_sets.append((train_x, train_y))

        test_x = id_x[test_idx]
        test_predicted = np.argmax(id_probs[test_idx], axis=1)
        id_scores["global_jaccard"][test_idx] = max_jaccard(test_x, train_x)
        id_scores["predicted_class_jaccard"][test_idx] = predicted_class_support(
            test_x, test_predicted, train_x, train_y
        )

        if len(ood_x):
            ood_predicted = np.argmax(ood_fold_probs[fold_index], axis=1)
            ood_scores["global_jaccard"].append(max_jaccard(ood_x, train_x))
            ood_scores["predicted_class_jaccard"].append(
                predicted_class_support(ood_x, ood_predicted, train_x, train_y)
            )
        print(f"support fold {fold_index + 1}/5 complete")

    return id_scores, ood_scores, fold_train_sets


def class_rows(
    accepted: np.ndarray,
    id_y: np.ndarray,
    correct: np.ndarray,
    evidence_gate: np.ndarray,
) -> list[dict]:
    rows = []
    for class_index, disease_id in enumerate(DISEASES):
        mask = evidence_gate & (id_y == class_index)
        accepted_mask = mask & accepted
        rows.append({
            "diseaseId": disease_id,
            "rowsAfterEvidenceGate": int(mask.sum()),
            "coverage": float(accepted_mask.sum() / mask.sum()) if mask.any() else None,
            "selectiveAccuracy": float(correct[accepted_mask].mean()) if accepted_mask.any() else None,
        })
    return rows


def operating_points(
    id_x: np.ndarray,
    id_y: np.ndarray,
    id_probs: np.ndarray,
    id_scores: dict[str, np.ndarray],
    ood_x: np.ndarray,
    ood_scores: dict[str, list[np.ndarray]],
) -> list[dict]:
    id_gate = id_x.sum(axis=1) >= MIN_CONCEPTS
    ood_gate = ood_x.sum(axis=1) >= MIN_CONCEPTS
    correct = np.argmax(id_probs, axis=1) == id_y
    rows: list[dict] = []

    for score_name in SCORES:
        score = id_scores[score_name]
        for target in TARGET_COVERAGES:
            threshold = float(np.quantile(score[id_gate], 1.0 - target, method="higher"))
            accepted = id_gate & (score >= threshold)
            ood_rates = [
                float((ood_gate & (fold_score >= threshold)).mean())
                for fold_score in ood_scores[score_name]
            ]
            accuracy = float(correct[accepted].mean()) if accepted.any() else None
            rows.append({
                "score": score_name,
                "targetIdCoverageWithinEvidenceGate": target,
                "threshold": threshold,
                "effectiveIdCoverage": float(accepted.mean()),
                "coverageWithinEvidenceGate": float(accepted.sum() / id_gate.sum()),
                "selectiveAccuracy": accuracy,
                "selectiveRisk": 1.0 - accuracy if accuracy is not None else None,
                "oodAcceptanceEndToEndMeanAcrossFolds": float(np.mean(ood_rates)),
                "oodAcceptanceEndToEndStdAcrossFolds": float(np.std(ood_rates)),
                "perTrueClass": class_rows(accepted, id_y, correct, id_gate),
            })
    return rows


def evaluate_cases(
    rows: list[dict],
    x: np.ndarray,
    shipped_probs: np.ndarray,
    fold_train_sets: list[tuple[np.ndarray, np.ndarray]],
    points: list[dict],
    group_key: str | None,
) -> list[dict]:
    evidence = x.sum(axis=1) >= MIN_CONCEPTS
    predicted = np.argmax(shipped_probs, axis=1)
    fold_scores = {name: [] for name in SCORES}
    for train_x, train_y in fold_train_sets:
        fold_scores["global_jaccard"].append(max_jaccard(x, train_x))
        fold_scores["predicted_class_jaccard"].append(
            predicted_class_support(x, predicted, train_x, train_y)
        )

    evaluations = []
    for point_index, point in enumerate(points):
        score_name = point["score"]
        threshold = point["threshold"]
        acceptance = np.vstack([
            evidence & (score >= threshold) for score in fold_scores[score_name]
        ])
        fractions = acceptance.mean(axis=0)
        item = {
            "operatingPointIndex": point_index,
            "score": score_name,
            "targetIdCoverageWithinEvidenceGate": point["targetIdCoverageWithinEvidenceGate"],
            "threshold": threshold,
            "meanAcceptanceAcrossCasesAndFolds": float(fractions.mean()),
            "casesAcceptedInAnyFold": int(np.sum(fractions > 0.0)),
            "casesAcceptedInAllFolds": int(np.sum(fractions == 1.0)),
            "casesRejectedInAllFolds": int(np.sum(fractions == 0.0)),
        }
        if group_key is None:
            correct = np.asarray([row.get("rank") == 1 for row in rows], dtype=bool)
            total_accepted = int(acceptance.sum())
            total_correct = int((acceptance & correct[None, :]).sum())
            item.update({
                "acceptedTop1AccuracyAcrossAllFoldDecisions": (
                    total_correct / total_accepted if total_accepted else None
                ),
                "meanAcceptedCasesAcrossFolds": float(acceptance.sum(axis=1).mean()),
                "meanRejectedCorrectCasesAcrossFolds": float(
                    ((~acceptance) & correct[None, :]).sum(axis=1).mean()
                ),
                "nonTop1Cases": [
                    {
                        "diseaseId": row.get("diseaseId"),
                        "variant": row.get("variant"),
                        "rank": row.get("rank"),
                        "predictedDiseaseId": DISEASES[predicted[i]],
                        "acceptanceFractionAcrossFolds": float(fractions[i]),
                    }
                    for i, row in enumerate(rows) if not correct[i]
                ],
            })
        else:
            for group in sorted({row[group_key] for row in rows}):
                mask = np.asarray([row[group_key] == group for row in rows], dtype=bool)
                gf = fractions[mask]
                item[f"{group}Summary"] = {
                    "caseCount": int(mask.sum()),
                    "meanAcceptanceAcrossCasesAndFolds": float(gf.mean()),
                    "casesAcceptedInAnyFold": int(np.sum(gf > 0.0)),
                    "casesAcceptedInAllFolds": int(np.sum(gf == 1.0)),
                    "casesRejectedInAllFolds": int(np.sum(gf == 0.0)),
                }
        evaluations.append(item)
    return evaluations


def evaluate(csv_path: Path, frozen_path: Path, boundary_path: Path, model_dir: Path) -> dict:
    id_x, id_y, ood_x, meta = load_id_and_ood(csv_path)
    id_probs, ood_fold_probs = oof_predict(id_x, id_y, ood_x)
    id_scores, ood_scores, fold_train_sets = compute_oof_support(
        id_x, id_y, ood_x, id_probs, ood_fold_probs
    )
    points = operating_points(id_x, id_y, id_probs, id_scores, ood_x, ood_scores)

    snapshot = load_shipped_model(model_dir)
    frozen_rows, frozen_x = load_case_vectors(frozen_path)
    boundary_rows, boundary_x = load_case_vectors(boundary_path)
    frozen_probs = shipped_predict(frozen_x, snapshot)
    boundary_probs = shipped_predict(boundary_x, snapshot)

    return {
        "notes": [
            "All support scores use only the same 47 complaint-derived binary concepts available to Android.",
            "ID support is strictly OOF: each ID pattern is compared only with its fold's training patterns.",
            "predicted_class_jaccard uses the MLP predicted class, never the true diagnosis at runtime.",
            "Frozen Russian and boundary cases are evaluation-only and never fit thresholds.",
            "These metrics are engineering research, not clinical validation or diagnosis probabilities.",
        ],
        "dataset": meta,
        "minConcepts": MIN_CONCEPTS,
        "operatingPoints": points,
        "frozenRussian": {
            "caseCount": len(frozen_rows),
            "baselineTop1Hits": int(sum(row.get("rank") == 1 for row in frozen_rows)),
            "evaluations": evaluate_cases(
                frozen_rows, frozen_x, frozen_probs, fold_train_sets, points, group_key=None
            ),
        },
        "curatedBoundary": {
            "caseCount": len(boundary_rows),
            "evaluations": evaluate_cases(
                boundary_rows, boundary_x, boundary_probs, fold_train_sets, points, group_key="group"
            ),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--frozen-json", required=True, type=Path)
    parser.add_argument("--boundary-json", required=True, type=Path)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("support_distance_report.json"))
    args = parser.parse_args()

    report = evaluate(args.csv, args.frozen_json, args.boundary_json, args.model_dir)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
