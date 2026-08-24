#!/usr/bin/env python3
"""Train/export Aritmia's symptom-only cardiovascular classifier.

The Android app accepts free Russian complaints and maps them to the same symptom
concepts used here. Only complaints that a patient can express in free text are model
inputs; demographics, ECG, labs and measured vitals are intentionally excluded.

The real symptom->disease matrix is augmented *only in training folds* with a small
curated clinical prior. The prior prevents obvious contradictions caused by dataset
correlations, and a mandatory sanity gate must pass before model export.
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
AUGMENTATION_PER_CLASS = 100
HEART_BLOCK_AUGMENTATION = 220

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
    "dizziness": ["dizziness", "dizziness.1", "loss_of_balance", "loss of balance"],
    "sweating": ["sweating", "sweating.1"],
    "palpitations": ["palpitations"],
    "dyspnea": ["shortness of breath", "shortness of breath.1", "breathlessness", "difficulty breathing"],
    "chest_pain": ["sharp chest pain", "chest_pain", "chest pain"],
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

CLINICAL_PROFILES: dict[str, dict[str, float]] = {
    "atrial_fibrillation": {"irregular_heartbeat":1.0,"palpitations":0.9,"dyspnea":0.55,"dizziness":0.45,"fatigue":0.35},
    "supraventricular_tachycardia": {"fast_heart_rate":1.0,"palpitations":0.95,"dizziness":0.45,"dyspnea":0.35,"chest_pain":0.3},
    "ventricular_tachycardia": {"fast_heart_rate":1.0,"palpitations":0.8,"syncope":0.8,"dizziness":0.65,"chest_pain":0.45,"dyspnea":0.45},
    "sinus_bradycardia": {"slow_heart_rate":1.0,"dizziness":0.65,"weakness":0.6,"fatigue":0.55,"syncope":0.45},
    "heart_block": {"slow_heart_rate":0.9,"syncope":0.9,"dizziness":0.75,"fatigue":0.55,"weakness":0.5,"dyspnea":0.35},
    "stable_angina": {"chest_pain":1.0,"chest_pressure":0.9,"chest_tightness":0.85,"arm_pain":0.65,"jaw_pain":0.65,"shoulder_pain":0.5,"dyspnea":0.45},
    "acute_coronary_syndrome": {"chest_pain":1.0,"chest_pressure":0.95,"sweating":0.75,"nausea":0.65,"vomiting":0.5,"arm_pain":0.7,"jaw_pain":0.65,"dyspnea":0.6},
    "heart_failure": {"dyspnea":1.0,"edema":0.95,"weight_gain":0.75,"fatigue":0.65,"weakness":0.45,"nocturia":0.4,"cough":0.35},
    "arterial_hypertension": {"high_bp":1.0,"headache":0.55,"dizziness":0.45,"palpitations":0.3,"dyspnea":0.25},
    "pericarditis": {"pleuritic_pain":1.0,"chest_pain":0.85,"dyspnea":0.5,"palpitations":0.35,"cough":0.25},
    "cardiomyopathy": {"dyspnea":0.9,"fatigue":0.75,"edema":0.7,"palpitations":0.6,"chest_pain":0.4,"syncope":0.35},
    "aortic_valve_disease": {"dyspnea":0.85,"chest_pain":0.75,"syncope":0.7,"fatigue":0.45,"palpitations":0.35},
    "pulmonary_hypertension": {"dyspnea":1.0,"fatigue":0.65,"dizziness":0.55,"syncope":0.45,"edema":0.5,"chest_pain":0.4,"hemoptysis":0.35},
    "aortic_aneurysm": {"chest_pain":0.9,"back_pain":0.9,"abdominal_pain":0.8,"dyspnea":0.35,"cough":0.25,"syncope":0.25},
}

SANITY_PROFILES: dict[str, list[str]] = {
    "atrial_fibrillation": ["irregular_heartbeat", "palpitations", "dyspnea"],
    "supraventricular_tachycardia": ["fast_heart_rate", "palpitations", "dizziness"],
    "ventricular_tachycardia": ["fast_heart_rate", "palpitations", "syncope", "chest_pain"],
    "sinus_bradycardia": ["slow_heart_rate", "fatigue", "weakness"],
    "heart_block": ["slow_heart_rate", "syncope", "dizziness", "dyspnea"],
    "stable_angina": ["chest_pain", "chest_pressure", "chest_tightness"],
    "acute_coronary_syndrome": ["chest_pain", "chest_pressure", "sweating", "nausea", "arm_pain"],
    "heart_failure": ["dyspnea", "edema", "weight_gain", "fatigue", "nocturia"],
    "arterial_hypertension": ["high_bp", "headache", "dizziness"],
    "pericarditis": ["pleuritic_pain", "chest_pain", "dyspnea"],
    "cardiomyopathy": ["dyspnea", "fatigue", "edema", "palpitations"],
    "aortic_valve_disease": ["dyspnea", "chest_pain", "syncope"],
    "pulmonary_hypertension": ["dyspnea", "fatigue", "dizziness", "edema", "hemoptysis"],
    "aortic_aneurysm": ["chest_pain", "back_pain", "abdominal_pain"],
}


def build_matrix(csv_path: Path) -> tuple[np.ndarray, np.ndarray, dict[str, int]]:
    header = pd.read_csv(csv_path, nrows=0).columns.tolist()
    label_col = header[0]
    usable = {k: [c for c in cols if c in header] for k, cols in CONCEPT_COLUMNS.items()}
    missing = [k for k, cols in usable.items() if not cols]
    if missing:
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
    for j, (_, cols) in enumerate(usable.items()):
        values = df[cols].apply(pd.to_numeric, errors="coerce").fillna(0).to_numpy()
        x[:, j] = (values > 0).any(axis=1)
    y = np.asarray([DISEASES.index(c) for c in df["_class"]], dtype=np.int64)
    joined = np.c_[x, y]
    _, first = np.unique(joined, axis=0, return_index=True)
    x, y = x[first], y[first]
    counts = dict(zip(DISEASES, np.bincount(y, minlength=len(DISEASES)).tolist()))
    return x, y, counts


def generate_augmentation(seed: int = SEED) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    concept_index = {c: i for i, c in enumerate(CONCEPT_COLUMNS)}
    xs: list[np.ndarray] = []
    ys: list[int] = []
    for class_index, disease in enumerate(DISEASES):
        profile = CLINICAL_PROFILES[disease]
        ranked = sorted(profile, key=profile.get, reverse=True)
        count = HEART_BLOCK_AUGMENTATION if disease == "heart_block" else AUGMENTATION_PER_CLASS
        for _ in range(count):
            v = np.zeros(len(CONCEPT_COLUMNS), dtype=np.float64)
            anchor = ranked[int(rng.integers(0, min(2, len(ranked))))]
            v[concept_index[anchor]] = 1.0
            for concept, weight in profile.items():
                if rng.random() < min(0.92, 0.20 + 0.67 * weight):
                    v[concept_index[concept]] = 1.0
            if rng.random() < 0.22:
                candidates = [c for c in CONCEPT_COLUMNS if c not in profile]
                v[concept_index[candidates[int(rng.integers(0, len(candidates)))]]] = 1.0
            xs.append(v)
            ys.append(class_index)
    return np.asarray(xs), np.asarray(ys, dtype=np.int64)


def new_model() -> MLPClassifier:
    return MLPClassifier(hidden_layer_sizes=(HIDDEN_SIZE,), activation="relu", solver="adam", alpha=0.0005, batch_size=32, learning_rate_init=0.001, max_iter=1000, early_stopping=True, validation_fraction=0.15, n_iter_no_change=40, random_state=SEED)


def sanity_check(model: MLPClassifier) -> tuple[int, list[dict]]:
    index = {c: i for i, c in enumerate(CONCEPT_COLUMNS)}
    rows = []
    for disease, concepts in SANITY_PROFILES.items():
        v = np.zeros((1, len(CONCEPT_COLUMNS)), dtype=np.float64)
        for concept in concepts:
            v[0, index[concept]] = 1.0
        probs = model.predict_proba(v)[0]
        pred = DISEASES[int(model.classes_[int(np.argmax(probs))])]
        rows.append({"expected": disease, "predicted": pred, "passed": pred == disease})
    return sum(int(row["passed"]) for row in rows), rows


def cross_validate(x: np.ndarray, y: np.ndarray) -> dict:
    splitter = StratifiedKFold(n_splits=5, shuffle=True, random_state=20260826)
    aug_x, aug_y = generate_augmentation()
    folds = []
    for fold, (train_idx, test_idx) in enumerate(splitter.split(x, y), 1):
        model = new_model()
        model.fit(np.vstack([x[train_idx], aug_x]), np.concatenate([y[train_idx], aug_y]))
        pred = model.predict(x[test_idx])
        probs = model.predict_proba(x[test_idx])
        folds.append({"fold": fold, "top1": float(accuracy_score(y[test_idx], pred)), "macro_f1": float(f1_score(y[test_idx], pred, average="macro", zero_division=0)), "top5": float(top_k_accuracy_score(y[test_idx], probs, k=5, labels=model.classes_)), "epochs": int(model.n_iter_)})
    summary = {}
    for metric in ("top1", "macro_f1", "top5"):
        values = np.asarray([row[metric] for row in folds])
        summary[metric] = {"mean": float(values.mean()), "std": float(values.std(ddof=1)), "folds": [float(v) for v in values]}
    return {"folds": folds, "summary": summary}


def rounded(values):
    return np.asarray(values).round(6).tolist()


def export_model(model: MLPClassifier, output: Path, evaluation: dict, real_samples: int, augmentation_samples: int, sanity_rows: list[dict]) -> None:
    payload = {"formatVersion":1,"modelType":"aritmia_symptom_multiclass_mlp","inputConceptIds":list(CONCEPT_COLUMNS),"outputDiseaseIds":DISEASES,"hiddenSize":HIDDEN_SIZE,"activation":"relu","outputActivation":"softmax","weightsInputHidden":rounded(model.coefs_[0]),"biasHidden":rounded(model.intercepts_[0]),"weightsHiddenOutput":rounded(model.coefs_[1]),"biasOutput":rounded(model.intercepts_[1]),"evaluation":{"method":"5-fold stratified CV on real 820 rows after exact dedup; each training fold augmented with curated symptom profiles","realSamples":real_samples,"augmentationPerClass":AUGMENTATION_PER_CLASS,"heartBlockAugmentation":HEART_BLOCK_AUGMENTATION,"source":"Symptom-to-Disease 820 row-level cleaned","onlyFreeTextDerivableSymptoms":True,**evaluation["summary"],"sanityTop1Passed":len(sanity_rows),"sanityTotal":len(sanity_rows)},"training":{"realSamples":real_samples,"augmentationSamples":augmentation_samples,"epochs":int(model.n_iter_),"loss":float(model.loss_),"randomSeed":SEED}}
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
    aug_x, aug_y = generate_augmentation()
    model = new_model()
    model.fit(np.vstack([x, aug_x]), np.concatenate([y, aug_y]))
    passed, sanity_rows = sanity_check(model)
    if passed != len(SANITY_PROFILES):
        raise RuntimeError(f"Clinical sanity gate failed {passed}/{len(SANITY_PROFILES)}: {[r for r in sanity_rows if not r['passed']]}")
    export_model(model, args.output, cv, len(y), len(aug_y), sanity_rows)
    metrics = {"classes":DISEASES,"concepts":list(CONCEPT_COLUMNS),"real_samples":int(len(y)),"augmentation_samples":int(len(aug_y)),"class_counts":counts,"cross_validation":cv,"sanity":sanity_rows,"training_epochs":int(model.n_iter_),"training_loss":float(model.loss_)}
    args.metrics.parent.mkdir(parents=True, exist_ok=True)
    args.metrics.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"cv":cv["summary"],"sanity":f"{passed}/{len(SANITY_PROFILES)}"}, indent=2))


if __name__ == "__main__":
    main()
