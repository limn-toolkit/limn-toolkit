<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Java のデスクトップアプリを、ゼロから描く。</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">ウェブサイト</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">はじめる</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">コンポーネント</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API リファレンス</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <b>日本語</b> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="メニューバー、タブ、フォーム、チャート、テーマ切り替えを備えた Limn アプリケーション" width="900">
  </picture>
</p>

Limn はピクセルを自分で描きます。ウィジェット、レイアウト、テキスト、チャート、メディア、3D ビューポートが依存 1 つで手に入り、**Swing も JavaFX も、下敷きになるネイティブツールキットもありません**。

## 今すぐ試す

キッチンシンク——すべてのウィジェット、チャート、メディアプレーヤー、3D ビューポート——がコマンド 1 つで動きます。クローンするものはなく、入れるものも [jbang](https://www.jbang.dev/download/) だけです。JDK がなければ、それも取ってきます。

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

macOS では `--java-options=-XstartOnFirstThread` を足してください。このフラグは macOS 専用で、ほかの OS の JVM は受け取ると起動を拒みます。

## インストール

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

その 1 行だけでインストールは終わりです。`limn-backend-lwjgl` はウィンドウとレンダラーであり、`limn-toolkit`——ウィジェット、レイアウト、シーングラフ——を、それに依存するものへエクスポートします。バックエンドはすべてのデスクトッププラットフォーム向けの LWJGL のネイティブを同梱するので、選ぶべき classifier はありません。

> [!IMPORTANT]
> macOS では JVM に `-XstartOnFirstThread` が必要です。初日に必ず出会う唯一のプラットフォーム固有の癖であり、これは macOS だけの話です。ほかのプラットフォームの JVM にこのフラグを渡すと、起動しません。

### 動画の再生

`VideoView` は上の 1 行に入っていますし、その背後にある純 Java のデコーダーも同じです。それで再生できるのは Y4M と合成ソースで、MP4 と Matroska には FFmpeg が必要です。FFmpeg が別の依存になっているのは、ネイティブのペイロードと独自のライセンスを持つ、Limn で唯一の部分だからです。

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0:natives-macos-aarch64")
}
```

1 行目は Java と、すべてのプラットフォーム向けの JNI シムを持ってきます。2 行目は FFmpeg のライブラリを持ってきます。こちらは対象ごとに 1 つの classifier で公開されるので、1 台のマシンがダウンロードするのは 6 つすべてではなく 2 メガバイトほどで済みます。

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

1 つのビルドをすべてのプラットフォームへ配布し、どのマシンに届くか知りようがないときは、代わりに `limn-video-ffmpeg-natives-all` を使ってください。これは classifier ではなく独立した成果物で、六つすべてをあなたの代わりに名指しします。複数の classifier を並べても構いません——2 つの対象向けの配布物なら 2 つです。

```kotlin
runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:0.5.0")
```

classifier を書かないままでも、ツールキットはビルドも実行もできます。デコーダーは、探したプラットフォームの名を挙げて自身が利用できないことを報告し、FFmpeg でないものはすべてそのまま動きます。FFmpeg のビルドは LGPL-2.1-or-later で、動的リンクで差し替え可能であり、ライセンス本文をそれを収める jar の中に併せて運びます。

## 画面にウィンドウを

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

マークアップ言語も、アノテーションプロセッサーも、ビルドプラグインもありません。ウィジェットは、あなたが組み立てるオブジェクトです。

## 手に入るもの

**自分で作らなくていいコンポーネント一式。** ボタン、入力欄、リスト、タブ、メニュー、ダイアログ、分割ペイン、カラーピッカー、棒・折れ線・ドーナツのチャート、そして 100 万行でも 20 行と同じコストで済む仮想化リスト。どれも色・形・密度をテーマから読みます。

**頭に収まるレイアウト。** ウィジェット 4 つとマーカー 1 つ。列は積み、行は並べ、スタックは重ね、パディングは内側に寄せ、`Expanded` が残りの空間を誰が取るかを決めます。設定すべき制約ソルバーも、導入すべきレイアウトマネージャーもありません。

**あなたの製品の見た目に、ツールキットの見た目を持ち込まない。** テーマは単なるデータ——すべての色、角の丸み、各コントロールが継承するサイズ段階——で、実行中に一度の呼び出しで差し替わります。

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="七つのテーマで描画した同じ画面" width="900">
</p>

**ユーザーの言語。** テキストは描画に使うのと同じ送り幅で測られ、フォントフォールバックは 1 文字ずつ働きます。だからラテン、ギリシャ、キリル、CJK が 1 つの文字列に混ざっても書体を選ぶ必要がありません。入力メソッドは入力欄の中で変換し、編集は書記素クラスタ単位で動きます。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="同じ画面を日本語・簡体中国語・韓国語・ロシア語で撮影したもの" width="900">
  </picture>
</p>

**動画も 3D もウィジェット。** 物理ベースの 3D ビューポートと動画プレーヤーが、普通のウィジェットとして合成されます。スクロールビューが切り取り、スタックが上に描き、ラベルと同じようにレイアウトに参加します。

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="普通のウィンドウに合成された 3D ビューポート" width="900">
</p>

## 自分の見た目にする

色も角丸もサイズの刻みも、すべてテーマから来ます。`limn-theme-editor` はそれを書くための画面です。自分の設定画面に埋め込んでもいいですし、そのまま起動しても構いません。

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:0.5.0
```

macOS のフラグは上と同じです。保存されるのはただのデータで、アプリケーションは `ThemeFormat` で読み込みます。

## モジュール

| | |
| --- | --- |
| `limn-toolkit` | ウィジェット一式、レイアウト、シーングラフ、バックエンドの SPI、そして純 Java の動画デコーダー。何にも依存しません |
| `limn-backend-lwjgl` | その SPI の背後にある GLFW、OpenGL、stb |
| `limn-video-ffmpeg` | FFmpeg 経由の H.264/HEVC/VP9/VP8 と AAC/Opus/Vorbis。デスクトップ対象ごとに 1 つの classifier |
| `limn-icons-tabler` | 必要なら使える Tabler のアイコンパック |
| `limn-theme-editor` | テーマを作る画面。あなたのアプリケーションに組み込めます |

## 採用を決める前に

どんなツールキットにも引き換えにするものがあります。3 週目に気づくより今読むほうがよいので、最初に書いておきます。

- **複雑なスクリプトはどこでも描かれます。ただし鏡像化はしません。** アラビア文字、ヘブライ文字、デーヴァナーガリー、タイ文字は、テキストが描かれるところならどこでも結合し、並べ替わり、記号も正しい位置に付きます——`Label`、`TextField`、`TextArea` の中でも、その周りのボタン、タブ、メニュー項目、プレースホルダーでも同じです。`ar` と `he` の翻訳も配布します。右から左に読む言語が得られないのはレイアウトのほうです。余白も、揃えも、スクロールバーの位置も、ポップアップの開く側も、テキストフィールドの外で矢印キーがどちらへ進むかも、言語によらず左から右です。
- **スクリーンリーダーへの橋渡しなし。** キーボード操作とフォーカスリングは完成していますが、プラットフォームのアクセシビリティ API には何も公開していません。
- **1.0 より前。** API はリリース間でまだ動きますし、描画経路は OpenGL だけです。バージョンを固定して、リリースノートを読んでください。

## ドキュメント

[ウェブサイト](https://limn-toolkit.github.io/limn-toolkit)がドキュメントです。動くプログラムで終わる[インストールガイド](https://limn-toolkit.github.io/limn-toolkit/docs/install/)、そのビルド中にすべての画像をツールキット自身が描画した[コンポーネントギャラリー](https://limn-toolkit.github.io/limn-toolkit/components/)、そして完全な [API リファレンス](https://limn-toolkit.github.io/limn-toolkit/api/)。

設計上の判断は [`docs/adr/`](docs/adr/) に、リリースの作り方は [`RELEASING.md`](RELEASING.md) にあります。

## ソースからビルドする

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

成果物が対象とするのは JDK 17 で、ビルド自体は 21 で動きます。GPU のないマシンでは、GL を使うテストは失敗ではなくスキップされます。

MP4 の再生には、このリポジトリに**含まれていない**ネイティブのペイロードが必要です。リリースはそれを 6 つのプラットフォーム向けにビルドし、それぞれを 1 つの classifier として公開します。手元に用意するなら、`./scripts/build-ffmpeg.sh` が 1 分ほどで 1 つビルドし、`./scripts/fetch-ffmpeg.sh` が公開済みの jar から 1 つ取り出します。

## ライセンス

[Apache-2.0](LICENSE)。明示的な特許許諾を含みます。同梱コンポーネントとそれぞれのライセンスは [`NOTICE`](NOTICE) に記載しています。FFmpeg のデコーダーは LGPL-2.1-or-later で、ライセンス本文を自身の jar の中に併せて運びます。
