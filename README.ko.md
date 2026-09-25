<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>자바 데스크톱 앱을, 처음부터 직접 그립니다.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">웹사이트</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">시작하기</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">컴포넌트</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API 레퍼런스</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <b>한국어</b> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a> ·
  <a href="README.ar.md">العربية</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Limn 애플리케이션: 메뉴 막대, 탭, 폼, 차트, 테마 선택기" width="900">
  </picture>
</p>

Limn은 픽셀을 스스로 그립니다. 위젯, 레이아웃, 텍스트, 차트, 미디어, 3D 뷰포트를 의존성 한 개로
제공합니다. **Swing도, JavaFX도, 아래에 깔린 네이티브 툴킷도 없습니다**.

**Claude Code로, 숨김없이 만들었습니다.** 코드는 Claude가 썼고, 방향을 정하고 검증한 것은 사람들입니다. 만드는 데 든 비용은 이미 치렀으니, 여러분이 다시
치를 필요가 없습니다. [Limn은 어떻게 만들어지는가](#limn은-어떻게-만들어지는가)

## 지금 실행해 보기

키친 싱크 — 모든 위젯, 차트, 미디어 플레이어, 3D 뷰포트 — 를 명령 하나로, 테마 편집기는 또
하나로. 클론할 것도 없고, 설치할 것도 [jbang](https://www.jbang.dev/download/) 하나뿐입니다. JDK가
없으면 그것까지 받아 옵니다. 둘 다 Maven Central에서 얇은 아티팩트로 옵니다. 내려받는 것은 툴킷과
폰트, 그리고 지금 쓰는 이 기계의 네이티브 라이브러리뿐이고, 나머지 다섯 플랫폼의 것은 오지 않습니다.

macOS에서는:

```bash
jbang --java-options=-XstartOnFirstThread demo@limn-toolkit/limn-toolkit
jbang --java-options=-XstartOnFirstThread theme-editor@limn-toolkit/limn-toolkit
```

Linux와 Windows에서는:

```bash
jbang demo@limn-toolkit/limn-toolkit
jbang theme-editor@limn-toolkit/limn-toolkit
```

이 플래그는 macOS 전용이라, 다른 곳의 JVM은 받으면 시작을 거부합니다. 이 두 명령은 최신 릴리스를
실행합니다. 버전을 고정하려면 대신 좌표를 적으세요 — `io.github.limn-toolkit:limn-demo:x.y.z`와
`io.github.limn-toolkit:limn-theme-editor:x.y.z`입니다. 네트워크가 없다면, 데모는 릴리스마다 파일
하나로도 첨부되어 있고 모든 플랫폼의 것이 그 안에 들어 있습니다.

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

JBang은 최신 릴리스를 한 번만 확인하고 그대로 유지합니다. 더 새 릴리스를 받으려면 `--fresh`를 붙이세요(예: `jbang --fresh demo@limn-toolkit/limn-toolkit`).

## 설치

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

`x.y.z`는 현재 릴리스입니다 — 이 페이지 맨 위의 Maven Central 배지가 그것을 보여 주고, 이 페이지의
모든 좌표가 같은 번호를 씁니다.

그 한 줄이 설치의 전부입니다. `limn-backend-lwjgl`은 창과 렌더러이고, 위젯과 레이아웃과 장면
그래프인 `limn-toolkit`을 자신에게 의존하는 모든 것에 그대로 내보냅니다. 백엔드가 모든
데스크톱 플랫폼용 LWJGL 네이티브 라이브러리를 함께 가져오므로, 고를 classifier가 없습니다.

> [!IMPORTANT]
> macOS에서는 JVM에 `-XstartOnFirstThread`가 필요합니다. 첫날 반드시 만나게 되는 유일한 플랫폼
> 특이사항이며, macOS 전용입니다 — 다른 곳의 JVM은 그 플래그를 받으면 시작되지 않습니다.

### 영상 재생

`VideoView`는 위의 그 한 줄 안에 들어 있고, 그 뒤에 있는 순수 자바 디코더도 마찬가지입니다.
그것으로 재생되는 것은 Y4M과 합성 소스이며, MP4와 Matroska에는 FFmpeg이 필요합니다. FFmpeg이
별도 의존성인 것은, Limn에서 네이티브 페이로드와 자체 라이선스를 가진 유일한 부분이기
때문입니다.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:x.y.z")
    runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:7.1.5.1:natives-macos-aarch64")
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
  <version>7.1.5.1</version>
  <classifier>natives-macos-aarch64</classifier>
  <scope>runtime</scope>
</dependency>
```

</details>

첫 줄은 자바 코드와, 그와 함께 모든 플랫폼용 JNI 연결 계층을 가져옵니다. 둘째 줄은 FFmpeg
라이브러리를 가져오는데 — 툴킷이 아니라 FFmpeg과 함께 버전이 오르는 아티팩트인
`limn-ffmpeg-natives`에서 오므로, Limn을 올려도 캐시에 그대로 남습니다 — 대상마다 classifier
하나씩이어서 한 대의 기기는 여섯 개 전부가 아니라 2메가바이트쯤만 내려받습니다:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

빌드 하나를 모든 플랫폼에 배포해 어느 기기에 내려앉을지 알 수 없다면, 대신
`limn-video-ffmpeg-natives-all`을 쓰세요. 이것은 툴킷과 함께 버전이 오르는 그 자체로 하나의
POM이며, 이 릴리스가 테스트된 페이로드 버전으로 여섯 개를 대신 적어 줍니다. 여러 classifier를
함께 적는 것도 막지 않습니다 — 두 대상을 겨냥한 묶음이라면 두 개를 적으면 됩니다.

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

classifier를 빼도 툴킷은 그대로 빌드되고 실행됩니다. 디코더가 어떤 플랫폼을 찾았는지 밝히며
자신을 쓸 수 없다고 알리고, FFmpeg이 아닌 것은 모두 그대로 동작합니다. 이 FFmpeg 빌드는
LGPL-2.1-or-later이고, 동적으로 링크되어 교체할 수 있으며, 라이선스 본문을 자신을 담은 jar 안에
함께 담고 있습니다.

## 화면에 창 하나

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

마크업 언어도, 애너테이션 프로세서도, 빌드 플러그인도 없습니다. 위젯은 당신이 직접 만드는
객체입니다.

## 무엇을 얻게 되는가

**직접 만들지 않아도 되는 컴포넌트 모음.** 버튼, 입력란, 목록, 탭, 메뉴, 대화상자, 분할 패널, 색
선택기, 막대·선·도넛 차트, 그리고 100만 행이 20행과 같은 비용으로 끝나는 가상화 목록. 모두 색과
모양과 밀도를 테마에서 읽습니다.

**표와 트리와 날짜, 이미 만들어져 있습니다.** 표는 애플리케이션이 가진 목록 위에 놓인 형식 있는
열이고, 툴킷이나 당신의 서버가 정렬하며, 바닥글이 합계를 냅니다. 트리는 당신이 주는 자식 위에
놓인 개요이고, 행은 자식의 이름을 대기 전에 자식이 있다고 약속할 수 있습니다. 날짜 입력란은
읽는 사람 자신의 달력과 형식으로 ISO 날짜를 입력하고, 날짜 선택기는 그 뒤에 월 격자를 엽니다.
세 플랫폼의 스크린 리더가 셋 모두를 읽습니다.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-data-dark.webp">
    <img src="media/readme/showcase-data-light.webp" alt="Limn 창 하나에 담긴 표, 트리, 날짜 입력란, 날짜 선택기" width="900">
  </picture>
</p>

**머릿속에 들어오는 레이아웃.** 위젯 넷과 표시자 하나. 열은 쌓고, 행은 펼치고, 스택은 겹치고,
패딩은 안쪽으로 들이고, `Expanded`가 남은 공간을 누가 가질지 정합니다. 설정할 제약 해결기도
설치할 레이아웃 매니저도 없습니다.

**툴킷의 얼굴이 아니라, 당신 제품의 얼굴.** 테마는 순수한 데이터이고 — 모든 색, 모서리 반경, 각
컨트롤이 상속하는 크기 단계까지 — 실행 중 한 번의 호출로 교체됩니다.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="같은 화면을 일곱 가지 테마로 렌더링한 모습" width="900">
</p>

**사용자의 언어.** 텍스트는 그릴 때와 같은 전진폭으로 측정되고, 글꼴 대체는 같은 문자 체계가 이어지는
구간마다 서체를 고릅니다. 그래서 라틴·그리스·키릴·CJK가 한 문자열에 섞여도 서체를 고를 필요가 없습니다 — CJK와
이모지 서체는 직접 골라 넣는 의존성 하나(`limn-fonts-all`)에 실려 오고, 나머지는 백엔드에 딸려
옵니다. 입력기는 입력란 안에서 조합하고, 편집은 자소 클러스터 단위로 움직입니다.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="같은 화면을 일본어, 중국어 간체, 한국어, 러시아어로 촬영한 모습" width="900">
  </picture>
</p>

**같은 언어 그대로, 소리 내어 읽힙니다.** 창은 Windows에서는 UI Automation에, macOS에서는
NSAccessibility에, Linux에서는 AT-SPI2에 자신을 공개합니다. 그래서 NVDA와 VoiceOver와 Orca가 Limn
인터페이스를 설명하고, 애플리케이션은 플랫폼 코드를 한 줄도 쓰지 않습니다. 이름은 인터페이스가
그리는 것과 같은 `I18nString`이고, 역할을 부르는 말은 툴킷이 배포하는 모든 언어로 직접 가지고
있습니다 — 셋 중 둘은 그 말을 해 주지 않기 때문입니다.

**영상과 3D도 위젯입니다.** 물리 기반 3D 뷰포트와 비디오 플레이어가 보통 위젯처럼 합성됩니다.
스크롤 뷰가 잘라내고, 스택이 위에 그리며, 라벨과 똑같이 레이아웃에 참여합니다.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="평범한 창에 합성된 3D 뷰포트" width="900">
</p>

## 당신의 것으로

색도, 모서리 반경도, 크기 단계도 모두 테마에서 옵니다. `limn-theme-editor`는 그 테마를 쓰는
프로그램입니다. 실행하기만 하면 됩니다.

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:x.y.z
```

macOS 플래그는 위와 같습니다. 저장되는 것은 `.limntheme` 파일, 즉 평범한 데이터이고, 애플리케이션은
`limn-toolkit`의 `ThemeFormat`으로 읽어 들입니다. 편집기 자체가 애플리케이션의 의존성이 되는 일은
없습니다.

## 모듈

<table>
  <tr>
    <td nowrap><samp>limn-toolkit</samp></td>
    <td>위젯 모음, 레이아웃, 장면 그래프, 백엔드 SPI, 그리고 순수 자바 영상 디코더. 의존성 없음</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-backend-lwjgl</samp></td>
    <td>그 SPI 뒤의 GLFW, OpenGL, stb</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-video-ffmpeg</samp></td>
    <td>FFmpeg을 통한 H.264/HEVC/VP9/VP8과 AAC/Opus/Vorbis. 페이로드는 FFmpeg과 함께 버전이 오르는 <code>limn-ffmpeg-natives</code>이며, 데스크톱 대상마다 classifier 하나씩</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-icons-tabler</samp></td>
    <td>원한다면 쓸 수 있는 Tabler 아이콘 팩 — 이제는 Tabler와 함께 버전이 오르는 독립 아티팩트(<code>3.46.0.x</code>는 Tabler 3.46.0)</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-test</samp></td>
    <td>화면 없이 UI를 테스트: 씬에서 클릭하고 입력하고 키를 누르는 드라이버, 화면 없는 런타임과 백엔드, 위젯이 지키는 접근성 계약</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-fonts-all</samp></td>
    <td>범 CJK 서체와 컬러 이모지 서체(그릴 일 없는 앱이 짊어질 이유가 없는 26메가바이트), 거기에 나머지 대체 글꼴까지, 이 릴리스가 테스트된 버전 그대로 — 서체 하나하나가 폰트와 함께 버전이 오르는 독립 아티팩트</td>
  </tr>
</table>

## 문서

[웹사이트](https://limn-toolkit.github.io/limn-toolkit)가 곧 문서입니다. 실행되는 프로그램으로
끝나는 [설치 가이드](https://limn-toolkit.github.io/limn-toolkit/docs/install/), 모든 그림을 그
빌드 중에 툴킷이 직접 그린 [컴포넌트 갤러리](https://limn-toolkit.github.io/limn-toolkit/components/),
그리고 완전한 [API 레퍼런스](https://limn-toolkit.github.io/limn-toolkit/api/)입니다.

설계 결정은 [`docs/adr/`](docs/adr/)에, 릴리스를 만드는 방법은 [`RELEASING.md`](RELEASING.md)에
있습니다.

## Limn은 어떻게 만들어지는가

Limn의 코드는 Anthropic의 모델인 Claude가 Claude Code 안에서 쓰고, 사람들이 방향을 정하고 검증합니다. 알고 싶으실 테니 처음부터 밝혀 둡니다.

방향을 정한다는 것은 모든 설계 결정을 코드보다 먼저 [`docs/adr`](docs/adr)에 글로 남기고, 논의하고, 승인한다는 뜻입니다. 검증한다는 것은 모든 변경을 코드
자체보다 큰 테스트 스위트에 돌리고, 접근성을 짐작하지 않고 직접 들어 보고(NVDA·VoiceOver·Orca가 Windows, macOS, Linux의 실제 창을 읽게 해서),
정확성과 성능과 API 설계를 거듭 감사한다는 뜻입니다.

여기까지 오는 데 수천 번의 모델 호출과 수백만 개의 생성 토큰이 들었습니다. 추정 모델 COCOMO II에 따르면, 테스트를 빼고도 10만 줄이 넘는 코드는 약 20명의 팀이
2년 넘게 해야 할 일에 해당합니다. 그 일은 끝났습니다. 다시 하는 대신 그 위에 만들 수 있습니다.

## 소스에서 빌드하기

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

아티팩트가 겨냥하는 것은 JDK 17이고, 빌드 자체는 21에서 돌아가며 그 21이 필요합니다. Gradle이
찾아보는 곳에 설치된 JDK 21은 그대로 쓰입니다. 표준이 아닌 위치에 있는 것은
`~/.gradle/gradle.properties`의 `org.gradle.java.installations.paths`로 지정합니다. 어디에서도
찾지 못하면 툴체인 리졸버가 처음 쓸 때 하나를 내려받는데, 의존성 가져오기 말고는 빌드가 네트워크에
닿는 유일한 지점입니다. GPU가 없는 기기에서는 GL 기반 테스트가 실패하는 대신 건너뜁니다.

MP4 재생에는 이 저장소에 **없는** 네이티브 페이로드가 필요합니다: 그것은 FFmpeg과 함께 버전이
오르는 [`limn-ffmpeg-natives`](https://github.com/limn-toolkit/limn-ffmpeg-natives) 아티팩트이고,
빌드는 테스트된 버전을 다른 의존성과 똑같이 Maven Central에서 가져옵니다 — 테스트와 데모는
로컬에서 아무것도 빌드하지 않고 영상을 재생합니다. 라이터 테스트에는 배포된 어떤 것에도 들어
있지 않은 인코더가 필요합니다. 그 저장소를 옆에 클론해 `full` 빌드를 해 두면 자동으로 집어 듭니다.

툴킷 자체를 손보는 일은 [`docs/design/README.md`](docs/design/README.md)에서 시작합니다: Javadoc,
ADR, 설계 노트에 각각 무엇이 들어가는지 말하고, 노트를 하위 시스템별로 색인합니다. 웹사이트는 이
저장소를 소비하는 쪽이고 빌드가 따로 있으며, [`docs/design/website.md`](docs/design/website.md)에
적혀 있습니다.

변경을 제안하는 방법과 풀 리퀘스트에 담을 내용은 [`CONTRIBUTING.md`](CONTRIBUTING.md)에 있습니다.

## 라이선스

[Apache-2.0](LICENSE), 명시적 특허 허여를 포함합니다. 함께 배포되는 구성 요소와 각각의 라이선스는
[`NOTICE`](NOTICE)에 적혀 있습니다. FFmpeg 디코더는 LGPL-2.1-or-later이며 라이선스 본문을 자신의
jar 안에 담고 있습니다.
