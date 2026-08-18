/**
 * French.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const fr: Catalog = {
  "site.name": "Limn",
  "site.tagline": "Une boîte à outils d’interface pour Java sur le bureau.",

  "nav.primaryLabel": "Site",
  "nav.menu": "Menu",
  "nav.components": "Composants",
  "nav.showcase": "Écrans",
  "nav.docs": "Guide",
  "nav.api": "API",
  "nav.licence": "Licence",
  "nav.privacy": "Confidentialité",
  "nav.repository": "GitHub",
  "nav.skipToContent": "Aller au contenu",
  "footer.linksLabel": "Liens du projet",

  "codeBlock.copy": "Copier",
  "codeBlock.copied": "Copié dans le presse-papiers",

  "theme.label": "Thème",
  "theme.system": "Auto",
  "theme.light": "Clair",
  "theme.dark": "Sombre",

  "language.label": "Langue",

  "consent.label": "Choix de confidentialité",
  "consent.title": "Aucun cookie tant que vous n’autorisez pas la mesure",
  "consent.body":
    "Trois choses sont conservées dans ce navigateur et nulle part ailleurs : le thème que vous choisissez, la langue que vous choisissez et la réponse que vous donnez ici. Tout ce qui est facultatif reste désactivé jusqu’à ce que vous l’activiez.",
  "consent.more": "Ce qui est conservé, en détail",
  "consent.accept": "Tout autoriser",
  "consent.reject": "Uniquement le nécessaire",
  "consent.choose": "Choisir",
  "consent.save": "Enregistrer",
  "consent.alwaysOn": "Toujours actif",
  "consent.necessaryName": "Strictement nécessaire",
  "consent.necessaryBody":
    "Le thème clair ou sombre que vous choisissez, la langue que vous choisissez, et cette réponse. Les trois restent locaux à ce navigateur, aucun n’est un cookie, et aucun ne quitte la machine.",
  "consent.analyticsName": "Mesure d’audience",
  "consent.analyticsBody":
    "Google Analytics, chargé depuis googletagmanager.com. Il dépose ses propres cookies et indique au projet quelles pages sont lues. Il est livré bloqué et ne s’exécute qu’une fois que vous l’autorisez ici.",

  // ------------------------------------------------------------------- home
  "home.title": "Limn : une boîte à outils d’interface pour Java sur le bureau",
  "home.description":
    "Créez des applications de bureau en Java avec vos propres widgets, mise en page, texte, graphiques, médias et 3D. Une dépendance, JDK 17, Windows, macOS et Linux. Apache-2.0.",

  "home.hero.eyebrow": "Interface de bureau pour Java",
  "home.hero.headline": "Des applications de bureau en Java, dessinées de zéro.",
  "home.hero.sub":
    "Limn dessine ses propres pixels. Widgets, mise en page, texte, graphiques, médias et une vue 3D, en une dépendance, sans Swing, sans JavaFX et sans boîte à outils native en dessous.",
  "home.hero.cta": "Commencer",
  "home.hero.secondary": "Parcourir les composants",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "L’application de démonstration, rendue par Limn pendant cette compilation.",

  "home.install.eyebrow": "Cinq minutes",
  "home.install.heading": "Une dépendance et une méthode main",
  "home.install.body":
    "Pas de langage de balisage, pas de processeur d’annotations, pas de greffon de build. Ajoutez le backend, qui apporte la boîte à outils avec lui, écrivez du Java ordinaire, et vous avez une fenêtre.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "Sur macOS, la JVM a besoin de <code>-XstartOnFirstThread</code>. C’est la seule particularité de plateforme que vous rencontrerez dès le premier jour : elle est donc ici, et non à trois clics de profondeur.",
  "home.install.more": "Lire le guide d’installation",

  "home.features.eyebrow": "Ce que vous obtenez",

  "home.features.components.heading": "Un ensemble de composants que vous n’avez pas à écrire",
  "home.features.components.body":
    "Boutons, champs, listes, onglets, menus, boîtes de dialogue, panneaux divisés, un sélecteur de couleur, des graphiques en barres, en courbes et en anneau, et une liste virtualisée où un million de lignes coûte autant que vingt. Chacun lit sa couleur, sa forme et sa densité dans le thème : rien n’est figé dans le code, et un mode compact tient en une ligne.",
  "home.features.components.link": "Les voir tous",

  "home.features.layout.heading": "Une mise en page qui tient dans la tête",
  "home.features.layout.body":
    "Quatre widgets et un marqueur : une colonne empile, une rangée répartit, une pile superpose, la marge intérieure retrait, et Expanded désigne qui prend l’espace restant. C’est tout le vocabulaire ; aucun solveur de contraintes à configurer, aucun gestionnaire de mise en page à installer.",
  "home.features.layout.link": "Lire le guide de mise en page",
  "home.features.layout.caption":
    "Une fenêtre faite d’une colonne, d’une rangée, d’un séparateur et d’un Expanded.",

  "home.features.forms.heading": "Des formulaires sans framework",
  "home.features.forms.body":
    "Un champ est un widget, une règle de validation est un écouteur, et soumettre est un appel de méthode. Rien à lier, rien à enregistrer, et des états de validation qui recolorent un champ à l’instant où l’utilisateur le corrige.",
  "home.features.forms.link": "Lire le guide des formulaires",
  "home.features.forms.caption": "Libellés, validation, un choix et la rangée d’actions.",

  "home.features.media.heading": "La vidéo et la 3D sont aussi des widgets",
  "home.features.media.body":
    "Une vue 3D à matériaux physiquement réalistes et un lecteur vidéo, composés comme des widgets ordinaires : une vue défilante les rogne, une pile dessine par-dessus, et ils participent à la mise en page comme un libellé.",
  "home.features.media.link": "Lire le guide des médias",
  "home.features.media.caption": "La vue 3D, intégrée à une fenêtre ordinaire.",

  "home.themes.heading": "Votre identité, pas celle du toolkit",
  "home.themes.body":
    "Votre application doit ressembler à votre produit, pas à la bibliothèque qui l'a produite. Un thème est une donnée brute (chaque couleur, le rayon des angles, le pas de taille dont hérite chaque contrôle), et un appel le remplace à l'exécution. Chargez la police de votre marque depuis un fichier qui vous appartient : il ne reste rien de l'apparence du toolkit.",
  "home.themes.link": "Comment fonctionne la thématisation",
  "home.themes.caption":
    "Un écran, sept thèmes. Le code derrière chaque bande est identique.",
  "home.themes.alt":
    "Le même écran dense de contrôles rendu sept fois côte à côte, chaque bande dans une palette, un pas de taille et une police différents.",

  "home.languages.heading": "Dans les langues de vos utilisateurs",
  "home.languages.body":
    "Le texte est mesuré avec les mêmes avances que celles de son tracé, et le repli de police se fait caractère par caractère. Latin, grec, cyrillique et CJC se mélangent donc dans une même chaîne sans que vous choisissiez de fonte. Les méthodes de saisie composent dans le champ, et l’édition avance par groupe de graphèmes : les signes combinants et les émojis en plusieurs parties ne sont jamais coupés.",
  "home.languages.alt":
    "Le même écran capturé en japonais, chinois simplifié, coréen et russe, assemblé en une seule fenêtre.",
  "home.languages.link": "Lire le guide du texte",
  "home.languages.caption":
    "Le même écran, capturé en quatre langues pendant cette compilation.",

  "home.limits.eyebrow": "Avant de vous engager",
  "home.limits.heading": "Ce que Limn ne fait pas",
  "home.limits.body":
    "Toute boîte à outils échange quelque chose. Voici ces échanges, annoncés d’emblée, parce que les découvrir la troisième semaine est pire que les lire maintenant.",
  "home.limits.scripts.heading": "Pas de rendu des écritures complexes",
  "home.limits.scripts.body":
    "L’arabe, l’hébreu et les écritures indiennes demandent des liaisons contextuelles et des réordonnancements que la couche de texte n’implémente pas, et il n’existe pas de sens de mise en page de droite à gauche. Les traductions dans ces langues ne sont délibérément pas publiées, plutôt que d’être mal dessinées.",
  "home.limits.a11y.heading": "Pas de pont vers les lecteurs d’écran",
  "home.limits.a11y.body":
    "La navigation au clavier et les anneaux de focus sont complets, mais rien n’est exposé aux API d’accessibilité de la plateforme. Si un lecteur d’écran doit fonctionner, ce n’est pas encore la bonne boîte à outils pour cette application.",
  "home.limits.version.heading": "Avant la 1.0",
  "home.limits.version.body":
    "L’API bouge encore d’une version à l’autre, et OpenGL est le seul chemin de rendu. Figez votre version et lisez les notes de publication.",

  "home.closing.heading": "Une fenêtre à l’écran en cinq minutes",
  "home.closing.body":
    "Le guide d’installation se termine par un programme qui tourne. Tout le reste, c’est le guide, la galerie de composants et la référence de l’API.",

  // ------------------------------------------------------------- components
  "components.title": "Limn : Composants",
  "components.description":
    "Chaque composant de Limn, rendu par la boîte à outils elle-même dans les deux palettes, à côté du code qui a produit chaque image.",
  "components.eyebrow": "L’ensemble",
  "components.heading": "Composants",
  "components.lede":
    "Chaque image ici a été rendue par la boîte à outils pendant cette compilation, et chaque extrait est le code qui a produit l’image à côté.",
  "components.filterLabel": "Filtrer les composants",
  "components.filterPlaceholder": "Filtrer…",
  "components.empty": "Rien ne correspond.",
  "components.showCode": "Code",
  "components.play": "Lire",
  "components.stop": "Arrêter",
  "components.videoNote":
    "La vue vidéo utilise la source de test en Java pur : elle montre donc le widget en fonctionnement, et non la couverture des codecs. Aucun décodeur natif n’intervient dans cette image.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn : Écrans",
  "showcase.description":
    "Des écrans entiers rendus par la boîte à outils : l’application de démonstration, la vue 3D, un formulaire, une fenêtre mise en page et le même écran en quatre langues.",
  "showcase.eyebrow": "Écrans entiers",
  "showcase.heading": "Écrans",
  "showcase.lede":
    "Ni recadrages ni maquettes. Chacun d’eux est une fenêtre que la boîte à outils a rendue pendant la construction de ce site.",
  "showcase.kitchen.heading": "L’application de démonstration",
  "showcase.kitchen.body":
    "Tous les composants dans une fenêtre, avec barre de menus, onglets, sélecteur de thème et un pied de page de performance en direct.",
  "showcase.forms.heading": "Un formulaire",
  "showcase.forms.body":
    "Libellés, un champ validé, un choix, un interrupteur et la rangée d’actions : l’exemple complet du guide des formulaires.",
  "showcase.layout.heading": "Une fenêtre mise en page",
  "showcase.layout.body":
    "Une barre d’outils, une barre latérale à côté d’un panneau de contenu et une ligne d’état : l’exemple complet du guide de mise en page.",
  "showcase.threeD.heading": "La vue 3D",
  "showcase.threeD.body":
    "Des matériaux physiquement réalistes sous trois lumières, rendus dans une cible linéaire à grande plage dynamique et composés comme une couche 2D. Une vue défilante la rogne comme n’importe quel autre widget. Faites glisser pour orbiter, faites défiler pour zoomer.",

  "showcase.editor.heading": "L'éditeur de thème, comme un widget que vous pouvez livrer",
  "showcase.editor.body":
    "Une palette est une donnée : l'éditer est donc un écran, et celui-ci est un module que votre application peut intégrer, pas un outil qui vit dans notre dépôt. Faites glisser le curseur des angles et la fenêtre se rhabille dans la même image : chaque champ, chaque bouton, chaque puits. Le rapport à côté mesure chaque encre contre toutes les surfaces où elle peut se poser, donc une palette qui échoue au contraste échoue visiblement.",
  "showcase.density.heading": "Chaque palier de taille",
  "showcase.density.body":
    "Les mêmes cinq contrôles, cinq fois, de XSMALL en haut à XLARGE en bas. Aucun ne reçoit de largeur, de police ni de marge intérieure : chaque rangée reçoit une taille de contrôle et rien d’autre, et les marges, la typographie, les rayons d’angle et les zones de contact bougent ensemble.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn : Licence",
  "licence.description":
    "Apache-2.0, sous quelles licences sont les composants embarqués, et un exposé honnête de la situation de FFmpeg.",
  "licence.eyebrow": "Conditions",
  "licence.heading": "Licence",
  "licence.lede":
    "Apache License 2.0, avec une concession de brevets explicite. Usage commercial, modification et redistribution sont tous permis.",
  "licence.core.heading": "La boîte à outils elle-même",
  "licence.core.body":
    "<code>limn-toolkit</code> n’a aucune dépendance en dehors du JDK : pour lui, Apache-2.0 est toute l’histoire. Le backend de rendu ajoute LWJGL, qui est en BSD-3-Clause.",
  "licence.fonts.heading": "Polices",
  "licence.fonts.body":
    "Roboto et les polices de repli Noto sont distribuées sous la SIL Open Font License. Chaque composant embarqué est listé avec sa licence dans le fichier NOTICE du projet.",
  "licence.mp3.heading": "Le décodage MP3 est en LGPL",
  "licence.mp3.body":
    "La prise en charge du MP3 vient de JLayer, en LGPL-2.1, gardé comme un jar isolé derrière l’interface de décodage audio. Excluez cette seule dépendance si votre distribution doit éviter les obligations LGPL ; WAV et Ogg Vorbis continuent de fonctionner.",
  "licence.ffmpeg.heading": "Vidéo FFmpeg, et ce qui est livré avec",
  "licence.ffmpeg.body":
    "Le décodeur H.264 optionnel lie dynamiquement un FFmpeg réduit, compilé en LGPL-2.1-ou-ultérieure. <b>Ses bibliothèques natives voyagent dans un classifier par cible de bureau, et dans <code>natives-all</code> pour un ensemble qui va partout</b>. Une distribution qui inclut <code>limn-video-ffmpeg</code> distribue donc FFmpeg, et le jar porte le texte de la licence et l'avis qu'elle exige. Elles sont liées dynamiquement et remplaçables, ce que cette licence demande précisément. Rien d'autre ne dépend de ce module : retirez-le et tous les autres formats continuent de fonctionner.",
  "licence.notAdvice":
    "Rien de tout cela n’est un avis juridique. Lisez les licences et consultez votre propre conseil.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn : Confidentialité",
  "privacy.description":
    "Ce que ce site conserve, ce qu’il ne conserve pas, et comment changer votre choix. Aucun cookie ni requête vers un tiers tant que vous n’autorisez pas la mesure, qui arrive désactivée.",
  "privacy.eyebrow": "Confidentialité",
  "privacy.heading": "Ce que ce site conserve",
  "privacy.lede":
    "Version courte : tant que la mesure est désactivée, et elle l’est à l’arrivée, il n’y a aucun cookie, aucune requête vers un tiers et rien qui vous identifie. Si vous l’autorisez, le site charge Google Analytics, et pas avant. La version longue est ci-dessous, car une version courte ne mérite d’être lue que si la longue lui donne raison.",
  "privacy.storage.heading": "Trois valeurs, dans votre navigateur",
  "privacy.storage.body":
    "Le thème que vous choisissez est conservé sous <code>starlight-theme</code>, la langue que vous choisissez sous <code>limn-language</code>, et votre réponse à la demande de confidentialité sous <code>limn-consent</code>. Les trois vivent dans le stockage local de ce navigateur, les trois ne sont lus que par les scripts de ce site, et effacer les données du site les supprime. Tout fonctionne ici en l’absence des trois.",
  "privacy.language.heading": "Comment votre langue est choisie",
  "privacy.language.body":
    "En arrivant sur une page en anglais, le site lit les langues que votre navigateur annonce déjà à tous les sites que vous visitez et, si l’une d’elles est publiée ici, vous envoie vers cette traduction. Cette liste est lue une fois, dans votre navigateur, pour choisir une adresse : elle n’est ni conservée ni transmise. C’est le choix d’une langue dans l’en-tête qui enregistre une décision, et elle prévaut ensuite sur la liste du navigateur. Une adresse traduite n’est jamais redirigée : un lien qu’on vous envoie s’ouvre dans la langue où il a été envoyé.",
  "privacy.cookies.heading": "Des cookies seulement si vous autorisez la mesure",
  "privacy.cookies.body":
    "Le site lui-même ne dépose aucun cookie : tant que la mesure est désactivée, rien n’est joint à une requête et rien ne vous suit sur un autre site. Si vous l’autorisez, Google Analytics dépose les siens, <code>_ga</code> et <code>_ga_…</code>. Le stockage local n’est pas un cookie : il n’est jamais transmis, et un serveur ne peut pas le demander.",
  "privacy.analytics.heading": "La mesure, désactivée tant que vous ne l’autorisez pas",
  "privacy.analytics.body":
    "Le site utilise Google Analytics, et uniquement avec votre autorisation. L’interrupteur de mesure dans la demande de confidentialité est désactivé par défaut, et cette désactivation est imposée plutôt que promise : la balise est livrée sous forme de bloc <code>text/plain</code>, qu’aucun navigateur n’exécute, et ne devient un script actif qu’au moment où vous l’autorisez, pas avant. Si vous retirez l’autorisation, elle n’est plus jamais chargée.",
  "privacy.thirdParty.heading": "Rien de chargé depuis ailleurs, tant que la mesure est désactivée",
  "privacy.thirdParty.body":
    "Chaque police, image, feuille de style et script vient de ce domaine. Aucun service de polices web, aucun CDN, aucune vidéo intégrée et aucun widget social : tant que la mesure est désactivée, lire une page ici ne contacte qu’un seul serveur. Si vous l’autorisez, la balise est en plus téléchargée depuis <code>googletagmanager.com</code>.",
  "privacy.hosting.heading": "Ce que l’hébergeur voit",
  "privacy.hosting.body":
    "Les pages sont des fichiers statiques chez un service d’hébergement. Comme tout serveur web, il voit la requête elle-même (une adresse IP, la page demandée, l’agent utilisateur du navigateur), et c’est sa propre politique de journalisation qui s’applique. Le projet n’exploite aucun serveur, aucun système de comptes ni base de données, et n’en reçoit rien.",
  "privacy.change.heading": "Changer votre réponse",
  "privacy.change.body":
    "Votre choix peut être modifié à tout moment, et il prend effet immédiatement. Le même lien figure dans le pied de page de chaque page.",
  "privacy.change.action": "Modifier mes choix de confidentialité",
  "privacy.noScript":
    "Ce bouton a besoin de JavaScript. Sans script, rien de facultatif ne s’exécute de toute façon, et effacer les données de ce site dans votre navigateur supprime les trois valeurs conservées.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn : Page introuvable",
  "notFound.eyebrow": "404",
  "notFound.heading": "Cette page n’existe pas",
  "notFound.body":
    "Le lien est peut-être périmé, ou la page a peut-être déménagé. Tout ce que le site possède est l’une de ces destinations.",
  "notFound.home": "Aller à la page d’accueil",
  "notFound.destinationsLabel": "Où aller à la place",
  "notFound.components.heading": "Composants",
  "notFound.components.body":
    "Chaque widget, rendu, avec le code qui a produit chaque image.",
  "notFound.showcase.heading": "Écrans",
  "notFound.showcase.body":
    "Des écrans entiers que la boîte à outils a rendus pendant la construction de ce site.",
  "notFound.docs.heading": "Documentation",
  "notFound.docs.body":
    "Installation, mise en page, formulaires, thèmes et livraison : le guide.",
  "notFound.api.heading": "Référence de l’API",
  "notFound.api.body": "Chaque classe et chaque méthode, générées depuis les sources.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn : cette page a déménagé",
  "moved.eyebrow": "Déplacée",
  "moved.heading": "Cette page a une nouvelle adresse",
  "moved.body": "Le démarrage fait désormais partie du guide. Redirection en cours…",
  "moved.link": "Aller au guide d’installation",
};
