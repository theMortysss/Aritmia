#!/usr/bin/env python3
"""Evaluate a complaint-concept specificity gate for ambiguous in-scope cases.

This is not a second diagnosis model. The gate uses the exact human-readable
DiseaseCatalog weights shipped with Android to ask a narrower question:
"does this complaint-derived concept set distinguish one supported profile from
its nearest alternative?"

Higher scores mean more specific evidence. Thresholds are selected by ID coverage
quantiles on out-of-fold cardiovascular rows, never from the frozen Russian set.
The frozen set is evaluation-only and must not be used for training or threshold fit.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from evaluate_abstention import DEFAULT_COVERAGES, DISEASES, load_id_and_ood, oof_predict, parse_coverages

MIN_CONCEPTS = 2


def load_catalog(path: Path) -> tuple[list[str], np.ndarray]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    concept_ids = list(payload["conceptIds"])
    disease_rows = payload["diseases"]
    disease_ids = [row["id"] for row in disease_rows]
    if disease_ids != list(DISEASES):
        raise ValueError(f"Disease order mismatch: catalog={disease_ids}, python={list(DISEASES)}")

    index = {concept_id: i for i, concept_id in enumerate(concept_ids)}
    weights = np.zeros((len(disease_rows), len(concept_ids)), dtype=np.float64)
    for d, row in enumerate(disease_rows):
        for concept_id, weight in row["weights"].items():
            if concept_id not in index:
                raise ValueError(f"Unknown catalog concept: {concept_id}")
            weights[d, index[concept_id]] = float(weight)

    if np.any(weights.sum(axis=1) <= 0):
        raise ValueError("Every disease must have positive catalog weight")
    return concept_ids, weights


def coverage_matrix(x: np.ndarray, weights: np.ndarray) -> np.ndarray:
    totals = weights.sum(axis=1)
    return (x @ weights.T) / totals[np.newaxis, :]


def catalog_scores(
    x: np.ndarray,
    weights: np.ndarray,
    model_top: np.ndarray | None = None,
) -> dict[str, np.ndarray]:
    if len(x) == 0:
        empty = np.empty(0, dtype=np.float64)
        result = {
            "catalog_margin": empty,
            "catalog_top_coverage": empty,
        }
        if model_top is not None:
            result["model_catalog_support_margin"] = empty
        return result

    coverage = coverage_matrix(x, weights)
    sorted_scores = np.sort(coverage, axis=1)
    result = {
        "catalog_margin": sorted_scores[:, -1] - sorted_scores[:, -2],
        "catalog_top_coverage": sorted_scores[:, -1],
    }

    if model_top is not None:
        if len(model_top) != len(x):
            raise ValueError("model_top length mismatch")
        rows = np.arange(len(x))
        chosen = coverage[rows, model_top]
        alternatives = coverage.copy()
        alternatives[rows, model_top] = -np.inf
        result["model_catalog_support_margin"] = chosen - np.max(alternatives, axis=1)
    return result


def ood_rates(
    score_name: str,
    threshold: float,
    ood_x: np.ndarray,
    ood_gate: np.ndarray,
    weights: np.ndarray,
    ood_fold_probs: list[np.ndarray],
) -> tuple[float, float]:
    rates: list[float] = []
    if score_name == "model_catalog_support_margin":
        for probs in ood_fold_probs:
            model_top = np.argmax(probs, axis=1)
            scores = catalog_scores(ood_x, weights, model_top)[score_name]
            rates.append(float(((scores >= threshold) & ood_gate).mean()))
    else:
        scores = catalog_scores(ood_x, weights)[score_name]
        rate = float(((scores >= threshold) & ood_gate).mean())
        fold_count = max(1, len(ood_fold_probs))
        rates = [rate] * fold_count
    return float(np.mean(rates)), float(np.std(rates))


def operating_points(
    id_x: np.ndarray,
    id_y: np.ndarray,
    id_probs: np.ndarray,
    ood_x: np.ndarray,
    ood_fold_probs: list[np.ndarray],
    weights: np.ndarray,
    coverages: tuple[float, ...],
) -> list[dict]:
    id_gate = id_x.sum(axis=1) >= MIN_CONCEPTS
    ood_gate = ood_x.sum(axis=1) >= MIN_CONCEPTS
    model_top = np.argmax(id_probs, axis=1)
    correct = model_top == id_y
    scores = catalog_scores(id_x, weights, model_top)

    rows: list[dict] = []
    for score_name, all_scores in scores.items():
        gated = all_scores[id_gate]
        for target_coverage in coverages:
            threshold = float(np.quantile(gated, 1.0 - target_coverage, method="higher"))
            accepted = id_gate & (all_scores >= threshold)
            selective_accuracy = float(correct[accepted].mean()) if accepted.any() else float("nan")
            ood_mean, ood_std = ood_rates(
                score_name, threshold, ood_x, ood_gate, weights, ood_fold_probs
            )
            rows.append(
                {
                    "score": score_name,
                    "targetCoverageWithinEvidenceGate": target_coverage,
                    "threshold": threshold,
                    "actualCoverageWithinEvidenceGate": float(accepted.sum() / id_gate.sum()),
                    "effectiveIdCoverageOfAllRows": float(accepted.mean()),
                    "selectiveAccuracy": selective_accuracy,
                    "selectiveRisk": 1.0 - selective_accuracy,
                    "oodAcceptanceEndToEndMeanAcrossFolds": ood_mean,
                    "oodAcceptanceEndToEndStdAcrossFolds": ood_std,
                }
            )
    return rows


def frozen_evaluation(
    frozen_path: Path,
    concept_ids: list[str],
    weights: np.ndarray,
    points: list[dict],
) -> dict:
    payload = json.loads(frozen_path.read_text(encoding="utf-8"))
    if not payload.get("frozen") or not payload.get("trainingUseForbidden"):
        raise ValueError("Frozen report must explicitly forbid training use")

    concept_index = {concept_id: i for i, concept_id in enumerate(concept_ids)}
    disease_index = {disease_id: i for i, disease_id in enumerate(DISEASES)}
    cases = payload["cases"]
    x = np.zeros((len(cases), len(concept_ids)), dtype=np.float64)
    model_top = np.zeros(len(cases), dtype=np.int64)
    correct = np.zeros(len(cases), dtype=bool)
    evidence_gate = np.zeros(len(cases), dtype=bool)

    for i, case in enumerate(cases):
        for concept_id in case["concepts"]:
            if concept_id not in concept_index:
                raise ValueError(f"Unknown frozen concept: {concept_id}")
            x[i, concept_index[concept_id]] = 1.0
        evidence_gate[i] = len(case["concepts"]) >= MIN_CONCEPTS
        if case["top5"]:
            model_top[i] = disease_index[case["top5"][0]["diseaseId"]]
        correct[i] = case.get("rank") == 1

    scores = catalog_scores(x, weights, model_top)
    evaluations: list[dict] = []
    for point in points:
        score_name = point["score"]
        threshold = point["threshold"]
        accepted = evidence_gate & (scores[score_name] >= threshold)
        accepted_count = int(accepted.sum())
        accepted_correct = int((accepted & correct).sum())
        accepted_failures = [
            {
                "diseaseId": case["diseaseId"],
                "variant": case["variant"],
                "rank": case.get("rank"),
                "score": float(scores[score_name][i]),
            }
            for i, case in enumerate(cases)
            if accepted[i] and not correct[i]
        ]
        evaluations.append(
            {
                "score": score_name,
                "targetCoverageWithinEvidenceGate": point["targetCoverageWithinEvidenceGate"],
                "thresholdFromOofId": threshold,
                "acceptedCases": accepted_count,
                "frozenCoverage": accepted_count / len(cases),
                "acceptedTop1Accuracy": (
                    accepted_correct / accepted_count if accepted_count else None
                ),
                "rejectedCorrectCases": int((~accepted & correct).sum()),
                "acceptedFailures": accepted_failures,
            }
        )

    per_case = []
    for i, case in enumerate(cases):
        per_case.append(
            {
                "diseaseId": case["diseaseId"],
                "variant": case["variant"],
                "rank": case.get("rank"),
                "conceptCount": len(case["concepts"]),
                "catalogMargin": float(scores["catalog_margin"][i]),
                "catalogTopCoverage": float(scores["catalog_top_coverage"][i]),
                "modelCatalogSupportMargin": float(scores["model_catalog_support_margin"][i]),
            }
        )

    return {
        "caseCount": len(cases),
        "baselineTop1Hits": int(correct.sum()),
        "operatingPointEvaluation": evaluations,
        "cases": per_case,
    }


def evaluate(
    csv_path: Path,
    catalog_path: Path,
    frozen_path: Path,
    coverages: tuple[float, ...],
) -> dict:
    concept_ids, weights = load_catalog(catalog_path)
    id_x, id_y, ood_x, meta = load_id_and_ood(csv_path)
    if id_x.shape[1] != len(concept_ids):
        raise ValueError("Concept count mismatch between dataset mapping and Android catalog")

    id_probs, ood_fold_probs = oof_predict(id_x, id_y, ood_x)
    points = operating_points(id_x, id_y, id_probs, ood_x, ood_fold_probs, weights, coverages)

    return {
        "notes": [
            "All inputs are complaint-derived concepts only.",
            "DiseaseCatalog weights are exported from the real Android Kotlin code in the same workflow run.",
            "Catalog scores are evidence-specificity heuristics, not disease probabilities.",
            "Thresholds are selected only by OOF ID coverage quantiles; frozen Russian cases never fit thresholds.",
            "This remains research validation and does not establish clinical safety.",
        ],
        "dataset": meta,
        "minConcepts": MIN_CONCEPTS,
        "operatingPoints": points,
        "frozenRussian": frozen_evaluation(frozen_path, concept_ids, weights, points),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--catalog-json", required=True, type=Path)
    parser.add_argument("--frozen-json", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("specificity_gate_report.json"))
    parser.add_argument("--coverages", type=parse_coverages, default=DEFAULT_COVERAGES)
    args = parser.parse_args()

    report = evaluate(args.csv, args.catalog_json, args.frozen_json, args.coverages)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
