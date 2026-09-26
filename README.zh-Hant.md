<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>用 Java 寫桌面應用程式，像素自己畫。</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">網站</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">開始使用</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">元件</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API 參考</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <b>繁體中文</b> ·
  <a href="README.ar.md">العربية</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="一個 Limn 應用程式：選單列、分頁、表單、圖表與主題選擇器" width="900">
  </picture>
</p>

Limn 自己畫出每一個像素。元件、版面、文字、圖表、媒體與 3D 視埠，只要一個相依套件，**沒有 Swing，沒有 JavaFX，底下也沒有原生工具組**。

**用 Claude Code 公開地做出來：**程式碼由 Claude 撰寫，由人來掌握方向、做驗證。打造它的代價已經付過了，你不必再付一次。[Limn 是怎麼做出來的](#limn-是怎麼做出來的)

## 立刻試試

整個陳列——每一個元件、圖表、媒體播放器、3D 視埠——一道指令就跑起來，主題編輯器再一道。沒有什麼要複製，也沒有什麼要裝，除了 [jbang](https://www.jbang.dev/download/)；你要是沒有 JDK，它連 JDK 一起取來。兩者都以精簡構件的形式來自 Maven Central：到手的是工具組、字型，以及你這台機器的原生程式庫——另外五個平台的不會下載。

macOS 上：

```bash
jbang --java-options=-XstartOnFirstThread demo@limn-toolkit/limn-toolkit
jbang --java-options=-XstartOnFirstThread theme-editor@limn-toolkit/limn-toolkit
```

Linux 和 Windows 上：

```bash
jbang demo@limn-toolkit/limn-toolkit
jbang theme-editor@limn-toolkit/limn-toolkit
```

這個開關只有 macOS 認得，別的系統上的 JVM 收到它會拒絕啟動。這兩道指令跑的是最新發行版；想固定某個版本，就改寫座標——`io.github.limn-toolkit:limn-demo:x.y.z` 和 `io.github.limn-toolkit:limn-theme-editor:x.y.z`。沒有網路時，展示程式也作為一個檔案附在每次發行裡，所有平台的東西都在裡面：

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

JBang 只解析一次最新版本並保留下來：要取得更新的版本，請加上 `--fresh`，例如 `jbang --fresh demo@limn-toolkit/limn-toolkit`。

## 安裝

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:x.y.z")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>x.y.z</version>
</dependency>
```

</details>

`x.y.z` 是目前的發行版本——本頁頂端的 Maven Central 徽章顯示著它，而本頁上的每一個座標都採用同一個號碼。

這一行就是全部的安裝。`limn-backend-lwjgl` 是視窗與繪製器，而它會把 `limn-toolkit`——元件、版面與場景圖——匯出給任何相依於它的東西。後端把 LWJGL 在每個桌面平台的原生庫都帶了進來，所以沒有 classifier 要挑。

> [!IMPORTANT]
> 在 macOS 上，JVM 需要 `-XstartOnFirstThread`。這是你第一天就會遇到的唯一平台怪癖，而且僅限 macOS——在其他平台上把這個旗標交給 JVM，它不會啟動。

### 播放影片

`VideoView` 就在上面那一行裡，它背後那些純 Java 的解碼器也是。那樣播得動的是 Y4M 與一個合成來源；MP4 與 Matroska 需要 FFmpeg，而它是一個獨立的相依套件，因為它是 Limn 裡唯一帶著原生負載、也帶著自己一份授權的部分。

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:x.y.z")
    runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:9.0.2.0:natives-macos-aarch64")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-video-ffmpeg</artifactId>
  <version>x.y.z</version>
</dependency>
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-ffmpeg-natives</artifactId>
  <version>9.0.2.0</version>
  <classifier>natives-macos-aarch64</classifier>
  <scope>runtime</scope>
</dependency>
```

</details>

第一行帶來 Java 的部分，連同每個平台的 JNI 墊片。第二行帶來 FFmpeg 的原生庫——來自 `limn-ffmpeg-natives`，一個版本跟著 FFmpeg 而不是跟著工具組走的成品，所以 Limn 升級時它仍留在你的快取裡——每個目標各一個 classifier，所以一台機器下載的大約是兩 MB，而不是全部六份：

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

若同一份建置要送到每個平台、而且無從得知它會落在哪一台機器上，就改用 `limn-video-ffmpeg-natives-all`。它是獨立的一個 POM，版本跟著工具組走，按這次發布測試過的負載版本替你把六個都列好了。也沒有什麼攔著你一次列出好幾個 classifier——要涵蓋兩個目標的套件包，就寫兩個。

```kotlin
runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:x.y.z")
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-video-ffmpeg-natives-all</artifactId>
  <version>x.y.z</version>
  <type>pom</type>
  <scope>runtime</scope>
</dependency>
```

</details>

把 classifier 留空，工具組照樣建置、照樣執行：解碼器會回報自己不可用，並說出它找過的平台，而所有不屬於 FFmpeg 的東西都繼續運作。這份 FFmpeg 建置採用 LGPL-2.1-或更高版本，動態連結且可替換，並在裝著它的 jar 裡帶著授權條款全文。

## 讓視窗出現在畫面上

```java
public static void main(String[] args) {
    try (Backend backend = new LwjglBackend()) {
        NativeWindow window = backend.createWindow(
                WindowConfig.of("Hello, Limn", 480, 320));

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

沒有標記語言，沒有註解處理器，沒有建置外掛。元件就是你自己建構出來的物件。

## 你會得到什麼

**一套不必自己做的元件。** 按鈕、輸入欄、清單、分頁、選單、對話框、分割窗格、選色器，長條圖、折線圖與環圈圖，還有一個虛擬化清單，其中一百萬列的成本與二十列相同。每一個都從主題讀取顏色、形狀與密度。

**表格、樹與日期，已經做好。** 表格是架在你的應用自己持有的清單上的具型別欄位，由工具包或你的伺服器排序，還有一列會加總的表尾；樹是架在你提供的子節點上的大綱，一列可以在說出子節點名字之前先承諾它們存在；日期欄位以讀者自己的曆法與格式輸入一個 ISO 日期，日期選擇器則在它後面打開一個月份格線。三個平台的螢幕閱讀器都能讀出這三者。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-data-dark.webp">
    <img src="media/readme/showcase-data-light.webp" alt="一個 Limn 視窗裡的表格、樹、日期欄位與日期選擇器" width="900">
  </picture>
</p>

**裝得進腦袋的版面。** 四個元件加一個標記：欄往下堆疊，列往旁鋪開，堆疊層層相覆，內距往內縮，`Expanded` 決定誰拿走剩下的空間。沒有約束求解器要設定，也沒有版面管理員要安裝。

**是你的產品觀感，不是工具包的。** 主題就是純資料——每一種顏色、圓角半徑、每個控件繼承的尺寸級距——執行時一次呼叫即可整套替換。

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="同一個介面，以七套主題渲染" width="900">
</p>

**你的使用者的語言。** 文字以繪製時相同的前進量來量測，字型遞補為同一文字系統的每一段各選一次字型，所以拉丁文、希臘文、西里爾文與中日韓文可以混在同一個字串裡，而你不必挑字體——中日韓與表情符號字體裝在一個自行選用的依賴（`limn-fonts-all`）裡，其餘隨後端附上。輸入法在欄位內完成組字，編輯以字素叢集為單位移動。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="同一個介面分別用日語、簡體中文、韓語與俄語擷取" width="900">
  </picture>
</p>

**同樣的語言，也能被朗讀出來。** 視窗會把自己發布到 Windows 的 UI Automation、macOS 的 NSAccessibility 與 Linux 的 AT-SPI2，於是 NVDA、VoiceOver 與 Orca 都能描述一個 Limn 介面，而你的應用不必寫一行平台程式碼。名字就是介面繪製時用的那些 `I18nString`；角色的叫法則由工具包自己以它發布的每一種語言攜帶——因為三個平台裡有兩個根本不會說。

**影片與 3D 同樣是元件。** 基於物理的 3D 視埠與影片播放器，像一般元件那樣參與合成：捲動視圖會裁切它們，堆疊會畫在它們上面，它們參與版面的方式和一個標籤沒有兩樣。

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="合成進一般視窗中的 3D 視埠" width="900">
</p>

## 讓它長成你的樣子

每一種顏色、每一個圓角、每一級尺寸都來自主題，而 `limn-theme-editor` 就是寫主題的那個程式。直接跑起來：

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:x.y.z
```

macOS 的開關和上面一樣。它存下來的是一個 `.limntheme` 檔案，純資料，你的應用程式用 `limn-toolkit` 裡的 `ThemeFormat` 讀回去。編輯器本身永遠不是你的應用程式的相依項。

## 模組

<table>
  <tr>
    <td nowrap><samp>limn-toolkit</samp></td>
    <td>元件集、版面、場景圖、後端 SPI 與純 Java 影片解碼器；不依賴任何東西</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-backend-lwjgl</samp></td>
    <td>那些 SPI 背後的 GLFW、OpenGL 與 stb</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-video-ffmpeg</samp></td>
    <td>透過 FFmpeg 支援 H.264/HEVC/VP9/VP8 與 AAC/Opus/Vorbis；負載是 <code>limn-ffmpeg-natives</code>，版本跟著 FFmpeg 走，每個桌面目標各一個 classifier</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-icons-tabler</samp></td>
    <td>Tabler 圖示包，需要就用——如今是獨立的一個成品，版本跟著 Tabler 走（<code>3.46.0.x</code> 即 Tabler 3.46.0）</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-test</samp></td>
    <td>不需顯示器即可測試你的介面：在場景中點擊、輸入和按鍵的驅動器，無顯示的執行環境與後端，以及元件所遵守的無障礙契約</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-fonts-all</samp></td>
    <td>泛中日韓字體與彩色表情符號字體（一個從不繪製它們的應用程式不該背負的 26 MB），加上其餘的遞補字體，各自固定在這次發布測試過的版本——每個字體都是獨立的一個成品，版本跟著字型走</td>
  </tr>
</table>

## 文件

[網站](https://limn-toolkit.github.io/limn-toolkit)就是文件：一份以跑得起來的程式收尾的[安裝指南](https://limn-toolkit.github.io/limn-toolkit/docs/install/)、一座[元件展示](https://limn-toolkit.github.io/limn-toolkit/components/)，其中每一張圖都是那次建置中由工具組繪製的，以及完整的 [API 參考](https://limn-toolkit.github.io/limn-toolkit/api/)。

設計決策放在 [`docs/adr/`](docs/adr/)，發布的做法放在 [`RELEASING.md`](RELEASING.md)。

## Limn 是怎麼做出來的

Limn 的程式碼由 Anthropic 的模型 Claude 在 Claude Code 中撰寫，由人來掌握方向並加以驗證。我們一開始就說明這一點，因為你會想知道。

掌握方向，指的是每一項設計決定都先在 [`docs/adr`](docs/adr) 裡寫成文字、經過論證並被接受，然後才有程式碼。加以驗證，指的是每一次變更都要跑過比程式碼本身還龐大的測試套件；無障礙不靠假設，而是真的去聽——讓 NVDA、VoiceOver 和 Orca 在 Windows、macOS 和 Linux 上朗讀真實的視窗；程式碼也經過多輪關於正確性、效能與 API 設計的稽核。

走到這一步，用了成千上萬次模型呼叫和數以百萬計的生成 token。依 COCOMO II 估算模型，不計測試，這十餘萬行程式碼相當於一支約二十人的團隊兩年多的工作量。這份工作已經做完了。你可以在它之上建構，而不必重做一遍。

## 從原始碼建置

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

成品的目標是 JDK 17，建置本身則在 21 上執行，也需要一個 21。裝在 Gradle 會尋找的位置的 JDK 21 直接可用；裝在非標準位置的，用 `~/.gradle/gradle.properties` 裡的 `org.gradle.java.installations.paths` 指明；哪裡都找不到時，工具鏈解析器會在第一次使用時下載一個——這是建置在取得相依套件之外唯一的一次連網。在沒有 GPU 的機器上，以 GL 為底的測試會跳過，而不是失敗。

MP4 播放需要一份**不在**這個倉庫裡的原生負載：它是 [`limn-ffmpeg-natives`](https://github.com/limn-toolkit/limn-ffmpeg-natives) 成品，版本跟著 FFmpeg 走，建置會像對待其他任何相依套件一樣，從 Maven Central 解析出它測試時所用的版本——測試與示範程式在本機什麼都不用建置就能播放影片。寫入端的測試需要一個已發布的任何成品都不帶的編碼器；在旁邊複製那個倉庫並做一次 `full` 建置，就會被自動拾取。

要改動工具組本身，從 [`docs/design/README.md`](docs/design/README.md) 開始：它說明什麼該寫進 Javadoc、什麼該寫進 ADR、什麼該寫進設計筆記，並按子系統替筆記編了索引。網站是這個倉庫的一個消費者，有自己的一套建置，寫在 [`docs/design/website.md`](docs/design/website.md) 裡。

如何提出改動，以及一個拉取請求應當包含什麼，見 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## 授權

[Apache-2.0](LICENSE)，含明確的專利授權。每個隨附元件及其授權都列在 [`NOTICE`](NOTICE) 中；FFmpeg 解碼器採用 LGPL-2.1-或更高版本，並在它的 jar 裡帶著授權條款全文。
