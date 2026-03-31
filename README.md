# KBGram 🛡️

**Антицензурный форк Telegram для Android**

KBGram — это модифицированная версия Telegram для Android с встроенными инструментами обхода блокировок. Разработан для пользователей в регионах, где Telegram подвергается цензуре или замедлению.

## ✨ Возможности

### 🔐 DNS-over-HTTPS (DoH)
- 5 DoH-провайдеров: Google, Cloudflare, Quad9, OpenDNS, AdGuard
- Автоматический fallback на рабочий провайдер
- Кэширование DNS-ответов (30 мин)
- Пред-разрешение доменов Telegram при старте

### 🌐 Автоматический поиск прокси
- Загрузка и тестирование MTProto/SOCKS5 прокси из онлайн-источников
- Автоматический выбор лучшего прокси по задержке
- Поддержка `tg://proxy?` и `https://t.me/proxy?` ссылок
- Встроенные fallback MTProto-прокси

### 🔍 Детектор блокировок
- Мониторинг соединения с 5 дата-центрами Telegram
- Автоматическое определение блокировки или замедления
- Автоматическое включение прокси при обнаружении блокировки
- Периодическая проверка (каждые 5 мин)

### 📱 Настройки
В разделе **Настройки → Данные и память → Anti-Censorship** доступны:
- Переключатель DNS-over-HTTPS
- Переключатель автоматического прокси
- Детектор цензуры
- Проверка соединения
- Ручной поиск прокси

## 🔧 Сборка

### Требования
- Android Studio 3.4+
- Android NDK rev. 20
- Android SDK 35
- JDK 1.8

### Шаги

1. **Клонируйте репозиторий:**
   ```bash
   git clone <repository-url>
   cd KBGram
   ```

2. **Получите API-ключи** (обязательно):
   - Перейдите на https://my.telegram.org/apps
   - Создайте новое приложение
   - Скопируйте `api_id` и `api_hash`
   - Вставьте значения в `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`:
     ```java
     public static int APP_ID = <ваш_api_id>;
     public static String APP_HASH = "<ваш_api_hash>";
     ```

3. **Настройте подпись:**
   - Создайте свой keystore:
     ```bash
     keytool -genkey -v -keystore TMessagesProj/config/release.keystore \
       -alias kbgram -keyalg RSA -keysize 2048 -validity 10000
     ```
   - Обновите `gradle.properties`:
     ```properties
     RELEASE_KEY_PASSWORD=<ваш_пароль>
     RELEASE_KEY_ALIAS=kbgram
     RELEASE_STORE_PASSWORD=<ваш_пароль>
     ```

4. **Соберите APK:**
   ```bash
   ./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
   ```
   APK будет в `TMessagesProj_AppStandalone/build/outputs/apk/`

### Firebase (опционально)
Для push-уведомлений:
1. Создайте проект на https://console.firebase.google.com/
2. Добавьте Android-приложение с пакетом `com.kbgram.messenger`
3. Скачайте `google-services.json` в `TMessagesProj/`

## 📁 Структура проекта

```
TMessagesProj/src/main/java/org/telegram/
├── messenger/
│   ├── BuildVars.java           # Настройки форка
│   ├── ApplicationLoader.java   # Инициализация антицензуры
│   ├── DoHResolver.java         # DNS-over-HTTPS модуль
│   ├── ProxyFetcher.java        # Автоматический поиск прокси
│   └── CensorshipDetector.java  # Детектор блокировок
├── tgnet/
│   └── ConnectionsManager.java  # Интеграция DoH в сетевой стек
└── ui/
    └── DataSettingsActivity.java # UI настроек антицензуры
```

## 📄 Лицензия

GNU General Public License v2.0 — см. [LICENSE](LICENSE)

## ⚠️ Отказ от ответственности

KBGram — неофициальный форк. Не имеет отношения к Telegram FZ-LLC. Используйте на свой страх и риск.
