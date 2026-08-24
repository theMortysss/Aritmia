#!/usr/bin/env python3
"""Train/export Aritmia's symptom-only cardiovascular classifier.

The mobile app accepts free Russian complaints. Android converts them to the same
47 symptom concepts defined below. This script uses only symptom columns that can
be expressed by a patient in free text; demographics, ECG, labs and vitals are not
model inputs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, f1_score, top_k_accuracy_score
from sklearn.model_selection import StratifiedKFold
from sklearn.neural_network import MLPClassifier

SEED = 20260824
HIDDEN_SIZE = 48

LABEL_MAP = {
    "atrial fibrillation": "atrial_fibrillation",
    "atrial flutter": "atrial_fibrillation",
    "paroxysmal supraventricular tachycardia": "supraventricular_tachycardia",
    "paroxysmal ventricular tachycardia": "ventricular_tachycardia",
    "sinus bradycardia": "sinus_bradycardia",
    "heart block": "heart_block",
    "angina": "stable_angina",
    "heart attack": "acute_coronary_syndrome",
    "heart failure": "heart_failure",
    "hypertension": "arterial_hypertension",
    "malignant hypertension": "arterial_hypertension",
    "hypertensive heart disease": "arterial_hypertension",
    "pericarditis": "pericarditis",
    "cardiomyopathy": "cardiomyopathy",
    "aortic valve disease": "aortic_valve_disease",
    "pulmonary hypertension": "pulmonary_hypertension",
    "abdominal aortic aneurysm": "aortic_aneurysm",
    "thoracic aortic aneurysm": "aortic_aneurysm",
}

DISEASES = list(dict.fromkeys(LABEL_MAP.values()))

# Exact order must match DiseaseCatalog.concepts in Android.
CONCEPT_COLUMNS: dict[str, list[str]] = {
    "vomiting": ["vomiting", "vomiting.1"],
    "cough": ["cough", "cough.1"],
    "fatigue": ["fatigue", "fatigue.1"],
    "headache": ["headache", "headache.1", "frontal headache"],
    "dizziness": ["dizziness", "dizziness.1"],
    "sweating": ["sweating", "sweating.1"],
    "palpitations": ["palpitations"],
    "dyspnea": ["shortness of breath", "shortness of breath.1", "breathlessness", "difficulty breathing"],
    "chest_pain": ["sharp chest pain"],
    "chest_tightness": ["chest tightness"],
    "irregular_heartbeat": ["irregular heartbeat"],
    "syncope": ["fainting", "fainting.1"],
    "abdominal_pain": ["abdominal_pain", "abdominal pain", "sharp abdominal pain", "lower abdominal pain", "upper abdominal pain"],
    "arm_pain": ["arm pain"],
    "back_pain": ["back pain", "back pain.1", "back_pain", "low back pain", "side pain"],
    "burning_abdominal_pain": ["burning abdominal pain"],
    "edema": ["peripheral edema", "leg swelling", "swollen_legs", "swollen ankles", "ankle swelling", "fluid retention", "fluid_overload"],
    "weight_gain": ["weight gain", "weight gain.1"],
    "weakness": ["weakness", "weakness.1", "muscle_weakness", "muscle weakness", "muscle weakness.1"],
    "slow_heart_rate": ["decreased heart rate", "pulse rate decrease"],
    "fast_heart_rate": ["increased heart rate", "fast_heart_rate", "rapid pulse"],
    "hemoptysis": ["hemoptysis", "blood_in_sputum", "coughing blood"],
    "apnea": ["apnea"],
    "burning_chest_pain": ["burning chest pain"],
    "pleuritic_pain": ["hurts to breath"],
    "chest_pressure": ["chest pain with pressure"],
    "high_bp": ["hypertension"],
    "nausea": ["nausea", "nausea.1"],
    "leg_pain": ["leg pain", "lower body pain"],
    "leg_cramps": ["leg cramps or spasms"],
    "jaw_pain": ["jaw pain"],
    "neck_pain": ["neck pain", "neck pain.1"],
    "shoulder_pain": ["shoulder pain"],
    "tachypnea": ["breathing fast", "rapid breathing"],
    "nocturia": ["excessive urination at night"],
    "chest_congestion": ["congestion in chest"],
    "abnormal_breathing_sounds": ["abnormal breathing sounds"],
    "heartburn": ["heartburn", "heartburn.1"],
    "sleep_issues": ["sleep issues", "insomnia"],
    "arm_swelling": ["arm swelling"],
    "leg_weakness": ["leg weakness"],
    "arm_weakness": ["arm weakness"],
    "ankle_pain": ["ankle pain"],
    "rib_pain": ["rib pain"],
    "painful_walking": ["painful_walking"],
    "neck_stiffness": ["stiff_neck", "stiff neck", "neck stiffness or tightness"],
    "phlegm": ["phlegm", "phlegm.1"],
}


def build_matrix(csv_path: Path) -> tuple[np.ndarray, np.ndarray, dict[str, int]]:
    header = pd.read_csv(csv_path, nrows=0).columns.tolist()
    label_col = header[0]
    usable = {k: [c for c in cols if c in header] for k, cols in CONCEPT_COLUMNS.items()}
    usable = {k: cols for k, cols in usable.items() if cols}
    if list(usable) != list(CONCEPT_COLUMNS):
        missing = sorted(set(CONCEPT_COLUMNS) - set(usable))
        raise ValueError(f"Dataset is missing expected concept columns: {missing}")

    usecols = [label_col] + sorted(set(sum(usable.values(), [])))
    chunks = []
    for chunk in pd.read_csv(csv_path, usecols=usecols, chunksize=25000, low_memory=False):
        raw = chunk[label_col].astype(str).str.strip().str.lower()
        mask = raw.isin(LABEL_MAP)
        if mask.any():
            selected = chunk.loc[mask].copy()
            selected["_class"] = raw[mask].map(LABEL_MAP)
            chunks.append(selected)
    if not chunks:
        raise ValueError("No supported cardiovascular labels found")

    df = pd.concat(chunks, ignore_index=True)
    x = np.zeros((len(df), len(CONCEPT_COLUMNS)), dtype=np.float64)
    for j, (_, cols) in enumerate(CONCEPT_COLUMNS.items()):
        values = df[cols].apply(pd.to_numeric, errors="coerce").fillna(0).to_numpy()
        x[:, j] = (values > 0).any(axis=1)
    y = np.asarray([DISEASES.index(c) for c in df["_class"]], dtype=np.int64)

    # Exact duplicates are removed before validation so repeated synthetic rows do
    # not artificially inflate metrics.
    joined = np.c_[x, y]
    _, first = np.unique(joined, axis=0, return_index=True)
    x, y = x[first], y[first]
    counts = dict(zip(DISEASES, np.bincount(y, minlength=len(DISEASES)).tolist()))
    return x, y, counts


def new_model() -> MLPClassifier:
    return MLPClassifier(
        hidden_layer_sizes=(HIDDEN_SIZE,), activation="relu", solver="adam",
        alpha=0.0005, batch_size=32, learning_rate_init=0.001, max_iter=1000,
        early_stopping=True, validation_fraction=0.15, n_iter_no_change=40,
        random_state=SEED,
    )


def cross_validate(x: np.ndarray, y: np.ndarray) -> dict:
    splitter = StratifiedKFold(n_splits=5, shuffle=True, random_state=20260826)
    folds = []
    for fold, (train_idx, test_idx) in enumerate(splitter.split(x, y), 1):
        model = new_model()
        model.fit(x[train_idx], y[train_idx])
        pred = model.predict(x[test_idx])
        probs = model.predict_proba(x[test_idx])
        folds.append({
            "fold": fold,
            "top1": float(accuracy_score(y[test_idx], pred)),
            "macro_f1": float(f1_score(y[test_idx], pred, average="macro", zero_division=0)),
            "top5": float(top_k_accuracy_score(y[test_idx], probs, k=5, labels=model.classes_)),
            "epochs": int(model.n_iter_),
        })
    summary = {}
    for metric in ("top1", "macro_f1", "top5"):
        values = np.asarray([row[metric] for row in folds])
        summary[metric] = {"mean": float(values.mean()), "std": float(values.std(ddof=1)), "folds": [float(v) for v in values]}
    return {"folds": folds, "summary": summary}


def export_model(model: MLPClassifier, output: Path, evaluation: dict, samples: int) -> None:
    # Six decimals cut the JSON size by more than half; on the training matrix this
    # changes max softmax probability by < 3e-6 and changes no top-1 prediction.
    round6 = np.vectorize(lambda value: round(float(value), 6))
    payload = {
        "formatVersion": 1,
        "modelType": "aritmia_symptom_multiclass_mlp",
        "inputConceptIds": list(CONCEPT_COLUMNS),
        "outputDiseaseIds": DISEASES,
        "hiddenSize": HIDDEN_SIZE,
        "activation": "relu",
        "outputActivation": "softmax",
        "weightsInputHidden": round6(model.coefs_[0]).tolist(),
        "biasHidden": round6(model.intercepts_[0]).tolist(),
        "weightsHiddenOutput": round6(model.coefs_[1]).tolist(),
        "biasOutput": round6(model.intercepts_[1]).tolist(),
        "evaluation": {
            "method": "5-fold stratified cross-validation after exact feature+label deduplication",
            "samples": samples,
            "source": "Symptom-to-Disease 820 row-level cleaned",
            "onlyFreeTextDerivableSymptoms": True,
            **evaluation["summary"],
        },
        "training": {"samples": samples, "epochs": int(model.n_iter_), "loss": float(model.loss_), "randomSeed": SEED},
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symptom-matrix", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/disease_model.json"))
    parser.add_argument("--metrics", type=Path, default=Path("ml/out/metrics.json"))
    args = parser.parse_args()

    x, y, counts = build_matrix(args.symptom_matrix)
    cv = cross_validate(x, y)
    model = new_model()
    model.fit(x, y)
    export_model(model, args.output, cv, len(y))

    metrics = {
        "classes": DISEASES,
        "concepts": list(CONCEPT_COLUMNS),
        "samples": int(len(y)),
        "class_counts": counts,
        "cross_validation": cv,
        "training_epochs": int(model.n_iter_),
        "training_loss": float(model.loss_),
    }
    args.metrics.parent.mkdir(parents=True, exist_ok=True)
    args.metrics.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics["cross_validation"]["summary"], indent=2))


if __name__ == "__main__":
    main()
