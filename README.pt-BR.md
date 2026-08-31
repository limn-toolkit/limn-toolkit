<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Aplicações desktop em Java, desenhadas do zero.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
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
uma dependência, **sem Swing, sem JavaFX e sem toolkit nativo por baixo**.

## Teste agora

A vitrine inteira — todos os widgets, os gráficos, o player de mídia, o viewport 3D — em um
comando. Nada para clonar, e nada para instalar além do
[jbang](https://www.jbang.dev/download/), que também baixa uma JDK se você não tiver nenhuma:

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

No macOS, acrescente `--java-options=-XstartOnFirstThread`. Essa flag é exclusiva do macOS, e uma
JVM que a receba em qualquer outro sistema se recusa a iniciar.

## Instalação

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

Essa única linha é a instalação inteira. `limn-backend-lwjgl` é a janela e o renderizador, e ele
exporta `limn-toolkit` — os widgets, o layout e o grafo de cena — para quem depender dele. O
backend traz os nativos da LWJGL para todas as plataformas desktop, então não há classifier a
escolher.

> [!IMPORTANT]
> No macOS a JVM precisa de `-XstartOnFirstThread`. É a única peculiaridade de plataforma que você
> encontra no primeiro dia, e é exclusiva do macOS — uma JVM em outro sistema que receba essa flag
> não inicia.

### Reproduzir vídeo

O `VideoView` está na linha acima, e os decodificadores em Java puro por trás dele também. O que
isso reproduz é Y4M e uma fonte sintética; MP4 e Matroska precisam do FFmpeg, que é uma dependência
separada porque é a única peça do Limn com um payload nativo e uma licença própria.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0:natives-macos-aarch64")
}
```

A primeira linha traz o Java e o shim JNI para todas as plataformas. A segunda traz as bibliotecas
do FFmpeg, que são publicadas com um classifier por alvo, então uma máquina baixa cerca de dois
megabytes em vez dos seis conjuntos:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Use `limn-video-ffmpeg-natives-all` no lugar disso quando um único build é distribuído para todas as
plataformas e não tem como saber em que máquina vai cair: ele é um artefato próprio, e não um
classifier, e nomeia os seis para que você não precise. Nada impede que você nomeie vários
classifiers, também — um pacote para dois alvos leva dois.

Deixe o classifier de fora e o toolkit continua compilando e rodando: o decodificador se declara
indisponível, nomeando a plataforma que procurou, e tudo que não é FFmpeg segue funcionando. O build
do FFmpeg é LGPL-2.1-ou-posterior, ligado dinamicamente e substituível, e carrega o texto da sua
licença dentro do jar que o contém.

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

## Deixe com a sua cara

É de um tema que vem cada cor, cada raio de canto e cada passo de tamanho, e o
`limn-theme-editor` é a tela que escreve um. Embuta na sua própria tela de preferências, ou
simplesmente rode:

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:0.5.0
```

Mesma flag do macOS de cima. O que ele salva é dado puro, que a sua aplicação carrega com
`ThemeFormat`.

## Os módulos

| | |
| --- | --- |
| `limn-toolkit` | o conjunto de widgets, o layout, o grafo de cena, as SPIs de backend e os decodificadores de vídeo em Java puro; não depende de nada |
| `limn-backend-lwjgl` | GLFW, OpenGL e stb por trás dessas SPIs |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 e AAC/Opus/Vorbis via FFmpeg; um classifier por alvo de desktop |
| `limn-icons-tabler` | o pacote de ícones Tabler, se você quiser |
| `limn-theme-editor` | a tela que cria um tema, embutível na sua aplicação |

## Antes de se comprometer com ele

Todo toolkit troca alguma coisa. Estas são as trocas, ditas de saída, porque descobri-las na
terceira semana é pior do que lê-las agora.

- **As escritas complexas são desenhadas em todo lugar, mas nada é espelhado.** O árabe, o
  hebraico, o devanágari e o tailandês se unem, se reordenam e posicionam suas marcas onde quer que
  haja texto: dentro de `Label`, `TextField` e `TextArea`, e em cada botão, aba, item de menu e
  texto de espera ao redor deles; os pacotes `ar` e `he` são publicados. O que um idioma da direita
  para a esquerda não ganha é o leiaute: recuos, alinhamento, o lado em que fica uma barra de
  rolagem, o lado em que um menu suspenso abre e o lado para onde uma tecla de seta leva quando o
  foco não está em um campo de texto — tudo vai da esquerda para a direita, seja qual for o idioma.
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
compila para seis plataformas e publica um classifier para cada. Para tê-lo localmente,
`./scripts/build-ffmpeg.sh` compila um em cerca de um minuto, ou `./scripts/fetch-ffmpeg.sh` extrai
um do jar publicado.

## Licença

[Apache-2.0](LICENSE), com concessão explícita de patentes. Os componentes embarcados e suas
próprias licenças estão listados em [`NOTICE`](NOTICE); o decodificador FFmpeg é
LGPL-2.1-ou-posterior e carrega o texto da sua licença dentro do jar.
