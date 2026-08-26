#!/usr/bin/env python3
"""Measure how confident pretrained v2 becomes on sparse concept inputs.

This is not an OOD validation and must not be used alone to choose a production reject
threshold. It is a deterministic engineering probe over the exact model artifact shipped
in the APK: all 47 singleton concepts and all 47-choose-2 concept pairs are evaluated.

The report helps answer one narrow question: can the closed-set softmax look confident
when the available evidence is sparse? If yes, a raw max-softmax cutoff is especially
unsafe without the separate evidence/OOD gates.
"""
from __future__ import annotations

import json
import math
from itertools import combinations
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARTS_DIR = ROOT / "app/src/main/assets/disease_model"


def load_model() -> dict:
    parts = sorted(PARTS_DIR.glob("v2-*.part"))
    if not parts:
        raise FileNotFoundError("No v2 model chunks found")
    raw = "".join(path.read_text(encoding="utf-8") for path in parts)
    return json.loads(raw)


def softmax(logits: list[float]) -> list[float]:
    peak = max(logits)
    exps = [math.exp(v - peak) for v in logits]
    total = sum(exps)
    return [v / total for v in exps]


def predict(model: dict, active: tuple[int, ...]) -> list[float]:
    w1 = model["weightsInputHidden"]
    b1 = model["biasHidden"]
    w2 = model["weightsHiddenOutput"]
    b2 = model["biasOutput"]

    hidden = []
    for h, bias in enumerate(b1):
        value = bias + sum(w1[i][h] for i in active)
        hidden.append(max(0.0, value))

    logits = []
    for o, bias in enumerate(b2):
        value = bias
        for h, activation in enumerate(hidden):
            value += activation * w2[h][o]
        logits.append(value)
    return softmax(logits)


def scores(probabilities: list[float]) -> tuple[float, float, float]:
    ordered = sorted(probabilities, reverse=True)
    max_confidence = ordered[0]
    margin = ordered[0] - ordered[1]
    entropy = -sum(p * math.log(max(p, 1e-12)) for p in probabilities)
    certainty = 1.0 - entropy / math.log(len(probabilities))
    return max_confidence, margin, certainty


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    pos = (len(ordered) - 1) * q
    lower = int(math.floor(pos))
    upper = int(math.ceil(pos))
    if lower == upper:
        return ordered[lower]
    weight = pos - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def summarize(rows: list[dict]) -> dict:
    result = {"count": len(rows)}
    for key in ("maxConfidence", "margin", "certainty"):
        values = [row[key] for row in rows]
        result[key] = {
            "min": min(values),
            "p25": percentile(values, 0.25),
            "median": percentile(values, 0.50),
            "p75": percentile(values, 0.75),
            "p90": percentile(values, 0.90),
            "p95": percentile(values, 0.95),
            "max": max(values),
            "shareAtLeast50pct": sum(v >= 0.50 for v in values) / len(values),
            "shareAtLeast70pct": sum(v >= 0.70 for v in values) / len(values),
            "shareAtLeast90pct": sum(v >= 0.90 for v in values) / len(values),
        }
    return result


def evaluate() -> dict:
    model = load_model()
    concepts = model["inputConceptIds"]
    diseases = model["outputDiseaseIds"]
    if len(concepts) != 47 or len(diseases) != 14:
        raise ValueError(f"Unexpected model shape: {len(concepts)} concepts, {len(diseases)} classes")

    singleton_rows = []
    for i, concept in enumerate(concepts):
        probabilities = predict(model, (i,))
        max_confidence, margin, certainty = scores(probabilities)
        winner = max(range(len(probabilities)), key=probabilities.__getitem__)
        singleton_rows.append({
            "concepts": [concept],
            "topDisease": diseases[winner],
            "maxConfidence": max_confidence,
            "margin": margin,
            "certainty": certainty,
        })

    pair_rows = []
    for i, j in combinations(range(len(concepts)), 2):
        probabilities = predict(model, (i, j))
        max_confidence, margin, certainty = scores(probabilities)
        winner = max(range(len(probabilities)), key=probabilities.__getitem__)
        pair_rows.append({
            "concepts": [concepts[i], concepts[j]],
            "topDisease": diseases[winner],
            "maxConfidence": max_confidence,
            "margin": margin,
            "certainty": certainty,
        })

    top_singletons = sorted(singleton_rows, key=lambda row: row["maxConfidence"], reverse=True)[:10]
    top_pairs = sorted(pair_rows, key=lambda row: row["maxConfidence"], reverse=True)[:15]

    return {
        "notes": [
            "This is a sparse-input probe of the shipped closed-set model, not an OOD validation set.",
            "Percentages are model confidence among 14 supported classes, not clinical disease probabilities.",
            "A high singleton/pair confidence demonstrates why confidence alone cannot define scope.",
        ],
        "model": {
            "conceptCount": len(concepts),
            "classCount": len(diseases),
            "singletonInputs": len(singleton_rows),
            "pairInputs": len(pair_rows),
        },
        "singletons": summarize(singleton_rows),
        "pairs": summarize(pair_rows),
        "highestConfidenceSingletons": top_singletons,
        "highestConfidencePairs": top_pairs,
    }


def main() -> None:
    report = evaluate()
    output = ROOT / "build-sparse-model-report.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"Sparse model report written to {output}")


if __name__ == "__main__":
    main()
