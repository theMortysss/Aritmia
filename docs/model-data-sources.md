# Источники и обучение многоклассовой cardiovascular-модели

## Архитектура

Пользователь вводит только свободные жалобы. Android-слой `FreeTextSymptomExtractor` преобразует русский текст в 29 устойчивых symptom-concepts. Затем многоклассовая MLP выполняет:

`29 symptom concepts -> ReLU(36) -> cardiovascular classes -> softmax -> top-5`.

Никакие демографические признаки, лабораторные показатели, ЭКГ, холестерин или измеренное давление автоматически не добавляются в ML-вектор: модель должна работать именно от жалоб пользователя.

## Внешние источники

### DDXPlus

- Paper: https://arxiv.org/abs/2205.09148
- NeurIPS: https://proceedings.neurips.cc/paper_files/paper/2022/hash/cae73a974390c0edd95ae7aeae09139c-Abstract.html
- Official repository: https://github.com/mila-iqia/ddxplus
- Hugging Face mirror: https://huggingface.co/datasets/aai530-group6/ddxplus

DDXPlus содержит около 1.3 млн синтетических пациентов, 49 патологий, 110 evidence/symptom variables, ground-truth pathology и differential diagnosis. Для Aritmia используются только symptom/evidence признаки. Antecedents, age и sex в текущую модель не подаются.

В DDXPlus точно присутствуют, среди прочего, `Atrial fibrillation`, `PSVT`, `Possible NSTEMI / STEMI` и `Myocarditis`. Он не покрывает все 12 классов Aritmia, поэтому один DDXPlus не должен использоваться как основание заявлять, что все 12 классов обучены на реальных строках этого датасета.

### Symptom-to-Disease 820

- Kaggle: https://www.kaggle.com/datasets/badhanamitroy/symptom-to-disease-descriptions-820-cleaned

Набор содержит широкий symptom-to-disease space и полезен как второй источник для классов, которых нет в DDXPlus. Перед использованием обязательно проверяются названия классов, class balance и provenance конкретной версии датасета.

### Symptom2Disease

- Kaggle: https://www.kaggle.com/datasets/niyarrbarman/symptom2disease
- Hugging Face: https://huggingface.co/datasets/NeuronZero/Symptom2Disease

Набор содержит естественно-языковые описания жалоб. Кардиологических классов мало, поэтому он полезнее для проверки free-text/NLP слоя, чем как основной cardiovascular training set.

## Offline training pipeline

Код находится в `ml/train_cardiovascular.py`.

Установка:

```bash
python -m venv .venv
source .venv/bin/activate              # Windows: .venv\\Scripts\\activate
pip install -r ml/requirements.txt
```

### Обучение на DDXPlus

После скачивания официальных файлов:

```bash
python ml/train_cardiovascular.py \
  --ddxplus-csv data/ddxplus/train.csv \
  --ddxplus-csv data/ddxplus/validate.csv \
  --ddxplus-csv data/ddxplus/test.csv \
  --ddxplus-evidences data/ddxplus/release_evidences.json \
  --output app/src/main/assets/disease_model.json \
  --metrics ml/out/metrics.json
```

Скрипт читает только `PATHOLOGY` и `EVIDENCES`, переводит evidence IDs/values через `release_evidences.json` и сопоставляет их с теми же 29 concepts, что использует Android.

### Добавление symptom-to-disease CSV

Можно совместить источники:

```bash
python ml/train_cardiovascular.py \
  --ddxplus-csv data/ddxplus/train.csv \
  --ddxplus-evidences data/ddxplus/release_evidences.json \
  --symptom-matrix data/symptom820/data.csv \
  --output app/src/main/assets/disease_model.json \
  --metrics ml/out/metrics.json
```

Для symptom-matrix импортёр автоматически ищет колонку `disease`, `diagnosis`, `condition`, `prognosis`, `pathology` или `label`, затем использует только строки классов, которые явно сопоставлены с cardiovascular-классами Aritmia.

## Что экспортируется

`disease_model.json` содержит:

- точный порядок 29 `inputConceptIds`;
- список реально представленных `outputDiseaseIds`;
- веса input -> hidden;
- bias hidden;
- веса hidden -> output;
- bias output;
- validation/test metrics.

Android проверяет формат, порядок входов и IDs классов перед загрузкой. Если asset корректен, обучение на телефоне вообще не выполняется. Если asset отсутствует/повреждён, включается старый bootstrap fallback только для разработки.

## Метрики

Пайплайн создаёт stratified train/validation/test split и считает:

- top-1 accuracy;
- top-5 accuracy;
- macro-F1;
- classification report по каждому представленному классу.

Для дипломной оценки дополнительно рекомендуется построить confusion matrix и проверить calibration (например, Expected Calibration Error / reliability diagram). Softmax confidence без calibration и клинической валидации нельзя называть медицинской вероятностью диагноза.

## Важное ограничение свободного текста

Нейросеть обучается не напрямую на русских предложениях, а на 29 symptom-concepts. Это осознанно: внешний датасет может быть англоязычным, а пользователь пишет по-русски. Один и тот же концепт связывает обе стороны:

`"сердце внезапно начинает колотиться" -> sudden_palpitations <- "sudden palpitations"`.

Поэтому качество всей системы состоит из двух независимых частей:

1. recall/precision `FreeTextSymptomExtractor` на русских жалобах;
2. качество disease classifier на корректно извлечённых concepts.

Обе части нужно валидировать отдельно.
