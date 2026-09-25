/**
 * Simplified Chinese.
 *
 * Product and technology names stay as they are: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader types into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const zhHans: Catalog = {
  "site.name": "Limn",
  "site.tagline": "面向桌面 Java 的 UI 工具包。",

  "nav.primaryLabel": "站点",
  "nav.menu": "菜单",
  "nav.components": "组件",
  "nav.showcase": "界面",
  "nav.docs": "指南",
  "nav.api": "API",
  "nav.licence": "许可",
  "nav.privacy": "隐私",
  "nav.repository": "GitHub",
  "nav.skipToContent": "跳到正文",
  "footer.linksLabel": "项目链接",

  "codeBlock.copy": "复制",
  "codeBlock.copied": "已复制到剪贴板",

  "theme.label": "主题",
  "theme.system": "自动",
  "theme.light": "浅色",
  "theme.dark": "深色",

  "language.label": "语言",

  "consent.label": "隐私选择",
  "consent.title": "除非你允许统计，本站不使用 Cookie",
  "consent.body": "只有三样东西保存在这个浏览器里，别处都没有：你选择的主题、你选择的语言，以及你在这里给出的答复。任何可选项在你亲自打开之前都保持关闭。",
  "consent.more": "完整说明：保存了什么",
  "consent.accept": "全部允许",
  "consent.reject": "仅必要项",
  "consent.choose": "自行选择",
  "consent.save": "保存选择",
  "consent.alwaysOn": "始终启用",
  "consent.necessaryName": "严格必要",
  "consent.necessaryBody": "你选择的浅色或深色主题、你选择的语言，以及这条答复。三者都只存在于这个浏览器中，都不是 Cookie，也都不会离开这台机器。",
  "consent.analyticsName": "统计",
  "consent.analyticsBody":
    "Google Analytics，从 googletagmanager.com 载入。它会设置自己的 Cookie，并告诉项目哪些页面被阅读。它以被拦截的形式发布，只有你在此允许之后才会运行。",

  // ------------------------------------------------------------------- home
  "home.title": "Limn：面向桌面 Java 的 UI 工具包",
  "home.description":
    "用你自己的组件、布局、文本、图表、媒体与 3D，在 Java 上构建桌面应用。一个依赖，JDK 17，支持 Windows、macOS 与 Linux，Apache-2.0。",

  "home.hero.eyebrow": "Java 的桌面 UI",
  "home.hero.headline": "用 Java 写桌面应用，像素由自己绘制。",
  "home.hero.sub":
    "Limn 自己绘制每一个像素。组件、布局、文本、图表、媒体与 3D 视口，只需一个依赖，没有 Swing，没有 JavaFX，底下也没有原生工具包。",
  "home.hero.cta": "开始使用",
  "home.hero.secondary": "浏览组件",
  "home.hero.meta": "JDK 17 · Windows、macOS、Linux · Apache-2.0",
  "home.hero.built":
    "用 Claude Code 公开地做出来：代码由 Claude 编写，由人来把握方向、做验证。造它的代价已经付过了，你不必再付一次。",
  "home.hero.builtLink": "Limn 是怎么做出来的",
  "home.hero.caption": "本次构建期间由 Limn 渲染的演示应用。",

  "home.try.heading": "立刻试试",
  "home.try.body":
    "演示与主题编辑器，直接来自 Maven Central。无需克隆；没有 JDK 时 jbang 会自动获取。JBang 只解析一次最新版本并保留下来：要获取更新的版本，请加上 --fresh。",
  "home.try.osLabel": "操作系统",
  "home.try.macos": "macOS",
  "home.try.linux": "Linux",
  "home.try.windows": "Windows",
  "home.try.demoComment": "陈列",
  "home.try.editorComment": "主题编辑器",

  "home.install.eyebrow": "五分钟",
  "home.install.heading": "一个依赖和一个 main 方法",
  "home.install.body":
    "没有标记语言，没有注解处理器，没有构建插件。加上后端，它会把工具包一起带来，写普通的 Java，你就有了一个窗口。",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.mavenLabel": "pom.xml",
  "codeTabs.buildTool": "构建工具",
  "home.install.helloLabel": "Main.java",
  "home.try.macosNote":
    "在 macOS 上，JVM 需要 <code>-XstartOnFirstThread</code>。这是你第一天就会遇到的唯一平台怪癖，所以它写在这里，而不是三次点击之外。",
  "home.install.more": "阅读安装指南",

  "home.features.eyebrow": "你会得到什么",

  "home.features.components.heading": "一套不必自己造的组件",
  "home.features.components.body":
    "按钮、输入框、列表、标签页、菜单、对话框、分栏、取色器，柱状图、折线图与环形图，还有一个虚拟化列表，其中一百万行的开销与二十行相同。每一个都从主题读取颜色、形状与密度，所以没有写死的值，紧凑模式只要一行。",
  "home.features.components.link": "查看全部",

  "home.features.layout.heading": "装得进脑子的布局",
  "home.features.layout.body":
    "四个组件加一个标记：列纵向堆叠，行横向铺开，栈层层叠加，内边距向内收，Expanded 决定谁占据剩下的空间。整套词汇就这些。没有约束求解器要配置，也没有布局管理器要安装。",
  "home.features.layout.link": "阅读布局指南",
  "home.features.layout.caption": "一个由列、行、分栏和一个 Expanded 组成的窗口。",
  "home.features.layout.alt": "用 Limn 排版的窗口",

  "home.features.forms.heading": "不需要框架的表单",
  "home.features.forms.body":
    "字段就是组件，校验规则就是监听器，提交就是一次方法调用。没有要绑定的，也没有要注册的；用户一改正，校验状态就把字段的颜色变回去。",
  "home.features.forms.link": "阅读表单指南",
  "home.features.forms.caption": "标签、校验、一个选择项和操作行。",
  "home.features.forms.alt": "用 Limn 构建的表单",

  "home.features.data.heading": "表格、树与日期，已经造好",
  "home.features.data.body":
    "表格是架在你的应用自己持有的列表上的带类型的列，由工具包或你的服务器排序，还有一行会求和的表尾。树是架在你提供的子节点上的大纲，一行可以在说出子节点的名字之前先承诺它们存在。日期字段以读者自己的历法输入一个 ISO 日期，日期选择器则在它后面打开一个月份网格。",
  "home.features.data.link": "阅读列表指南",
  "home.features.data.caption": "一个窗口里的表格、树、日期字段和日期选择器。",
  "home.features.data.alt": "Limn 窗口中的表格、树和两个日期组件",

  "home.features.media.heading": "视频与 3D 同样是组件",
  "home.features.media.body":
    "基于物理的 3D 视口和视频播放器，像普通组件一样参与合成：滚动视图会裁剪它们，栈会画在它们上面，它们参与布局的方式与一个标签无异。",
  "home.features.media.link": "阅读媒体指南",
  "home.features.media.caption": "合成进普通窗口中的 3D 视口。",
  "home.features.media.alt": "Limn 的 3D 视口",

  "home.themes.heading": "是你的产品气质，不是工具包的",
  "home.themes.body":
    "你的应用该像你的产品，而不像它所依赖的那个库。主题就是纯数据（每一种颜色、圆角半径、每个控件继承的尺寸档位），运行时一次调用即可整套替换。品牌字体从你自己掌握的文件加载，工具包自带的观感一点都不会留下。",
  "home.themes.link": "主题是怎么工作的",
  "home.themes.caption":
    "同一个界面，七套主题。每一条背后的代码完全相同。",
  "home.themes.alt":
    "同一个控件密集的界面并排渲染七次，每一条都用不同的配色、尺寸档位和字体。",

  "home.everyone.eyebrow": "为每一个人",
  "home.everyone.heading": "每一种语言，每一种屏幕阅读器",

  "home.languages.heading": "用你的用户的语言",
  "home.languages.body":
    "拉丁、希腊、西里尔与中日韩文字可混在同一个字符串里，字体按同一种文字的连续片段选择。阿拉伯文、希伯来文、天城文和泰文会被正确塑形——连写、合字、附加符号——从右到左的语言则让整个窗口镜像：行、边距、滚动条、方向键。输入法在字段内组字，编辑按字素移动。",
  "home.languages.alt":
    "同一个界面分别用日语、简体中文、韩语和俄语截取，拼接成一个窗口。",
  "home.languages.link": "阅读文本指南",
  "home.languages.caption": "同一个界面，在本次构建中以四种语言截取。",

  "home.accessibility.heading": "用同样的语言，读出来",
  "home.accessibility.body":
    "NVDA、VoiceOver 和 Orca 通过各平台自己的无障碍 API 读取 Limn 窗口，你的应用无需任何平台代码。每个控件都以界面所用的语言，自己说出角色、名称和状态。为控件命名只需一次调用。",
  "home.accessibility.link": "阅读无障碍指南",
  "home.accessibility.caption":
    "上面的表单，正如屏幕阅读器被告知的那样，在本次构建中捕获。",

  "home.built.eyebrow": "公开",
  "home.built.heading": "Limn 是怎么做出来的",
  "home.built.intro":
    "Limn 的代码由 Anthropic 的模型 Claude 在 Claude Code 中编写，由人来把握方向并加以验证。我们一开始就说明这一点，因为你会想知道。",
  "home.built.method":
    "把握方向，指的是每一项设计决定都先在 <code>docs/adr</code> 里写成文字、经过论证并被接受，然后才有代码。加以验证，指的是每一次改动都要跑过比代码本身还庞大的测试套件；无障碍不靠假设，而是真的去听——让 NVDA、VoiceOver 和 Orca 在 Windows、macOS 和 Linux 上朗读真实的窗口；代码也经过了多轮关于正确性、性能和 API 设计的审查。",
  "home.built.cost":
    "走到这一步，用了成千上万次模型调用和数以百万计的生成 token。按 COCOMO II 估算模型，不算测试，这十余万行代码相当于一支约二十人的团队两年多的工作量。这份工作已经做完了。你可以在它之上构建，而不必重做一遍。",
  "home.built.link": "阅读设计记录",

  "home.closing.heading": "五分钟，让窗口出现在屏幕上",
  "home.closing.body":
    "安装指南以一个能跑起来的程序收尾。此后的一切，就是指南、组件画廊和 API 参考。",

  // ------------------------------------------------------------- components
  "components.title": "Limn：组件",
  "components.description":
    "Limn 的每一个组件，都由工具包自身在两套配色下渲染，并与生成该图的代码并排呈现。",
  "components.eyebrow": "全套",
  "components.heading": "组件",
  "components.lede":
    "这里的每一张图都是本次构建中由工具包渲染的，每一段代码都是生成旁边那张图的代码。",
  "components.filterLabel": "筛选组件",
  "components.filterPlaceholder": "筛选…",
  "components.empty": "没有匹配的内容。",
  "components.showCode": "代码",
  "components.play": "播放",
  "components.stop": "停止",
  "components.videoNote":
    "视频视图使用纯 Java 的测试源，因此展示的是组件本身的工作情况，而不是编解码器的覆盖范围。这张图中没有任何原生解码器参与。",
  "components.mediaCredit":
    '这组播放控件下的影片是 <a href="https://peach.blender.org/">Big Buck Bunny</a>，© 2008 Blender Foundation，依 <a href="https://creativecommons.org/licenses/by/3.0/">CC BY 3.0</a> 使用。本页其余所有图像都出自工具包本身。',

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn：界面",
  "showcase.description":
    "由工具包渲染的完整界面：演示应用、从右向左镜像的同一窗口、3D 视口、一个表单和一个完成布局的窗口。",
  "showcase.rtl.heading": "同一窗口，从右向左",
  "showcase.rtl.body":
    "在 ar 翻译下把方向轴翻转的演示应用：菜单栏、标签页、输入框、滚动条和性能页脚都从右侧读起，其中的文字按阿拉伯文应有的方式连写与重排。没有重写任何界面——方向是一条继承的轴，像控件尺寸一样设置。",
  "showcase.eyebrow": "完整界面",
  "showcase.heading": "界面",
  "showcase.lede":
    "不是裁剪图，也不是效果图。每一张都是本站构建期间工具包渲染出的窗口。",
  "showcase.kitchen.heading": "演示应用",
  "showcase.kitchen.body":
    "所有组件集中在一个窗口里，带菜单栏、标签页、主题选择器，以及一条实时性能状态栏。",
  "showcase.forms.heading": "一个表单",
  "showcase.forms.body":
    "标签、带校验的字段、一个选择项、一个开关和操作行：表单指南里的完整示例。",
  "showcase.layout.heading": "完成布局的窗口",
  "showcase.layout.body":
    "工具栏、内容区旁的侧边栏，以及一行状态：布局指南里的完整示例。",
  "showcase.threeD.heading": "3D 视口",
  "showcase.threeD.body":
    "三盏灯下的基于物理的材质，渲染到线性高动态范围目标，再作为一个 2D 图层合成。滚动视图裁剪它，和裁剪其他任何组件一样。拖动可环绕观察，滚动可缩放。",

  "showcase.editor.heading": "主题编辑器，一个你可以随包发布的控件",
  "showcase.editor.body":
    "配色就是数据，所以编辑它本身就是一个界面。而这个界面是你的应用可以嵌入的模块，不是住在我们仓库里的工具。拖动圆角滑块，窗口就在同一帧里换装：画面里的每个输入框、按钮和色块。旁边的报告会把每种墨色对着它可能落下的每一种表面来量，所以对比度不过关的配色会当场露馅。",
  "showcase.density.heading": "每一档尺寸",
  "showcase.density.body":
    "同样的五个控件，重复五次，从上方的 XSMALL 到下方的 XLARGE。没有一个被指定宽度、字体或内边距：每一行只被告知一个控件尺寸，而内边距、字号、圆角与点按区域会一起变化。",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn：许可",
  "licence.description":
    "Apache-2.0、随附组件各自的许可，以及关于 FFmpeg 现状的坦白说明。",
  "licence.eyebrow": "条款",
  "licence.heading": "许可",
  "licence.lede":
    "Apache License 2.0，含明确的专利授权。商业使用、修改与再分发均获允许。",
  "licence.core.heading": "工具包本身",
  "licence.core.body":
    "<code>limn-toolkit</code> 除 JDK 外没有任何依赖，所以对它而言，Apache-2.0 就是全部。渲染后端会引入 LWJGL，它采用 BSD-3-Clause。",
  "licence.fonts.heading": "字体",
  "licence.fonts.body":
    "Roboto 与 Noto 回退字体以 SIL Open Font License 分发。每个随附组件及其许可都列在项目的 NOTICE 文件中。",
  "licence.mp3.heading": "MP3 解码采用 LGPL",
  "licence.mp3.body":
    "MP3 支持来自 JLayer，它是 LGPL-2.1，并作为一个独立的 jar 保存在音频解码接口之后。如果你的分发需要避免 LGPL 义务，只排除这一个依赖即可。WAV 与 Ogg Vorbis 照常可用。",
  "licence.ffmpeg.heading": "FFmpeg 视频，以及随之分发的东西",
  "licence.ffmpeg.body":
    "可选的视频解码器支持 MP4 和 Matroska 中的 H.264、HEVC、VP9 与 VP8，动态链接一份精简的 FFmpeg，按 LGPL-2.1-或更高版本 构建。<b>它的原生库是一个独立的制品 <code>limn-ffmpeg-natives</code>，每个桌面目标一个 classifier，另有 <code>limn-video-ffmpeg-natives-all</code> 列出全部，作为走遍所有平台的一揽子依赖</b>，因此包含 <code>limn-video-ffmpeg</code> 及这些库的分发就是在分发 FFmpeg，而这些 jar 各自带着许可证文本和必需的声明。它们是动态链接且可替换的，这正是该许可证的要求。没有别的东西依赖这个模块：去掉它，其他媒体格式照旧工作。",
  "licence.notAdvice": "以上均不构成法律意见。请阅读许可全文，并咨询你自己的律师。",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn：隐私",
  "privacy.description":
    "本站保存什么、不保存什么，以及如何更改你的选择。在你允许统计之前，没有 Cookie，也没有第三方请求；统计默认关闭。",
  "privacy.eyebrow": "隐私",
  "privacy.heading": "本站保存了什么",
  "privacy.lede":
    "简版：统计关闭时——它就是这样送到你手上的——没有 Cookie，没有第三方请求，也没有任何能识别你的信息。只有在你允许之后，本站才会载入 Google Analytics。详版在下面，因为简版只有在详版与之一致时才值得一读。",
  "privacy.storage.heading": "浏览器里的三个值",
  "privacy.storage.body":
    "你选择的主题保存在 <code>starlight-theme</code> 下，你选择的语言保存在 <code>limn-language</code> 下，你对隐私提示的答复保存在 <code>limn-consent</code> 下。三者都位于这个浏览器的本地存储中，都只被本站自己的脚本读取，清除站点数据即可删除。三者都不存在时，这里的一切照常工作。",
  "privacy.language.heading": "语言是怎么选定的",
  "privacy.language.body":
    "当你打开一个英文页面时，本站会读取浏览器本来就会告知每个网站的语言列表；如果其中有本站已发布的语言，就把你带到那份译文。这份列表只在你的浏览器里被读取一次，用来选一个网址：既不保存，也不发送。只有在页头选择语言才算作一次选择，此后就用它而不是浏览器的列表。译文网址永远不会被重定向，所以别人发给你的链接会以发送时的语言打开。",
  "privacy.cookies.heading": "只有你允许统计时才有 Cookie",
  "privacy.cookies.body":
    "本站自身不设置任何 Cookie：统计关闭时，不会有东西附在请求上，也不会有东西跟着你去别的网站。一旦允许，Google Analytics 会设置它自己的 Cookie（<code>_ga</code>、<code>_ga_…</code>）。本地存储不是 Cookie：它从不被传输，服务器也无法索取。",
  "privacy.analytics.heading": "统计默认关闭，直到你允许",
  "privacy.analytics.body":
    "本站使用 Google Analytics，且仅在你允许时。隐私提示中的统计开关默认关闭，而这个关闭是被强制执行的，不是口头承诺：标签以 <code>text/plain</code> 区块的形式发布，任何浏览器都不会执行它；只有在你允许的那一刻，它才会变成真正运行的脚本，在此之前不会。撤回允许后，它不会再被载入。",
  "privacy.thirdParty.heading": "在你允许统计之前，不从别处加载任何东西",
  "privacy.thirdParty.body":
    "每一个字体、图片、样式表和脚本都来自本域名。没有网络字体服务，没有 CDN，没有嵌入视频，也没有社交组件：统计关闭时，在这里读一个页面只会与一台服务器通信。一旦允许，标签还会从 <code>googletagmanager.com</code> 取得。",
  "privacy.hosting.heading": "托管方能看到什么",
  "privacy.hosting.body":
    "页面是托管服务上的静态文件。和任何 Web 服务器一样，它能看到请求本身（IP 地址、被请求的页面、浏览器的 User-Agent），这由它自己的日志策略决定。本项目不运行服务器，没有账号系统，也没有数据库，这些内容一概不会到达我们这里。",
  "privacy.change.heading": "更改你的答复",
  "privacy.change.body":
    "你的选择随时可以更改，并立即生效。每个页面的页脚都有同一个链接。",
  "privacy.change.action": "更改我的隐私选择",
  "privacy.noScript": "此按钮需要 JavaScript。若脚本关闭，可选项本来就不会运行；在浏览器中清除本站数据，即可删除那三个保存的值。",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn：找不到页面",
  "notFound.eyebrow": "404",
  "notFound.heading": "这个页面不存在",
  "notFound.body":
    "链接可能已经过时，或者页面已经搬家。本站拥有的内容，就是下面这几处。",
  "notFound.home": "前往首页",
  "notFound.destinationsLabel": "改去哪里",
  "notFound.components.heading": "组件",
  "notFound.components.body": "每一个组件的渲染图，以及生成该图的代码。",
  "notFound.showcase.heading": "界面",
  "notFound.showcase.body": "本站构建期间工具包渲染出的完整界面。",
  "notFound.docs.heading": "文档",
  "notFound.docs.body": "安装、布局、表单、主题与发布：这就是指南。",
  "notFound.api.heading": "API 参考",
  "notFound.api.body": "由源码生成的每一个类与每一个方法。",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn：此页已迁移",
  "moved.eyebrow": "已迁移",
  "moved.heading": "这个页面有了新的位置",
  "moved.body": "“开始使用”现在是指南的一部分。正在带你过去…",
  "moved.link": "前往安装指南",
};
