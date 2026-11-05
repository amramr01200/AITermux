# AI+Termux

تطبيق أندرويد (Kotlin + Jetpack Compose) لإدارة وتشغيل نماذج الذكاء الاصطناعي من مزودين (رابط + كيجن) أو من أدوات Termux مثل Ollama.

## البناء على GitHub (بدون حاسوب)
1. أنشئ مستودعاً جديداً على GitHub وارفع محتوى هذا المجلد كاملاً.
2. افتح تبويب **Actions** ثم **Build APK** ثم **Run workflow** (أو يبني تلقائياً عند أي push).
3. بعد انتهاء البناء افتح التشغيلة ونزّل **AI-Termux-APK** من قسم Artifacts، فك الضغط وثبّت الـ APK.

## ربط Termux
1. ثبّت Termux من F-Droid، ثم ثبّت Ollama: `pkg install ollama`
2. داخل Termux نفّذ مرة واحدة ثم أعد تشغيل Termux:
   `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`
3. في التطبيق: الترس ← الإعدادات ← **منح صلاحية Termux**، وفعّل "تشغيل Ollama تلقائياً" إن أردت.
4. التطبيق يكتشف الخادم على `127.0.0.1:11434` تلقائياً، ويمكنك إضافة عناوين أخرى من الإضافة ← جلب من Termux.

## ربط المواقع
الإضافة ← ربط: اختر قالب GitHub أو Google أو Supabase، افتح صفحة الرمز، أنشئ الرمز والصقه مرة واحدة.
