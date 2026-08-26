#!/usr/bin/env python3
"""Evaluate confidence thresholds after explicit complaint-evidence gates.

The baseline abstention report shows that closed-set softmax confidence alone does not
separate the 14 supported cardiovascular classes from other diseases. This companion
report measures the actual two-stage policy used by Android:

  1. require at least N complaint-derived model concepts;
  2. optionally require a confidence / margin / entropy-certainty threshold.

For each minimum concept count (1, 2, 3, 4) the report includes ID accuracy and
coverage, OOD evidence-gate pass rate, and selective risk/coverage with both
conditional and end-to-end OOD acceptance. It still does not replace an external
free-text OOD validation set.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from evaluate_abstention import (
    DEFAULT_COVERAGES,
    DISEASES,
    load_id_and_ood,
    oof_predict,
    parse_coverages,
    score_vectors,
)

MIN_CONCEPT_GATES = (1, 2, 3, 4)


def top5_accuracy(y_true: np.ndarray, probabilities: np.ndarray) -> float:
    if len(y_true) == 0:
        return float("nan")
    k = min(5, probabilities.shape[1])
    top = np.argpartition(probabilities, -k, axis=1)[:, -k:]
    return float(np.mean([target in row for target, row in zip(y_true, top)]))


def ood_acceptance_for_gate(
    ood_fold_probs: list[np.ndarray],
    ood_gate: np.ndarray,
    score_name: str,
    threshold: float,
) -> dict[str, float]:
    if not ood_fold_probs or not len(ood_gate):
        nan = float("nan")
        return {
            "conditionalMeanAcrossFolds": nan,
            "conditionalStdAcrossFolds": nan,
            "endToEndMeanAcrossFolds": nan,
            "endToEndStdAcrossFolds": nan,
        }

    conditional_rates: list[float] = []
    end_to_end_rates: list[float] = []
    for probabilities in ood_fold_probs:
        scores = score_vectors(probabilities)[score_name]
        accepted = scores >= threshold
        conditional_rates.append(
            float(accepted[ood_gate].mean()) if ood_gate.any() else float("nan")
        )
        end_to_end_rates.append(float((accepted & ood_gate).mean()))

    return {
        "conditionalMeanAcrossFolds": float(np.nanmean(conditional_rates)),
        "conditionalStdAcrossFolds": float(np.nanstd(conditional_rates)),
        "endToEndMeanAcrossFolds": float(np.mean(end_to_end_rates)),
        "endToEndStdAcrossFolds": float(np.std(end_to_end_rates)),
    }


def risk_coverage_for_gate(
    id_gate: np.ndarray,
    ood_gate: np.ndarray,
    id_scores: dict[str, np.ndarray],
    correct: np.ndarray,
    ood_fold_probs: list[np.ndarray],
    coverages: tuple[float, ...],
) -> list[dict]:
    rows: list[dict] = []
    for score_name, all_scores in id_scores.items():
        gated_scores = all_scores[id_gate]
        if not len(gated_scores):
            continue
        for target_coverage in coverages:
            threshold = float(
                np.quantile(gated_scores, 1.0 - target_coverage, method="higher")
            )
            accepted = id_gate & (all_scores >= threshold)
            conditional_coverage = float(accepted.sum() / id_gate.sum())
            effective_coverage = float(accepted.mean())
            selective_accuracy = (
                float(correct[accepted].mean()) if accepted.any() else float("nan")
            )
            ood = ood_acceptance_for_gate(
                ood_fold_probs, ood_gate, score_name, threshold
            )
            rows.append(
                {
                    "score": score_name,
                    "targetCoverageWithinEvidenceGate": target_coverage,
                    "threshold": threshold,
                    "actualCoverageWithinEvidenceGate": conditional_coverage,
                    "effectiveIdCoverageOfAllRows": effective_coverage,
                    "selectiveAccuracy": selective_accuracy,
                    "selectiveRisk": 1.0 - selective_accuracy,
                    "oodAcceptanceConditionalOnEvidenceGate": {
                        "meanAcrossFolds": ood["conditionalMeanAcrossFolds"],
                        "stdAcrossFolds": ood["conditionalStdAcrossFolds"],
                    },
                    "oodAcceptanceEndToEnd": {
                        "meanAcrossFolds": ood["endToEndMeanAcrossFolds"],
                        "stdAcrossFolds": ood["endToEndStdAcrossFolds"],
                    },
                }
            )
    return rows


def evaluate(csv_path: Path, coverages: tuple[float, ...]) -> dict:
    id_x, id_y, ood_x, meta = load_id_and_ood(csv_path)
    id_probs, ood_fold_probs = oof_predict(id_x, id_y, ood_x)

    predictions = np.argmax(id_probs, axis=1)
    correct = predictions == id_y
    id_scores = score_vectors(id_probs)
    id_counts = id_x.sum(axis=1).astype(int)
    ood_counts = ood_x.sum(axis=1).astype(int)

    gates: list[dict] = []
    for min_concepts in MIN_CONCEPT_GATES:
        id_gate = id_counts >= min_concepts
        ood_gate = ood_counts >= min_concepts
        gated_probs = id_probs[id_gate]
        gated_y = id_y[id_gate]
        gates.append(
            {
                "minConcepts": min_concepts,
                "idRowsPassing": int(id_gate.sum()),
                "idEvidenceGateCoverage": float(id_gate.mean()),
                "idTop1AccuracyAfterEvidenceGate": float(correct[id_gate].mean()),
                "idTop5AccuracyAfterEvidenceGate": top5_accuracy(gated_y, gated_probs),
                "oodPatternsPassing": int(ood_gate.sum()),
                "oodEvidenceGatePassRate": float(ood_gate.mean()),
                "riskCoverage": risk_coverage_for_gate(
                    id_gate=id_gate,
                    ood_gate=ood_gate,
                    id_scores=id_scores,
                    correct=correct,
                    ood_fold_probs=ood_fold_probs,
                    coverages=coverages,
                ),
            }
        )

    return {
        "notes": [
            "All inputs are the same 47 complaint-derived concepts used by Android.",
            "minConcepts=2 corresponds to the current Android evidence gate before MLP ranking.",
            "Conditional OOD acceptance is measured only among OOD patterns that pass the evidence gate.",
            "End-to-end OOD acceptance includes both the evidence gate and the score threshold.",
            "These OOD rows come from other diseases in the same 820-disease matrix; an external free-text OOD set is still required before promoting a production threshold.",
            "Softmax outputs are classifier confidence among 14 supported classes, not clinical probabilities.",
        ],
        "dataset": meta,
        "overallOofTop1Accuracy": float(correct.mean()),
        "evidenceGates": gates,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument(
        "--output", type=Path, default=Path("evidence_gate_report.json")
    )
    parser.add_argument(
        "--coverages",
        type=parse_coverages,
        default=DEFAULT_COVERAGES,
    )
    args = parser.parse_args()

    report = evaluate(args.csv, args.coverages)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
