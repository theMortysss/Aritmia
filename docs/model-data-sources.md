# Источники данных для многоклассовой диагностики

## Что используется в приложении сейчас

Мобильная модель принимает только свободно введённые жалобы. Текст нормализуется в компактный набор symptom-concepts, после чего многоклассовая MLP выдаёт softmax-распределение по поддерживаемым сердечно-сосудистым состояниям.

Текущая версия содержит bootstrap-обучение на вариативных комбинациях symptom-concepts, чтобы модель могла работать полностью офлайн сразу после установки. Это учебный baseline, а не клинически валидированная модель.

## Датасеты, на которые рассчитана архитектура

### DDXPlus

- Paper: https://arxiv.org/abs/2205.09148
- NeurIPS: https://proceedings.neurips.cc/paper_files/paper/2022/hash/cae73a974390c0edd95ae7aeae09139c-Abstract.html
- Repository/data documentation: https://github.com/mila-iqia/ddxplus

DDXPlus содержит около 1.3 млн синтетических пациентов, ground-truth pathology, симптомы, antecedents и differential diagnosis. Датасет хорошо подходит для построения и проверки differential-diagnosis pipeline, но его классы и evidence-space шире текущей сердечно-сосудистой области приложения.

### Symptom-to-Disease 820

- Kaggle: https://www.kaggle.com/datasets/badhanamitroy/symptom-to-disease-descriptions-820-cleaned

Содержит 820 заболеваний, 654 бинарных симптома и 190672 строк symptom-disease matrix. Подходит для supervised multi-class classification и отбора сердечно-сосудистого subset.

### Symptom2Disease

- Kaggle: https://www.kaggle.com/datasets/niyarrbarman/symptom2disease
- Hugging Face mirror: https://huggingface.co/datasets/NeuronZero/Symptom2Disease

1200 естественно-языковых описаний жалоб для 24 заболеваний. Кардиологических классов мало, поэтому как основной датасет он недостаточен, но полезен для тестирования слоя free-text -> symptom concepts / disease.

## Рекомендуемый следующий шаг

1. Скачать DDXPlus и/или 820-disease dataset вне Android-приложения.
2. Оставить только сердечно-сосудистые классы, которые поддерживает DiseaseCatalog.
3. Сопоставить исходные symptoms/evidences с `SymptomConcept.id`.
4. Сформировать train/validation/test split по пациентам/строкам.
5. Обучить модель офлайн в Python и измерить macro-F1, top-1 accuracy, top-5 recall и calibration.
6. Экспортировать фиксированные веса в приложение вместо обучения на телефоне.
7. Отдельно протестировать русские свободные формулировки жалоб на собственном validation-наборе.

Важно: softmax score внутри приложения является относительной уверенностью модели между поддерживаемыми классами и без отдельной calibration/clinical validation не должен называться медицинской вероятностью диагноза.
