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
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Limn 애플리케이션: 메뉴 막대, 탭, 폼, 차트, 테마 선택기" width="900">
  </picture>
</p>

Limn은 픽셀을 스스로 그립니다. 위젯, 레이아웃, 텍스트, 차트, 미디어, 3D 뷰포트를 의존성 한 개로
제공합니다. **Swing도, JavaFX도, 아래에 깔린 네이티브 툴킷도 없습니다**.

## 설치

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:0.3.0")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>0.3.0</version>
</dependency>
```

</details>

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
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.3.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.3.0:natives-macos-aarch64")
}
```

첫 줄은 모든 플랫폼용 자바 코드와 JNI 연결 계층을 가져옵니다. 둘째 줄은 FFmpeg 라이브러리를
가져오는데, 대상마다 classifier 하나씩으로 배포되므로 한 대의 기기는 여섯 개 전부가 아니라
2메가바이트쯤만 내려받습니다:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

빌드 하나를 모든 플랫폼에 배포해 어느 기기에 내려앉을지 알 수 없다면, 대신 `natives-all`을
쓰세요. 여러 개를 함께 적는 것도 막지 않습니다 — 두 대상을 겨냥한 묶음이라면 두 개를 적으면
됩니다.

classifier를 빼도 툴킷은 그대로 빌드되고 실행됩니다. 디코더가 어떤 플랫폼을 찾았는지 밝히며
자신을 쓸 수 없다고 알리고, FFmpeg이 아닌 것은 모두 그대로 동작합니다. 이 FFmpeg 빌드는
LGPL-2.1-or-later이고, 동적으로 링크되어 교체할 수 있으며, 라이선스 본문을 자신을 담은 jar 안에
함께 담고 있습니다.

## 화면에 창 하나

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

마크업 언어도, 애너테이션 프로세서도, 빌드 플러그인도 없습니다. 위젯은 당신이 직접 만드는
객체입니다.

## 무엇을 얻게 되는가

**직접 만들지 않아도 되는 컴포넌트 모음.** 버튼, 입력란, 목록, 탭, 메뉴, 대화상자, 분할 패널, 색
선택기, 막대·선·도넛 차트, 그리고 100만 행이 20행과 같은 비용으로 끝나는 가상화 목록. 모두 색과
모양과 밀도를 테마에서 읽습니다.

**머릿속에 들어오는 레이아웃.** 위젯 넷과 표시자 하나. 열은 쌓고, 행은 펼치고, 스택은 겹치고,
패딩은 안쪽으로 들이고, `Expanded`가 남은 공간을 누가 가질지 정합니다. 설정할 제약 해결기도
설치할 레이아웃 매니저도 없습니다.

**툴킷의 얼굴이 아니라, 당신 제품의 얼굴.** 테마는 순수한 데이터이고 — 모든 색, 모서리 반경, 각
컨트롤이 상속하는 크기 단계까지 — 실행 중 한 번의 호출로 교체됩니다.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="같은 화면을 일곱 가지 테마로 렌더링한 모습" width="900">
</p>

**사용자의 언어.** 텍스트는 그릴 때와 같은 전진폭으로 측정되고, 글꼴 대체는 글자 단위로
동작합니다. 그래서 라틴·그리스·키릴·CJK가 한 문자열에 섞여도 서체를 고를 필요가 없습니다. 입력기는
입력란 안에서 조합하고, 편집은 자소 클러스터 단위로 움직입니다.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="같은 화면을 일본어, 중국어 간체, 한국어, 러시아어로 촬영한 모습" width="900">
  </picture>
</p>

**영상과 3D도 위젯입니다.** 물리 기반 3D 뷰포트와 비디오 플레이어가 보통 위젯처럼 합성됩니다.
스크롤 뷰가 잘라내고, 스택이 위에 그리며, 라벨과 똑같이 레이아웃에 참여합니다.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="평범한 창에 합성된 3D 뷰포트" width="900">
</p>

## 모듈

| | |
| --- | --- |
| `limn-toolkit` | 위젯 모음, 레이아웃, 장면 그래프, 백엔드 SPI, 그리고 순수 자바 영상 디코더. 의존성 없음 |
| `limn-backend-lwjgl` | 그 SPI 뒤의 GLFW, OpenGL, stb |
| `limn-video-ffmpeg` | FFmpeg을 통한 H.264/HEVC/VP9/VP8과 AAC/Opus/Vorbis. 데스크톱 대상마다 classifier 하나씩 |
| `limn-icons-tabler` | 원한다면 쓸 수 있는 Tabler 아이콘 팩 |
| `limn-theme-editor` | 테마를 만드는 화면, 애플리케이션에 넣을 수 있음 |

## 결정하기 전에

모든 툴킷은 무언가를 맞바꿉니다. 3주 차에 알게 되는 것보다 지금 읽는 편이 나으므로, 먼저 적어
둡니다.

- **복잡한 문자의 셰이핑 미지원.** 아랍 문자, 히브리 문자, 인도계 문자에는 텍스트 계층이 구현하지
  않은 문맥 결합과 재배열이 필요하고, 오른쪽에서 왼쪽으로 가는 레이아웃 방향도 없습니다. 해당 언어
  번역은 잘못 그리느니 아예 배포하지 않기로 했습니다.
- **화면 낭독기 연결 없음.** 키보드 이동과 포커스 링은 완성되어 있지만, 플랫폼 접근성 API로는
  아무것도 노출하지 않습니다.
- **1.0 이전.** API는 아직 릴리스마다 움직이고, 렌더링 경로는 OpenGL 하나뿐입니다. 버전을 고정하고
  릴리스 노트를 읽으세요.

## 문서

[웹사이트](https://limn-toolkit.github.io/limn-toolkit)가 곧 문서입니다. 실행되는 프로그램으로
끝나는 [설치 가이드](https://limn-toolkit.github.io/limn-toolkit/docs/install/), 모든 그림을 그
빌드 중에 툴킷이 직접 그린 [컴포넌트 갤러리](https://limn-toolkit.github.io/limn-toolkit/components/),
그리고 완전한 [API 레퍼런스](https://limn-toolkit.github.io/limn-toolkit/api/)입니다.

설계 결정은 [`docs/adr/`](docs/adr/)에, 릴리스를 만드는 방법은 [`RELEASING.md`](RELEASING.md)에
있습니다.

## 소스에서 빌드하기

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

아티팩트가 겨냥하는 것은 JDK 17이고, 빌드 자체는 21에서 돌아갑니다. GPU가 없는 기기에서는 GL 기반
테스트가 실패하는 대신 건너뜁니다.

MP4 재생에는 이 저장소에 **없는** 네이티브 페이로드가 필요합니다 — 릴리스가 여섯 플랫폼용으로
빌드해 각각 classifier 하나씩으로 배포합니다. 로컬에 두려면 `./scripts/build-ffmpeg.sh`가 1분쯤이면 하나를
빌드하고, `./scripts/fetch-ffmpeg.sh`는 배포된 jar에서 하나를 꺼내 풉니다.

## 라이선스

[Apache-2.0](LICENSE), 명시적 특허 허여를 포함합니다. 함께 배포되는 구성 요소와 각각의 라이선스는
[`NOTICE`](NOTICE)에 적혀 있습니다. FFmpeg 디코더는 LGPL-2.1-or-later이며 라이선스 본문을 자신의
jar 안에 담고 있습니다.
