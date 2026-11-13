# DSH Mobile with Termux

**DeepSeek Harness auf Android: Ein Agent mit Zugriff auf die Smartphone-Oberfläche, Dateien und Kommandozeilenwerkzeuge.**

[APK herunterladen](https://github.com/hazukieq/DSH-Mobile-with-Termux/releases) · [Dokumentation](docs/README.md) · [Probleme melden](https://github.com/hazukieq/DSH-Mobile-with-Termux/issues) · [中文](README.md) · [English](README_EN.md)

DSH Mobile bündelt eine für Android angepasste DeepSeek-Harness-Engine, WebUI, Node.js und eine Termux-Umgebung.
Unterhaltungen, Werkzeuge und Plugins lassen sich direkt auf dem Smartphone nutzen, ohne Termux separat zu installieren.
Root und ein Computer sind dafür nicht erforderlich.

Die Engine läuft auf dem Smartphone. Für die Chat-Inferenz werden der selbst konfigurierte Modell-Endpunkt und API-Schlüssel verwendet.
Die APK enthält kein großes Sprachmodell für Unterhaltungen. Lokale OCR und optionale Symbolerkennung dienen zum Lesen der Oberfläche.

## Funktionen

| Funktion | Verwendung |
|---|---|
| Unterhaltungen und Werkzeuge | Eingebettete WebUI mit Sitzungen, Werkzeugen und Plugins; die Engine liefert das Layout für schmale Bildschirme |
| Smartphone-Steuerung | Oberfläche lesen, tippen, wischen, Text eingeben und Screenshots aufnehmen – über den autorisierten Bedienungshilfedienst und die lokale Gerätebrücke |
| Oberflächenerkennung | Mitgeliefertes ML Kit als Standard, optional PP-OCRv6 im App-Prozess und Symbolerkennung; OCR benötigt keine Python-Installation |
| Terminal und Dateien | PTY-Terminal in derselben Termux-Umgebung wie die Engine, Paketverwaltung mit `pkg` / `apt`, Dateibearbeitung und Teilen |
| Hintergrundfortschritt | Schwebende Kugel/Karte, Benachrichtigungen oder ausgeschaltete Anzeige; scrollbarer Text, Einrasten am Rand, Sitzung und Laufzeit |
| Android-Integration | Apps und Links öffnen, Inhalte teilen, Medien abspielen, Lautstärke steuern und APK-Installationen vom System bestätigen lassen |
| LAN-Zugriff | Optionaler WebUI-Proxy mit Zugangscode für den Zugriff anderer Geräte auf die Smartphone-Engine |

## Installation und Einstieg

1. Eine passende APK von [Releases](https://github.com/hazukieq/DSH-Mobile-with-Termux/releases) herunterladen. Der übliche Geräte-Build ist `arm64-v8a`.
2. App öffnen und Sprache, Ausrichtung sowie Berechtigungsmodus wählen. Für den Einstieg genügt **Normal**.
3. Warten, bis die Laufzeitumgebung entpackt und die Engine gestartet ist; anschließend Modell-Endpunkt und API-Schlüssel in der WebUI konfigurieren.
4. Benachrichtigungen, Dateizugriff und Bedienungshilfen nach Bedarf freigeben.

Die Quellkonfiguration setzt mindestens Android 8.0 (API 26) voraus. Laufzeit- und Oberflächenfunktionen hängen auch vom Gerät und der WebView ab.
App-übergreifende Screenshots benötigen Android 11 (API 30) oder neuer sowie einen verbundenen Bedienungshilfedienst.
Bei einer zu alten WebView zeigt die App einen Aktualisierungshinweis.

**Paketkonflikt mit Termux:** Die App verwendet `com.termux` und kann nicht neben einer anderen App mit diesem Paketnamen installiert sein.
Vor einem Wechsel vorhandene Termux-Daten sichern. APKs mit unterschiedlichen Signaturen können sich nicht direkt gegenseitig aktualisieren.
Weitere Hinweise stehen in der [Terminal-Anleitung](docs/TERMINAL_AND_LINUX.en.md).

## Berechtigungen und Fortschritt

Im Normal-Modus funktionieren Engine, Terminal, Dateiwerkzeuge und die Smartphone-Steuerung über Bedienungshilfen.
Root ist für diese Steuerung und das Fortschrittsfenster nicht erforderlich.

| Option | Umfang |
|---|---|
| Normal | Engine mit App-UID; interne Wartungs- und Ausweichpfade rufen su oder Shizuku nicht automatisch auf |
| Shizuku | Autorisierte Shell-Funktionen; die Engine behält die App-UID, bei Fehlern erfolgt kein Rückgriff auf Root |
| Root | Startet die Engine nach ausdrücklicher Auswahl mit Root-Rechten und erweitert den Lese- und Änderungszugriff des Agenten |
| Bedienungshilfen | Andere Apps lesen und bedienen, Bildschirm aufnehmen und zugehörige Ereignisse empfangen; Engine, Terminal, Teilen und gewöhnliche Dateioperationen bleiben davon unabhängig |
| Über anderen Apps einblenden | Für das Anwendungs-Overlay bei getrenntem Bedienungshilfedienst; ein verbundener Dienst kann das Fortschrittsfenster direkt anzeigen |
| Benachrichtigungen und Dateien | Benachrichtigungen zustellen und Dateien im freigegebenen Umfang lesen oder schreiben |

Tippen auf die Kugel öffnet die Karte, Tippen auf deren linken Statuspunkt schließt sie. Ziehen verschiebt das Fenster.
In den Einstellungen zwischen Overlay, Benachrichtigungen und ausgeschalteter Anzeige wählen.
Das Ausschalten der Fortschrittsanzeige lässt Bedienungshilfen, OCR und Terminal verfügbar.
Die Aktion „Beenden“ in der Benachrichtigung stoppt die Engine und entfernt das Fenster; erneutes Öffnen der App stellt den Betrieb wieder her.

Siehe [Abhängigkeiten von Bedienungshilfen](docs/A11Y-SCOPE.en.md) und [Fortschrittsanzeige](docs/PROGRESS_OVERLAY.md).

## Daten und Netzwerk

Die Engine-WebUI lauscht standardmäßig auf `127.0.0.1:3080`, die Gerätebrücke auf `127.0.0.1:3083` mit Token-Prüfung.
LAN-Zugriff ist zunächst ausgeschaltet und verwendet bei Aktivierung einen separaten Proxy mit Zugangscode.

Konfiguration und Zugangsdaten liegen im privaten App-Speicher. Sitzungen und Arbeitsbereiche können im gemeinsamen Speicher liegen.
Modellanfragen, Suche, Webseitenabrufe, TTS, Downloads und Plugins können das Netzwerk nutzen.
Welche Daten extern übertragen werden, hängt von den konfigurierten Diensten und Aufgaben ab.
Bei Sicherungen die tatsächlichen privaten und externen Speicherorte prüfen; der logische Pfad allein sagt nicht, was eine Deinstallation übersteht.

[Hinweise zu Berechtigungen und Audits](docs/SECURITY-AUDIT.md) beschreiben Netzwerkzugänge, Daten, Debug-Zugriff und APK-Prüfungen.

## Aus dem Quellcode bauen

Benötigt werden JDK 17, Gradle 8.9, Android SDK 36, CMake 3.22.1 und ein passendes NDK. Ein Gradle-Wrapper ist nicht enthalten.

**Zuerst die Laufzeitdateien vorbereiten.** Termux-Bootstrap und angepasste Engine-Archive sind große, nicht in Git enthaltene Dateien.
Ein Quellcode-Klon allein genügt nicht für eine nutzbare APK. Die [Build-Umgebung](docs/DEVELOPMENT-ENV.md) und
[Engine-Anleitung](docs/ENGINE-BUILD.md) beschreiben die Vorbereitung einschließlich pnpm und nativer Abhängigkeiten. Danach ausführen:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
bash scripts/acceptance.sh --static
gradle testDebugUnitTest
gradle assembleRelease -Pabi=arm64-v8a
```

Ergebnis: `app/build/outputs/apk/release/app-release.apk`. Den Gradle-Ablauf des Projekts verwenden, nicht `android build`.
Die Release-Signatur über `keystore.properties` oder Umgebungsvariablen konfigurieren; ohne Konfiguration wird ein Debug-Schlüssel verwendet.
Details: [Signatur und Validierung](docs/DEVELOPMENT-ENV.md).

Für Geräteinstallation, Screenshots und Interaktion bevorzugt die android CLI verwenden; siehe [CLI-Anleitung](docs/ANDROID-CLI.md).
Der [Overlay-Testbericht vom echten Gerät](docs/qa/overlay-physical-normal-report.md) nennt Build, Gerät, Ergebnisse und Grenzen der Testabdeckung.

## Dokumentation und Herkunft

Einstieg über den [Dokumentationsindex](docs/README.md), die [Smartphone-Steuerung](docs/ACCESSIBILITY.en.md),
das [Terminal](docs/TERMINAL_AND_LINUX.en.md), das [Overlay](docs/OVERLAY.md) oder die [LAN-WebUI](docs/LAN_WEBUI.en.md).
Ein Teil der ausführlichen Entwicklerdokumentation ist auf Chinesisch.

Fork von [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android),
auf Basis von [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) und [Termux](https://github.com/termux/termux-app),
mit ML Kit, PaddleOCR, ONNX Runtime und weiteren Komponenten.

Der Repository-Code steht unter der [MIT-Lizenz](LICENSE); ursprüngliche Copyright-Hinweise bleiben erhalten.
Mitgelieferte Laufzeitumgebungen, Abhängigkeiten und Modelle unterliegen ihren jeweiligen Lizenzen.
Siehe [Quellen und Hinweise zur Weitergabe](docs/CREDITS.md).
