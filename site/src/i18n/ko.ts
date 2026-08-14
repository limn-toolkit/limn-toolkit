/**
 * Korean.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const ko: Catalog = {
  "site.name": "Limn",
  "site.tagline": "데스크톱 자바를 위한 UI 툴킷.",

  "nav.primaryLabel": "사이트",
  "nav.menu": "메뉴",
  "nav.components": "컴포넌트",
  "nav.showcase": "화면",
  "nav.docs": "가이드",
  "nav.api": "API",
  "nav.licence": "라이선스",
  "nav.privacy": "개인정보",
  "nav.repository": "GitHub",
  "nav.skipToContent": "본문으로 건너뛰기",
  "footer.linksLabel": "프로젝트 링크",

  "codeBlock.copy": "복사",
  "codeBlock.copied": "클립보드에 복사했습니다",

  "theme.label": "테마",
  "theme.system": "자동",
  "theme.light": "밝게",
  "theme.dark": "어둡게",

  "language.label": "언어",

  "consent.label": "개인정보 선택",
  "consent.title": "이 사이트는 쿠키를 쓰지 않습니다",
  "consent.body":
    "이 브라우저에만 저장되는 것은 세 가지입니다. 선택한 테마, 선택한 언어, 그리고 여기서 한 답변입니다. 선택 사항은 직접 켜기 전까지 꺼진 채로 있습니다.",
  "consent.more": "무엇이 저장되는지 자세히",
  "consent.accept": "모두 허용",
  "consent.reject": "필요한 것만",
  "consent.choose": "선택하기",
  "consent.save": "선택 저장",
  "consent.alwaysOn": "항상 켜짐",
  "consent.necessaryName": "필수",
  "consent.necessaryBody":
    "선택한 밝은/어두운 테마, 선택한 언어, 그리고 이 답변입니다. 셋 다 이 브라우저 안에만 있고, 쿠키가 아니며, 기기 밖으로 나가지 않습니다.",
  "consent.analyticsName": "측정",
  "consent.analyticsBody":
    "지금 이것을 쓰는 것은 없습니다. 이 사이트에는 분석 도구가 전혀 들어 있지 않습니다. 언젠가 방문을 측정하는 무언가가 생기더라도 여기서 허용하기 전에는 동작하지 않도록 두는 스위치입니다.",

  // ------------------------------------------------------------------- home
  "home.title": "Limn: 데스크톱 자바를 위한 UI 툴킷",
  "home.description":
    "직접 만든 위젯, 레이아웃, 텍스트, 차트, 미디어, 3D로 자바 데스크톱 애플리케이션을 만드세요. 의존성 두 개, JDK 17, Windows·macOS·Linux, Apache-2.0.",

  "home.hero.eyebrow": "자바를 위한 데스크톱 UI",
  "home.hero.headline": "자바 데스크톱 앱을, 처음부터 직접 그립니다.",
  "home.hero.sub":
    "Limn은 픽셀을 스스로 그립니다. 위젯, 레이아웃, 텍스트, 차트, 미디어, 3D 뷰포트를 의존성 두 개로 제공합니다. Swing도, JavaFX도, 아래에 깔린 네이티브 툴킷도 없습니다.",
  "home.hero.cta": "시작하기",
  "home.hero.secondary": "컴포넌트 둘러보기",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "이번 빌드 중에 Limn이 그린 데모 애플리케이션.",

  "home.install.eyebrow": "5분",
  "home.install.heading": "의존성 두 개와 main 메서드",
  "home.install.body":
    "마크업 언어도, 애너테이션 프로세서도, 빌드 플러그인도 없습니다. 툴킷과 백엔드를 추가하고 평범한 자바를 쓰면 창이 생깁니다.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "macOS에서는 JVM에 <code>-XstartOnFirstThread</code>가 필요합니다. 첫날 반드시 만나게 되는 유일한 플랫폼 특이사항이라, 세 번 클릭해야 나오는 곳이 아니라 여기에 적어 두었습니다.",
  "home.install.more": "설치 가이드 읽기",

  "home.features.eyebrow": "무엇을 얻게 되는가",

  "home.features.components.heading": "직접 만들지 않아도 되는 컴포넌트 모음",
  "home.features.components.body":
    "버튼, 입력란, 목록, 탭, 메뉴, 대화상자, 분할 패널, 색 선택기, 막대·선·도넛 차트, 그리고 100만 행이 20행과 같은 비용으로 끝나는 가상화 목록. 모두 색과 모양과 밀도를 테마에서 읽으므로 코드에 박아 넣은 값이 없고, 조밀 모드는 한 줄입니다.",
  "home.features.components.link": "전부 보기",

  "home.features.layout.heading": "머릿속에 들어오는 레이아웃",
  "home.features.layout.body":
    "위젯 넷과 표시자 하나. 열은 쌓고, 행은 펼치고, 스택은 겹치고, 패딩은 안쪽으로 들이고, Expanded가 남은 공간을 누가 가질지 정합니다. 어휘는 이게 전부이며, 설정할 제약 해결기도 설치할 레이아웃 매니저도 없습니다.",
  "home.features.layout.link": "레이아웃 가이드 읽기",
  "home.features.layout.caption": "열 하나, 행 하나, 분할 하나, Expanded 하나로 만든 창.",

  "home.features.forms.heading": "프레임워크 없는 폼",
  "home.features.forms.body":
    "입력란은 위젯이고, 검증 규칙은 리스너이며, 제출은 메서드 호출입니다. 바인딩할 것도 등록할 것도 없고, 사용자가 고치는 순간 검증 상태가 입력란 색을 되돌립니다.",
  "home.features.forms.link": "폼 가이드 읽기",
  "home.features.forms.caption": "라벨, 검증, 선택, 그리고 동작 줄.",

  "home.features.media.heading": "영상과 3D도 위젯입니다",
  "home.features.media.body":
    "물리 기반 3D 뷰포트와 비디오 플레이어가 보통 위젯처럼 합성됩니다. 스크롤 뷰가 잘라내고, 스택이 위에 그리며, 라벨과 똑같이 레이아웃에 참여합니다.",
  "home.features.media.link": "미디어 가이드 읽기",
  "home.features.media.caption": "평범한 창에 합성된 3D 뷰포트.",

  "home.themes.heading": "툴킷의 얼굴이 아니라, 당신 제품의 얼굴",
  "home.themes.body":
    "애플리케이션은 그것을 만든 라이브러리가 아니라 당신의 제품처럼 보여야 합니다. 테마는 순수한 데이터입니다. 모든 색, 모서리 반경, 각 컨트롤이 상속하는 크기 단계까지 데이터이며, 실행 중 한 번의 호출로 교체됩니다. 브랜드 서체는 당신이 관리하는 파일에서 불러오므로 툴킷 고유의 외형은 하나도 남지 않습니다.",
  "home.themes.link": "테마가 동작하는 방식",
  "home.themes.caption":
    "화면 하나, 테마 일곱 개. 각 띠 뒤의 코드는 동일합니다.",
  "home.themes.alt":
    "컨트롤이 빽빽한 같은 화면을 일곱 번 나란히 렌더링한 모습으로, 띠마다 팔레트와 크기 단계와 서체가 다릅니다.",

  "home.languages.heading": "사용자의 언어로",
  "home.languages.body":
    "텍스트는 그릴 때와 같은 전진폭으로 측정되고, 글꼴 대체는 글자 단위로 동작합니다. 그래서 라틴·그리스·키릴·CJK가 한 문자열에 섞여도 서체를 고를 필요가 없습니다. 입력기는 입력란 안에서 조합하고, 편집은 자소 클러스터 단위로 움직여 결합 기호나 여러 부분으로 된 이모지가 잘리지 않습니다.",
  "home.languages.alt":
    "같은 화면을 일본어, 중국어 간체, 한국어, 러시아어로 촬영해 하나의 창으로 이어 붙인 모습.",
  "home.languages.link": "텍스트 가이드 읽기",
  "home.languages.caption": "이번 빌드 중에 네 가지 언어로 찍은 같은 화면.",

  "home.limits.eyebrow": "결정하기 전에",
  "home.limits.heading": "Limn이 하지 않는 것",
  "home.limits.body":
    "모든 툴킷은 무언가를 맞바꿉니다. 3주 차에 알게 되는 것보다 지금 읽는 편이 나으므로, 먼저 적어 둡니다.",
  "home.limits.scripts.heading": "복잡한 문자의 셰이핑 미지원",
  "home.limits.scripts.body":
    "아랍 문자, 히브리 문자, 인도계 문자에는 텍스트 계층이 구현하지 않은 문맥 결합과 재배열이 필요하고, 오른쪽에서 왼쪽으로 가는 레이아웃 방향도 없습니다. 해당 언어 번역은 잘못 그리느니 아예 배포하지 않기로 했습니다.",
  "home.limits.a11y.heading": "화면 낭독기 연결 없음",
  "home.limits.a11y.body":
    "키보드 이동과 포커스 링은 완성되어 있지만, 플랫폼 접근성 API로는 아무것도 노출하지 않습니다. 화면 낭독기가 반드시 동작해야 한다면, 그 애플리케이션에는 아직 맞는 툴킷이 아닙니다.",
  "home.limits.version.heading": "1.0 이전",
  "home.limits.version.body":
    "API는 아직 릴리스마다 움직이고, 렌더링 경로는 OpenGL 하나뿐입니다. 버전을 고정하고 릴리스 노트를 읽으세요.",

  "home.closing.heading": "5분이면 화면에 창 하나",
  "home.closing.body":
    "설치 가이드는 실행되는 프로그램으로 끝납니다. 그 뒤는 가이드와 컴포넌트 갤러리, 그리고 API 레퍼런스입니다.",

  // ------------------------------------------------------------- components
  "components.title": "Limn: 컴포넌트",
  "components.description":
    "Limn의 모든 컴포넌트를 툴킷이 직접 두 팔레트로 그렸고, 각 그림을 만든 코드를 옆에 두었습니다.",
  "components.eyebrow": "구성",
  "components.heading": "컴포넌트",
  "components.lede":
    "여기 있는 그림은 모두 이번 빌드 중에 툴킷이 그린 것이고, 코드는 모두 옆의 그림을 만든 바로 그 코드입니다.",
  "components.filterLabel": "컴포넌트 거르기",
  "components.filterPlaceholder": "거르기…",
  "components.empty": "해당하는 것이 없습니다.",
  "components.showCode": "코드",
  "components.play": "재생",
  "components.stop": "정지",
  "components.videoNote":
    "비디오 뷰는 순수 자바 테스트 소스를 쓰므로 코덱 지원 범위가 아니라 위젯의 동작을 보여 줍니다. 이 그림에 네이티브 디코더는 관여하지 않습니다.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn: 화면",
  "showcase.description":
    "툴킷이 그린 화면 전체: 데모 애플리케이션, 3D 뷰포트, 폼, 배치한 창, 그리고 네 언어로 된 같은 화면.",
  "showcase.eyebrow": "화면 전체",
  "showcase.heading": "화면",
  "showcase.lede":
    "잘라낸 조각도 목업도 아닙니다. 하나하나가 이 사이트를 빌드하는 동안 툴킷이 그린 창입니다.",
  "showcase.kitchen.heading": "데모 애플리케이션",
  "showcase.kitchen.body":
    "메뉴 막대, 탭, 테마 선택기, 실시간 성능 표시줄이 있는 하나의 창에 모든 컴포넌트가 들어 있습니다.",
  "showcase.forms.heading": "폼",
  "showcase.forms.body":
    "라벨, 검증되는 입력란, 선택, 스위치, 동작 줄까지, 폼 가이드의 예제 그대로입니다.",
  "showcase.layout.heading": "배치한 창",
  "showcase.layout.body":
    "도구 막대, 내용 창 옆의 사이드바, 상태 줄까지, 레이아웃 가이드의 예제 그대로입니다.",
  "showcase.threeD.heading": "3D 뷰포트",
  "showcase.threeD.body":
    "세 개의 광원 아래 물리 기반 재질을 선형 하이 다이내믹 레인지 대상에 그린 뒤 2D 레이어로 합성합니다. 스크롤 뷰는 다른 위젯과 똑같이 이것을 잘라냅니다. 끌어서 돌리고, 스크롤해서 확대하세요.",

  "showcase.editor.heading": "함께 배포할 수 있는 위젯으로서의 테마 편집기",
  "showcase.editor.body":
    "팔레트는 데이터이므로 그것을 편집하는 일은 하나의 화면이 됩니다. 그리고 이것은 우리 저장소에 머무는 도구가 아니라, 당신의 애플리케이션이 품을 수 있는 모듈입니다. 모서리 슬라이더를 끌면 같은 프레임 안에서 창이 옷을 갈아입습니다. 화면 속 모든 입력란과 버튼과 색 견본이 함께. 옆의 보고서는 각 잉크를 그것이 놓일 수 있는 모든 표면에 대해 재므로, 명도 대비가 부족한 팔레트는 눈에 보이게 떨어집니다.",
  "showcase.density.heading": "모든 크기 단계",
  "showcase.density.body":
    "같은 컨트롤 다섯 개를 다섯 번, 위의 XSMALL부터 아래의 XLARGE까지. 어느 것에도 너비나 글꼴이나 여백을 주지 않았습니다. 각 줄에 알려 주는 것은 컨트롤 크기 하나뿐이고, 여백과 글자와 모서리 반경과 터치 영역이 함께 움직입니다.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn: 라이선스",
  "licence.description":
    "Apache-2.0, 함께 배포되는 구성 요소의 라이선스, 그리고 FFmpeg 상황에 대한 솔직한 설명.",
  "licence.eyebrow": "약관",
  "licence.heading": "라이선스",
  "licence.lede":
    "Apache License 2.0이며 명시적 특허 허여를 포함합니다. 상업적 이용, 수정, 재배포가 모두 허용됩니다.",
  "licence.core.heading": "툴킷 자체",
  "licence.core.body":
    "<code>limn-toolkit</code>과 <code>limn-components</code>는 JDK 외에 의존성이 없으므로, 이 둘에 대해서는 Apache-2.0이 전부입니다. 렌더링 백엔드는 BSD-3-Clause인 LWJGL을 더합니다.",
  "licence.fonts.heading": "글꼴",
  "licence.fonts.body":
    "Roboto와 Noto 대체 글꼴은 SIL Open Font License로 배포됩니다. 함께 배포되는 구성 요소는 모두 라이선스와 함께 프로젝트의 NOTICE 파일에 적혀 있습니다.",
  "licence.mp3.heading": "MP3 디코딩은 LGPL",
  "licence.mp3.body":
    "MP3 지원은 LGPL-2.1인 JLayer에서 오며, 오디오 디코더 인터페이스 뒤에 독립된 jar로 유지됩니다. 배포판이 LGPL 의무를 피해야 한다면 이 의존성 하나만 제외하세요. WAV와 Ogg Vorbis는 그대로 동작합니다.",
  "licence.ffmpeg.heading": "FFmpeg 영상과, 함께 배포되는 것",
  "licence.ffmpeg.body":
    "선택적인 H.264 디코더는 축소한 FFmpeg을 LGPL-2.1-or-later로 동적 링크합니다. <b>그 네이티브 라이브러리는 모든 데스크톱 대상에 대해 배포되는 jar 안에 함께 들어갑니다.</b> 따라서 <code>limn-video-ffmpeg</code>을 포함한 배포물은 FFmpeg을 배포하는 것이며, jar는 라이선스 본문과 요구되는 고지를 함께 담고 있습니다. 동적으로 링크되어 교체할 수 있고, 그것이 이 라이선스가 요구하는 바입니다. 이 모듈에 의존하는 것은 아무것도 없습니다. 빼버려도 다른 미디어 형식은 모두 그대로 동작합니다.",
  "licence.notAdvice":
    "이 가운데 어느 것도 법률 자문이 아닙니다. 라이선스를 직접 읽고, 귀하의 법률 자문을 구하십시오.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn: 개인정보",
  "privacy.description":
    "이 사이트가 저장하는 것과 저장하지 않는 것, 그리고 선택을 바꾸는 방법. 쿠키도, 분석 도구도, 제3자 요청도 없습니다.",
  "privacy.eyebrow": "개인정보",
  "privacy.heading": "이 사이트가 저장하는 것",
  "privacy.lede":
    "짧게 말하면 쿠키도, 분석 도구도, 제3자 요청도 없고, 당신을 특정하는 것도 없습니다. 긴 설명은 아래에 있습니다. 짧은 설명은 긴 설명이 같은 말을 할 때에만 읽을 가치가 있으니까요.",
  "privacy.storage.heading": "브라우저 안의 값 세 개",
  "privacy.storage.body":
    "선택한 테마는 <code>starlight-theme</code>에, 선택한 언어는 <code>limn-language</code>에, 개인정보 안내에 대한 답변은 <code>limn-consent</code>에 저장됩니다. 셋 다 이 브라우저의 로컬 저장소에 있고, 이 사이트 자신의 스크립트만 읽으며, 사이트 데이터를 지우면 함께 사라집니다. 셋 다 없어도 이곳의 모든 것이 그대로 동작합니다.",
  "privacy.language.heading": "언어가 정해지는 방식",
  "privacy.language.body":
    "영어 페이지에 도착하면, 이 사이트는 브라우저가 방문하는 모든 사이트에 이미 알리고 있는 언어 목록을 읽고, 그중 여기에 공개된 언어가 있으면 그 번역으로 보냅니다. 이 목록은 주소를 고르기 위해 브라우저 안에서 한 번 읽힐 뿐, 저장되지도 전송되지도 않습니다. 선택으로 기록되는 것은 머리말에서 언어를 고를 때뿐이며, 그다음부터는 브라우저 목록 대신 그 선택이 쓰입니다. 번역된 주소에서는 다른 곳으로 보내지 않으므로, 누군가 보내준 링크는 보낸 그 언어로 열립니다.",
  "privacy.cookies.heading": "쿠키 없음",
  "privacy.cookies.body":
    "이 사이트는 어떤 쿠키도 설정하지 않으므로 요청에 무언가가 붙지도, 다른 사이트까지 따라가지도 않습니다. 로컬 저장소는 쿠키가 아닙니다. 전송되지 않으며 서버가 요구할 수도 없습니다.",
  "privacy.analytics.heading": "분석 도구는 없지만 스위치는 있습니다",
  "privacy.analytics.body":
    "분석 도구도, 태그 관리자도, 추적 픽셀도 없습니다. 개인정보 안내의 측정 스위치는 기본이 꺼짐이며, 앞으로 추가될 수 있는 모든 것을 통제합니다. 그 범주의 스크립트는 실행되지 않는 형태로 배포되고, 허용한 뒤에야 실행되는 스크립트로 바뀝니다.",
  "privacy.thirdParty.heading": "바깥에서 불러오는 것 없음",
  "privacy.thirdParty.body":
    "글꼴, 이미지, 스타일시트, 스크립트가 모두 이 도메인에서 옵니다. 웹폰트 서비스도 CDN도, 삽입된 영상도 소셜 위젯도 없습니다. 그래서 여기서 페이지를 읽으면 정확히 한 대의 서버와만 통신합니다.",
  "privacy.hosting.heading": "호스팅 업체가 볼 수 있는 것",
  "privacy.hosting.body":
    "페이지는 호스팅 서비스에 있는 정적 파일입니다. 어떤 웹 서버와도 마찬가지로 요청 자체(IP 주소, 요청한 페이지, 브라우저의 사용자 에이전트)는 볼 수 있고, 그것은 해당 업체의 로그 정책을 따릅니다. 프로젝트는 서버도, 계정 시스템도, 데이터베이스도 운영하지 않으며 그 어느 것도 받지 않습니다.",
  "privacy.change.heading": "답변 바꾸기",
  "privacy.change.body":
    "선택은 언제든지 바꿀 수 있고 즉시 적용됩니다. 같은 링크가 모든 페이지의 바닥글에 있습니다.",
  "privacy.change.action": "개인정보 선택 바꾸기",
  "privacy.noScript":
    "이 버튼에는 JavaScript가 필요합니다. 스크립트가 꺼져 있으면 선택 사항은 애초에 아무것도 실행되지 않으며, 브라우저에서 이 사이트의 데이터를 지우면 저장된 값 세 개도 사라집니다.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn: 페이지를 찾을 수 없습니다",
  "notFound.eyebrow": "404",
  "notFound.heading": "그런 페이지는 없습니다",
  "notFound.body":
    "링크가 오래되었거나 페이지가 옮겨졌을 수 있습니다. 이 사이트에 있는 것은 다음 중 하나입니다.",
  "notFound.home": "홈으로 가기",
  "notFound.destinationsLabel": "대신 갈 곳",
  "notFound.components.heading": "컴포넌트",
  "notFound.components.body": "모든 위젯을 그린 그림과, 각 그림을 만든 코드.",
  "notFound.showcase.heading": "화면",
  "notFound.showcase.body": "이 사이트를 빌드하는 동안 툴킷이 그린 화면 전체.",
  "notFound.docs.heading": "문서",
  "notFound.docs.body": "설치, 레이아웃, 폼, 테마, 배포를 다루는 가이드입니다.",
  "notFound.api.heading": "API 레퍼런스",
  "notFound.api.body": "소스에서 생성한 모든 클래스와 메서드.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn: 이 페이지는 옮겨졌습니다",
  "moved.eyebrow": "이동",
  "moved.heading": "이 페이지에는 새 주소가 있습니다",
  "moved.body": "시작하기는 이제 가이드의 일부입니다. 그곳으로 이동합니다…",
  "moved.link": "설치 가이드로 가기",
};
