/**
 * Spanish.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const es: Catalog = {
  "site.name": "Limn",
  "site.tagline": "Un kit de interfaz para Java en el escritorio.",

  "nav.primaryLabel": "Sitio",
  "nav.menu": "Menú",
  "nav.components": "Componentes",
  "nav.showcase": "Pantallas",
  "nav.docs": "Guía",
  "nav.api": "API",
  "nav.licence": "Licencia",
  "nav.privacy": "Privacidad",
  "nav.repository": "GitHub",
  "nav.skipToContent": "Ir al contenido",
  "footer.linksLabel": "Enlaces del proyecto",

  "codeBlock.copy": "Copiar",
  "codeBlock.copied": "Copiado al portapapeles",

  "theme.label": "Tema",
  "theme.system": "Automático",
  "theme.light": "Claro",
  "theme.dark": "Oscuro",

  "language.label": "Idioma",

  "consent.label": "Opciones de privacidad",
  "consent.title": "Sin cookies salvo que permitas la medición",
  "consent.body":
    "Solo se guardan tres cosas en este navegador y en ningún otro sitio: el tema que eliges, el idioma que eliges y la respuesta que das aquí. Todo lo opcional permanece desactivado hasta que tú lo actives.",
  "consent.more": "Qué se guarda, en detalle",
  "consent.accept": "Permitir todo",
  "consent.reject": "Solo lo necesario",
  "consent.choose": "Elegir",
  "consent.save": "Guardar opciones",
  "consent.alwaysOn": "Siempre activo",
  "consent.necessaryName": "Estrictamente necesario",
  "consent.necessaryBody":
    "El tema claro u oscuro que eliges, el idioma que eliges y esta respuesta. Los tres son locales a este navegador, ninguno es una cookie y ninguno sale de la máquina.",
  "consent.analyticsName": "Medición",
  "consent.analyticsBody":
    "Google Analytics, cargado desde googletagmanager.com. Establece sus propias cookies y le indica al proyecto qué páginas se leen. Se publica bloqueado y solo empieza a funcionar cuando lo permites aquí.",

  // ------------------------------------------------------------------- home
  "home.title": "Limn: un kit de interfaz para Java en el escritorio",
  "home.description":
    "Crea aplicaciones de escritorio en Java con tus propios widgets, disposición, texto, gráficos, medios y 3D. Dos dependencias, JDK 17, Windows, macOS y Linux. Apache-2.0.",

  "home.hero.eyebrow": "Interfaz de escritorio para Java",
  "home.hero.headline": "Aplicaciones de escritorio en Java, dibujadas desde cero.",
  "home.hero.sub":
    "Limn dibuja sus propios píxeles. Widgets, disposición, texto, gráficos, medios y una vista 3D, en dos dependencias, sin Swing, sin JavaFX y sin ningún kit nativo por debajo.",
  "home.hero.cta": "Empezar",
  "home.hero.secondary": "Ver los componentes",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "La aplicación de demostración, renderizada por Limn durante esta compilación.",

  "home.install.eyebrow": "Cinco minutos",
  "home.install.heading": "Dos dependencias y un método main",
  "home.install.body":
    "Sin lenguaje de marcado, sin procesador de anotaciones, sin complemento de compilación. Añade el kit y el backend, escribe Java normal y ya tienes una ventana.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "En macOS la JVM necesita <code>-XstartOnFirstThread</code>. Es la única peculiaridad de plataforma con la que te toparás el primer día, así que está aquí y no a tres clics de distancia.",
  "home.install.more": "Leer la guía de instalación",

  "home.features.eyebrow": "Lo que obtienes",

  "home.features.components.heading": "Un conjunto de componentes que no tienes que construir",
  "home.features.components.body":
    "Botones, campos, listas, pestañas, menús, diálogos, paneles divididos, un selector de color, gráficos de barras, de líneas y de anillo, y una lista virtualizada donde un millón de filas cuesta lo mismo que veinte. Cada uno lee su color, su forma y su densidad del tema, así que nada queda fijado en el código y un modo compacto es una línea.",
  "home.features.components.link": "Verlos todos",

  "home.features.layout.heading": "Una disposición que cabe en la cabeza",
  "home.features.layout.body":
    "Cuatro widgets y un marcador: una columna apila, una fila reparte, una pila superpone, el relleno separa y Expanded dice quién se queda con el espacio sobrante. Ese es todo el vocabulario; no hay solucionador de restricciones que configurar ni gestor de disposición que instalar.",
  "home.features.layout.link": "Leer la guía de disposición",
  "home.features.layout.caption":
    "Una ventana hecha de una columna, una fila, un divisor y un Expanded.",

  "home.features.forms.heading": "Formularios sin framework",
  "home.features.forms.body":
    "Un campo es un widget, una regla de validación es un oyente y enviar es una llamada a un método. Nada que enlazar, nada que registrar, y estados de validación que recolorean el campo en el momento en que el usuario lo corrige.",
  "home.features.forms.link": "Leer la guía de formularios",
  "home.features.forms.caption": "Etiquetas, validación, una elección y la fila de acciones.",

  "home.features.media.heading": "El vídeo y el 3D también son widgets",
  "home.features.media.body":
    "Una vista 3D con materiales físicamente realistas y un reproductor de vídeo, compuestos como widgets corrientes: una vista con desplazamiento los recorta, una pila dibuja encima y ambos participan en la disposición igual que una etiqueta.",
  "home.features.media.link": "Leer la guía de medios",
  "home.features.media.caption": "La vista 3D, compuesta en una ventana corriente.",

  "home.themes.heading": "Tu identidad, no la del toolkit",
  "home.themes.body":
    "Tu aplicación debe parecerse a tu producto, no a la biblioteca con la que se construyó. Un tema son datos puros (cada color, el radio de las esquinas, el paso de tamaño que hereda cada control), y una llamada lo cambia en tiempo de ejecución. Carga la tipografía de tu marca desde un archivo tuyo y no sobrevive nada del aspecto del toolkit.",
  "home.themes.link": "Cómo funcionan los temas",
  "home.themes.caption":
    "Una pantalla, siete temas. El código detrás de cada franja es idéntico.",
  "home.themes.alt":
    "La misma pantalla densa de controles renderizada siete veces en paralelo, cada franja con una paleta, un paso de tamaño y una tipografía distintos.",

  "home.languages.heading": "En los idiomas de tus usuarios",
  "home.languages.body":
    "El texto se mide con los mismos avances con los que se dibuja, y el respaldo de fuentes funciona carácter a carácter, así que latino, griego, cirílico y CJK se mezclan en una misma cadena sin que elijas tipografía. Los métodos de entrada componen dentro del campo y la edición avanza por grupos de grafemas, de modo que las marcas combinantes y los emojis de varias partes nunca se parten.",
  "home.languages.alt":
    "La misma pantalla capturada en japonés, chino simplificado, coreano y ruso, unida en una sola ventana.",
  "home.languages.link": "Lee la guía de texto",
  "home.languages.caption":
    "La misma pantalla, capturada en cuatro idiomas durante esta compilación.",

  "home.limits.eyebrow": "Antes de comprometerte",
  "home.limits.heading": "Lo que Limn no hace",
  "home.limits.body":
    "Todo kit renuncia a algo. Estas son las renuncias, dichas de entrada, porque descubrirlas en la tercera semana es peor que leerlas ahora.",
  "home.limits.scripts.heading": "Sin composición de escrituras complejas",
  "home.limits.scripts.body":
    "El árabe, el hebreo y las escrituras índicas requieren unión contextual y reordenación que la capa de texto no implementa, y no existe dirección de derecha a izquierda. Las traducciones a esos idiomas deliberadamente no se publican, en lugar de dibujarse mal.",
  "home.limits.a11y.heading": "Sin puente para lectores de pantalla",
  "home.limits.a11y.body":
    "La navegación con teclado y los anillos de foco están completos, pero nada se expone a las API de accesibilidad de la plataforma. Si un lector de pantalla tiene que funcionar, este todavía no es el kit para esa aplicación.",
  "home.limits.version.heading": "Anterior a 1.0",
  "home.limits.version.body":
    "La API todavía se mueve entre versiones, y OpenGL es la única vía de renderizado. Fija tu versión y lee las notas de publicación.",

  "home.closing.heading": "Una ventana en pantalla en cinco minutos",
  "home.closing.body":
    "La guía de instalación termina con un programa en marcha. Todo lo demás es la guía, la galería de componentes y la referencia de la API.",

  // ------------------------------------------------------------- components
  "components.title": "Limn: Componentes",
  "components.description":
    "Cada componente de Limn, renderizado por el propio kit en ambas paletas, junto al código que produjo cada imagen.",
  "components.eyebrow": "El conjunto",
  "components.heading": "Componentes",
  "components.lede":
    "Cada imagen de aquí la renderizó el kit durante esta compilación, y cada fragmento es el código que produjo la imagen de al lado.",
  "components.filterLabel": "Filtrar componentes",
  "components.filterPlaceholder": "Filtrar…",
  "components.empty": "No hay nada que coincida.",
  "components.showCode": "Código",
  "components.play": "Reproducir",
  "components.stop": "Detener",
  "components.videoNote":
    "La vista de vídeo usa la fuente de prueba en Java puro, así que muestra el widget funcionando y no la cobertura de códecs. En esta imagen no interviene ningún decodificador nativo.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn: Pantallas",
  "showcase.description":
    "Pantallas enteras renderizadas por el kit: la aplicación de demostración, la vista 3D, un formulario, una ventana compuesta y la misma pantalla en cuatro idiomas.",
  "showcase.eyebrow": "Pantallas enteras",
  "showcase.heading": "Pantallas",
  "showcase.lede":
    "Ni recortes ni maquetas. Cada una es una ventana que el kit renderizó mientras se construía este sitio.",
  "showcase.kitchen.heading": "La aplicación de demostración",
  "showcase.kitchen.body":
    "Todos los componentes en una ventana, con barra de menús, pestañas, selector de tema y un pie de página de rendimiento en vivo.",
  "showcase.forms.heading": "Un formulario",
  "showcase.forms.body":
    "Etiquetas, un campo validado, una elección, un interruptor y la fila de acciones: el ejemplo completo de la guía de formularios.",
  "showcase.layout.heading": "Una ventana compuesta",
  "showcase.layout.body":
    "Una barra de herramientas, una barra lateral junto a un panel de contenido y una línea de estado: el ejemplo completo de la guía de disposición.",
  "showcase.threeD.heading": "La vista 3D",
  "showcase.threeD.body":
    "Materiales físicamente realistas bajo tres luces, renderizados en un destino lineal de alto rango dinámico y compuestos como una capa 2D. Una vista con desplazamiento la recorta igual que a cualquier otro widget. Arrastra para orbitar, desplaza para acercar.",

  "showcase.editor.heading": "El editor de temas, como un widget que puedes distribuir",
  "showcase.editor.body":
    "Una paleta son datos, así que editarla es una pantalla; esta es un módulo que tu aplicación puede incorporar, no una herramienta que vive en nuestro repositorio. Arrastra el control de esquinas y la ventana se re-viste en el mismo fotograma: cada campo, botón y pocillo de la imagen. El informe contiguo mide cada tinta contra toda superficie donde puede caer, así que una paleta que falla en contraste falla a la vista.",
  "showcase.density.heading": "Todos los pasos de tamaño",
  "showcase.density.body":
    "Los mismos cinco controles, cinco veces, de XSMALL arriba a XLARGE abajo. A ninguno se le da ancho, tipografía ni relleno: cada fila recibe un tamaño de control y nada más, y el relleno, la tipografía, los radios de esquina y las zonas de pulsación se mueven juntos.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn: Licencia",
  "licence.description":
    "Apache-2.0, bajo qué licencias están los componentes incluidos y una exposición honesta de la situación de FFmpeg.",
  "licence.eyebrow": "Términos",
  "licence.heading": "Licencia",
  "licence.lede":
    "Apache License 2.0, con concesión explícita de patentes. El uso comercial, la modificación y la redistribución están todos permitidos.",
  "licence.core.heading": "El kit en sí",
  "licence.core.body":
    "<code>limn-toolkit</code> y <code>limn-components</code> no tienen dependencias más allá del JDK, así que para esos dos Apache-2.0 es toda la historia. El backend de renderizado añade LWJGL, que es BSD-3-Clause.",
  "licence.fonts.heading": "Tipografías",
  "licence.fonts.body":
    "Roboto y las tipografías de respaldo Noto se distribuyen bajo la SIL Open Font License. Cada componente incluido aparece con su licencia en el archivo NOTICE del proyecto.",
  "licence.mp3.heading": "La decodificación de MP3 es LGPL",
  "licence.mp3.body":
    "La compatibilidad con MP3 viene de JLayer, que es LGPL-2.1 y se mantiene como un jar aislado detrás de la interfaz de decodificación de audio. Excluye esa única dependencia si tu distribución necesita evitar obligaciones LGPL; WAV y Ogg Vorbis siguen funcionando.",
  "licence.ffmpeg.heading": "Vídeo con FFmpeg, y qué se distribuye con él",
  "licence.ffmpeg.body":
    "El decodificador H.264 opcional enlaza dinámicamente un FFmpeg reducido, compilado como LGPL-2.1-o-posterior. <b>Sus bibliotecas nativas viajan dentro del jar publicado, para todos los destinos de escritorio</b>, así que una distribución que incluya <code>limn-video-ffmpeg</code> está distribuyendo FFmpeg, y el jar lleva el texto de la licencia y el aviso que exige. Están enlazadas dinámicamente y son reemplazables, que es lo que esa licencia pide. Nada más depende de este módulo: déjalo fuera y todos los demás formatos siguen funcionando.",
  "licence.notAdvice":
    "Nada de esto es asesoramiento legal. Lee las licencias y consulta a tu propio abogado.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn: Privacidad",
  "privacy.description":
    "Qué guarda este sitio, qué no y cómo cambiar tu elección. Sin cookies ni peticiones a terceros mientras no permitas la medición, que llega desactivada.",
  "privacy.eyebrow": "Privacidad",
  "privacy.heading": "Qué guarda este sitio",
  "privacy.lede":
    "Versión corta: con la medición desactivada, que es como llega, no hay cookies, ni peticiones a terceros, ni nada que te identifique. Si la permites, el sitio carga Google Analytics, y solo entonces. La versión larga está debajo, porque una versión corta solo merece leerse si la larga le da la razón.",
  "privacy.storage.heading": "Tres valores, en tu navegador",
  "privacy.storage.body":
    "El tema que eliges se guarda en <code>starlight-theme</code>, el idioma que eliges en <code>limn-language</code>, y tu respuesta al aviso de privacidad en <code>limn-consent</code>. Los tres viven en el almacenamiento local de este navegador, los tres los lee únicamente el propio código de este sitio, y borrar los datos del sitio los elimina. Todo aquí funciona con los tres ausentes.",
  "privacy.language.heading": "Cómo se elige tu idioma",
  "privacy.language.body":
    "Al llegar a una página en inglés, el sitio lee los idiomas que tu navegador ya anuncia a todas las webs que visitas y, si alguno está publicado aquí, te lleva a esa traducción. Esa lista se lee una vez, en tu navegador, para elegir una dirección: no se guarda ni se transmite. Elegir un idioma en la cabecera es lo que registra una decisión, y a partir de ahí se usa en lugar de la lista del navegador. De una dirección traducida nunca se te redirige, así que un enlace que alguien te envíe se abre en el idioma en que te lo enviaron.",
  "privacy.cookies.heading": "Cookies solo si permites la medición",
  "privacy.cookies.body":
    "El sitio en sí no establece ninguna cookie: con la medición desactivada, no se adjunta nada a una petición y nada te sigue a otro sitio. Si la permites, Google Analytics establece las suyas, <code>_ga</code> y <code>_ga_…</code>. El almacenamiento local no es una cookie: nunca se transmite y un servidor no puede pedirlo.",
  "privacy.analytics.heading": "Medición, desactivada hasta que la permitas",
  "privacy.analytics.body":
    "El sitio usa Google Analytics, y solo con tu permiso. El interruptor de medición del aviso de privacidad viene desactivado, y ese apagado se impone en vez de prometerse: la etiqueta se publica como un bloque <code>text/plain</code>, que ningún navegador ejecuta, y se convierte en un script en ejecución en el momento en que lo permites, no antes. Si retiras el permiso, no vuelve a cargarse.",
  "privacy.thirdParty.heading": "Nada cargado desde otro lugar, hasta que permitas la medición",
  "privacy.thirdParty.body":
    "Cada tipografía, imagen, hoja de estilos y script viene de este dominio. Sin servicio de fuentes web, sin CDN, sin vídeo incrustado y sin widget social: con la medición desactivada, leer una página aquí habla con exactamente un servidor. Si la permites, la etiqueta se descarga además de <code>googletagmanager.com</code>.",
  "privacy.hosting.heading": "Qué ve el alojamiento",
  "privacy.hosting.body":
    "Las páginas son archivos estáticos en un servicio de alojamiento. Como cualquier servidor web, ve la petición en sí (una dirección IP, la página solicitada, el agente de usuario del navegador), y su propia política de registro rige eso. El proyecto no opera ningún servidor, ni sistema de cuentas ni base de datos, y no recibe nada de eso.",
  "privacy.change.heading": "Cambiar tu respuesta",
  "privacy.change.body":
    "Tu elección se puede cambiar en cualquier momento y surte efecto de inmediato. El mismo enlace está en el pie de página de todas las páginas.",
  "privacy.change.action": "Cambiar mis opciones de privacidad",
  "privacy.noScript":
    "Este botón necesita JavaScript. Con el scripting desactivado no se ejecuta nada opcional de todos modos, y borrar los datos de este sitio en tu navegador elimina los tres valores guardados.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn: Página no encontrada",
  "notFound.eyebrow": "404",
  "notFound.heading": "Esa página no existe",
  "notFound.body":
    "Puede que el enlace esté desactualizado o que la página se haya movido. Todo lo que tiene el sitio es una de estas.",
  "notFound.home": "Ir a la página de inicio",
  "notFound.destinationsLabel": "Adónde ir en su lugar",
  "notFound.components.heading": "Componentes",
  "notFound.components.body":
    "Cada widget, renderizado, con el código que produjo cada imagen.",
  "notFound.showcase.heading": "Pantallas",
  "notFound.showcase.body":
    "Pantallas enteras que el kit renderizó mientras se construía este sitio.",
  "notFound.docs.heading": "Documentación",
  "notFound.docs.body":
    "Instalación, disposición, formularios, temas y publicación: la guía.",
  "notFound.api.heading": "Referencia de la API",
  "notFound.api.body": "Cada clase y cada método, generados desde el código fuente.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn: esta página se movió",
  "moved.eyebrow": "Movida",
  "moved.heading": "Esta página tiene una dirección nueva",
  "moved.body": "Empezar ahora forma parte de la guía. Te llevamos allí…",
  "moved.link": "Ir a la guía de instalación",
};
