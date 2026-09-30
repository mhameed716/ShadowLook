ShadowLook - MobileFaceNet Model
================================

الخطوة 1: إضافة الملف mobilefacenet.tflite داخل مجلد الموارد

المسار المطلوب:
app/src/main/assets/mobilefacenet.tflite

مواصفات النموذج:
- الاسم: mobilefacenet.tflite
- الحجم: ~4MB
- المدخل: 112x112x3 (صورة وجه)
- المخرج: 128 float (بصمة الوجه Embedding)
- Normalization: (pixel - 127.5) / 128.0 => [-1.0 to 1.0]
- L2 Normalized: نعم

مصادر التحميل:
1. GitHub: https://github.com/sirius-ai/MobileFaceNet_TF
2. TensorFlow Hub: https://tfhub.dev/google/mobilefacenet/1 (يحتاج تحويل إلى tflite)
3. Community: ابحث عن "mobilefacenet.tflite download"

بعد التحميل:
- ضع الملف في app/src/main/assets/mobilefacenet.tflite
- أعد بناء التطبيق

ملاحظة: التطبيق يعمل حتى بدون النموذج (وضع المحاكاة) لكن بدقة أقل.
مع النموذج الحقيقي، الدقة تزيد بشكل كبير.

Pipeline الكامل:
1. mobilefacenet.tflite في assets/
2. Face Detection (ML Kit) -> قص الوجه فقط
3. Pre-processing: 112x112 + Normalization
4. Inference: تمرير للنموذج -> 128 embedding
5. Comparison/Saving: حفظ أو مقارنة

للتحميل التلقائي، شغل:
./download_model.sh
