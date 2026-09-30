# كيفية رفع ShadowLook على GitHub والحصول على رابط APK مباشر

## الخطوة 1: إنشاء مستودع جديد على GitHub (دقيقة واحدة)

1. اذهب إلى https://github.com/new
2. اسم المستودع: `ShadowLook`
3. الوصف: `AI Face Recognition App - Cyber Theme`
4. اختر **Public** (حتى يعمل الـ Release)
5. **لا تضع علامة** على Initialize with README
6. اضغط **Create repository**

## الخطوة 2: رفع المشروع (دقيقتان)

بعد إنشاء المستودع، GitHub سيعطيك أوامر. استخدم هذه الأوامر في جهازك:

### إذا حملت الملف المضغوط:

```bash
# فك الضغط
unzip ShadowLook_Full_Project_v2.zip
cd MyAndroidApp

# اربطه بـ GitHub (استبدل USERNAME باسمك)
git remote add origin https://github.com/USERNAME/ShadowLook.git
git branch -M main
git push -u origin main
```

### أو إذا تريد رفع مباشر من هنا:

انسخ رابط المستودع الذي أنشأته (مثلاً https://github.com/ahmed/ShadowLook.git) والصقه هنا، وسأرفعه لك.

## الخطوة 3: البناء التلقائي يبدأ (3-5 دقائق)

1. اذهب إلى مستودعك على GitHub
2. اضغط على تبويب **Actions**
3. ستجد Workflow يعمل باسم **"Build ShadowLook APK"**
4. انتظر حتى ينتهي (علامة خضراء ✅)

## الخطوة 4: الحصول على رابط التحميل المباشر

### الطريقة A: من Artifacts (أسرع)

1. في صفحة Actions → اضغط على آخر Build
2. انزل للأسفل → ستجد **Artifacts**
3. حمل **ShadowLook-APK** → بداخله `app-debug.apk`

### الطريقة B: من Releases (رابط مباشر دائم)

1. اذهب إلى تبويب **Releases** في مستودعك
2. ستجد Release جديد: `ShadowLook v1.0 Build 1`
3. اضغط عليه → ستجد ملف `app-debug.apk`
4. انسخ رابط الملف - هذا هو رابط التحميل المباشر!

مثال للرابط:
```
https://github.com/USERNAME/ShadowLook/releases/download/v1.0-1/app-debug.apk
```

يمكنك مشاركة هذا الرابط مع أي شخص ليحمل التطبيق مباشرة!

## ملاحظة مهمة: نموذج الذكاء الاصطناعي

التطبيق يعمل بدون نموذج `mobilefacenet.tflite` (يستخدم نظام تجريبي)، لكن للدقة الكاملة:

1. حمل النموذج من: https://github.com/sirius-ai/MobileFaceNet_TF
2. ضعه في `app/src/main/assets/mobilefacenet.tflite`
3. اعمل Commit و Push مرة أخرى → سيبني APK جديد تلقائياً

## هل تريد مني رفع المشروع الآن؟

إذا أعطيتني رابط المستودع، يمكنني رفعه لك مباشرة من هنا باستخدام:

```bash
git remote add origin YOUR_REPO_URL
git push -u origin main
```

الصق رابط المستودع هنا!
