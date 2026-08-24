#!/usr/bin/env python3
"""Train the Aritmia cardiovascular multiclass model from symptom-only datasets.

Supported sources:
  * DDXPlus CSV splits + release_evidences.json
  * Symptom-to-Disease style CSV with disease label + binary symptom columns

The script deliberately ignores demographics, labs, ECG and vitals. Only symptoms are
mapped to the same 29 concepts used by the Android FreeTextSymptomExtractor.
"""
from __future__ import annotations

import argparse
import ast
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, classification_report, f1_score, top_k_accuracy_score
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPClassifier

SEED = 20260824
HIDDEN_SIZE = 36

# Must stay in exactly the same order as DiseaseCatalog.concepts in Android.
CONCEPTS: dict[str, list[str]] = {
    "palpitations": ["palpitation", "heart pounding", "heart racing", "rapid heartbeat"],
    "sudden_palpitations": ["sudden palpitation", "sudden heart racing", "paroxysmal palpitation"],
    "irregular_rhythm": ["irregular heartbeat", "irregular pulse", "irregular rhythm", "arrhythmia"],
    "skipped_beats": ["skipped beat", "premature beat", "heart pause", "extrasystole"],
    "fast_pulse": ["tachycardia", "fast pulse", "rapid pulse", "fast heart rate"],
    "slow_pulse": ["bradycardia", "slow pulse", "low heart rate"],
    "chest_pain": ["chest pain", "retrosternal pain", "precordial pain"],
    "exertional_chest_pain": ["chest pain on exertion", "exertional chest pain", "angina on exertion"],
    "pleuritic_chest_pain": ["pleuritic chest pain", "chest pain breathing", "pain on inspiration"],
    "positional_chest_pain": ["positional chest pain", "pain worse lying", "pain relieved sitting"],
    "pain_radiation": ["pain radiating arm", "pain radiating jaw", "radiating chest pain"],
    "chest_pressure": ["chest pressure", "chest tightness", "chest heaviness", "chest discomfort"],
    "dyspnea": ["dyspnea", "shortness of breath", "breathlessness", "difficulty breathing"],
    "exertional_dyspnea": ["dyspnea on exertion", "exertional dyspnea", "shortness of breath walking"],
    "orthopnea": ["orthopnea", "shortness of breath lying", "breathless lying flat"],
    "nocturnal_dyspnea": ["paroxysmal nocturnal dyspnea", "nocturnal dyspnea", "wakes breathless"],
    "edema": ["leg edema", "ankle edema", "peripheral edema", "swollen legs", "swollen ankles"],
    "syncope": ["syncope", "fainting", "loss of consciousness"],
    "exertional_syncope": ["exertional syncope", "fainting on exertion"],
    "dizziness": ["dizziness", "lightheadedness", "presyncope"],
    "weakness": ["weakness", "general weakness"],
    "fatigue": ["fatigue", "tiredness", "exercise intolerance"],
    "cold_sweat": ["cold sweat", "diaphoresis", "clammy sweat"],
    "nausea": ["nausea", "feeling sick"],
    "high_bp": ["hypertension", "high blood pressure", "elevated blood pressure"],
    "headache": ["headache"],
    "murmur": ["heart murmur", "cardiac murmur"],
    "fever": ["fever", "febrile"],
    "anxiety": ["anxiety", "panic", "fear"],
}

# Exact Android output order.
DISEASES = [
    "atrial_fibrillation",
    "supraventricular_tachycardia",
    "extrasystole",
    "sinus_bradycardia",
    "stable_angina",
    "acute_coronary_syndrome",
    "heart_failure",
    "arterial_hypertension",
    "aortic_stenosis",
    "pericarditis",
    "dilated_cardiomyopathy",
    "sinus_tachycardia",
]

# Dataset label aliases. Keep conservative: only map labels with the same clinical target.
DISEASE_ALIASES: dict[str, list[str]] = {
    "atrial_fibrillation": ["atrial fibrillation", "atrial flutter", "afib"],
    "supraventricular_tachycardia": ["psvt", "supraventricular tachycardia", "svt"],
    "extrasystole": ["extrasystole", "premature ventricular contraction", "premature atrial contraction", "pvc", "pac"],
    "sinus_bradycardia": ["sinus bradycardia", "bradycardia"],
    "stable_angina": ["stable angina", "angina pectoris", "ischemic heart disease", "ischaemic heart disease"],
    "acute_coronary_syndrome": ["possible nstemi / stemi", "myocardial infarction", "acute myocardial infarction", "nstemi", "stemi", "acute coronary syndrome"],
    "heart_failure": ["heart failure", "congestive heart failure", "chf"],
    "arterial_hypertension": ["hypertension", "arterial hypertension", "high blood pressure"],
    "aortic_stenosis": ["aortic stenosis"],
    "pericarditis": ["pericarditis"],
    "dilated_cardiomyopathy": ["dilated cardiomyopathy", "cardiomyopathy, dilated", "dcm"],
    "sinus_tachycardia": ["sinus tachycardia"],
}

LABEL_TO_ID = {
    alias.strip().lower(): disease_id
    for disease_id, aliases in DISEASE_ALIASES.items()
    for alias in aliases
}


def norm(text: object) -> str:
    value = str(text).lower().replace("_", " ").replace("-", " ")
    value = re.sub(r"[^a-z0-9+% ]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def map_label(raw: object) -> str | None:
    n = norm(raw)
    if n in LABEL_TO_ID:
        return LABEL_TO_ID[n]
    # Some datasets add punctuation/parenthetical descriptions.
    for alias, disease_id in LABEL_TO_ID.items():
        if len(alias) >= 6 and (alias in n or n in alias):
            return disease_id
    return None


def concepts_from_text(parts: Iterable[object]) -> np.ndarray:
    text = " | ".join(norm(x) for x in parts if str(x).strip())
    vec = np.zeros(len(CONCEPTS), dtype=np.float64)
    for i, aliases in enumerate(CONCEPTS.values()):
        if any(norm(alias) in text for alias in aliases):
            vec[i] = 1.0
    return vec


def parse_evidence_tokens(value: object) -> list[str]:
    if isinstance(value, list):
        return [str(x) for x in value]
    raw = str(value).strip()
    if not raw:
        return []
    try:
        parsed = ast.literal_eval(raw)
        if isinstance(parsed, list):
            return [str(x) for x in parsed]
    except (ValueError, SyntaxError):
        pass
    return [x.strip() for x in raw.strip("[]").split(",") if x.strip()]


def evidence_to_text(token: str, evidence_meta: dict) -> str:
    # DDXPlus tokens can be E_123 or E_123_@_V_45 / E_123_@_value.
    clean = token.strip().strip("'\"")
    if "_@_" in clean:
        evidence_id, raw_value = clean.split("_@_", 1)
    else:
        evidence_id, raw_value = clean, None
    meta = evidence_meta.get(evidence_id, {})
    chunks = [meta.get("question_en", "")]
    if raw_value is not None:
        meanings = meta.get("value_meaning", {}) or {}
        meaning = meanings.get(raw_value, {})
        if isinstance(meaning, dict):
            chunks.append(meaning.get("en", ""))
        else:
            chunks.append(str(meaning))
        chunks.append(raw_value)
    return " ".join(str(x) for x in chunks if x)


def load_ddxplus(csv_paths: list[Path], evidence_json: Path) -> tuple[np.ndarray, np.ndarray]:
    evidence_meta = json.loads(evidence_json.read_text(encoding="utf-8"))
    xs: list[np.ndarray] = []
    ys: list[int] = []
    for path in csv_paths:
        df = pd.read_csv(path)
        pathology_col = next((c for c in df.columns if c.upper() == "PATHOLOGY"), None)
        evidence_col = next((c for c in df.columns if c.upper() == "EVIDENCES"), None)
        if not pathology_col or not evidence_col:
            raise ValueError(f"{path}: expected PATHOLOGY and EVIDENCES columns")
        for _, row in df[[pathology_col, evidence_col]].iterrows():
            disease_id = map_label(row[pathology_col])
            if disease_id is None:
                continue
            texts = [evidence_to_text(t, evidence_meta) for t in parse_evidence_tokens(row[evidence_col])]
            vector = concepts_from_text(texts)
            if vector.sum() == 0:
                continue
            xs.append(vector)
            ys.append(DISEASES.index(disease_id))
    return np.asarray(xs), np.asarray(ys, dtype=np.int64)


def find_label_column(df: pd.DataFrame) -> str:
    preferred = ["disease", "diagnosis", "condition", "prognosis", "pathology", "label"]
    normalized = {norm(c): c for c in df.columns}
    for name in preferred:
        if name in normalized:
            return normalized[name]
    raise ValueError("Could not find disease/diagnosis/condition/prognosis/pathology/label column")


def load_symptom_matrix(csv_path: Path) -> tuple[np.ndarray, np.ndarray]:
    df = pd.read_csv(csv_path)
    label_col = find_label_column(df)
    symptom_cols = [c for c in df.columns if c != label_col]
    concept_order = list(CONCEPTS.keys())
    col_to_concepts: dict[str, list[int]] = {}
    for col in symptom_cols:
        text = norm(col)
        indices = [i for i, aliases in enumerate(CONCEPTS.values()) if any(norm(a) in text or text in norm(a) for a in aliases if len(norm(a)) >= 4)]
        if indices:
            col_to_concepts[col] = indices

    xs: list[np.ndarray] = []
    ys: list[int] = []
    for _, row in df.iterrows():
        disease_id = map_label(row[label_col])
        if disease_id is None:
            continue
        vector = np.zeros(len(CONCEPTS), dtype=np.float64)
        for col, indices in col_to_concepts.items():
            value = row[col]
            present = False
            if pd.isna(value):
                present = False
            elif isinstance(value, (int, float, np.number)):
                present = float(value) > 0
            else:
                present = norm(value) not in {"", "0", "false", "no", "none", "nan"}
            if present:
                for i in indices:
                    vector[i] = 1.0
        if vector.sum() == 0:
            continue
        xs.append(vector)
        ys.append(DISEASES.index(disease_id))
    return np.asarray(xs), np.asarray(ys, dtype=np.int64)


def combine(parts: list[tuple[np.ndarray, np.ndarray]]) -> tuple[np.ndarray, np.ndarray]:
    valid = [(x, y) for x, y in parts if len(y)]
    if not valid:
        raise ValueError("No usable cardiovascular samples were produced from the supplied datasets")
    return np.concatenate([p[0] for p in valid]), np.concatenate([p[1] for p in valid])


def split_data(x: np.ndarray, y: np.ndarray):
    counts = np.bincount(y, minlength=len(DISEASES))
    present = np.flatnonzero(counts)
    if len(present) < 2:
        raise ValueError("Need at least two represented cardiovascular classes")
    too_small = [DISEASES[i] for i in present if counts[i] < 5]
    if too_small:
        raise ValueError(f"Need >=5 samples per represented class for stratified split: {too_small}")
    x_train, x_tmp, y_train, y_tmp = train_test_split(
        x, y, test_size=0.30, random_state=SEED, stratify=y
    )
    x_val, x_test, y_val, y_test = train_test_split(
        x_tmp, y_tmp, test_size=0.50, random_state=SEED, stratify=y_tmp
    )
    return x_train, y_train, x_val, y_val, x_test, y_test


def evaluate(model: MLPClassifier, x: np.ndarray, y: np.ndarray, name: str) -> dict:
    probs = model.predict_proba(x)
    pred = model.classes_[np.argmax(probs, axis=1)]
    labels = list(model.classes_)
    metrics = {
        "split": name,
        "samples": int(len(y)),
        "accuracy_top1": float(accuracy_score(y, pred)),
        "macro_f1": float(f1_score(y, pred, labels=labels, average="macro", zero_division=0)),
        "top5_accuracy": float(top_k_accuracy_score(y, probs, k=min(5, probs.shape[1]), labels=labels)),
        "report": classification_report(y, pred, labels=labels, target_names=[DISEASES[i] for i in labels], output_dict=True, zero_division=0),
    }
    return metrics


def export_model(model: MLPClassifier, output_path: Path, metrics: dict) -> None:
    if len(model.coefs_) != 2:
        raise ValueError("Android model expects exactly one hidden layer")
    # sklearn only allocates outputs for represented classes. Export those class IDs explicitly.
    class_ids = [DISEASES[int(i)] for i in model.classes_]
    payload = {
        "formatVersion": 1,
        "modelType": "aritmia_symptom_multiclass_mlp",
        "inputConceptIds": list(CONCEPTS.keys()),
        "outputDiseaseIds": class_ids,
        "hiddenSize": int(model.hidden_layer_sizes[0] if isinstance(model.hidden_layer_sizes, tuple) else model.hidden_layer_sizes),
        "activation": "relu",
        "outputActivation": "softmax",
        "weightsInputHidden": model.coefs_[0].tolist(),
        "biasHidden": model.intercepts_[0].tolist(),
        "weightsHiddenOutput": model.coefs_[1].tolist(),
        "biasOutput": model.intercepts_[1].tolist(),
        "metrics": metrics,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ddxplus-csv", action="append", type=Path, default=[], help="DDXPlus train/validate/test CSV; may be repeated")
    parser.add_argument("--ddxplus-evidences", type=Path, help="DDXPlus release_evidences.json")
    parser.add_argument("--symptom-matrix", action="append", type=Path, default=[], help="Symptom-to-disease CSV; may be repeated")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/disease_model.json"))
    parser.add_argument("--metrics", type=Path, default=Path("ml/out/metrics.json"))
    args = parser.parse_args()

    sources: list[tuple[np.ndarray, np.ndarray]] = []
    if args.ddxplus_csv:
        if not args.ddxplus_evidences:
            parser.error("--ddxplus-evidences is required with --ddxplus-csv")
        sources.append(load_ddxplus(args.ddxplus_csv, args.ddxplus_evidences))
    for path in args.symptom_matrix:
        sources.append(load_symptom_matrix(path))

    x, y = combine(sources)
    x_train, y_train, x_val, y_val, x_test, y_test = split_data(x, y)

    model = MLPClassifier(
        hidden_layer_sizes=(HIDDEN_SIZE,),
        activation="relu",
        solver="adam",
        alpha=2e-4,
        batch_size=min(256, max(16, len(y_train) // 10)),
        learning_rate_init=1e-3,
        max_iter=600,
        early_stopping=True,
        validation_fraction=0.15,
        n_iter_no_change=30,
        random_state=SEED,
    )
    model.fit(x_train, y_train)

    all_metrics = {
        "dataset": {
            "total_samples": int(len(y)),
            "concepts": list(CONCEPTS.keys()),
            "class_counts": {DISEASES[i]: int((y == i).sum()) for i in range(len(DISEASES))},
        },
        "validation": evaluate(model, x_val, y_val, "validation"),
        "test": evaluate(model, x_test, y_test, "test"),
        "iterations": int(model.n_iter_),
        "loss": float(model.loss_),
    }
    args.metrics.parent.mkdir(parents=True, exist_ok=True)
    args.metrics.write_text(json.dumps(all_metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    export_model(model, args.output, all_metrics)

    print(json.dumps({
        "samples": len(y),
        "represented_classes": [DISEASES[int(i)] for i in model.classes_],
        "validation_macro_f1": all_metrics["validation"]["macro_f1"],
        "test_macro_f1": all_metrics["test"]["macro_f1"],
        "test_top5_accuracy": all_metrics["test"]["top5_accuracy"],
        "model": str(args.output),
        "metrics": str(args.metrics),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
