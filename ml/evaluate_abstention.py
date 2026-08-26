#!/usr/bin/env python3
"""Evaluate abstention / OOD gates for Aritmia's cardiovascular classifier.

This script intentionally does not invent a confidence threshold. It reuses the same
47 complaint-derived concepts and 14 cardiovascular labels as train_cardiovascular.py,
produces out-of-fold (OOF) predictions for in-domain cardiovascular rows, and treats
all other diseases in the 820-disease matrix as an OOD challenge pool when at least
one of the 47 model concepts is present.

The report compares three simple selective-prediction scores:
  * max_confidence = largest softmax output
  * margin         = top-1 minus top-2 softmax output
  * certainty      = 1 - normalized predictive entropy

For each score it reports risk/coverage operating points and how many OOD rows would
incorrectly be accepted at the same threshold. A threshold should only be promoted to
Android after reviewing this report and, ideally, an additional external text-OOD set.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, top_k_accuracy_score
from sklearn.model_selection import StratifiedKFold

from train_cardiovascular import (
    CONCEPT_COLUMNS,
    DISEASES,
    LABEL_MAP,
    SEED,
    generate_augmentation,
    new_model,
)

CV_SEED = 20260826
DEFAULT_COVERAGES = (0.50, 0.60, 0.70, 0.80, 0.90, 0.95)


def load_id_and_ood(csv_path: Path) -> tuple[np.ndarray, np.ndarray, np.ndarray, dict]:
    header = pd.read_csv(csv_path, nrows=0).columns.tolist()
    if not header:
        raise ValueError("CSV has no columns")
    label_col = header[0]

    usable = {key: [c for c in columns if c in header] for key, columns in CONCEPT_COLUMNS.items()}
    missing = [key for key, columns in usable.items() if not columns]
    if missing:
        raise ValueError(f"Dataset is missing expected concept columns: {missing}")

    usecols = [label_col] + sorted(set(sum(usable.values(), [])))
    frames: list[pd.DataFrame] = []
    for chunk in pd.read_csv(csv_path, usecols=usecols, chunksize=25000, low_memory=False):
        frames.append(chunk)
    if not frames:
        raise ValueError("CSV has no rows")
    df = pd.concat(frames, ignore_index=True)

    x = np.zeros((len(df), len(CONCEPT_COLUMNS)), dtype=np.float64)
    for j, (_, columns) in enumerate(usable.items()):
        values = df[columns].apply(pd.to_numeric, errors="coerce").fillna(0).to_numpy()
        x[:, j] = (values > 0).any(axis=1)

    raw_labels = df[label_col].astype(str).str.strip().str.lower().to_numpy()
    in_domain = np.asarray([label in LABEL_MAP for label in raw_labels], dtype=bool)
    has_model_evidence = x.sum(axis=1) > 0

    id_x = x[in_domain]
    id_y = np.asarray([DISEASES.index(LABEL_MAP[label]) for label in raw_labels[in_domain]], dtype=np.int64)

    # Match training behavior: deduplicate identical concept-vector + target combinations.
    joined = np.c_[id_x, id_y]
    _, first = np.unique(joined, axis=0, return_index=True)
    id_x, id_y = id_x[first], id_y[first]

    # OOD rows deliberately include only examples that can reach the cardiovascular model.
    # Rows with zero recognized model concepts are already caught by OUT_OF_SCOPE before MLP inference.
    ood_mask = (~in_domain) & has_model_evidence
    ood_x = x[ood_mask]
    ood_labels = raw_labels[ood_mask]

    # Deduplicate OOD concept patterns so very large diseases do not dominate the acceptance metric.
    if len(ood_x):
        _, ood_first = np.unique(ood_x, axis=0, return_index=True)
        ood_x = ood_x[ood_first]
        ood_labels = ood_labels[ood_first]

    meta = {
        "rawRows": int(len(df)),
        "idUniqueRows": int(len(id_x)),
        "oodUniqueRowsWithKnownConcepts": int(len(ood_x)),
        "oodUniqueDiseaseLabels": int(len(set(ood_labels.tolist()))),
        "conceptCount": len(CONCEPT_COLUMNS),
        "classCount": len(DISEASES),
    }
    return id_x, id_y, ood_x, meta


def oof_predict(id_x: np.ndarray, id_y: np.ndarray, ood_x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    splitter = StratifiedKFold(n_splits=5, shuffle=True, random_state=CV_SEED)
    aug_x, aug_y = generate_augmentation()

    id_probs = np.zeros((len(id_x), len(DISEASES)), dtype=np.float64)
    ood_fold_probs: list[np.ndarray] = []

    for fold, (train_idx, test_idx) in enumerate(splitter.split(id_x, id_y), start=1):
        train_x = np.vstack([id_x[train_idx], aug_x])
        train_y = np.concatenate([id_y[train_idx], aug_y])
        model = new_model()
        model.fit(train_x, train_y)

        test_probs = model.predict_proba(id_x[test_idx])
        aligned = np.zeros((len(test_idx), len(DISEASES)), dtype=np.float64)
        for local_col, class_id in enumerate(model.classes_):
            aligned[:, int(class_id)] = test_probs[:, local_col]
        id_probs[test_idx] = aligned

        if len(ood_x):
            raw_ood = model.predict_proba(ood_x)
            aligned_ood = np.zeros((len(ood_x), len(DISEASES)), dtype=np.float64)
            for local_col, class_id in enumerate(model.classes_):
                aligned_ood[:, int(class_id)] = raw_ood[:, local_col]
            ood_fold_probs.append(aligned_ood)

        print(f"fold {fold}/5 complete")

    ood_probs = (
        np.mean(np.stack(ood_fold_probs, axis=0), axis=0)
        if ood_fold_probs
        else np.empty((0, len(DISEASES)), dtype=np.float64)
    )
    return id_probs, ood_probs


def score_vectors(probabilities: np.ndarray) -> dict[str, np.ndarray]:
    if len(probabilities) == 0:
        empty = np.empty(0, dtype=np.float64)
        return {"max_confidence": empty, "margin": empty, "certainty": empty}

    sorted_probs = np.sort(probabilities, axis=1)
    max_confidence = sorted_probs[:, -1]
    margin = sorted_probs[:, -1] - sorted_probs[:, -2]

    clipped = np.clip(probabilities, 1e-12, 1.0)
    entropy = -(clipped * np.log(clipped)).sum(axis=1)
    normalized_entropy = entropy / np.log(probabilities.shape[1])
    certainty = 1.0 - normalized_entropy

    return {
        "max_confidence": max_confidence,
        "margin": margin,
        "certainty": certainty,
    }


def selective_table(
    name: str,
    id_score: np.ndarray,
    ood_score: np.ndarray,
    correct: np.ndarray,
    coverages: tuple[float, ...],
) -> list[dict]:
    rows: list[dict] = []
    for target_coverage in coverages:
        threshold = float(np.quantile(id_score, 1.0 - target_coverage, method="higher"))
        accepted = id_score >= threshold
        coverage = float(accepted.mean())
        accuracy = float(correct[accepted].mean()) if accepted.any() else float("nan")
        risk = 1.0 - accuracy if accepted.any() else float("nan")
        ood_acceptance = float((ood_score >= threshold).mean()) if len(ood_score) else float("nan")
        rows.append(
            {
                "score": name,
                "targetCoverage": target_coverage,
                "threshold": threshold,
                "actualCoverage": coverage,
                "selectiveAccuracy": accuracy,
                "selectiveRisk": risk,
                "oodAcceptanceRate": ood_acceptance,
            }
        )
    return rows


def concept_count_breakdown(id_x: np.ndarray, correct: np.ndarray, confidence: np.ndarray) -> list[dict]:
    counts = id_x.sum(axis=1).astype(int)
    groups = [
        ("1", counts == 1),
        ("2", counts == 2),
        ("3", counts == 3),
        ("4+", counts >= 4),
    ]
    rows = []
    for label, mask in groups:
        if not mask.any():
            continue
        rows.append(
            {
                "conceptCount": label,
                "rows": int(mask.sum()),
                "top1Accuracy": float(correct[mask].mean()),
                "meanMaxConfidence": float(confidence[mask].mean()),
                "medianMaxConfidence": float(np.median(confidence[mask])),
            }
        )
    return rows


def evaluate(csv_path: Path, coverages: tuple[float, ...]) -> dict:
    id_x, id_y, ood_x, meta = load_id_and_ood(csv_path)
    if len(id_x) == 0:
        raise ValueError("No supported cardiovascular rows found")
    if np.min(np.bincount(id_y, minlength=len(DISEASES))) < 5:
        raise ValueError("At least one cardiovascular class has fewer than 5 unique rows")

    id_probs, ood_probs = oof_predict(id_x, id_y, ood_x)
    predictions = np.argmax(id_probs, axis=1)
    correct = predictions == id_y
    top5 = top_k_accuracy_score(id_y, id_probs, k=min(5, len(DISEASES)), labels=np.arange(len(DISEASES)))

    id_scores = score_vectors(id_probs)
    ood_scores = score_vectors(ood_probs)

    selective: list[dict] = []
    aucs: dict[str, float | None] = {}
    for name, id_score in id_scores.items():
        selective.extend(selective_table(name, id_score, ood_scores[name], correct, coverages))
        if len(ood_scores[name]):
            labels = np.concatenate([np.ones(len(id_score)), np.zeros(len(ood_scores[name]))])
            scores = np.concatenate([id_score, ood_scores[name]])
            aucs[name] = float(roc_auc_score(labels, scores))
        else:
            aucs[name] = None

    return {
        "notes": [
            "All model inputs are the same 47 complaint-derived concepts used by Android.",
            "OOD rows are diseases outside the 14 supported classes but containing at least one known model concept.",
            "The current Android 0/1-concept evidence gate is evaluated separately and is not a calibrated confidence threshold.",
            "Do not call softmax scores clinical disease probabilities.",
        ],
        "dataset": meta,
        "overall": {
            "oofTop1Accuracy": float(correct.mean()),
            "oofTop5Accuracy": float(top5),
        },
        "inDomainVsOodAuc": aucs,
        "conceptCountBreakdown": concept_count_breakdown(id_x, correct, id_scores["max_confidence"]),
        "riskCoverage": selective,
    }


def parse_coverages(value: str) -> tuple[float, ...]:
    result = tuple(float(part.strip()) for part in value.split(",") if part.strip())
    if not result or any(not 0.0 < item <= 1.0 for item in result):
        raise argparse.ArgumentTypeError("coverages must be comma-separated values in (0, 1]")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, type=Path, help="unified 820-disease symptom matrix CSV")
    parser.add_argument("--output", type=Path, default=Path("abstention_report.json"))
    parser.add_argument(
        "--coverages",
        type=parse_coverages,
        default=DEFAULT_COVERAGES,
        help="comma-separated target ID coverages, e.g. 0.5,0.6,0.7,0.8,0.9,0.95",
    )
    args = parser.parse_args()

    report = evaluate(args.csv, args.coverages)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written to {args.output}")


if __name__ == "__main__":
    main()
