# Video Translator - Benutzerhandbuch

Willkommen bei Video Translator! Diese Anwendung ermöglicht es Ihnen, Videos von YouTube herunterzuladen, deren Audio zu
transkribieren, Untertitel in Ihre bevorzugte Sprache zu übersetzen und das Ergebnis mit eingebrannten Untertiteln oder
als separate Untertiteldateien zu exportieren.

---

## Inhaltsverzeichnis

1. [Systemanforderungen](#systemanforderungen)
2. [Installation](#installation)
3. [Erster Start - Einrichtungsassistent](#erster-start---einrichtungsassistent)
4. [Hauptoberfläche](#hauptoberfläche)
5. [Ein Video übersetzen](#ein-video-übersetzen)
6. [Einstellungen](#einstellungen)
7. [Fehlerbehebung](#fehlerbehebung)
8. [Häufige Fragen (FAQ)](#häufige-fragen-faq)

---

## Systemanforderungen

### Mindestanforderungen

| Komponente              | Anforderung                                                                  |
|-------------------------|------------------------------------------------------------------------------|
| **Betriebssystem**      | Windows 10/11, macOS 11+ oder Linux (Ubuntu 20.04+)                          |
| **Arbeitsspeicher**     | 8 GB (16 GB empfohlen für größere Modelle)                                   |
| **Festplattenspeicher** | 5 GB für Anwendung und Abhängigkeiten                                        |
| **Internet**            | Erforderlich zum Herunterladen von Videos und für Online-Übersetzungsdienste |

### Empfohlen für beste Leistung

- **GPU**: NVIDIA-Grafikkarte mit CUDA-Unterstützung (beschleunigt die Transkription erheblich)
- **Arbeitsspeicher**: 16 GB oder mehr
- **SSD**: Für schnellere Verarbeitung von Videodateien

---

## Installation

### Windows

1. Laden Sie das `.msi`-Installationsprogramm von der Release-Seite herunter
2. Doppelklicken Sie auf das Installationsprogramm und folgen Sie den Anweisungen auf dem Bildschirm
3. Starten Sie Video Translator über das Startmenü

### macOS

1. Laden Sie die `.dmg`-Datei von der Release-Seite herunter
2. Öffnen Sie das DMG und ziehen Sie Video Translator in Ihren Programme-Ordner
3. Beim ersten Start klicken Sie mit der rechten Maustaste auf die App und wählen Sie "Öffnen", um Gatekeeper zu umgehen

### Linux

1. Laden Sie das `.deb`-Paket (Debian/Ubuntu) oder `.rpm`-Paket (Fedora/RHEL) herunter
2. Installieren Sie mit Ihrem Paketmanager:
   ```bash
   # Debian/Ubuntu
   sudo dpkg -i video-translator_*.deb

   # Fedora/RHEL
   sudo rpm -i video-translator_*.rpm
   ```
3. Starten Sie die Anwendung über das Anwendungsmenü oder führen Sie `video-translator` im Terminal aus

---

## Erster Start - Einrichtungsassistent

Beim ersten Start von Video Translator führt Sie ein Einrichtungsassistent durch die Erstkonfiguration.

### Schritt 1: Willkommensbildschirm

![Screenshot: Willkommensbildschirm mit dem Video Translator-Logo, einer kurzen Beschreibung der Anwendung und einer "Erste Schritte"-Schaltfläche am unteren Rand]

Klicken Sie auf **"Erste Schritte"**, um den Einrichtungsprozess zu beginnen.

### Schritt 2: Übersetzungsdienst auswählen

![Screenshot: Bildschirm zur Auswahl des Übersetzungsdienstes mit vier Optionen als Karten - LibreTranslate (mit "Kostenlos, läuft lokal" als Untertitel), DeepL (mit "Hohe Qualität, API-Schlüssel erforderlich" als Untertitel), OpenAI (mit "GPT-basiert, API-Schlüssel erforderlich" als Untertitel) und Google Translate (mit "Schnell, API-Schlüssel erforderlich" als Untertitel). LibreTranslate ist als ausgewählt hervorgehoben]

Wählen Sie Ihren bevorzugten Übersetzungsdienst:

| Dienst               | Beschreibung                                                  | Voraussetzungen                                            |
|----------------------|---------------------------------------------------------------|------------------------------------------------------------|
| **LibreTranslate**   | Kostenlos, Open-Source, läuft lokal auf Ihrem Computer        | Kein API-Schlüssel nötig, lädt ~2GB Sprachmodelle herunter |
| **DeepL**            | Hochwertige Übersetzungen, besonders für europäische Sprachen | Erfordert DeepL API-Schlüssel (kostenlose Stufe verfügbar) |
| **OpenAI**           | GPT-basierte Übersetzungen mit Kontextbewusstsein             | Erfordert OpenAI API-Schlüssel                             |
| **Google Translate** | Schnell und zuverlässig                                       | Erfordert Google Cloud API-Schlüssel                       |

Wenn Sie einen Dienst mit API-Schlüssel auswählen, werden Sie aufgefordert, diesen einzugeben.

### Schritt 3: Whisper-Modell auswählen

![Screenshot: Bildschirm zur Auswahl des Whisper-Modells mit einem Dropdown-Menü mit Optionen: Tiny, Base, Small, Medium, Large. Jede Option zeigt die Modellgröße in Klammern (z.B. "Base (142 MB)"). Eine Beschreibung darunter erklärt, dass größere Modelle genauer aber langsamer sind]

Wählen Sie das Whisper-Modell für die Audio-Transkription:

| Modell     | Größe  | Geschwindigkeit | Genauigkeit | Empfohlen für                          |
|------------|--------|-----------------|-------------|----------------------------------------|
| **Tiny**   | 75 MB  | Am schnellsten  | Grundlegend | Schnelle Tests, kurze Clips            |
| **Base**   | 142 MB | Schnell         | Gut         | Allgemeine Nutzung, die meisten Videos |
| **Small**  | 466 MB | Mittel          | Besser      | Wenn Genauigkeit wichtig ist           |
| **Medium** | 1,5 GB | Langsam         | Sehr gut    | Professionelle Nutzung                 |
| **Large**  | 3 GB   | Am langsamsten  | Am besten   | Maximale Genauigkeit erforderlich      |

**Tipp:** Beginnen Sie mit "Base" für ein gutes Gleichgewicht zwischen Geschwindigkeit und Genauigkeit. Sie können dies
später in den Einstellungen ändern.

### Schritt 4: Abhängigkeiten herunterladen

![Screenshot: Download-Fortschrittsbildschirm mit einer Liste der heruntergeladenen Komponenten mit Häkchen für abgeschlossene Elemente und einem Fortschrittsbalken für den aktuellen Download. Aufgelistete Komponenten: yt-dlp (abgehakt), FFmpeg (abgehakt), FFprobe (abgehakt), Whisper (wird heruntergeladen, 45%), Python (ausstehend), LibreTranslate (ausstehend)]

Die Anwendung lädt die erforderlichen Komponenten herunter:

- **yt-dlp**: Zum Herunterladen von Videos von YouTube
- **FFmpeg & FFprobe**: Für Video-/Audioverarbeitung
- **Whisper**: Für Audio-Transkription (Sprache-zu-Text)
- **Python** (falls erforderlich): Benötigt für LibreTranslate
- **LibreTranslate** (falls ausgewählt): Lokaler Übersetzungsdienst

Dies kann je nach Internetverbindung mehrere Minuten dauern.

### Schritt 5: Einrichtung abgeschlossen

![Screenshot: Bildschirm "Einrichtung abgeschlossen" mit einem grünen Häkchen, Text "Alles bereit!", einer Zusammenfassung der konfigurierten Einstellungen und einer Schaltfläche "Video Translator verwenden"]

Klicken Sie auf **"Video Translator verwenden"**, um zu beginnen!

---

## Hauptoberfläche

![Screenshot: Hauptanwendungsfenster zeigt: 1) Ein URL-Eingabefeld oben mit Platzhaltertext "YouTube-URL hier einfügen...", 2) Eine "Video-Info abrufen"-Schaltfläche daneben, 3) Einen großen leeren Bereich in der Mitte mit dem Text "Fügen Sie eine YouTube-URL ein, um zu beginnen", 4) Zahnrad-Symbol für Einstellungen in der oberen rechten Ecke]

### Oberflächenelemente

1. **URL-Eingabefeld**: Fügen Sie hier Ihre YouTube-Video-URL ein
2. **Abrufen-Schaltfläche**: Klicken Sie, um Videoinformationen abzurufen
3. **Video-Vorschaubereich**: Zeigt Video-Miniaturansicht und Details nach dem Abrufen
4. **Einstellungen-Schaltfläche**: Zugriff auf Anwendungseinstellungen (Zahnrad-Symbol)

---

## Ein Video übersetzen

### Schritt 1: Video-URL eingeben

![Screenshot: Hauptbildschirm mit einer eingefügten YouTube-URL im Eingabefeld (z.B. "https://www.youtube.com/watch?v=beispiel"), die Abrufen-Schaltfläche ist hervorgehoben]

1. Kopieren Sie eine YouTube-Video-URL aus Ihrem Browser
2. Fügen Sie sie in das URL-Eingabefeld ein
3. Klicken Sie auf **"Video-Info abrufen"**

### Schritt 2: Videoinformationen überprüfen

![Screenshot: Hauptbildschirm mit abgerufenen Videoinformationen - Miniaturansicht links, Videotitel "Beispiel-Videotitel" fett gedruckt, Kanalname darunter, Dauer "12:34", erkannte Sprache "Englisch" und verfügbare Untertitel aufgelistet. Darunter befinden sich Dropdown-Menüs für "Quellsprache" (auf "Automatisch erkennen" eingestellt) und "Zielsprache" (auf "Deutsch" eingestellt)]

Nach dem Abrufen sehen Sie:

- Video-Miniaturansicht
- Titel und Kanalname
- Dauer
- Erkannte Sprache (falls Untertitel verfügbar sind)

Konfigurieren Sie Ihre Übersetzung:

- **Quellsprache**: Normalerweise automatisch erkannt, oder manuell auswählen
- **Zielsprache**: Wählen Sie Ihre gewünschte Untertitelsprache

### Schritt 3: Ausgabeoptionen konfigurieren

![Screenshot: Ausgabeoptionen-Bereich zeigt: 1) Ausgabemodus-Dropdown mit Optionen "Eingebrannte Untertitel", "Externe SRT-Datei", "Beides", 2) Ausgabeordner-Auswahl mit aktuellem Pfad und einer "Durchsuchen"-Schaltfläche, 3) Kontrollkästchen "Hardware-Beschleunigung verwenden" (aktiviert), 4) Große "Übersetzung starten"-Schaltfläche unten]

- **Ausgabemodus**:
    - *Eingebrannt*: Untertitel ins Video eingebettet (dauerhaft)
    - *Externe SRT*: Separate Untertiteldatei
    - *Beides*: Erstellt beide Versionen
- **Ausgabeordner**: Wo das übersetzte Video gespeichert wird
- **Hardware-Beschleunigung**: Aktivieren für schnellere Verarbeitung (falls unterstützt)

### Schritt 4: Übersetzung starten

Klicken Sie auf **"Übersetzung starten"**, um den Prozess zu beginnen.

### Schritt 5: Fortschritt verfolgen

![Screenshot: Fortschrittsbildschirm zeigt die Übersetzungs-Pipeline-Stufen als vertikale Zeitleiste: 1) "Video herunterladen" mit grünem Häkchen, 2) "Audio extrahieren" mit grünem Häkchen, 3) "Audio transkribieren" mit drehendem Indikator und "45% - Verarbeite Segment 12/27", 4) "Untertitel übersetzen" ausgegraut, 5) "Video rendern" ausgegraut. Eine Abbrechen-Schaltfläche ist unten sichtbar]

Der Fortschrittsbildschirm zeigt jede Stufe:

1. **Video herunterladen**: Video von YouTube abrufen
2. **Audio extrahieren**: Audio für Transkription vorbereiten
3. **Audio transkribieren**: Sprache in Text umwandeln (Whisper)
4. **Untertitel übersetzen**: In Zielsprache übersetzen
5. **Video rendern**: Endgültiges Video mit Untertiteln erstellen

Sie können jederzeit auf **"Abbrechen"** klicken, um den Prozess zu stoppen.

### Schritt 6: Fertig

![Screenshot: Abschlussbildschirm mit grünem Häkchen, Nachricht "Übersetzung abgeschlossen!", dem Ausgabedateipfad und zwei Schaltflächen: "Datei öffnen" und "Ordner öffnen". Angezeigte Statistiken: Dauer 12:34, Verarbeitungszeit 8:45, Übersetzte Segmente: 127]

Nach Abschluss:

- Klicken Sie auf **"Datei öffnen"**, um das übersetzte Video abzuspielen
- Klicken Sie auf **"Ordner öffnen"**, um alle Ausgabedateien anzuzeigen
- Klicken Sie auf **"Weiteres Video übersetzen"**, um eine neue Übersetzung zu starten

---

## Einstellungen

Greifen Sie auf die Einstellungen zu, indem Sie auf das Zahnrad-Symbol in der oberen rechten Ecke klicken.

### Allgemein-Tab

![Screenshot: Einstellungsfenster mit ausgewähltem "Allgemein"-Tab. Zeigt: 1) Ausgabeordner-Pfad mit Durchsuchen-Schaltfläche, 2) Kontrollkästchen "Temporäre Dateien nach Abschluss löschen" (aktiviert), 3) Design-Auswahl-Dropdown (Hell/Dunkel/System), 4) Sprach-Dropdown für Oberflächensprache]

- **Standard-Ausgabeordner**: Wo übersetzte Videos gespeichert werden
- **Temporäre Dateien löschen**: Automatisch nach Verarbeitung aufräumen
- **Design**: Hell, Dunkel oder Systemeinstellung folgen
- **Oberflächensprache**: Sprache der Anwendungsoberfläche

### Transkription-Tab

![Screenshot: Einstellungsfenster mit ausgewähltem "Transkription"-Tab. Zeigt: 1) Whisper-Modell-Dropdown (Tiny/Base/Small/Medium/Large), 2) Kontrollkästchen "YouTube-Untertitel bevorzugen, wenn verfügbar" (aktiviert), 3) Kontrollkästchen "GPU-Beschleunigung verwenden" mit erkannter GPU darunter angezeigt]

- **Whisper-Modell**: Transkriptionsmodell ändern
- **YouTube-Untertitel bevorzugen**: Vorhandene Untertitel verwenden, falls verfügbar (schneller)
- **GPU-Beschleunigung**: NVIDIA-GPU für schnellere Transkription nutzen

### Übersetzung-Tab

![Screenshot: Einstellungsfenster mit ausgewähltem "Übersetzung"-Tab. Zeigt: 1) Übersetzungsdienst-Dropdown (LibreTranslate/DeepL/OpenAI/Google), 2) API-Schlüssel-Eingabefeld (für DeepL/OpenAI/Google angezeigt), 3) Standard-Quellsprache-Dropdown, 4) Standard-Zielsprache-Dropdown, 5) "Glossar verwalten"-Schaltfläche]

- **Übersetzungsdienst**: Zwischen Übersetzungsanbietern wechseln
- **API-Schlüssel**: API-Schlüssel für kostenpflichtige Dienste eingeben oder aktualisieren
- **Standardsprachen**: Bevorzugte Quell-/Zielsprachen festlegen
- **Glossar**: Benutzerdefinierte Begriffsübersetzungen definieren

### Untertitel-Tab

![Screenshot: Einstellungsfenster mit ausgewähltem "Untertitel"-Tab. Zeigt: 1) Standard-Ausgabemodus-Dropdown, 2) Kontrollkästchen "SRT-Datei immer exportieren", 3) Schriftart-Einstellungsbereich mit Schriftfamilien-Dropdown, Schriftgrößen-Schieberegler (16-48), 4) Farbauswahl für Textfarbe und Umrissfarbe, 5) Position-Dropdown (Unten/Oben), 6) Vorschaubereich mit Beispieluntertitel in aktuellen Einstellungen]

- **Standard-Ausgabemodus**: Eingebrannt, Extern oder Beides
- **SRT immer exportieren**: SRT-Datei unabhängig vom Ausgabemodus erstellen
- **Schriftart-Einstellungen**: Untertitel-Erscheinungsbild anpassen
    - Schriftfamilie
    - Schriftgröße
    - Textfarbe
    - Umrissfarbe
    - Position (oben/unten)

### Aktualisierungen-Tab

![Screenshot: Einstellungsfenster mit ausgewähltem "Aktualisierungen"-Tab. Zeigt: 1) Aktuelle Versionsnummer, 2) Kontrollkästchen "Automatisch nach Updates suchen", 3) "Jetzt prüfen"-Schaltfläche, 4) Update-Statusmeldungsbereich, 5) "Abhängigkeiten aktualisieren"-Schaltfläche zum Aktualisieren von yt-dlp, FFmpeg usw.]

- **Automatische Update-Prüfung**: Automatisch nach neuen Versionen suchen
- **Jetzt prüfen**: Manuell nach Updates suchen
- **Abhängigkeiten aktualisieren**: Neueste Versionen von yt-dlp, FFmpeg usw. herunterladen

---

## Fehlerbehebung

### Video-Download schlägt fehl

**Symptome**: Fehlermeldung beim Versuch, ein Video abzurufen oder herunterzuladen

**Lösungen**:

1. Überprüfen Sie Ihre Internetverbindung
2. Stellen Sie sicher, dass die YouTube-URL korrekt ist und das Video öffentlich ist
3. Aktualisieren Sie yt-dlp unter Einstellungen → Aktualisierungen → Abhängigkeiten aktualisieren
4. Einige Videos können geografisch eingeschränkt oder altersbeschränkt sein

### Transkription ist sehr langsam

**Symptome**: Die "Audio transkribieren"-Stufe dauert sehr lange

**Lösungen**:

1. Verwenden Sie ein kleineres Whisper-Modell (Tiny oder Base)
2. Aktivieren Sie GPU-Beschleunigung, wenn Sie eine NVIDIA-GPU haben
3. Aktivieren Sie "YouTube-Untertitel bevorzugen", um die Transkription zu überspringen, wenn Untertitel existieren
4. Schließen Sie andere ressourcenintensive Anwendungen

### Übersetzungsqualität ist schlecht

**Symptome**: Übersetzte Untertitel haben Fehler oder klingen unnatürlich

**Lösungen**:

1. Probieren Sie einen anderen Übersetzungsdienst (DeepL bietet oft bessere Qualität)
2. Verwenden Sie ein größeres Whisper-Modell für bessere Transkriptionsgenauigkeit
3. Erstellen Sie ein Glossar für fachspezifische Begriffe
4. Korrigieren Sie die Quellsprache manuell, wenn die automatische Erkennung falsch liegt

### LibreTranslate startet nicht

**Symptome**: Fehler bei der Verwendung von LibreTranslate für die Übersetzung

**Lösungen**:

1. Stellen Sie sicher, dass Python installiert ist (prüfen Sie unter Einstellungen → Aktualisierungen)
2. Installieren Sie LibreTranslate neu unter Einstellungen → Aktualisierungen → Abhängigkeiten aktualisieren
3. Prüfen Sie, ob eine andere Anwendung Port 5000 verwendet
4. Versuchen Sie, die Anwendung neu zu starten

### Ausgabevideo hat keinen Ton

**Symptome**: Das gerenderte Video ist stumm

**Lösungen**:

1. Probieren Sie einen anderen Hardware-Encoder in den Einstellungen
2. Deaktivieren Sie Hardware-Beschleunigung und verwenden Sie Software-Encoding
3. Stellen Sie sicher, dass FFmpeg ordnungsgemäß installiert ist

### Anwendung startet nicht

**Symptome**: Die Anwendung stürzt ab oder öffnet sich nicht

**Lösungen**:

1. **Windows**: Installieren Sie Visual C++ Redistributable 2015-2022
2. **macOS**: Stellen Sie sicher, dass Sie die App in den Sicherheits- und Datenschutzeinstellungen erlaubt haben
3. **Linux**: Überprüfen Sie, ob alle Abhängigkeiten installiert sind
4. Versuchen Sie, den Konfigurationsordner zu löschen und neu zu starten:
    - Windows: `%APPDATA%\VideoTranslator`
    - macOS: `~/Library/Application Support/VideoTranslator`
    - Linux: `~/.local/share/VideoTranslator`

---

## Häufige Fragen (FAQ)

### Ist Video Translator kostenlos?

Ja, Video Translator ist kostenlose Open-Source-Software. Einige Übersetzungsdienste (DeepL, OpenAI, Google) erfordern
jedoch kostenpflichtige API-Schlüssel für hohe Nutzungsvolumen.

### Kann ich Videos von anderen Quellen als YouTube übersetzen?

Derzeit ist Video Translator für YouTube-Videos optimiert. Unterstützung für andere Plattformen könnte in zukünftigen
Versionen hinzugefügt werden.

### Wie genau ist die Transkription?

Die Genauigkeit hängt vom verwendeten Whisper-Modell und der Audioqualität ab. Das "Base"-Modell erreicht typischerweise
über 90% Genauigkeit bei klarem Audio. Verwenden Sie größere Modelle für schwieriges Audio oder spezialisierte Inhalte.

### Kann ich die Untertitel vor dem Rendern bearbeiten?

Derzeit ist die Untertitelbearbeitung nicht in die Anwendung integriert. Sie können als SRT exportieren, mit einem
Texteditor oder einer Untertitelsoftware bearbeiten und dann die SRT-Datei in einem Videoeditor verwenden.

### Funktioniert es offline?

- **Video-Download**: Erfordert Internet
- **Transkription**: Funktioniert vollständig offline
- **Übersetzung mit LibreTranslate**: Funktioniert nach der Ersteinrichtung offline
- **Übersetzung mit anderen Diensten**: Erfordert Internet

### Wie kann ich die Übersetzungsqualität verbessern?

1. Verwenden Sie DeepL oder OpenAI für qualitativ hochwertigere Übersetzungen
2. Erstellen Sie ein Glossar für Fachbegriffe oder Namen
3. Stellen Sie sicher, dass die Quelltranskription genau ist, indem Sie ein größeres Whisper-Modell verwenden
4. Stellen Sie die korrekte Quellsprache manuell ein, anstatt automatische Erkennung zu verwenden

### Welche Sprachen werden unterstützt?

- **Transkription**: Whisper unterstützt über 99 Sprachen
- **Übersetzung**: Abhängig vom Dienst:
    - LibreTranslate: ~30 Sprachen
    - DeepL: 30+ Sprachen
    - OpenAI: 100+ Sprachen
    - Google: 130+ Sprachen

### Wo werden meine übersetzten Videos gespeichert?

Standardmäßig werden Videos in Ihrem Videos-Ordner gespeichert. Sie können dies unter Einstellungen → Allgemein →
Standard-Ausgabeordner ändern.

### Wie aktualisiere ich die Anwendung?

Prüfen Sie unter Einstellungen → Aktualisierungen auf neue Versionen. Sie können auch die Abhängigkeiten (yt-dlp, FFmpeg
usw.) von diesem Bildschirm aus aktualisieren.

---

## Tastaturkürzel

| Tastenkürzel               | Aktion                          |
|----------------------------|---------------------------------|
| `Strg+V` / `Cmd+V`         | URL aus Zwischenablage einfügen |
| `Strg+Enter` / `Cmd+Enter` | Übersetzung starten             |
| `Escape`                   | Aktuellen Vorgang abbrechen     |
| `Strg+,` / `Cmd+,`         | Einstellungen öffnen            |
| `Strg+Q` / `Cmd+Q`         | Anwendung beenden               |

---

## Hilfe erhalten

Wenn Sie auf Probleme stoßen, die in diesem Handbuch nicht behandelt werden:

1. Überprüfen Sie die [GitHub Issues](https://github.com/your-repo/video-translator/issues) auf bekannte Probleme
2. Erstellen Sie ein neues Issue mit Details zu Ihrem Problem
3. Geben Sie Ihr Betriebssystem, die Anwendungsversion und Fehlermeldungen an

---

*Zuletzt aktualisiert: Januar 2026*
*Version: 1.0.0*
