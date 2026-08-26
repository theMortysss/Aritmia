#!/usr/bin/env python3
"""Evaluate a joint deployable abstention policy for Aritmia.

The policy separates two questions that one softmax threshold cannot answer:

1. scope: does the complaint-derived concept pattern resemble the supported
   cardiovascular domain rather than other diseases in the source matrix?
2. ranking uncertainty: is the 14-class MLP confident enough to expose a top-5?

Both gates use only the same complaint-derived concepts available to Android.
Thresholds are fitted from OOF research predictions only. Frozen Russian and curated
boundary cases are evaluation-only and never fit thresholds.

Class-aware thresholds are keyed by the MLP *predicted* class, not the true diagnosis,
so the policy is implementable at runtime without privileged clinical labels.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold

from evaluate_abstention import (
    CONCEPT_COLUMNS,
    DISEASES,
    load_id_and_ood,
    oof_predict,
    score_vectors,
)

CV_SEED = 20260826
MIN_CONCEPTS = 2
SCOPE_TARGETS = (0.80, 0.90, 0.95)
MLP_TARGETS = (0.70, 0.80, 0.90, 0.95)
MLP_SCORES = ("max_confidence", "margin")


def load_case_vectors(path: Path) -> tuple[list[dict], np.ndarray]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(rows, dict):
        rows = rows.get("cases")
    if not isinstance(rows, list) or not rows:
        raise ValueError(f"Expected non-empty case list in {path}")

    concept_ids = list(CONCEPT_COLUMNS.keys())
    concept_index = {concept_id: i for i, concept_id in enumerate(concept_ids)}
    vectors = np.zeros((len(rows), len(concept_ids)), dtype=np.float64)
    for row_index, row in enumerate(rows):
        ids = row.get("conceptIds", row.get("concepts"))
        if not isinstance(ids, list):
            raise ValueError(f"Case {row_index} has no concept list")
        unknown = sorted(set(ids) - set(concept_index))
        if unknown:
            raise ValueError(f"Case {row_index} has unknown concepts: {unknown}")
        for concept_id in ids:
            vectors[row_index, concept_index[concept_id]] = 1.0
    return rows, vectors


def train_scope_oof(
    id_x: np.ndarray,
    ood_x: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, list[LogisticRegression]]:
    x = np.vstack([id_x, ood_x])
    y = np.concatenate([
        np.ones(len(id_x), dtype=np.int64),
        np.zeros(len(ood_x), dtype=np.int64),
    ])
    scores = np.zeros(len(x), dtype=np.float64)
    models: list[LogisticRegression] = []

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
        scores[test_idx] = model.predict_proba(x[test_idx])[:, 1]
        models.append(model)
        print(f"joint scope fold {fold}/5 complete")

    return scores[: len(id_x)], scores[len(id_x) :], models


def quantile_threshold(values: np.ndarray, target_coverage: float) -> float:
    if not len(values):
        return float("inf")
    return float(np.quantile(values, 1.0 - target_coverage, method="higher"))


def thresholds_by_predicted_class(
    values: np.ndarray,
    predicted: np.ndarray,
    gate: np.ndarray,
    target_coverage: float,
) -> tuple[float, np.ndarray]:
    global_threshold = quantile_threshold(values[gate], target_coverage)
    thresholds = np.full(len(DISEASES), global_threshold, dtype=np.float64)
    for class_index in range(len(DISEASES)):
        mask = gate & (predicted == class_index)
        if mask.any():
            thresholds[class_index] = quantile_threshold(values[mask], target_coverage)
    return global_threshold, thresholds


def class_rows(
    accepted: np.ndarray,
    id_y: np.ndarray,
    correct: np.ndarray,
    id_gate: np.ndarray,
) -> list[dict]:
    rows: list[dict] = []
    for class_index, disease_id in enumerate(DISEASES):
        mask = id_gate & (id_y == class_index)
        accepted_mask = mask & accepted
        rows.append({
            "diseaseId": disease_id,
            "rowsAfterEvidenceGate": int(mask.sum()),
            "coverage": float(accepted_mask.sum() / mask.sum()) if mask.any() else None,
            "selectiveAccuracy": (
                float(correct[accepted_mask].mean()) if accepted_mask.any() else None
            ),
        })
    return rows


def ood_joint_acceptance(
    ood_x: np.ndarray,
    ood_scope_scores: np.ndarray,
    ood_fold_probs: list[np.ndarray],
    scope_global: float,
    scope_by_class: np.ndarray,
    mlp_global: float,
    mlp_by_class: np.ndarray,
    mlp_score_name: str,
    class_aware: bool,
) -> tuple[float, float]:
    ood_gate = ood_x.sum(axis=1) >= MIN_CONCEPTS
    rates: list[float] = []
    for probabilities in ood_fold_probs:
        predicted = np.argmax(probabilities, axis=1)
        mlp_scores = score_vectors(probabilities)[mlp_score_name]
        if class_aware:
            scope_pass = ood_scope_scores >= scope_by_class[predicted]
            mlp_pass = mlp_scores >= mlp_by_class[predicted]
        else:
            scope_pass = ood_scope_scores >= scope_global
            mlp_pass = mlp_scores >= mlp_global
        rates.append(float((ood_gate & scope_pass & mlp_pass).mean()))
    return float(np.mean(rates)), float(np.std(rates))


def build_operating_points(
    id_x: np.ndarray,
    id_y: np.ndarray,
    id_probs: np.ndarray,
    id_scope_scores: np.ndarray,
    ood_x: np.ndarray,
    ood_scope_scores: np.ndarray,
    ood_fold_probs: list[np.ndarray],
) -> list[dict]:
    id_gate = id_x.sum(axis=1) >= MIN_CONCEPTS
    predicted = np.argmax(id_probs, axis=1)
    correct = predicted == id_y
    mlp_scores_by_name = score_vectors(id_probs)

    rows: list[dict] = []
    for scope_target in SCOPE_TARGETS:
        scope_global, scope_by_class = thresholds_by_predicted_class(
            id_scope_scores, predicted, id_gate, scope_target
        )
        for mlp_score_name in MLP_SCORES:
            all_mlp_scores = mlp_scores_by_name[mlp_score_name]
            for mlp_target in MLP_TARGETS:
                mlp_global, mlp_by_class = thresholds_by_predicted_class(
                    all_mlp_scores, predicted, id_gate, mlp_target
                )
                for mode in ("global", "class_aware"):
                    class_aware = mode == "class_aware"
                    if class_aware:
                        scope_pass = id_scope_scores >= scope_by_class[predicted]
                        mlp_pass = all_mlp_scores >= mlp_by_class[predicted]
                    else:
                        scope_pass = id_scope_scores >= scope_global
                        mlp_pass = all_mlp_scores >= mlp_global
                    accepted = id_gate & scope_pass & mlp_pass
                    accuracy = float(correct[accepted].mean()) if accepted.any() else None
                    ood_mean, ood_std = ood_joint_acceptance(
                        ood_x=ood_x,
                        ood_scope_scores=ood_scope_scores,
                        ood_fold_probs=ood_fold_probs,
                        scope_global=scope_global,
                        scope_by_class=scope_by_class,
                        mlp_global=mlp_global,
                        mlp_by_class=mlp_by_class,
                        mlp_score_name=mlp_score_name,
                        class_aware=class_aware,
                    )
                    rows.append({
                        "mode": mode,
                        "scopeTargetCoverage": scope_target,
                        "mlpScore": mlp_score_name,
                        "mlpTargetCoverage": mlp_target,
                        "scopeGlobalThreshold": scope_global,
                        "scopeThresholdByPredictedClass": {
                            DISEASES[i]: float(scope_by_class[i]) for i in range(len(DISEASES))
                        },
                        "mlpGlobalThreshold": mlp_global,
                        "mlpThresholdByPredictedClass": {
                            DISEASES[i]: float(mlp_by_class[i]) for i in range(len(DISEASES))
                        },
                        "effectiveIdCoverage": float(accepted.mean()),
                        "coverageWithinEvidenceGate": float(accepted.sum() / id_gate.sum()),
                        "selectiveAccuracy": accuracy,
                        "selectiveRisk": (1.0 - accuracy) if accuracy is not None else None,
                        "oodAcceptanceEndToEndMeanAcrossMlpFolds": ood_mean,
                        "oodAcceptanceEndToEndStdAcrossMlpFolds": ood_std,
                        "perTrueClass": class_rows(accepted, id_y, correct, id_gate),
                    })
    return rows


def load_shipped_model(model_dir: Path) -> dict:
    parts = sorted(model_dir.glob("v2-*.part"))
    if len(parts) != 8:
        raise ValueError(f"Expected 8 v2 parts in {model_dir}, got {len(parts)}")
    snapshot = json.loads("".join(part.read_text(encoding="utf-8") for part in parts))
    if snapshot["inputConceptIds"] != list(CONCEPT_COLUMNS.keys()):
        raise ValueError("Shipped v2 concept order mismatch")
    if snapshot["outputDiseaseIds"] != list(DISEASES):
        raise ValueError("Shipped v2 disease order mismatch")
    return snapshot


def shipped_predict(x: np.ndarray, snapshot: dict) -> np.ndarray:
    w1 = np.asarray(snapshot["weightsInputHidden"], dtype=np.float64)
    b1 = np.asarray(snapshot["biasHidden"], dtype=np.float64)
    w2 = np.asarray(snapshot["weightsHiddenOutput"], dtype=np.float64)
    b2 = np.asarray(snapshot["biasOutput"], dtype=np.float64)
    hidden = np.maximum(0.0, x @ w1 + b1)
    logits = hidden @ w2 + b2
    logits = logits - logits.max(axis=1, keepdims=True)
    exp = np.exp(logits)
    return exp / exp.sum(axis=1, keepdims=True)


def case_gate_evaluation(
    rows: list[dict],
    x: np.ndarray,
    shipped_probs: np.ndarray,
    scope_models: list[LogisticRegression],
    operating_points: list[dict],
    group_key: str | None,
) -> list[dict]:
    predicted = np.argmax(shipped_probs, axis=1)
    shipped_scores = score_vectors(shipped_probs)
    evidence = x.sum(axis=1) >= MIN_CONCEPTS
    scope_fold_scores = np.vstack([
        model.predict_proba(x)[:, 1] for model in scope_models
    ])

    evaluations: list[dict] = []
    for point_index, point in enumerate(operating_points):
        scope_by_class = np.asarray([
            point["scopeThresholdByPredictedClass"][disease_id] for disease_id in DISEASES
        ])
        mlp_by_class = np.asarray([
            point["mlpThresholdByPredictedClass"][disease_id] for disease_id in DISEASES
        ])
        if point["mode"] == "class_aware":
            scope_threshold = scope_by_class[predicted]
            mlp_threshold = mlp_by_class[predicted]
        else:
            scope_threshold = np.full(len(rows), point["scopeGlobalThreshold"])
            mlp_threshold = np.full(len(rows), point["mlpGlobalThreshold"])

        mlp_pass = shipped_scores[point["mlpScore"]] >= mlp_threshold
        fold_acceptance = []
        for fold_scores in scope_fold_scores:
            fold_acceptance.append(evidence & mlp_pass & (fold_scores >= scope_threshold))
        acceptance = np.vstack(fold_acceptance)
        fractions = acceptance.mean(axis=0)

        item = {
            "operatingPointIndex": point_index,
            "mode": point["mode"],
            "scopeTargetCoverage": point["scopeTargetCoverage"],
            "mlpScore": point["mlpScore"],
            "mlpTargetCoverage": point["mlpTargetCoverage"],
            "meanAcceptanceAcrossCasesAndScopeFolds": float(fractions.mean()),
            "casesAcceptedInAnyScopeFold": int(np.sum(fractions > 0.0)),
            "casesAcceptedInAllScopeFolds": int(np.sum(fractions == 1.0)),
            "casesRejectedInAllScopeFolds": int(np.sum(fractions == 0.0)),
        }

        if group_key is None:
            correct = np.asarray([row.get("rank") == 1 for row in rows], dtype=bool)
            all_fold_pairs = []
            for fold_mask in acceptance:
                accepted_count = int(fold_mask.sum())
                accepted_correct = int((fold_mask & correct).sum())
                all_fold_pairs.append((accepted_count, accepted_correct))
            total_accepted = sum(pair[0] for pair in all_fold_pairs)
            total_correct = sum(pair[1] for pair in all_fold_pairs)
            item.update({
                "meanAcceptedCasesAcrossScopeFolds": float(np.mean([p[0] for p in all_fold_pairs])),
                "acceptedTop1AccuracyAcrossAllScopeFoldDecisions": (
                    total_correct / total_accepted if total_accepted else None
                ),
                "meanRejectedCorrectCasesAcrossScopeFolds": float(np.mean([
                    int((~fold_mask & correct).sum()) for fold_mask in acceptance
                ])),
                "nonTop1Cases": [
                    {
                        "diseaseId": row.get("diseaseId"),
                        "variant": row.get("variant"),
                        "rank": row.get("rank"),
                        "predictedDiseaseId": DISEASES[predicted[i]],
                        "mlpScore": float(shipped_scores[point["mlpScore"]][i]),
                        "acceptanceFractionAcrossScopeFolds": float(fractions[i]),
                    }
                    for i, row in enumerate(rows)
                    if not correct[i]
                ],
            })
        else:
            for group in sorted({row[group_key] for row in rows}):
                mask = np.asarray([row[group_key] == group for row in rows], dtype=bool)
                group_fractions = fractions[mask]
                item[f"{group}Summary"] = {
                    "caseCount": int(mask.sum()),
                    "meanAcceptanceAcrossCasesAndScopeFolds": float(group_fractions.mean()),
                    "casesAcceptedInAnyScopeFold": int(np.sum(group_fractions > 0.0)),
                    "casesAcceptedInAllScopeFolds": int(np.sum(group_fractions == 1.0)),
                    "casesRejectedInAllScopeFolds": int(np.sum(group_fractions == 0.0)),
                }
            item["positiveCases"] = [
                {
                    "id": row.get("id"),
                    "expectedDiseaseId": row.get("expectedDiseaseId"),
                    "predictedDiseaseId": DISEASES[predicted[i]],
                    "mlpMaxConfidence": float(shipped_scores["max_confidence"][i]),
                    "mlpMargin": float(shipped_scores["margin"][i]),
                    "acceptanceFractionAcrossScopeFolds": float(fractions[i]),
                }
                for i, row in enumerate(rows)
                if row.get(group_key) == "positive"
            ]
        evaluations.append(item)
    return evaluations


def evaluate(
    csv_path: Path,
    frozen_path: Path,
    boundary_path: Path,
    model_dir: Path,
) -> dict:
    id_x, id_y, ood_x, meta = load_id_and_ood(csv_path)
    id_probs, ood_fold_probs = oof_predict(id_x, id_y, ood_x)
    id_scope_scores, ood_scope_scores, scope_models = train_scope_oof(id_x, ood_x)

    operating_points = build_operating_points(
        id_x=id_x,
        id_y=id_y,
        id_probs=id_probs,
        id_scope_scores=id_scope_scores,
        ood_x=ood_x,
        ood_scope_scores=ood_scope_scores,
        ood_fold_probs=ood_fold_probs,
    )

    snapshot = load_shipped_model(model_dir)
    frozen_rows, frozen_x = load_case_vectors(frozen_path)
    frozen_payload = json.loads(frozen_path.read_text(encoding="utf-8"))
    if not frozen_payload.get("frozen") or not frozen_payload.get("trainingUseForbidden"):
        raise ValueError("Frozen challenge must explicitly forbid training use")
    boundary_rows, boundary_x = load_case_vectors(boundary_path)

    frozen_probs = shipped_predict(frozen_x, snapshot)
    boundary_probs = shipped_predict(boundary_x, snapshot)

    return {
        "notes": [
            "All gates consume only complaint-derived concepts.",
            "Scope and MLP thresholds are fitted from OOF research predictions; frozen/curated Russian cases never fit thresholds.",
            "Class-aware thresholds are selected by the MLP predicted class and therefore do not require a true diagnosis at runtime.",
            "The shipped v2 MLP is used exactly for Russian case evaluation; its softmax remains classifier confidence, not clinical probability.",
            "The source-matrix OOD pool and small curated Russian sets are research probes, not clinical validation.",
        ],
        "dataset": meta,
        "minConcepts": MIN_CONCEPTS,
        "operatingPoints": operating_points,
        "frozenRussian": {
            "caseCount": len(frozen_rows),
            "baselineTop1Hits": int(sum(row.get("rank") == 1 for row in frozen_rows)),
            "evaluations": case_gate_evaluation(
                frozen_rows, frozen_x, frozen_probs, scope_models, operating_points, None
            ),
        },
        "curatedBoundary": {
            "caseCount": len(boundary_rows),
            "evaluations": case_gate_evaluation(
                boundary_rows, boundary_x, boundary_probs, scope_models, operating_points, "group"
            ),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--frozen-json", required=True, type=Path)
    parser.add_argument("--boundary-json", required=True, type=Path)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("joint_abstention_report.json"))
    args = parser.parse_args()

    report = evaluate(args.csv, args.frozen_json, args.boundary_json, args.model_dir)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
