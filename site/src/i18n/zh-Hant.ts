/**
 * Traditional Chinese.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const zhHant: Catalog = {
  "site.name": "Limn",
  "site.tagline": "為桌面 Java 打造的 UI 工具組。",

  "nav.primaryLabel": "網站",
  "nav.menu": "選單",
  "nav.components": "元件",
  "nav.showcase": "畫面",
  "nav.docs": "指南",
  "nav.api": "API",
  "nav.licence": "授權",
  "nav.privacy": "隱私",
  "nav.repository": "GitHub",
  "nav.skipToContent": "跳至內容",
  "footer.linksLabel": "專案連結",

  "codeBlock.copy": "複製",
  "codeBlock.copied": "已複製到剪貼簿",

  "theme.label": "主題",
  "theme.system": "自動",
  "theme.light": "淺色",
  "theme.dark": "深色",

  "language.label": "語言",

  "consent.label": "隱私選擇",
  "consent.title": "除非你允許統計，本站不使用 Cookie",
  "consent.body": "只有三樣東西存在這個瀏覽器裡，別處都沒有：你選的主題、你選的語言，以及你在這裡給的答覆。任何選用項目在你親自開啟之前都維持關閉。",
  "consent.more": "完整說明：儲存了什麼",
  "consent.accept": "全部允許",
  "consent.reject": "僅必要項目",
  "consent.choose": "自行選擇",
  "consent.save": "儲存選擇",
  "consent.alwaysOn": "永遠啟用",
  "consent.necessaryName": "嚴格必要",
  "consent.necessaryBody": "你選的淺色或深色主題、你選的語言，以及這則答覆。三者都只存在於這個瀏覽器，都不是 Cookie，也都不會離開這台機器。",
  "consent.analyticsName": "統計",
  "consent.analyticsBody":
    "Google Analytics，從 googletagmanager.com 載入。它會設定自己的 Cookie，並告訴專案哪些頁面被閱讀。它以被攔截的形式發布，只有你在此允許之後才會執行。",

  // ------------------------------------------------------------------- home
  "home.title": "Limn：為桌面 Java 打造的 UI 工具組",
  "home.description":
    "用你自己的元件、版面、文字、圖表、媒體與 3D，在 Java 上打造桌面應用程式。一個相依套件，JDK 17，支援 Windows、macOS 與 Linux，Apache-2.0。",

  "home.hero.eyebrow": "Java 的桌面 UI",
  "home.hero.headline": "用 Java 寫桌面應用程式，像素自己畫。",
  "home.hero.sub":
    "Limn 自己畫出每一個像素。元件、版面、文字、圖表、媒體與 3D 視埠，只要一個相依套件，沒有 Swing，沒有 JavaFX，底下也沒有原生工具組。",
  "home.hero.cta": "開始使用",
  "home.hero.secondary": "瀏覽元件",
  "home.hero.meta": "JDK 17 · Windows、macOS、Linux · Apache-2.0",
  "home.hero.caption": "本次建置期間由 Limn 繪製的示範應用程式。",

  "home.install.eyebrow": "五分鐘",
  "home.install.heading": "一個相依套件和一個 main 方法",
  "home.install.body":
    "沒有標記語言，沒有註解處理器，沒有建置外掛。加上後端，它會把工具組一起帶來，寫普通的 Java，你就有了一個視窗。",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "在 macOS 上，JVM 需要 <code>-XstartOnFirstThread</code>。這是你第一天就會遇到的唯一平台怪癖，所以它寫在這裡，而不是三次點擊之外。",
  "home.install.more": "閱讀安裝指南",

  "home.features.eyebrow": "你會得到什麼",

  "home.features.components.heading": "一套不必自己做的元件",
  "home.features.components.body":
    "按鈕、輸入欄、清單、分頁、選單、對話框、分割窗格、選色器，長條圖、折線圖與環圈圖，還有一個虛擬化清單，其中一百萬列的成本與二十列相同。每一個都從主題讀取顏色、形狀與密度，所以沒有寫死的值，緊湊模式只要一行。",
  "home.features.components.link": "查看全部",

  "home.features.layout.heading": "裝得進腦袋的版面",
  "home.features.layout.body":
    "四個元件加一個標記：欄往下堆疊，列往旁鋪開，堆疊層層相覆，內距往內縮，Expanded 決定誰拿走剩下的空間。整套語彙就這些。沒有約束求解器要設定，也沒有版面管理員要安裝。",
  "home.features.layout.link": "閱讀版面指南",
  "home.features.layout.caption": "由一欄、一列、一個分割與一個 Expanded 組成的視窗。",

  "home.features.forms.heading": "不需要框架的表單",
  "home.features.forms.body":
    "欄位就是元件，驗證規則就是監聽器，送出就是一次方法呼叫。沒有要繫結的，也沒有要註冊的；使用者一改正，驗證狀態就把欄位的顏色換回來。",
  "home.features.forms.link": "閱讀表單指南",
  "home.features.forms.caption": "標籤、驗證、一個選項與動作列。",

  "home.features.media.heading": "影片與 3D 同樣是元件",
  "home.features.media.body":
    "基於物理的 3D 視埠與影片播放器，像一般元件那樣參與合成：捲動視圖會裁切它們，堆疊會畫在它們上面，它們參與版面的方式和一個標籤沒有兩樣。",
  "home.features.media.link": "閱讀媒體指南",
  "home.features.media.caption": "合成進一般視窗中的 3D 視埠。",

  "home.themes.heading": "是你的產品氣質，不是工具包的",
  "home.themes.body":
    "你的應用該像你的產品，而不像它所依賴的那個函式庫。主題就是純資料（每一種顏色、圓角半徑、每個控件繼承的尺寸級距），執行時一次呼叫即可整套替換。品牌字體從你自己掌握的檔案載入，工具包自帶的觀感一點都不會留下。",
  "home.themes.link": "主題是怎麼運作的",
  "home.themes.caption":
    "同一個介面，七套主題。每一條背後的程式碼完全相同。",
  "home.themes.alt":
    "同一個控件密集的介面並排渲染七次，每一條都用不同的配色、尺寸級距與字體。",

  "home.languages.heading": "用你的使用者的語言",
  "home.languages.body":
    "文字以繪製時相同的前進量來量測，字型遞補逐字元進行，所以拉丁文、希臘文、西里爾文與中日韓文可以混在同一個字串裡，而你不必挑字體。輸入法在欄位內完成組字，編輯以字素叢集為單位移動，因此組合符號與多段式表情永遠不會被切開。",
  "home.languages.alt":
    "同一個介面分別用日語、簡體中文、韓語與俄語擷取，拼接成一個視窗。",
  "home.languages.link": "閱讀文字指南",
  "home.languages.caption": "同一個畫面，在本次建置中以四種語言擷取。",

  "home.limits.eyebrow": "在你投入之前",
  "home.limits.heading": "Limn 做不到的事",
  "home.limits.body":
    "任何工具組都有取捨。這些就是取捨，先講清楚，因為到第三週才發現，比現在讀到糟糕得多。",
  "home.limits.scripts.heading": "字形會正確排版，版面不會鏡像",
  "home.limits.scripts.body":
    "阿拉伯文、希伯來文、天城文與泰文在任何繪製文字的地方都會連寫、會重排，標記也落在該在的位置——Label、TextField、TextArea 裡如此，它們之外的每個按鈕、分頁、選單項目與預留文字也是如此，ar 與 he 的翻譯照常發布。由右至左的語言拿不到的是版面：內邊距、對齊、捲軸在哪一側、彈出選單從哪側展開、文字欄位之外按方向鍵會往哪邊走，不論什麼語言都由左至右。",
  "home.limits.a11y.heading": "沒有螢幕閱讀器橋接",
  "home.limits.a11y.body":
    "鍵盤導覽與焦點框是完整的，但沒有向平台的無障礙 API 公開任何內容。若螢幕閱讀器必須可用，這個工具組目前還不適合那樣的應用程式。",
  "home.limits.version.heading": "1.0 之前",
  "home.limits.version.body":
    "API 在各版本之間仍會變動，而 OpenGL 是唯一的繪製路徑。請鎖定版本並閱讀發行說明。",

  "home.closing.heading": "五分鐘，讓視窗出現在畫面上",
  "home.closing.body":
    "安裝指南以一個跑得起來的程式收尾。之後的一切，就是指南、元件展示與 API 參考。",

  // ------------------------------------------------------------- components
  "components.title": "Limn：元件",
  "components.description":
    "Limn 的每一個元件，都由工具組本身以兩套配色繪製，並與產生該圖的程式碼並列。",
  "components.eyebrow": "全套",
  "components.heading": "元件",
  "components.lede":
    "這裡的每一張圖都是本次建置中由工具組繪製的，每一段程式碼都是產生旁邊那張圖的程式碼。",
  "components.filterLabel": "篩選元件",
  "components.filterPlaceholder": "篩選…",
  "components.empty": "沒有符合的項目。",
  "components.showCode": "程式碼",
  "components.play": "播放",
  "components.stop": "停止",
  "components.videoNote":
    "影片視圖使用純 Java 的測試來源，因此展示的是元件本身的運作，而不是編解碼器的支援範圍。這張圖中沒有任何原生解碼器參與。",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn：畫面",
  "showcase.description":
    "由工具組繪製的完整畫面：示範應用程式、3D 視埠、一個表單、一個完成版面的視窗，以及四種語言下的同一個畫面。",
  "showcase.eyebrow": "完整畫面",
  "showcase.heading": "畫面",
  "showcase.lede":
    "不是裁切圖，也不是示意圖。每一張都是本站建置期間工具組繪製出的視窗。",
  "showcase.kitchen.heading": "示範應用程式",
  "showcase.kitchen.body":
    "所有元件集中在一個視窗裡，帶選單列、分頁、主題選擇器，以及一條即時效能狀態列。",
  "showcase.forms.heading": "一個表單",
  "showcase.forms.body":
    "標籤、帶驗證的欄位、一個選項、一個切換開關與動作列：表單指南裡的完整範例。",
  "showcase.layout.heading": "完成版面的視窗",
  "showcase.layout.body":
    "工具列、內容區旁的側邊欄，以及一行狀態：版面指南裡的完整範例。",
  "showcase.threeD.heading": "3D 視埠",
  "showcase.threeD.body":
    "三盞燈下基於物理的材質，繪製到線性高動態範圍目標，再作為一個 2D 圖層合成。捲動視圖裁切它，就和裁切其他任何元件一樣。拖曳可環繞觀看，捲動可縮放。",

  "showcase.editor.heading": "主題編輯器，一個你可以隨包發布的控件",
  "showcase.editor.body":
    "配色就是資料，所以編輯它本身就是一個介面。而這個介面是你的應用可以嵌入的模組，不是住在我們倉庫裡的工具。拖動圓角滑桿，視窗就在同一格裡換裝：畫面裡的每個輸入框、按鈕與色塊。旁邊的報告會把每種墨色對著它可能落下的每一種表面來量，所以對比度不過關的配色會當場露餡。",
  "showcase.density.heading": "每一階尺寸",
  "showcase.density.body":
    "同樣的五個控制項，重複五次，從上方的 XSMALL 到下方的 XLARGE。沒有一個被指定寬度、字型或內距：每一列只被告知一個控制項尺寸，而內距、字級、圓角與點按範圍會一起變化。",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn：授權",
  "licence.description":
    "Apache-2.0、隨附元件各自的授權，以及關於 FFmpeg 現況的坦白說明。",
  "licence.eyebrow": "條款",
  "licence.heading": "授權",
  "licence.lede":
    "Apache License 2.0，含明確的專利授權。商業使用、修改與再散布皆獲允許。",
  "licence.core.heading": "工具組本身",
  "licence.core.body":
    "<code>limn-toolkit</code> 除 JDK 外沒有任何相依套件，所以對它而言，Apache-2.0 就是全部。繪製後端會引入 LWJGL，它採用 BSD-3-Clause。",
  "licence.fonts.heading": "字型",
  "licence.fonts.body":
    "Roboto 與 Noto 遞補字型以 SIL Open Font License 散布。每個隨附元件及其授權都列在專案的 NOTICE 檔案中。",
  "licence.mp3.heading": "MP3 解碼採用 LGPL",
  "licence.mp3.body":
    "MP3 支援來自 JLayer，它是 LGPL-2.1，並作為一個獨立的 jar 保存在音訊解碼介面之後。若你的散布需要避開 LGPL 義務，只排除這一個相依套件即可。WAV 與 Ogg Vorbis 照常可用。",
  "licence.ffmpeg.heading": "FFmpeg 影片，以及隨之散布的東西",
  "licence.ffmpeg.body":
    "選用的 H.264 解碼器動態連結一份精簡的 FFmpeg，以 LGPL-2.1-或更高版本 建置。<b>它的原生庫按每個桌面目標一個 classifier 發布，另有一個涵蓋所有平台的 <code>natives-all</code></b>，因此包含 <code>limn-video-ffmpeg</code> 的散布就是在散布 FFmpeg，而 jar 裡帶著授權條款全文與必要的聲明。它們是動態連結且可替換的，這正是該授權的要求。沒有別的東西依賴這個模組：拿掉它，其他媒體格式照舊運作。",
  "licence.notAdvice": "以上皆不構成法律意見。請閱讀授權全文，並諮詢你自己的律師。",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn：隱私",
  "privacy.description":
    "本站儲存什麼、不儲存什麼，以及如何更改你的選擇。在你允許統計之前，沒有 Cookie，也沒有第三方請求；統計預設關閉。",
  "privacy.eyebrow": "隱私",
  "privacy.heading": "本站儲存了什麼",
  "privacy.lede":
    "簡版：統計關閉時——它就是這樣送到你手上的——沒有 Cookie，沒有第三方請求，也沒有任何能辨識你的資訊。只有在你允許之後，本站才會載入 Google Analytics。詳版在下面，因為簡版只有在詳版與之一致時才值得一讀。",
  "privacy.storage.heading": "瀏覽器裡的三個值",
  "privacy.storage.body":
    "你選的主題儲存在 <code>starlight-theme</code> 下，你選的語言儲存在 <code>limn-language</code> 下，你對隱私提示的答覆儲存在 <code>limn-consent</code> 下。三者都位於這個瀏覽器的本機儲存空間，都只被本站自己的指令碼讀取，清除網站資料即可刪除。三者都不存在時，這裡的一切照常運作。",
  "privacy.language.heading": "語言是怎麼決定的",
  "privacy.language.body":
    "當你開啟一個英文頁面時，本站會讀取瀏覽器本來就會告知每個網站的語言清單；若其中有本站已發布的語言，就把你帶到那份譯文。這份清單只在你的瀏覽器裡被讀取一次，用來選一個網址：既不儲存，也不傳送。只有在頁首選擇語言才算作一次選擇，此後就用它而不是瀏覽器的清單。譯文網址永遠不會被重新導向，所以別人傳給你的連結會以傳送時的語言開啟。",
  "privacy.cookies.heading": "只有你允許統計時才有 Cookie",
  "privacy.cookies.body":
    "本站自身不設定任何 Cookie：統計關閉時，不會有東西附在請求上，也不會有東西跟著你去別的網站。一旦允許，Google Analytics 會設定它自己的 Cookie（<code>_ga</code>、<code>_ga_…</code>）。本機儲存空間不是 Cookie：它從不被傳送，伺服器也無法索取。",
  "privacy.analytics.heading": "統計預設關閉，直到你允許",
  "privacy.analytics.body":
    "本站使用 Google Analytics，且僅在你允許時。隱私提示中的統計開關預設關閉，而這個關閉是被強制執行的，不是口頭承諾：標籤以 <code>text/plain</code> 區塊的形式發布，任何瀏覽器都不會執行它；只有在你允許的那一刻，它才會變成真正執行的指令碼，在此之前不會。撤回允許後，它不會再被載入。",
  "privacy.thirdParty.heading": "在你允許統計之前，不從別處載入任何東西",
  "privacy.thirdParty.body":
    "每一個字型、圖片、樣式表與指令碼都來自本網域。沒有網頁字型服務，沒有 CDN，沒有嵌入影片，也沒有社群小工具：統計關閉時，在這裡讀一個頁面只會與一台伺服器通訊。一旦允許，標籤還會從 <code>googletagmanager.com</code> 取得。",
  "privacy.hosting.heading": "代管方能看到什麼",
  "privacy.hosting.body":
    "頁面是代管服務上的靜態檔案。和任何網頁伺服器一樣，它能看到請求本身（IP 位址、被請求的頁面、瀏覽器的 User-Agent），這由它自己的記錄政策決定。本專案不營運伺服器，沒有帳號系統，也沒有資料庫，這些內容一概不會到達我們這裡。",
  "privacy.change.heading": "更改你的答覆",
  "privacy.change.body":
    "你的選擇隨時可以更改，並立即生效。每個頁面的頁尾都有同一個連結。",
  "privacy.change.action": "更改我的隱私選擇",
  "privacy.noScript": "此按鈕需要 JavaScript。若指令碼關閉，選用項目本來就不會執行；在瀏覽器中清除本站資料，即可刪除那三個儲存的值。",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn：找不到頁面",
  "notFound.eyebrow": "404",
  "notFound.heading": "這個頁面不存在",
  "notFound.body":
    "連結可能已經過時，或者頁面已經搬家。本站擁有的內容，就是下面這幾處。",
  "notFound.home": "前往首頁",
  "notFound.destinationsLabel": "改去哪裡",
  "notFound.components.heading": "元件",
  "notFound.components.body": "每一個元件的繪製圖，以及產生該圖的程式碼。",
  "notFound.showcase.heading": "畫面",
  "notFound.showcase.body": "本站建置期間工具組繪製出的完整畫面。",
  "notFound.docs.heading": "文件",
  "notFound.docs.body": "安裝、版面、表單、主題與發布：這就是指南。",
  "notFound.api.heading": "API 參考",
  "notFound.api.body": "由原始碼產生的每一個類別與每一個方法。",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn：此頁已搬移",
  "moved.eyebrow": "已搬移",
  "moved.heading": "這個頁面有了新的位置",
  "moved.body": "「開始使用」現在是指南的一部分。正在帶你過去…",
  "moved.link": "前往安裝指南",
};
