<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>用 Java 寫桌面應用程式，像素自己畫。</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-components"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-components?label=Maven%20Central&color=6d4aff"></a>
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
  <b>繁體中文</b>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="一個 Limn 應用程式：選單列、分頁、表單、圖表與主題選擇器" width="900">
  </picture>
</p>

Limn 自己畫出每一個像素。元件、版面、文字、圖表、媒體與 3D 視埠，只要兩個相依套件，**沒有 Swing，沒有 JavaFX，底下也沒有原生工具組**。

## 安裝

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

`limn-components` 是元件集，`limn-backend-lwjgl` 是視窗與繪製器。後端把 LWJGL 在每個桌面平台的原生庫都帶了進來，所以沒有 classifier 要挑。

> [!IMPORTANT]
> 在 macOS 上，JVM 需要 `-XstartOnFirstThread`。這是你第一天就會遇到的唯一平台怪癖，而且僅限 macOS——在其他平台上把這個旗標交給 JVM，它不會啟動。

## 讓視窗出現在畫面上

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

沒有標記語言，沒有註解處理器，沒有建置外掛。元件就是你自己建構出來的物件。

## 你會得到什麼

**一套不必自己做的元件。** 按鈕、輸入欄、清單、分頁、選單、對話框、分割窗格、選色器，長條圖、折線圖與環圈圖，還有一個虛擬化清單，其中一百萬列的成本與二十列相同。每一個都從主題讀取顏色、形狀與密度。

**裝得進腦袋的版面。** 四個元件加一個標記：欄往下堆疊，列往旁鋪開，堆疊層層相覆，內距往內縮，`Expanded` 決定誰拿走剩下的空間。沒有約束求解器要設定，也沒有版面管理員要安裝。

**是你的產品觀感，不是工具包的。** 主題就是純資料——每一種顏色、圓角半徑、每個控件繼承的尺寸級距——執行時一次呼叫即可整套替換。

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="同一個介面，以七套主題渲染" width="900">
</p>

**你的使用者的語言。** 文字以繪製時相同的前進量來量測，字型遞補逐字元進行，所以拉丁文、希臘文、西里爾文與中日韓文可以混在同一個字串裡，而你不必挑字體。輸入法在欄位內完成組字，編輯以字素叢集為單位移動。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="同一個介面分別用日語、簡體中文、韓語與俄語擷取" width="900">
  </picture>
</p>

**影片與 3D 同樣是元件。** 基於物理的 3D 視埠與影片播放器，像一般元件那樣參與合成：捲動視圖會裁切它們，堆疊會畫在它們上面，它們參與版面的方式和一個標籤沒有兩樣。

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="合成進一般視窗中的 3D 視埠" width="900">
</p>

## 模組

| | |
| --- | --- |
| `limn-toolkit` | 元件、版面、場景圖與後端 SPI；不依賴任何東西 |
| `limn-components` | 元件集 |
| `limn-backend-lwjgl` | 那些 SPI 背後的 GLFW、OpenGL 與 stb |
| `limn-video` | 純 Java 解碼器：沒有原生庫，沒有第三方相依套件 |
| `limn-video-ffmpeg` | 透過 FFmpeg 支援 H.264/HEVC/VP9/VP8 與 AAC/Opus/Vorbis，jar 內含六個桌面目標的原生庫 |
| `limn-icons-tabler` | Tabler 圖示包，需要就用 |
| `limn-theme-editor` | 編寫主題的畫面，可嵌入你的應用程式 |

## 在你投入之前

任何工具組都有取捨。這些就是取捨，先講清楚，因為到第三週才發現，比現在讀到糟糕得多。

- **不支援複雜文字的字形排版。** 阿拉伯文、希伯來文與印度系文字需要脈絡連寫與重新排序，而文字層並未實作這些，也沒有由右至左的版面方向。這些語言的翻譯我們刻意不發布，而不是把它們畫錯。
- **沒有螢幕閱讀器橋接。** 鍵盤導覽與焦點框是完整的，但沒有向平台的無障礙 API 公開任何內容。
- **1.0 之前。** API 在各版本之間仍會變動，而 OpenGL 是唯一的繪製路徑。請鎖定版本並閱讀發行說明。

## 文件

[網站](https://limn-toolkit.github.io/limn-toolkit)就是文件：一份以跑得起來的程式收尾的[安裝指南](https://limn-toolkit.github.io/limn-toolkit/docs/install/)、一座[元件展示](https://limn-toolkit.github.io/limn-toolkit/components/)，其中每一張圖都是那次建置中由工具組繪製的，以及完整的 [API 參考](https://limn-toolkit.github.io/limn-toolkit/api/)。

設計決策放在 [`docs/adr/`](docs/adr/)，發布的做法放在 [`RELEASING.md`](RELEASING.md)。

## 從原始碼建置

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

成品的目標是 JDK 17，建置本身則在 21 上執行。在沒有 GPU 的機器上，以 GL 為底的測試會跳過，而不是失敗。

MP4 播放需要一份**不在**這個倉庫裡的原生負載——發布時會為六個平台建置它，並放進 jar 裡一起送出。若要在本機取得，`./scripts/build-ffmpeg.sh` 大約一分鐘就能建置一份，或者 `./scripts/fetch-ffmpeg.sh` 會從已發布的 jar 解出一份。

## 授權

[Apache-2.0](LICENSE)，含明確的專利授權。每個隨附元件及其授權都列在 [`NOTICE`](NOTICE) 中；FFmpeg 解碼器採用 LGPL-2.1-或更高版本，並在它的 jar 裡帶著授權條款全文。
