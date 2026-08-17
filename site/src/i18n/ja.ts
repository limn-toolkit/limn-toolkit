/**
 * Japanese.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const ja: Catalog = {
  "site.name": "Limn",
  "site.tagline": "デスクトップ Java のための UI ツールキット。",

  "nav.primaryLabel": "サイト",
  "nav.menu": "メニュー",
  "nav.components": "コンポーネント",
  "nav.showcase": "画面例",
  "nav.docs": "ガイド",
  "nav.api": "API",
  "nav.licence": "ライセンス",
  "nav.privacy": "プライバシー",
  "nav.repository": "GitHub",
  "nav.skipToContent": "本文へスキップ",
  "footer.linksLabel": "プロジェクトのリンク",

  "codeBlock.copy": "コピー",
  "codeBlock.copied": "クリップボードにコピーしました",

  "theme.label": "テーマ",
  "theme.system": "自動",
  "theme.light": "ライト",
  "theme.dark": "ダーク",

  "language.label": "言語",

  "consent.label": "プライバシーの選択",
  "consent.title": "計測を許可しない限り Cookie は使いません",
  "consent.body": "このブラウザーにだけ保存されるものが 3 つあります。選んだテーマ、選んだ言語、そしてここでの回答です。任意のものは、あなたが有効にするまで無効のままです。",
  "consent.more": "保存されるものの詳細",
  "consent.accept": "すべて許可",
  "consent.reject": "必要なものだけ",
  "consent.choose": "選択する",
  "consent.save": "選択を保存",
  "consent.alwaysOn": "常に有効",
  "consent.necessaryName": "必須",
  "consent.necessaryBody":
    "選んだライト／ダークのテーマ、選んだ言語、そしてこの回答です。3 つともこのブラウザー内だけのもので、Cookie ではなく、この端末から出ることもありません。",
  "consent.analyticsName": "計測",
  "consent.analyticsBody":
    "Google Analytics を googletagmanager.com から読み込みます。独自の Cookie を設定し、どのページが読まれているかをプロジェクトに伝えます。ブロックされた状態で配置され、ここで許可したときにだけ動きます。",

  // ------------------------------------------------------------------- home
  "home.title": "Limn：デスクトップ Java のための UI ツールキット",
  "home.description":
    "独自のウィジェット、レイアウト、テキスト、チャート、メディア、3D を備えたデスクトップアプリを Java で作れます。依存は 2 つ、JDK 17、Windows・macOS・Linux 対応、Apache-2.0。",

  "home.hero.eyebrow": "Java のデスクトップ UI",
  "home.hero.headline": "Java のデスクトップアプリを、ゼロから描く。",
  "home.hero.sub":
    "Limn はピクセルを自分で描きます。ウィジェット、レイアウト、テキスト、チャート、メディア、3D ビューポートが依存 2 つで手に入り、Swing も JavaFX も、下敷きになるネイティブツールキットもありません。",
  "home.hero.cta": "はじめる",
  "home.hero.secondary": "コンポーネントを見る",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "このビルド中に Limn が描画したデモアプリケーション。",

  "home.install.eyebrow": "5 分",
  "home.install.heading": "依存 2 つと main メソッド",
  "home.install.body":
    "マークアップ言語も、アノテーションプロセッサーも、ビルドプラグインもありません。ツールキットとバックエンドを追加して素の Java を書けば、ウィンドウができます。",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "macOS では JVM に <code>-XstartOnFirstThread</code> が必要です。初日に必ず出会う唯一のプラットフォーム固有の癖なので、3 クリック先ではなくここに書いてあります。",
  "home.install.more": "インストールガイドを読む",

  "home.features.eyebrow": "手に入るもの",

  "home.features.components.heading": "自分で作らなくていいコンポーネント一式",
  "home.features.components.body":
    "ボタン、入力欄、リスト、タブ、メニュー、ダイアログ、分割ペイン、カラーピッカー、棒・折れ線・ドーナツのチャート、そして 100 万行でも 20 行と同じコストで済む仮想化リスト。どれも色・形・密度をテーマから読むので、ハードコードされた値はなく、コンパクトモードは 1 行です。",
  "home.features.components.link": "すべて見る",

  "home.features.layout.heading": "頭に収まるレイアウト",
  "home.features.layout.body":
    "ウィジェット 4 つとマーカー 1 つ。列は積み、行は並べ、スタックは重ね、パディングは内側に寄せ、Expanded が残りの空間を誰が取るかを決めます。語彙はこれだけで、設定すべき制約ソルバーも、導入すべきレイアウトマネージャーもありません。",
  "home.features.layout.link": "レイアウトガイドを読む",
  "home.features.layout.caption": "列、行、分割、そして Expanded 1 つで組んだウィンドウ。",

  "home.features.forms.heading": "フレームワークのないフォーム",
  "home.features.forms.body":
    "入力欄はウィジェット、検証ルールはリスナー、送信はメソッド呼び出しです。バインドするものも登録するものもなく、ユーザーが直したその瞬間に入力欄の色が戻ります。",
  "home.features.forms.link": "フォームガイドを読む",
  "home.features.forms.caption": "ラベル、検証、選択、そしてアクション行。",

  "home.features.media.heading": "動画も 3D もウィジェット",
  "home.features.media.body":
    "物理ベースの 3D ビューポートと動画プレーヤーが、普通のウィジェットとして合成されます。スクロールビューが切り取り、スタックが上に描き、ラベルと同じようにレイアウトに参加します。",
  "home.features.media.link": "メディアガイドを読む",
  "home.features.media.caption": "普通のウィンドウに合成された 3D ビューポート。",

  "home.themes.heading": "あなたの製品の見た目に、ツールキットの見た目を持ち込まない",
  "home.themes.body":
    "アプリケーションは、それを作ったライブラリではなく、あなたの製品らしく見えるべきです。テーマは単なるデータ（すべての色、角の丸み、各コントロールが継承するサイズ段階）で、実行中に一度の呼び出しで差し替わります。ブランドの書体は自分の管理下のファイルから読み込めるので、ツールキット由来の見た目は何も残りません。",
  "home.themes.link": "テーマの仕組み",
  "home.themes.caption":
    "一つの画面、七つのテーマ。どの帯も背後のコードは同一です。",
  "home.themes.alt":
    "コントロールが密に並ぶ同じ画面を七回横並びに描画したもの。帯ごとにパレット、サイズ段階、書体が異なります。",

  "home.languages.heading": "ユーザーの言語で",
  "home.languages.body":
    "テキストは描画に使うのと同じ送り幅で測られ、フォントフォールバックは 1 文字ずつ働きます。だからラテン、ギリシャ、キリル、CJK が 1 つの文字列に混ざっても書体を選ぶ必要がありません。入力メソッドは入力欄の中で変換し、編集は書記素クラスタ単位で動くので、結合記号や複数コードポイントの絵文字が途中で切れることはありません。",
  "home.languages.alt":
    "同じ画面を日本語・簡体中国語・韓国語・ロシア語で撮影し、一つの窓に継ぎ合わせたもの。",
  "home.languages.link": "テキストのガイドを読む",
  "home.languages.caption": "このビルド中に 4 言語で撮った、同じ画面。",

  "home.limits.eyebrow": "採用を決める前に",
  "home.limits.heading": "Limn にできないこと",
  "home.limits.body":
    "どんなツールキットにも引き換えにするものがあります。3 週目に気づくより今読むほうがよいので、最初に書いておきます。",
  "home.limits.scripts.heading": "複雑なスクリプトのシェーピングは非対応",
  "home.limits.scripts.body":
    "アラビア文字、ヘブライ文字、インド系文字には、テキストスタックが実装していない文脈依存の結合と並べ替えが必要で、右から左へのレイアウト方向もありません。これらの言語の翻訳は、誤った形で描くくらいならと、意図的に公開していません。",
  "home.limits.a11y.heading": "スクリーンリーダーへの橋渡しなし",
  "home.limits.a11y.body":
    "キーボード操作とフォーカスリングは完成していますが、プラットフォームのアクセシビリティ API には何も公開していません。スクリーンリーダー対応が必須のアプリケーションには、まだ向いていません。",
  "home.limits.version.heading": "1.0 より前",
  "home.limits.version.body":
    "API はリリース間でまだ動きますし、描画経路は OpenGL だけです。バージョンを固定して、リリースノートを読んでください。",

  "home.closing.heading": "5 分で画面にウィンドウを",
  "home.closing.body":
    "インストールガイドは動くプログラムで終わります。その先はガイドと、コンポーネントギャラリーと、API リファレンスです。",

  // ------------------------------------------------------------- components
  "components.title": "Limn：コンポーネント",
  "components.description":
    "Limn のすべてのコンポーネントを、ツールキット自身が両方のパレットで描画し、その画像を生んだコードと並べています。",
  "components.eyebrow": "一式",
  "components.heading": "コンポーネント",
  "components.lede":
    "ここの画像はすべて、このビルド中にツールキットが描画したものです。コードはどれも、隣の画像を生んだそのコードです。",
  "components.filterLabel": "コンポーネントを絞り込む",
  "components.filterPlaceholder": "絞り込み…",
  "components.empty": "該当するものはありません。",
  "components.showCode": "コード",
  "components.play": "再生",
  "components.stop": "停止",
  "components.videoNote":
    "動画ビューは純 Java のテスト用ソースを使っているので、コーデックの対応範囲ではなくウィジェットの動作を示しています。この画像にネイティブデコーダーは関与していません。",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn：画面例",
  "showcase.description":
    "ツールキットが描画した画面全体。デモアプリケーション、3D ビューポート、フォーム、レイアウトしたウィンドウ、そして 4 言語の同じ画面。",
  "showcase.eyebrow": "画面全体",
  "showcase.heading": "画面例",
  "showcase.lede":
    "切り抜きでもモックアップでもありません。どれも、このサイトのビルド中にツールキットが描画したウィンドウです。",
  "showcase.kitchen.heading": "デモアプリケーション",
  "showcase.kitchen.body":
    "メニューバー、タブ、テーマ切り替え、そして実時間の性能表示を備えた 1 つのウィンドウに、全コンポーネントが入っています。",
  "showcase.forms.heading": "フォーム",
  "showcase.forms.body":
    "ラベル、検証つきの入力欄、選択、スイッチ、アクション行。フォームガイドの実例そのものです。",
  "showcase.layout.heading": "レイアウトしたウィンドウ",
  "showcase.layout.body":
    "ツールバー、コンテンツ枠の横のサイドバー、ステータス行。レイアウトガイドの実例そのものです。",
  "showcase.threeD.heading": "3D ビューポート",
  "showcase.threeD.body":
    "3 灯の下の物理ベースマテリアルを、リニアのハイダイナミックレンジターゲットに描画し、2D レイヤーとして合成しています。スクロールビューは他のウィジェットと同じようにこれを切り取ります。ドラッグで回転、スクロールでズーム。",

  "showcase.editor.heading": "同梱できるウィジェットとしてのテーマエディタ",
  "showcase.editor.body":
    "パレットはデータなので、それを編集することは一つの画面になります。そしてこれは、私たちのリポジトリに住む道具ではなく、あなたのアプリケーションが組み込めるモジュールです。角の丸みのスライダーを動かせば、同じフレームのうちに窓が着替えます。画面の中のすべての入力欄、ボタン、色見本がまとめて変わります。隣の判定は、それぞれの色がのりうるすべての面に対して測られるので、コントラストの足りないパレットは目に見えて落ちます。",
  "showcase.density.heading": "すべてのサイズ段階",
  "showcase.density.body":
    "同じ 5 つのコントロールを 5 回、上の XSMALL から下の XLARGE まで。幅もフォントもパディングも与えていません。各行に伝えるのはコントロールサイズだけで、パディング、文字、角の丸み、タップ領域がまとめて動きます。",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn：ライセンス",
  "licence.description":
    "Apache-2.0、同梱コンポーネントのライセンス、そして FFmpeg の状況についての率直な説明。",
  "licence.eyebrow": "条件",
  "licence.heading": "ライセンス",
  "licence.lede":
    "Apache License 2.0。明示的な特許許諾を含みます。商用利用も改変も再配布も、すべて認められています。",
  "licence.core.heading": "ツールキット本体",
  "licence.core.body":
    "<code>limn-toolkit</code> と <code>limn-components</code> は JDK 以外に依存がないので、この 2 つについては Apache-2.0 がすべてです。描画バックエンドは BSD-3-Clause の LWJGL を追加します。",
  "licence.fonts.heading": "フォント",
  "licence.fonts.body":
    "Roboto と Noto のフォールバックフォントは SIL Open Font License で配布されています。同梱コンポーネントはすべて、ライセンスとともにプロジェクトの NOTICE ファイルに記載しています。",
  "licence.mp3.heading": "MP3 のデコードは LGPL",
  "licence.mp3.body":
    "MP3 対応は LGPL-2.1 の JLayer によるもので、音声デコーダーのインターフェイスの背後に独立した jar として保たれています。配布物で LGPL の義務を避ける必要があれば、この依存だけを除外してください。WAV と Ogg Vorbis はそのまま動きます。",
  "licence.ffmpeg.heading": "FFmpeg による動画と、一緒に配布されるもの",
  "licence.ffmpeg.body":
    "任意の H.264 デコーダは、削り込んだ FFmpeg を LGPL-2.1-or-later として動的にリンクします。<b>そのネイティブライブラリは、すべてのデスクトップ対象について、公開される jar の中に同梱されます</b>。したがって <code>limn-video-ffmpeg</code> を含む配布物は FFmpeg を配布していることになり、jar はライセンス本文と必要な告知を併せて運びます。動的リンクで差し替え可能であり、それがこのライセンスの求めるところです。この module に依存しているものは他にありません。外してしまえば、ほかのメディア形式はすべてそのまま動きます。",
  "licence.notAdvice":
    "以上はいずれも法的助言ではありません。ライセンスを読み、ご自身の弁護士にご相談ください。",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn：プライバシー",
  "privacy.description":
    "このサイトが保存するもの、しないもの、そして選択を変える方法。計測を許可しない限り、Cookie もサードパーティへのリクエストもありません。計測は最初オフです。",
  "privacy.eyebrow": "プライバシー",
  "privacy.heading": "このサイトが保存するもの",
  "privacy.lede":
    "短く言えば、計測がオフのあいだは——届いた時点ではオフです——Cookie なし、サードパーティへのリクエストなし、あなたを特定するものもなし。許可したときにだけ、このサイトは Google Analytics を読み込みます。長い版は下にあります。短い版は、長い版が同じことを言っているときにだけ読む価値があるからです。",
  "privacy.storage.heading": "ブラウザーの中の 3 つの値",
  "privacy.storage.body":
    "選んだテーマは <code>starlight-theme</code> に、選んだ言語は <code>limn-language</code> に、プライバシーの確認への回答は <code>limn-consent</code> に保存されます。3 つともこのブラウザーのローカルストレージにあり、読むのはこのサイト自身のスクリプトだけで、サイトデータを消せば消えます。3 つとも無くても、ここのすべては問題なく動きます。",
  "privacy.language.heading": "言語の選ばれ方",
  "privacy.language.body":
    "英語のページに来ると、このサイトはあなたのブラウザーが訪問先すべてに元から伝えている言語の一覧を読み、その中にここで公開しているものがあれば、その翻訳へ案内します。この一覧は、アドレスを選ぶためにあなたのブラウザーの中で一度読まれるだけで、保存も送信もされません。選択として記録されるのはヘッダーで言語を選んだときだけで、それ以降はブラウザーの一覧よりその選択が使われます。翻訳されたアドレスから転送されることはないので、誰かが送ってくれたリンクは送られた言語のまま開きます。",
  "privacy.cookies.heading": "Cookie は計測を許可したときだけ",
  "privacy.cookies.body":
    "サイト自体はいかなる Cookie も設定しません。計測がオフのあいだは、リクエストに何かが付いて回ることも、他のサイトまであなたを追いかけることもありません。許可すると、Google Analytics が自身の Cookie（<code>_ga</code>、<code>_ga_…</code>）を設定します。ローカルストレージは Cookie ではありません。送信されることはなく、サーバーが要求することもできません。",
  "privacy.analytics.heading": "計測は、許可するまでオフ",
  "privacy.analytics.body":
    "このサイトは Google Analytics を使いますが、あなたの許可があるときだけです。プライバシーの確認にある計測スイッチは既定でオフで、そのオフは約束ではなく仕組みで守られています。タグは <code>text/plain</code> のブロックとして配置され、どのブラウザーもそれを実行しません。あなたが許可した瞬間にはじめて実行されるスクリプトに変わり、それ以前には変わりません。許可を取り消せば、二度と読み込まれません。",
  "privacy.thirdParty.heading": "計測を許可するまでは、外部から読み込むものはなし",
  "privacy.thirdParty.body":
    "フォントも画像もスタイルシートもスクリプトも、すべてこのドメインから届きます。ウェブフォントのサービスも CDN も、埋め込み動画もソーシャルウィジェットもありません。計測がオフのあいだは、ここのページを読むと通信先はちょうど 1 台のサーバーだけです。許可すると、タグが <code>googletagmanager.com</code> からも取得されます。",
  "privacy.hosting.heading": "ホスティング事業者から見えるもの",
  "privacy.hosting.body":
    "ページはホスティングサービス上の静的ファイルです。どのウェブサーバーとも同じく、リクエストそのもの（IP アドレス、要求されたページ、ブラウザーのユーザーエージェント）は見えており、それは事業者自身のログ方針に従います。プロジェクト側はサーバーもアカウント基盤もデータベースも運用しておらず、それらを受け取ることもありません。",
  "privacy.change.heading": "回答を変える",
  "privacy.change.body":
    "選択はいつでも変更でき、すぐに反映されます。同じリンクはすべてのページのフッターにあります。",
  "privacy.change.action": "プライバシーの選択を変更する",
  "privacy.noScript":
    "このボタンには JavaScript が必要です。スクリプトが無効なら、そもそも任意のものは何も動きません。ブラウザーでこのサイトのデータを消せば、保存された 3 つの値も消えます。",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn：ページが見つかりません",
  "notFound.eyebrow": "404",
  "notFound.heading": "そのページはありません",
  "notFound.body":
    "リンクが古いか、ページが移動した可能性があります。このサイトにあるのは、次のいずれかです。",
  "notFound.home": "ホームへ",
  "notFound.destinationsLabel": "代わりの行き先",
  "notFound.components.heading": "コンポーネント",
  "notFound.components.body": "すべてのウィジェットを描画し、各画像を生んだコードと並べています。",
  "notFound.showcase.heading": "画面例",
  "notFound.showcase.body": "このサイトのビルド中にツールキットが描画した画面全体。",
  "notFound.docs.heading": "ドキュメント",
  "notFound.docs.body": "インストール、レイアウト、フォーム、テーマ、配布のガイドです。",
  "notFound.api.heading": "API リファレンス",
  "notFound.api.body": "ソースから生成された、すべてのクラスとメソッド。",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn：このページは移動しました",
  "moved.eyebrow": "移動",
  "moved.heading": "このページには新しい置き場所があります",
  "moved.body": "はじめかたはガイドの一部になりました。移動しています…",
  "moved.link": "インストールガイドへ",
};
