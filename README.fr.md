<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Des applications de bureau en Java, dessinées de zéro.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Site</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Commencer</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Composants</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">Référence de l’API</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <b>Français</b> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Une application Limn : barre de menus, onglets, formulaires, graphiques et sélecteur de thème" width="900">
  </picture>
</p>

Limn dessine ses propres pixels. Widgets, mise en page, texte, graphiques, médias et une vue 3D,
en une dépendance, **sans Swing, sans JavaFX et sans boîte à outils native en dessous**.

## Essayez tout de suite

La vitrine entière — chaque widget, les graphiques, le lecteur, la vue 3D — en une commande. Rien
à cloner, et rien à installer sinon
[jbang](https://www.jbang.dev/download/), qui télécharge aussi un JDK si vous n’en avez pas :

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

Sur macOS, ajoutez `--java-options=-XstartOnFirstThread`. Cette option n’existe que sur macOS, et
une JVM qui la reçoit ailleurs refuse de démarrer.

## Installation

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:0.5.0")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>0.5.0</version>
</dependency>
```

</details>

Cette seule ligne, c’est toute l’installation. `limn-backend-lwjgl` est la fenêtre et le moteur de
rendu, et il exporte `limn-toolkit` — les widgets, la mise en page et le graphe de scène — vers
tout ce qui en dépend. Le backend embarque les bibliothèques natives de LWJGL pour toutes les
plateformes de bureau : il n’y a donc aucun classifier à choisir.

> [!IMPORTANT]
> Sur macOS, la JVM a besoin de `-XstartOnFirstThread`. C’est la seule particularité de plateforme
> que vous rencontrerez dès le premier jour, et elle est propre à macOS — une JVM lancée ailleurs
> avec cette option ne démarrera pas.

### Lire de la vidéo

`VideoView` est dans la ligne ci-dessus, et les décodeurs en Java pur qui le font fonctionner
aussi. Ce que cela lit, c’est le Y4M et une source synthétique ; le MP4 et le Matroska demandent
FFmpeg, qui est une dépendance à part, parce que c’est la seule pièce de Limn avec une charge
native et une licence à elle.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0:natives-macos-aarch64")
}
```

La première ligne apporte le Java et la couche JNI pour toutes les plateformes. La seconde apporte
les bibliothèques FFmpeg, publiées à raison d’un classifier par cible, de sorte qu’une machine
télécharge environ deux mégaoctets plutôt que les six :

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Utilisez plutôt `limn-video-ffmpeg-natives-all` quand une seule compilation est livrée à toutes les
plateformes et ne peut pas savoir sur quelle machine elle atterrira : c’est un artefact à part
entière et non un classifier, et il nomme les six pour vous. Rien ne vous empêche non plus de
nommer plusieurs classifiers : une distribution pour deux cibles en prend deux.

Omettez le classifier et la boîte à outils compile et tourne quand même : le décodeur se déclare
indisponible, en nommant la plateforme qu’il a cherchée, et tout ce qui n’est pas FFmpeg continue
de fonctionner. Le FFmpeg compilé est en LGPL-2.1-ou-ultérieure, lié dynamiquement et remplaçable,
et porte le texte de sa licence dans le jar qui le contient.

## Une fenêtre à l’écran

```java
public static void main(String[] args) {
    try (Backend backend = new LwjglBackend()) {
        NativeWindow window = backend.createWindow(
                new WindowConfig("Hello, Limn", 480, 320, true, true));

        Column column = new Column();
        column.gap(12);
        column.add(new Label("A window, drawn by Limn."));
        column.add(new Button("Close").onAction(window::requestClose));

        Scene scene = new Scene(new Padding(Insets.all(24), column));
        scene.bind(window);

        backend.runEventLoop();
    }
}
```

Pas de langage de balisage, pas de processeur d’annotations, pas de greffon de build. Les widgets
sont des objets que vous construisez.

## Ce que vous obtenez

**Un ensemble de composants que vous n’avez pas à écrire.** Boutons, champs, listes, onglets,
menus, boîtes de dialogue, panneaux divisés, un sélecteur de couleur, des graphiques en barres, en
courbes et en anneau, et une liste virtualisée où un million de lignes coûte autant que vingt.
Chacun lit sa couleur, sa forme et sa densité dans le thème.

**Une mise en page qui tient dans la tête.** Quatre widgets et un marqueur : une colonne empile,
une rangée répartit, une pile superpose, la marge intérieure retrait, et `Expanded` désigne qui
prend l’espace restant. Aucun solveur de contraintes à configurer, aucun gestionnaire de mise en
page à installer.

**L’apparence de votre produit, pas celle du toolkit.** Un thème est une donnée brute — chaque
couleur, le rayon des angles, le pas de taille dont hérite chaque contrôle — et un appel le
remplace à l’exécution.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="La même interface rendue sous sept thèmes" width="900">
</p>

**Les langues de vos utilisateurs.** Le texte est mesuré avec les mêmes avances que celles de son
tracé, et le repli de police se fait caractère par caractère. Latin, grec, cyrillique et CJC se
mélangent donc dans une même chaîne sans que vous choisissiez de fonte. Les méthodes de saisie
composent dans le champ, et l’édition avance par groupe de graphèmes.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="Le même écran capturé en japonais, chinois simplifié, coréen et russe" width="900">
  </picture>
</p>

**La vidéo et la 3D sont aussi des widgets.** Une vue 3D à matériaux physiquement réalistes et un
lecteur vidéo, composés comme des widgets ordinaires : une vue défilante les rogne, une pile
dessine par-dessus, et ils participent à la mise en page comme un libellé.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="La vue 3D, intégrée à une fenêtre ordinaire" width="900">
</p>

## À votre image

C’est d’un thème que viennent chaque couleur, chaque rayon d’angle et chaque pas de taille, et
`limn-theme-editor` est l’écran qui en écrit un. Intégrez-le à votre propre écran de réglages, ou
lancez-le tel quel :

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:0.5.0
```

La même option macOS que ci-dessus. Ce qu’il enregistre est une donnée simple, que votre
application charge avec `ThemeFormat`.

## Les modules

| | |
| --- | --- |
| `limn-toolkit` | l’ensemble de widgets, la mise en page, le graphe de scène, les SPI des backends et les décodeurs vidéo en Java pur ; ne dépend de rien |
| `limn-backend-lwjgl` | GLFW, OpenGL et stb derrière ces SPI |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 et AAC/Opus/Vorbis via FFmpeg ; un classifier par cible de bureau |
| `limn-icons-tabler` | le jeu d’icônes Tabler, si vous le voulez |
| `limn-theme-editor` | l’écran qui compose un thème, intégrable dans votre application |

## Avant de vous engager

Toute boîte à outils échange quelque chose. Voici ces échanges, annoncés d’emblée, parce que les
découvrir la troisième semaine est pire que les lire maintenant.

- **Les écritures complexes sont dessinées partout, mais rien n’est mis en miroir.** L’arabe,
  l’hébreu, le devanagari et le thaï se lient, se réordonnent et placent leurs signes partout où du
  texte est dessiné : dans `Label`, `TextField` et `TextArea`, comme sur chaque bouton, onglet,
  entrée de menu et texte indicatif autour d’eux ; les paquets `ar` et `he` sont bien publiés. Ce
  qu’une langue de droite à gauche n’obtient pas, c’est la mise en page : marges intérieures,
  alignement, côté où se place une barre de défilement, côté d’ouverture d’une popup, sens d’une
  touche fléchée ailleurs que dans un champ de texte — tout va de gauche à droite, quelle que soit
  la langue.
- **Pas de pont vers les lecteurs d’écran.** La navigation au clavier et les anneaux de focus sont
  complets, mais rien n’est exposé aux API d’accessibilité de la plateforme.
- **Avant la 1.0.** L’API bouge encore d’une version à l’autre, et OpenGL est le seul chemin de
  rendu. Figez votre version et lisez les notes de publication.

## Documentation

Le [site](https://limn-toolkit.github.io/limn-toolkit) est la documentation : un
[guide d’installation](https://limn-toolkit.github.io/limn-toolkit/docs/install/) qui se termine
par un programme qui tourne, une
[galerie de composants](https://limn-toolkit.github.io/limn-toolkit/components/) où chaque image a
été rendue par la boîte à outils pendant cette compilation, et la
[référence complète de l’API](https://limn-toolkit.github.io/limn-toolkit/api/).

Les décisions de conception vivent dans [`docs/adr/`](docs/adr/), et la façon dont une version est
publiée dans [`RELEASING.md`](RELEASING.md).

## Compiler depuis les sources

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

JDK 17 est ce que visent les artefacts ; la compilation elle-même tourne sur 21. Sur une machine
sans GPU, les tests qui passent par GL sont ignorés plutôt que mis en échec.

La lecture MP4 a besoin d’une charge native qui n’est **pas** dans ce dépôt — une publication la
compile pour six plateformes et en publie un classifier pour chacune. Pour en avoir une localement,
`./scripts/build-ffmpeg.sh` en construit une en une minute environ, ou `./scripts/fetch-ffmpeg.sh`
en extrait une du jar publié.

## Licence

[Apache-2.0](LICENSE), avec une concession de brevets explicite. Les composants embarqués et leurs
propres licences sont listés dans [`NOTICE`](NOTICE) ; le décodeur FFmpeg est en
LGPL-2.1-ou-ultérieure et porte le texte de sa licence dans son jar.
