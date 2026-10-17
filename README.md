# KoserAI

Türkçe konuşan kişisel yapay zekâ asistanı (Android). **Yapımcı: Sait**

- "Hey Koser" ile eller serbest uyandırma (çevrimdışı, cümle ayarlanabilir; "Koser" kelimesi zorunlu)
- Telefonun varsayılan dijital asistanı olabilir (Gemini / Google Asistan yerine)
- WhatsApp'tan kişiye mesaj yazma/gönderme, arama, SMS, uygulama açma
- Televizyon kontrolü: Samsung, LG, Roku ve kendi HTTP API'n (Home Assistant vb.), Wake-on-LAN
- Alarm, zamanlayıcı, fener, ses, müzik, hava durumu, yol tarifi, notlar, hesap makinesi
- Gemini ile sohbet (ücretsiz API anahtarı)
- 3 tema: KOSER (mavi), KIZIL (kırmızı), ALTIN

## Derleme
GitHub Actions her `main` gönderiminde APK'yı derler (`ci-output` dalı ve Actions çıktıları).
Yerelde: Android Studio ile aç ve çalıştır.

## Lisanslar
Rajdhani yazı tipi: SIL Open Font License (app/src/main/assets/web/fonts/OFL-Rajdhani.txt).
Vosk: Apache 2.0. LG eşleşme manifesti: lgtv2 (MIT).
