/**
 * German.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const de: Catalog = {
  "site.name": "Limn",
  "site.tagline": "Ein UI-Toolkit für Java auf dem Desktop.",

  "nav.primaryLabel": "Website",
  "nav.menu": "Menü",
  "nav.components": "Komponenten",
  "nav.showcase": "Oberflächen",
  "nav.docs": "Handbuch",
  "nav.api": "API",
  "nav.licence": "Lizenz",
  "nav.privacy": "Datenschutz",
  "nav.repository": "GitHub",
  "nav.skipToContent": "Zum Inhalt springen",
  "footer.linksLabel": "Projektlinks",

  "codeBlock.copy": "Kopieren",
  "codeBlock.copied": "In die Zwischenablage kopiert",

  "theme.label": "Design",
  "theme.system": "Automatisch",
  "theme.light": "Hell",
  "theme.dark": "Dunkel",

  "language.label": "Sprache",

  "consent.label": "Datenschutzeinstellungen",
  "consent.title": "Diese Website setzt keine Cookies",
  "consent.body":
    "Drei Dinge werden in diesem Browser gespeichert und sonst nirgends: das von Ihnen gewählte Design, die von Ihnen gewählte Sprache und Ihre Antwort hier. Alles Optionale bleibt ausgeschaltet, bis Sie es einschalten.",
  "consent.more": "Was gespeichert wird, vollständig",
  "consent.accept": "Alles erlauben",
  "consent.reject": "Nur das Notwendige",
  "consent.choose": "Auswählen",
  "consent.save": "Auswahl speichern",
  "consent.alwaysOn": "Immer aktiv",
  "consent.necessaryName": "Unbedingt erforderlich",
  "consent.necessaryBody":
    "Das gewählte helle oder dunkle Design, die gewählte Sprache und diese Antwort. Alle drei bleiben lokal in diesem Browser, keines davon ist ein Cookie, und keines verlässt den Rechner.",
  "consent.analyticsName": "Messung",
  "consent.analyticsBody":
    "Dafür gibt es derzeit keinen Verwender; die Website liefert überhaupt keine Analyse-Werkzeuge aus. Der Schalter existiert, damit alles, was jemals einen Besuch misst, blockiert bleibt, bis Sie es hier erlauben.",

  // ------------------------------------------------------------------- home
  "home.title": "Limn: ein UI-Toolkit für Java auf dem Desktop",
  "home.description":
    "Desktop-Anwendungen in Java bauen, mit eigenen Widgets, Layout, Text, Diagrammen, Medien und 3D. Zwei Abhängigkeiten, JDK 17, Windows, macOS und Linux. Apache-2.0.",

  "home.hero.eyebrow": "Desktop-UI für Java",
  "home.hero.headline": "Desktop-Anwendungen in Java, von Grund auf gezeichnet.",
  "home.hero.sub":
    "Limn zeichnet seine Pixel selbst. Widgets, Layout, Text, Diagramme, Medien und ein 3D-Viewport, in zwei Abhängigkeiten, ohne Swing, ohne JavaFX und ohne natives Toolkit darunter.",
  "home.hero.cta": "Loslegen",
  "home.hero.secondary": "Komponenten ansehen",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "Die Demo-Anwendung, von Limn während dieses Builds gerendert.",

  "home.install.eyebrow": "Fünf Minuten",
  "home.install.heading": "Zwei Abhängigkeiten und eine main-Methode",
  "home.install.body":
    "Keine Auszeichnungssprache, kein Annotation Processor, kein Build-Plugin. Toolkit und Backend hinzufügen, einfaches Java schreiben, und Sie haben ein Fenster.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "Unter macOS braucht die JVM <code>-XstartOnFirstThread</code>. Das ist die eine Plattform-Eigenheit, die Ihnen am ersten Tag begegnet; deshalb steht sie hier und nicht drei Klicks tiefer.",
  "home.install.more": "Installationsanleitung lesen",

  "home.features.eyebrow": "Was Sie bekommen",

  "home.features.components.heading": "Ein Komponentensatz, den Sie nicht selbst bauen müssen",
  "home.features.components.body":
    "Schaltflächen, Felder, Listen, Reiter, Menüs, Dialoge, geteilte Bereiche, ein Farbwähler, Balken-, Linien- und Ringdiagramme sowie eine virtualisierte Liste, in der eine Million Zeilen so viel kostet wie zwanzig. Jede davon liest Farbe, Form und Dichte aus dem Design, also ist nichts fest verdrahtet und ein kompakter Modus ist eine Zeile.",
  "home.features.components.link": "Alle ansehen",

  "home.features.layout.heading": "Layout, das in den Kopf passt",
  "home.features.layout.body":
    "Vier Widgets und ein Marker: eine Spalte stapelt, eine Zeile verteilt, ein Stapel überlagert, Padding rückt ein, und Expanded sagt, wer den übrigen Platz bekommt. Das ist das ganze Vokabular; es gibt keinen Constraint-Solver zu konfigurieren und keinen Layout-Manager zu installieren.",
  "home.features.layout.link": "Layout-Anleitung lesen",
  "home.features.layout.caption":
    "Ein Fenster aus einer Spalte, einer Zeile, einem Split und einem Expanded.",

  "home.features.forms.heading": "Formulare ohne Framework",
  "home.features.forms.body":
    "Ein Feld ist ein Widget, eine Validierungsregel ist ein Listener, und Absenden ist ein Methodenaufruf. Nichts zu binden, nichts zu registrieren, und Validierungszustände, die ein Feld in dem Moment umfärben, in dem der Nutzer es korrigiert.",
  "home.features.forms.link": "Formular-Anleitung lesen",
  "home.features.forms.caption": "Beschriftungen, Validierung, eine Auswahl und die Aktionszeile.",

  "home.features.media.heading": "Video und 3D sind auch nur Widgets",
  "home.features.media.body":
    "Ein physikalisch basierter 3D-Viewport und ein Videoplayer, zusammengesetzt wie gewöhnliche Widgets: eine Scroll-Ansicht beschneidet sie, ein Stapel zeichnet darüber, und sie nehmen am Layout teil wie eine Beschriftung.",
  "home.features.media.link": "Medien-Anleitung lesen",
  "home.features.media.caption": "Der 3D-Viewport, eingesetzt in ein gewöhnliches Fenster.",

  "home.themes.heading": "Ihre Identität, nicht die des Toolkits",
  "home.themes.body":
    "Ihre Anwendung soll nach Ihrem Produkt aussehen, nicht nach der Bibliothek, mit der sie gebaut wurde. Ein Theme sind reine Daten (jede Farbe, der Eckenradius, die Größenstufe, die jedes Steuerelement erbt), und ein Aufruf tauscht es zur Laufzeit. Laden Sie die Schrift Ihrer Marke aus einer Datei, die Ihnen gehört: vom Aussehen des Toolkits bleibt nichts übrig.",
  "home.themes.link": "So funktioniert das Theming",
  "home.themes.caption":
    "Ein Bildschirm, sieben Themes. Der Code hinter jedem Streifen ist identisch.",
  "home.themes.alt":
    "Derselbe dichte Bildschirm mit Steuerelementen, siebenmal nebeneinander gerendert, jeder Streifen in einer anderen Palette, Größenstufe und Schrift.",

  "home.languages.heading": "In den Sprachen Ihrer Nutzer",
  "home.languages.body":
    "Text wird mit denselben Vorschüben gemessen, mit denen er gezeichnet wird, und der Schrift-Fallback läuft pro Zeichen, so mischen sich Latein, Griechisch, Kyrillisch und CJK in einer Zeichenkette, ohne dass Sie eine Schrift wählen. Eingabemethoden komponieren im Feld selbst, und die Bearbeitung bewegt sich in Graphem-Clustern, sodass kombinierende Zeichen und mehrteilige Emoji nie zerteilt werden.",
  "home.languages.alt":
    "Derselbe Bildschirm, aufgenommen auf Japanisch, vereinfachtem Chinesisch, Koreanisch und Russisch, zu einem Fenster zusammengesetzt.",
  "home.languages.link": "Zum Text-Leitfaden",
  "home.languages.caption":
    "Dieselbe Oberfläche, während dieses Builds in vier Sprachen aufgenommen.",

  "home.limits.eyebrow": "Bevor Sie sich festlegen",
  "home.limits.heading": "Was Limn nicht kann",
  "home.limits.body":
    "Jedes Toolkit tauscht etwas ein. Das sind die Tauschgeschäfte, vorab genannt; sie in Woche drei zu entdecken ist schlimmer, als sie jetzt zu lesen.",
  "home.limits.scripts.heading": "Kein Shaping komplexer Schriften",
  "home.limits.scripts.body":
    "Arabisch, Hebräisch und die indischen Schriften brauchen kontextabhängige Verbindungen und Umstellungen, die der Textstack nicht umsetzt, und eine Layout-Richtung von rechts nach links gibt es nicht. Übersetzungen für diese Sprachen werden bewusst nicht ausgeliefert, statt sie falsch zu zeichnen.",
  "home.limits.a11y.heading": "Keine Screenreader-Brücke",
  "home.limits.a11y.body":
    "Tastaturnavigation und Fokusringe sind vollständig, aber nichts wird an die Barrierefreiheits-APIs der Plattform gemeldet. Wenn ein Screenreader funktionieren muss, ist dies für diese Anwendung noch nicht das richtige Toolkit.",
  "home.limits.version.heading": "Vor 1.0",
  "home.limits.version.body":
    "Die API bewegt sich zwischen Releases noch, und OpenGL ist der einzige Renderpfad. Pinnen Sie Ihre Version und lesen Sie die Release Notes.",

  "home.closing.heading": "In fünf Minuten ein Fenster auf dem Bildschirm",
  "home.closing.body":
    "Die Installationsanleitung endet mit einem laufenden Programm. Alles danach ist das Handbuch, die Komponentengalerie und die API-Referenz.",

  // ------------------------------------------------------------- components
  "components.title": "Limn: Komponenten",
  "components.description":
    "Jede Limn-Komponente, vom Toolkit selbst in beiden Paletten gerendert, neben dem Code, der das jeweilige Bild erzeugt hat.",
  "components.eyebrow": "Der Satz",
  "components.heading": "Komponenten",
  "components.lede":
    "Jedes Bild hier wurde während dieses Builds vom Toolkit gerendert, und jeder Ausschnitt ist der Code, der das Bild daneben erzeugt hat.",
  "components.filterLabel": "Komponenten filtern",
  "components.filterPlaceholder": "Filtern…",
  "components.empty": "Dazu passt nichts.",
  "components.showCode": "Code",
  "components.play": "Abspielen",
  "components.stop": "Stopp",
  "components.videoNote":
    "Die Video-Ansicht nutzt die reine Java-Testquelle, zeigt also das Widget in Betrieb und nicht die Codec-Abdeckung. An diesem Bild ist kein nativer Decoder beteiligt.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn: Oberflächen",
  "showcase.description":
    "Ganze Oberflächen, vom Toolkit gerendert: die Demo-Anwendung, der 3D-Viewport, ein Formular, ein gesetztes Fenster und dieselbe Oberfläche in vier Sprachen.",
  "showcase.eyebrow": "Ganze Oberflächen",
  "showcase.heading": "Oberflächen",
  "showcase.lede":
    "Keine Ausschnitte und keine Mock-ups. Jedes davon ist ein Fenster, das das Toolkit gerendert hat, während diese Website gebaut wurde.",
  "showcase.kitchen.heading": "Die Demo-Anwendung",
  "showcase.kitchen.body":
    "Jede Komponente in einem Fenster, mit Menüleiste, Reitern, Designwahl und einer laufenden Leistungsanzeige in der Fußzeile.",
  "showcase.forms.heading": "Ein Formular",
  "showcase.forms.body":
    "Beschriftungen, ein validiertes Feld, eine Auswahl, ein Schalter und die Aktionszeile: das ausgearbeitete Beispiel aus der Formular-Anleitung.",
  "showcase.layout.heading": "Ein gesetztes Fenster",
  "showcase.layout.body":
    "Eine Werkzeugleiste, eine Seitenleiste neben einem Inhaltsbereich und eine Statuszeile: das ausgearbeitete Beispiel aus der Layout-Anleitung.",
  "showcase.threeD.heading": "Der 3D-Viewport",
  "showcase.threeD.body":
    "Physikalisch basierte Materialien unter drei Lichtern, in ein lineares HDR-Ziel gerendert und als 2D-Ebene zusammengesetzt. Eine Scroll-Ansicht beschneidet ihn wie jedes andere Widget. Ziehen zum Kreisen, scrollen zum Zoomen.",

  "showcase.editor.heading": "Der Theme-Editor, als Widget zum Mitliefern",
  "showcase.editor.body":
    "Eine Palette sind Daten, also ist ihre Bearbeitung ein Bildschirm; dieser ist ein Modul, das Ihre Anwendung einbetten kann, kein Werkzeug, das in unserem Repository lebt. Ziehen Sie den Ecken-Regler, und das Fenster kleidet sich im selben Bild neu ein: jedes Feld, jeder Knopf, jedes Farbfeld. Der Bericht daneben misst jede Tinte gegen jede Fläche, auf der sie landen kann, sodass eine Palette mit zu wenig Kontrast sichtbar scheitert.",
  "showcase.density.heading": "Jede Größenstufe",
  "showcase.density.body":
    "Dieselben fünf Bedienelemente, fünfmal, von XSMALL oben bis XLARGE unten. Keinem davon wird eine Breite, eine Schrift oder ein Innenabstand gegeben: jede Zeile bekommt eine Steuergröße und sonst nichts, und Innenabstand, Typografie, Eckradien und Trefferflächen bewegen sich gemeinsam.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn: Lizenz",
  "licence.description":
    "Apache-2.0, unter welchen Lizenzen die mitgelieferten Komponenten stehen und eine ehrliche Darstellung der FFmpeg-Lage.",
  "licence.eyebrow": "Bedingungen",
  "licence.heading": "Lizenz",
  "licence.lede":
    "Apache License 2.0, einschließlich einer ausdrücklichen Patentgewährung. Kommerzielle Nutzung, Änderung und Weitergabe sind allesamt erlaubt.",
  "licence.core.heading": "Das Toolkit selbst",
  "licence.core.body":
    "<code>limn-toolkit</code> und <code>limn-components</code> haben außer dem JDK keine Abhängigkeiten, für diese beiden ist Apache-2.0 also die ganze Geschichte. Das Render-Backend fügt LWJGL hinzu, das unter BSD-3-Clause steht.",
  "licence.fonts.heading": "Schriften",
  "licence.fonts.body":
    "Roboto und die Noto-Fallback-Schriften stehen unter der SIL Open Font License. Jede mitgelieferte Komponente ist mit ihrer Lizenz in der NOTICE-Datei des Projekts aufgeführt.",
  "licence.mp3.heading": "MP3-Decodierung ist LGPL",
  "licence.mp3.body":
    "Die MP3-Unterstützung stammt von JLayer, das unter LGPL-2.1 steht und als isoliertes Jar hinter der Audio-Decoder-Schnittstelle gehalten wird. Schließen Sie diese eine Abhängigkeit aus, wenn Ihre Distribution LGPL-Pflichten vermeiden muss; WAV und Ogg Vorbis funktionieren weiter.",
  "licence.ffmpeg.heading": "FFmpeg-Video, und was damit ausgeliefert wird",
  "licence.ffmpeg.body":
    "Der optionale H.264-Decoder bindet ein reduziertes FFmpeg dynamisch ein, gebaut als LGPL-2.1-oder-später. <b>Seine nativen Bibliotheken liegen im veröffentlichten Jar, für alle Desktop-Ziele</b>. Eine Distribution, die <code>limn-video-ffmpeg</code> enthält, verteilt also FFmpeg, und das Jar führt den Lizenztext und den erforderlichen Hinweis mit. Sie sind dynamisch gebunden und austauschbar, genau das verlangt diese Lizenz. Nichts sonst hängt von diesem Modul ab: lassen Sie es weg, und jedes andere Medienformat funktioniert weiter.",
  "licence.notAdvice":
    "Nichts davon ist Rechtsberatung. Lesen Sie die Lizenzen und fragen Sie Ihren eigenen Anwalt.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn: Datenschutz",
  "privacy.description":
    "Was diese Website speichert, was nicht, und wie Sie Ihre Auswahl ändern. Keine Cookies, keine Analyse, keine Anfragen an Dritte.",
  "privacy.eyebrow": "Datenschutz",
  "privacy.heading": "Was diese Website speichert",
  "privacy.lede":
    "Kurzfassung: keine Cookies, keine Analyse, keine Anfragen an Dritte und nichts, was Sie identifiziert. Die Langfassung steht unten, denn eine Kurzfassung ist nur dann lesenswert, wenn die Langfassung ihr zustimmt.",
  "privacy.storage.heading": "Drei Werte, in Ihrem Browser",
  "privacy.storage.body":
    "Das gewählte Design wird unter <code>starlight-theme</code> gespeichert, die gewählte Sprache unter <code>limn-language</code>, Ihre Antwort auf die Datenschutzabfrage unter <code>limn-consent</code>. Alle drei liegen im lokalen Speicher dieses Browsers, alle drei werden nur von den eigenen Skripten dieser Website gelesen, und das Löschen der Websitedaten entfernt sie. Alles hier funktioniert auch, wenn alle drei fehlen.",
  "privacy.language.heading": "Wie Ihre Sprache gewählt wird",
  "privacy.language.body":
    "Auf einer englischen Seite liest die Website die Sprachen, die Ihr Browser ohnehin jeder Website mitteilt, und schickt Sie zu einer davon, sofern sie hier veröffentlicht ist. Diese Liste wird einmal gelesen, in Ihrem Browser, um eine Adresse zu wählen: sie wird weder gespeichert noch übertragen. Erst die Auswahl im Kopfbereich hält eine Entscheidung fest, und von da an gilt sie statt der Browserliste. Von einer übersetzten Adresse werden Sie nie weggeleitet; ein Link, den Ihnen jemand schickt, öffnet in der Sprache, in der er geschickt wurde.",
  "privacy.cookies.heading": "Keine Cookies",
  "privacy.cookies.body":
    "Die Website setzt keinerlei Cookies, also wird an keine Anfrage etwas angehängt und nichts folgt Ihnen auf eine andere Website. Lokaler Speicher ist kein Cookie: er wird nie übertragen, und ein Server kann ihn nicht anfordern.",
  "privacy.analytics.heading": "Keine Analyse, und trotzdem ein Schalter",
  "privacy.analytics.body":
    "Es gibt kein Analysewerkzeug, keinen Tag-Manager und kein Zählpixel. Der Mess-Schalter in der Datenschutzabfrage ist standardmäßig aus und kontrolliert alles, was jemals hinzukommen könnte: ein Skript dieser Kategorie wird inaktiv ausgeliefert und erst dann in ein laufendes Skript verwandelt, wenn Sie es erlauben.",
  "privacy.thirdParty.heading": "Nichts von anderswo geladen",
  "privacy.thirdParty.body":
    "Jede Schrift, jedes Bild, jedes Stylesheet und jedes Skript kommt von dieser Domain. Kein Webfont-Dienst, kein CDN, kein eingebettetes Video und kein Social-Widget. Deshalb spricht das Lesen einer Seite hier mit genau einem Server.",
  "privacy.hosting.heading": "Was der Hoster sieht",
  "privacy.hosting.body":
    "Die Seiten sind statische Dateien bei einem Hosting-Dienst. Wie jeder Webserver sieht er die Anfrage selbst (eine IP-Adresse, die angeforderte Seite, den User Agent des Browsers), und dessen eigene Protokollierungsrichtlinie regelt das. Das Projekt betreibt keinen Server, kein Kontosystem und keine Datenbank und erhält nichts davon.",
  "privacy.change.heading": "Ihre Antwort ändern",
  "privacy.change.body":
    "Ihre Auswahl lässt sich jederzeit ändern und wirkt sofort. Derselbe Link steht in der Fußzeile jeder Seite.",
  "privacy.change.action": "Datenschutzeinstellungen ändern",
  "privacy.noScript":
    "Diese Schaltfläche braucht JavaScript. Ist Scripting aus, läuft ohnehin nichts Optionales, und das Löschen der Daten dieser Website in Ihrem Browser entfernt alle drei gespeicherten Werte.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn: Seite nicht gefunden",
  "notFound.eyebrow": "404",
  "notFound.heading": "Diese Seite gibt es nicht",
  "notFound.body":
    "Der Link ist vielleicht veraltet, oder die Seite ist umgezogen. Alles, was die Website hat, ist eines davon.",
  "notFound.home": "Zur Startseite",
  "notFound.destinationsLabel": "Wohin stattdessen",
  "notFound.components.heading": "Komponenten",
  "notFound.components.body":
    "Jedes Widget, gerendert, mit dem Code, der das jeweilige Bild erzeugt hat.",
  "notFound.showcase.heading": "Oberflächen",
  "notFound.showcase.body":
    "Ganze Oberflächen, die das Toolkit beim Bau dieser Website gerendert hat.",
  "notFound.docs.heading": "Dokumentation",
  "notFound.docs.body":
    "Installation, Layout, Formulare, Design und Auslieferung: das Handbuch.",
  "notFound.api.heading": "API-Referenz",
  "notFound.api.body": "Jede Klasse und jede Methode, aus dem Quelltext erzeugt.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn: Diese Seite ist umgezogen",
  "moved.eyebrow": "Umgezogen",
  "moved.heading": "Diese Seite hat ein neues Zuhause",
  "moved.body": "Der Einstieg ist jetzt Teil des Handbuchs. Sie werden dorthin gebracht …",
  "moved.link": "Zur Installationsanleitung",
};
