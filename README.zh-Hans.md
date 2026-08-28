<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>用 Java 写桌面应用，像素由自己绘制。</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">网站</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">开始使用</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">组件</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API 参考</a>
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
  <b>简体中文</b> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="一个 Limn 应用：菜单栏、标签页、表单、图表和主题选择器" width="900">
  </picture>
</p>

Limn 自己绘制每一个像素。组件、布局、文本、图表、媒体与 3D 视口，只需一个依赖，**没有 Swing，没有 JavaFX，底下也没有原生工具包**。

## 立刻试试

整个陈列——每一个控件、图表、媒体播放器、3D 视口——一条命令就跑起来。没有什么要克隆，也没有什么要装，除了 [jbang](https://www.jbang.dev/download/)；你要是没有 JDK，它连 JDK 一起取来。

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

在 macOS 上加 `--java-options=-XstartOnFirstThread`。这个开关只有 macOS 认，别的系统上的 JVM 收到它会拒绝启动。

## 安装

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

这一行就是全部的安装。`limn-backend-lwjgl` 是窗口与渲染器，而它会把 `limn-toolkit`——组件、布局与场景图——导出给依赖它的一切。后端自带 LWJGL 在每个桌面平台上的原生库，所以没有 classifier 要选。

> [!IMPORTANT]
> 在 macOS 上，JVM 需要 `-XstartOnFirstThread`。这是你第一天就会遇到的唯一平台怪癖，而且只在 macOS 上如此——在别的平台上，加了这个参数的 JVM 根本起不来。

### 播放视频

`VideoView` 就在上面那一行里，它背后那些纯 Java 解码器也是。它们能播放的是 Y4M 和一个合成源；MP4 与 Matroska 需要 FFmpeg，而它是一个独立的依赖，因为那是 Limn 中唯一带有原生载荷、又自带一份许可的部分。

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0:natives-macos-aarch64")
}
```

第一行带来 Java 部分和覆盖每个平台的 JNI shim。第二行带来 FFmpeg 库，它们按每个目标发布一个 classifier，所以一台机器下载的大约是两兆字节，而不是全部六份：

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

如果一个构建产物要发往所有平台，无从知道自己会落在哪台机器上，那就改用 `limn-video-ffmpeg-natives-all`。它不是 classifier，而是独立的一个构件，替你把六个都写上了。同时写上好几个 classifier 也没什么不可以——面向两个目标的分发包就写两个。

把 classifier 整个省掉，工具包照样能构建、能运行：解码器会报告自己不可用，并说出它找过的平台，而所有不属于 FFmpeg 的部分照常工作。这份 FFmpeg 构建采用 LGPL-2.1-或更高版本，动态链接且可替换，许可证文本就放在装着它的那个 jar 里。

## 窗口出现在屏幕上

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

没有标记语言，没有注解处理器，没有构建插件。组件就是你亲手构造的对象。

## 你会得到什么

**一套不必自己造的组件。** 按钮、输入框、列表、标签页、菜单、对话框、分栏、取色器，柱状图、折线图与环形图，还有一个虚拟化列表，其中一百万行的开销与二十行相同。每一个都从主题读取颜色、形状与密度。

**装得进脑子的布局。** 四个组件加一个标记：列纵向堆叠，行横向铺开，栈层层叠加，内边距向内收，`Expanded` 决定谁占据剩下的空间。没有约束求解器要配置，也没有布局管理器要安装。

**是你产品的观感，不是工具包的。** 主题就是纯数据——每一种颜色、圆角半径、每个控件继承的尺寸档位——运行时一次调用即可整套替换。

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="同一个界面在七套主题下渲染" width="900">
</p>

**你的用户的语言。** 文本用与绘制相同的步进量来度量，字体回退逐字符进行，所以拉丁文、希腊文、西里尔文和中日韩文可以混在同一个字符串里，而你无需选择字体。输入法在字段内完成组字，编辑按字素簇移动。

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="同一个界面分别用日语、简体中文、韩语和俄语截取" width="900">
  </picture>
</p>

**视频与 3D 同样是组件。** 基于物理的 3D 视口和视频播放器，像普通组件一样参与合成：滚动视图会裁剪它们，栈会画在它们上面，它们参与布局的方式与一个标签无异。

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="合成进普通窗口中的 3D 视口" width="900">
</p>

## 让它长成你的样子

每一种颜色、每一个圆角、每一档尺寸都来自主题，而 `limn-theme-editor` 就是写主题的那块界面。把它嵌进你自己的设置页，或者直接跑起来：

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:0.5.0
```

macOS 的开关和上面一样。它存下来的是纯数据，你的应用用 `ThemeFormat` 读回去。

## 模块

| | |
| --- | --- |
| `limn-toolkit` | 组件集、布局、场景图、后端 SPI 与纯 Java 视频解码器；不依赖任何东西 |
| `limn-backend-lwjgl` | 这些 SPI 背后的 GLFW、OpenGL 与 stb |
| `limn-video-ffmpeg` | 通过 FFmpeg 支持 H.264/HEVC/VP9/VP8 与 AAC/Opus/Vorbis；每个桌面目标一个 classifier |
| `limn-icons-tabler` | Tabler 图标包，如果你需要的话 |
| `limn-theme-editor` | 编写主题的那个界面，可以嵌入你的应用 |

## 在你投入之前

任何工具包都有取舍。这些就是取舍，先说清楚，因为到第三周才发现，比现在读到要糟糕得多。

- **不支持复杂文字的塑形。** 阿拉伯文、希伯来文和印度系文字需要上下文连写与重排，而文本层没有实现这些，也没有从右向左的布局方向。这些语言的翻译我们特意不发布，而不是把它们画错。
- **没有屏幕阅读器桥接。** 键盘导航与焦点环是完整的，但没有向平台的无障碍 API 暴露任何内容。
- **1.0 之前。** API 在版本之间仍会变动，而 OpenGL 是唯一的渲染路径。请锁定版本并阅读发行说明。

## 文档

[网站](https://limn-toolkit.github.io/limn-toolkit)就是文档：一份以能跑起来的程序收尾的[安装指南](https://limn-toolkit.github.io/limn-toolkit/docs/install/)，一个[组件画廊](https://limn-toolkit.github.io/limn-toolkit/components/)——里面每一张图都是那次构建期间由工具包渲染的——以及完整的 [API 参考](https://limn-toolkit.github.io/limn-toolkit/api/)。

设计决策记录在 [`docs/adr/`](docs/adr/) 里，发布的做法写在 [`RELEASING.md`](RELEASING.md) 里。

## 从源码构建

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

构件面向的是 JDK 17，构建本身跑在 21 上。在没有 GPU 的机器上，依赖 GL 的测试会跳过，而不是失败。

MP4 播放需要一份**不在**本仓库里的原生载荷——发布时会为六个平台构建它，并为每个平台发布一个 classifier。想在本地拥有一份，`./scripts/build-ffmpeg.sh` 大约一分钟就能构建出来，或者 `./scripts/fetch-ffmpeg.sh` 从已发布的 jar 中解出一份。

## 许可

[Apache-2.0](LICENSE)，含明确的专利授权。随附的组件及其各自的许可列在 [`NOTICE`](NOTICE) 中；FFmpeg 解码器采用 LGPL-2.1-或更高版本，许可证文本随它的 jar 一起提供。
