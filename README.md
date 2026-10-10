# OK Copilot (Android)

App que escucha en segundo plano y abre **Microsoft Copilot** cuando decís **"OK Copilot"**.
Reconocimiento de voz 100% offline con [Vosk](https://alphacephei.com/vosk/) (modelo inglés pequeño, ~40 MB, se descarga desde la app la primera vez).

## Compilar el APK

**Opción A — GitHub (sin instalar nada):**
1. Creá un repo en GitHub y subí esta carpeta.
2. Pestaña **Actions** → workflow **Build APK** corre solo (o "Run workflow").
3. Al terminar, descargá el artifact **OkCopilot-apk** → `app-debug.apk`.

**Opción B — Android Studio:** File → Open → esta carpeta → Build → Build APK(s).

## Instalar y configurar en el teléfono
1. Copiá el APK al teléfono y abrilo (permití "instalar apps de origen desconocido").
2. En la app, tocá los botones en orden:
   1. Permiso de micrófono y notificaciones
   2. "Mostrar sobre otras apps" → activalo para *OK Copilot* (necesario para que Android deje abrir Copilot desde segundo plano)
   3. Descargar modelo de voz (requiere internet una sola vez)
   4. Quitar restricción de batería (para que Android no mate el servicio)
3. **▶ Iniciar escucha**. Vas a ver una notificación fija "Escuchando…".
4. Decí **"OK Copilot"** (o "Okay Copilot").

## Limitaciones
- Con la pantalla bloqueada, Android puede no mostrar Copilot encima del lockscreen: desbloqueá o tocá la notificación.
- Mientras Copilot usa el micrófono, esta app queda en pausa (Android le da prioridad a la app en pantalla).
- No se inicia sola al reiniciar el teléfono (Android 14+ no permite iniciar micrófono en segundo plano al arrancar): abrí la app y tocá Iniciar.
- Escuchar siempre consume algo de batería.
- Algunas marcas (Xiaomi, Samsung, Huawei) tienen ajustes extra de "autoinicio"/"apps en suspensión": agregá OK Copilot como excepción.
