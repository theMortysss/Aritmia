# Источники и обучение cardiovascular-модели

## Финальная схема

Пользователь вводит только свободные жалобы на русском языке. `FreeTextSymptomExtractor` переводит текст в 47 symptom-concepts, после чего pretrained MLP выполняет:

`47 symptom concepts -> ReLU(48) -> 14 cardiovascular classes -> softmax -> top-5`.

В disease classifier не подаются пол, возраст, ЭКГ, лабораторные показатели или другие клинические измерения. Даже признаки вроде частого/редкого пульса и высокого давления появляются только тогда, когда пользователь сам описывает их в жалобе.

## 14 выходных классов

- фибрилляция/трепетание предсердий;
- наджелудочковая тахикардия;
- желудочковая тахикардия;
- синусовая брадикардия;
- AV/сердечная блокада;
- ИБС / стенокардия;
- острый коронарный синдром / инфаркт;
- сердечная недостаточность;
- артериальная / гипертензивная болезнь;
- перикардит;
- кардиомиопатия;
- заболевание аортального клапана;
- лёгочная гипертензия;
- аневризма аорты.

Близкие исходные labels намеренно объединяются там, где их нельзя надёжно разделять только по жалобам. Например, hypertension/malignant hypertension/hypertensive heart disease образуют один класс.

## Основной обучающий источник

### Symptom-to-Disease 820

Kaggle: https://www.kaggle.com/datasets/badhanamitroy/symptom-to-disease-descriptions-820-cleaned

Для текущей pretrained-модели используется row-level cleaned symptom-to-disease matrix. Из неё выбираются только явно сопоставленные cardiovascular labels и только symptom columns, которые потенциально можно получить из свободной жалобы.

После формирования 47 признаков и удаления точных дубликатов feature+label остаётся 4722 реальных cardiovascular-комбинации.

## Дополнительные источники

### Symptom2Disease

- Kaggle: https://www.kaggle.com/datasets/niyarrbarman/symptom2disease
- Hugging Face: https://huggingface.co/datasets/NeuronZero/Symptom2Disease

Этот набор содержит естественно-языковые описания жалоб. Кардиологических классов мало, поэтому он используется прежде всего как внешний sanity-check NLP/free-text слоя, а не как основной источник disease classifier.

### DDXPlus

- Paper: https://arxiv.org/abs/2205.09148
- NeurIPS: https://proceedings.neurips.cc/paper_files/paper/2022/hash/cae73a974390c0edd95ae7aeae09139c-Abstract.html
- Official repository: https://github.com/mila-iqia/ddxplus
- Dataset: https://figshare.com/articles/dataset/DDXPlus_Dataset/20043374

DDXPlus полезен для дальнейшей внешней проверки ФП, PSVT, angina/ACS и перикардита. Он не покрывает все 14 текущих классов, поэтому не используется как основание заявлять, что вся модель обучена на DDXPlus.

## Почему используется clinical augmentation

Первый вариант, обученный только на 820-матрице, показывал хорошие aggregate-метрики, но проваливал несколько очевидных контрольных сценариев. Например, комбинация `быстрый пульс + сердцебиение + обморок` иногда давала нелогичный top-1.

Поэтому training pipeline добавляет только в training folds небольшой curated complaint-only prior. Он не добавляется в validation folds и не участвует в расчёте метрик как тестовые данные.

- 100 вариативных профилей на класс;
- 220 для `heart_block`, который был особенно слабым классом;
- низкий уровень случайного complaint-noise;
- обязательный sanity gate: 14 типичных высокоинформативных профилей должны быть распознаны top-1 как 14/14. Если нет, экспорт модели прерывается.

Это исследовательская эвристика, а не клиническая разметка, поэтому её необходимо явно учитывать при интерпретации результатов.

## Воспроизводимое обучение

Код: `ml/train_cardiovascular.py`.

```bash
python -m venv .venv
source .venv/bin/activate              # Windows: .venv\\Scripts\\activate
pip install -r ml/requirements.txt

python ml/train_cardiovascular.py \
  --symptom-matrix data/symptom820/unified_820_diseases_symptoms_dataset_ROWLEVEL_CLEANED.csv \
  --output app/src/main/assets/disease_model.json \
  --metrics ml/out/metrics.json
```

Пайплайн выполняет exact feature+label deduplication, 5-fold stratified cross-validation, augmentation только training fold, sanity gate, обучение финальной модели на всех реальных строках + augmentation и экспорт весов с округлением до 6 знаков.

## Метрики текущей модели

5-fold stratified cross-validation проводится только на реальных строках 820-набора после deduplication. Curated augmentation присутствует только в обучающей части каждого fold.

| Метрика | Среднее | Std |
|---|---:|---:|
| Top-1 accuracy | 79.16% | 0.51 п.п. |
| Macro-F1 | 0.706 | 0.012 |
| Top-5 accuracy | 98.50% | 0.19 п.п. |
| Clinical sanity top-1 | 14/14 | — |

Финальная модель обучена на 4722 реальных комбинациях + 1520 augmentation samples. Архитектура — 47 -> 48 -> 14.

Эти показатели являются исследовательскими метриками на конкретном symptom-to-disease наборе. Они не означают клиническую чувствительность/специфичность приложения на реальных пациентах.

## Android inference

Готовые веса лежат в `app/src/main/assets/disease_model/v2-00.part` ... `v2-07.part`. Части являются простой побайтовой разбивкой одного JSON и склеиваются в алфавитном порядке при запуске.

`DiseaseNetworkRepository` проверяет:

- `formatVersion` и тип модели;
- ReLU + softmax;
- точный порядок всех 47 `inputConceptIds`;
- известность и уникальность 14 `outputDiseaseIds`;
- размерность матриц весов при `loadWeights`.

Если pretrained asset отсутствует или несовместим, приложение переходит на bootstrap fallback, предназначенный только для разработки.

## Ограничение свободного текста

Классификатор обучается не непосредственно на русских предложениях, а на 47 symptom-concepts. Поэтому систему нужно оценивать как два независимых слоя:

1. `free Russian text -> symptom concepts` — precision/recall NLP/extractor;
2. `symptom concepts -> top-5 cardiovascular classes` — метрики disease classifier.

Softmax confidence не является клинически откалиброванной вероятностью заболевания. До отдельной calibration и клинической валидации UI должен описывать значения как confidence/оценку модели, а результат — как предварительный дифференциальный список, а не диагноз.
