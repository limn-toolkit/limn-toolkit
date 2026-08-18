<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Aplicações desktop em Java, desenhadas do zero.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-components"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-components?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Site</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Começar</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Componentes</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">Referência da API</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <b>Português (Brasil)</b> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Uma aplicação Limn: barra de menus, abas, formulários, gráficos e um seletor de tema" width="900">
  </picture>
</p>

O Limn desenha os próprios pixels. Widgets, layout, texto, gráficos, mídia e um viewport 3D, em
duas dependências, **sem Swing, sem JavaFX e sem toolkit nativo por baixo**.

## Instalação

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-components:0.2.0")
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:0.2.0")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-components</artifactId>
  <version>0.2.0</version>
</dependency>
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>0.2.0</version>
</dependency>
```

</details>

`limn-components` é o conjunto de widgets; `limn-backend-lwjgl` é a janela e o renderizador. O
backend traz os nativos da LWJGL para todas as plataformas desktop, então não há classifier a
escolher.

> [!IMPORTANT]
> No macOS a JVM precisa de `-XstartOnFirstThread`. É a única peculiaridade de plataforma que você
> encontra no primeiro dia, e é exclusiva do macOS — uma JVM em outro sistema que receba essa flag
> não inicia.

## Uma janela na tela

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

Sem linguagem de marcação, sem processador de anotações, sem plugin de build. Widgets são objetos
que você constrói.

## O que você ganha

**Um conjunto de componentes que você não precisa construir.** Botões, campos, listas, abas, menus,
diálogos, painéis divididos, um seletor de cor, gráficos de barra, linha e rosca, e uma lista
virtualizada em que um milhão de linhas custa o mesmo que vinte. Cada um deles lê a cor, a forma e a
densidade do tema.

**Um layout que cabe na cabeça.** Quatro widgets e um marcador: uma coluna empilha, uma linha
distribui, uma pilha sobrepõe, o padding recua e o `Expanded` diz quem fica com o espaço que sobrou.
Não há solver de restrições para configurar nem layout manager para instalar.

**A aparência do seu produto, não a do toolkit.** Um tema é dado puro — cada cor, o raio dos cantos,
o passo de tamanho que todo controle herda — e uma chamada troca tudo em tempo de execução.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="A mesma interface renderizada sob sete temas" width="900">
</p>

**Os idiomas dos seus usuários.** O texto é medido com os mesmos avanços com que é desenhado, e o
fallback de fonte roda caractere a caractere, então latino, grego, cirílico e CJK se misturam em uma
mesma string sem você escolher tipografia. Os métodos de entrada compõem dentro do campo, e a edição
anda por cluster de grafema.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="A mesma tela capturada em japonês, chinês simplificado, coreano e russo" width="900">
  </picture>
</p>

**Vídeo e 3D também são widgets.** Um viewport 3D com materiais fisicamente corretos e um player de
vídeo, compostos como widgets comuns: uma scroll view os recorta, uma pilha desenha por cima e ambos
participam do layout como um rótulo participa.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="O viewport 3D, composto em uma janela comum" width="900">
</p>

## Os módulos

| | |
| --- | --- |
| `limn-toolkit` | widgets, layout, o grafo de cena e as SPIs de backend; não depende de nada |
| `limn-components` | o conjunto de widgets |
| `limn-backend-lwjgl` | GLFW, OpenGL e stb por trás dessas SPIs |
| `limn-video` | decodificadores em Java puro: sem nativo, sem dependência de terceiros |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 e AAC/Opus/Vorbis via FFmpeg, nativos para seis alvos de desktop dentro do jar |
| `limn-icons-tabler` | o pacote de ícones Tabler, se você quiser |
| `limn-theme-editor` | a tela que cria um tema, embutível na sua aplicação |

## Antes de se comprometer com ele

Todo toolkit troca alguma coisa. Estas são as trocas, ditas de saída, porque descobri-las na
terceira semana é pior do que lê-las agora.

- **Sem shaping de escritas complexas.** Árabe, hebraico e as escritas índicas precisam de junção
  contextual e reordenação que a camada de texto não implementa, e não existe direção de layout da
  direita para a esquerda. As traduções para esses idiomas deliberadamente não são publicadas, em
  vez de serem desenhadas de forma errada.
- **Sem ponte para leitores de tela.** A navegação por teclado e os anéis de foco estão completos,
  mas nada é exposto às APIs de acessibilidade da plataforma.
- **Pré-1.0.** A API ainda se move entre releases, e OpenGL é o único caminho de renderização. Fixe
  a sua versão e leia as notas de release.

## Documentação

O [site](https://limn-toolkit.github.io/limn-toolkit) é a documentação: um
[guia de instalação](https://limn-toolkit.github.io/limn-toolkit/docs/install/) que termina com um
programa rodando, uma [galeria de componentes](https://limn-toolkit.github.io/limn-toolkit/components/)
em que cada imagem foi renderizada pelo toolkit durante aquele build, e a
[referência da API](https://limn-toolkit.github.io/limn-toolkit/api/) completa.

As decisões de design vivem em [`docs/adr/`](docs/adr/), e como um release é feito, em
[`RELEASING.md`](RELEASING.md).

## Compilar a partir do código-fonte

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

O JDK 17 é o alvo dos artefatos; o build em si roda no 21. Em uma máquina sem GPU, os testes
apoiados em GL são pulados em vez de falharem.

A reprodução de MP4 precisa de um payload nativo que **não** está neste repositório — um release o
compila para seis plataformas e o publica dentro do jar. Para tê-lo localmente,
`./scripts/build-ffmpeg.sh` compila um em cerca de um minuto, ou `./scripts/fetch-ffmpeg.sh` extrai
um do jar publicado.

## Licença

[Apache-2.0](LICENSE), com concessão explícita de patentes. Os componentes embarcados e suas
próprias licenças estão listados em [`NOTICE`](NOTICE); o decodificador FFmpeg é
LGPL-2.1-ou-posterior e carrega o texto da sua licença dentro do jar.
