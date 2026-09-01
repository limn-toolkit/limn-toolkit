/**
 * Brazilian Portuguese.
 *
 * Product and technology names are not translated: `Swing`, `JavaFX`, `LWJGL`, `FFmpeg`,
 * `Apache-2.0` and the module names are what a reader will type into a build file. Every
 * fragment of markup a string carries is preserved exactly.
 */
import type { Catalog } from "./index";

export const ptBR: Catalog = {
  "site.name": "Limn",
  "site.tagline": "Um toolkit de UI para Java no desktop.",

  "nav.primaryLabel": "Site",
  "nav.menu": "Menu",
  "nav.components": "Componentes",
  "nav.showcase": "Telas",
  "nav.docs": "Guia",
  "nav.api": "API",
  "nav.licence": "Licença",
  "nav.privacy": "Privacidade",
  "nav.repository": "GitHub",
  "nav.skipToContent": "Ir para o conteúdo",
  "footer.linksLabel": "Links do projeto",

  "codeBlock.copy": "Copiar",
  "codeBlock.copied": "Copiado para a área de transferência",

  "theme.label": "Tema",
  "theme.system": "Automático",
  "theme.light": "Claro",
  "theme.dark": "Escuro",

  "language.label": "Idioma",

  "consent.label": "Escolhas de privacidade",
  "consent.title": "Nenhum cookie enquanto você não permitir a medição",
  "consent.body":
    "Três coisas ficam guardadas neste navegador e em nenhum outro lugar: o tema que você escolhe, o idioma que você escolhe e a resposta que você dá aqui. Tudo o que é opcional permanece desligado até você ligar.",
  "consent.more": "O que é guardado, na íntegra",
  "consent.accept": "Permitir tudo",
  "consent.reject": "Só o necessário",
  "consent.choose": "Escolher",
  "consent.save": "Salvar escolhas",
  "consent.alwaysOn": "Sempre ativo",
  "consent.necessaryName": "Estritamente necessário",
  "consent.necessaryBody":
    "O tema claro ou escuro que você escolhe, o idioma que você escolhe e esta resposta. Os três são locais a este navegador, nenhum deles é cookie e nenhum sai da máquina.",
  "consent.analyticsName": "Medição",
  "consent.analyticsBody":
    "O Google Analytics, carregado de googletagmanager.com. Ele define cookies próprios e informa ao projeto quais páginas são lidas. Vai publicado bloqueado e só passa a rodar depois que você permite aqui.",

  // ------------------------------------------------------------------- home
  "home.title": "Limn: um toolkit de UI para Java no desktop",
  "home.description":
    "Construa aplicações desktop em Java com seus próprios widgets, layout, texto, gráficos, mídia e 3D. Uma dependência, JDK 17, Windows, macOS e Linux. Apache-2.0.",

  "home.hero.eyebrow": "UI desktop para Java",
  "home.hero.headline": "Aplicações desktop em Java, desenhadas do zero.",
  "home.hero.sub":
    "O Limn desenha os próprios pixels. Widgets, layout, texto, gráficos, mídia e um viewport 3D, em uma dependência, sem Swing, sem JavaFX e sem toolkit nativo por baixo.",
  "home.hero.cta": "Começar",
  "home.hero.secondary": "Ver os componentes",
  "home.hero.meta": "JDK 17 · Windows, macOS, Linux · Apache-2.0",
  "home.hero.caption": "A aplicação de demonstração, renderizada pelo Limn durante este build.",

  "home.install.eyebrow": "Cinco minutos",
  "home.install.heading": "Uma dependência e um método main",
  "home.install.body":
    "Sem linguagem de marcação, sem processador de anotações, sem plugin de build. Adicione o backend, que traz o toolkit junto, escreva Java puro e você tem uma janela.",
  "home.install.gradleLabel": "build.gradle.kts",
  "home.install.helloLabel": "Main.java",
  "home.install.macos":
    "No macOS a JVM precisa de <code>-XstartOnFirstThread</code>. É a única peculiaridade de plataforma que você encontra no primeiro dia, então ela está aqui e não a três cliques de distância.",
  "home.install.more": "Ler o guia de instalação",

  "home.features.eyebrow": "O que você ganha",

  "home.features.components.heading": "Um conjunto de componentes que você não precisa construir",
  "home.features.components.body":
    "Botões, campos, listas, abas, menus, diálogos, painéis divididos, um seletor de cor, gráficos de barra, linha e rosca, e uma lista virtualizada em que um milhão de linhas custa o mesmo que vinte. Cada um deles lê a cor, a forma e a densidade do tema, então nada fica fixo no código e um modo compacto é uma linha.",
  "home.features.components.link": "Ver todos",

  "home.features.layout.heading": "Um layout que cabe na cabeça",
  "home.features.layout.body":
    "Quatro widgets e um marcador: uma coluna empilha, uma linha distribui, uma pilha sobrepõe, o padding recua e o Expanded diz quem fica com o espaço que sobrou. Esse é todo o vocabulário; não há solver de restrições para configurar nem layout manager para instalar.",
  "home.features.layout.link": "Ler o guia de layout",
  "home.features.layout.caption": "Uma janela feita de uma coluna, uma linha, um split e um Expanded.",

  "home.features.forms.heading": "Formulários sem framework",
  "home.features.forms.body":
    "Um campo é um widget, uma regra de validação é um listener e submeter é uma chamada de método. Nada para vincular, nada para registrar, e estados de validação que recolorem o campo no instante em que o usuário corrige.",
  "home.features.forms.link": "Ler o guia de formulários",
  "home.features.forms.caption": "Rótulos, validação, uma escolha e a linha de ações.",

  "home.features.media.heading": "Vídeo e 3D também são widgets",
  "home.features.media.body":
    "Um viewport 3D com materiais fisicamente corretos e um player de vídeo, compostos como widgets comuns: uma scroll view os recorta, uma pilha desenha por cima e ambos participam do layout como um rótulo participa.",
  "home.features.media.link": "Ler o guia de mídia",
  "home.features.media.caption": "O viewport 3D, composto em uma janela comum.",

  "home.themes.heading": "Sua identidade, não a do toolkit",
  "home.themes.body":
    "Sua aplicação deve parecer com o seu produto, não com a biblioteca em que foi feita. Um tema é dado puro (cada cor, o raio dos cantos, o passo de tamanho que todo controle herda), e uma chamada troca tudo em tempo de execução. Carregue a tipografia da sua marca de um arquivo seu, e nada da aparência do toolkit sobrevive.",
  "home.themes.link": "Como funciona a temização",
  "home.themes.caption":
    "Uma tela, sete temas. O código por trás de cada faixa é idêntico.",
  "home.themes.alt":
    "A mesma tela densa de controles renderizada sete vezes lado a lado, cada faixa em uma paleta, um passo de tamanho e uma tipografia diferentes.",

  "home.languages.heading": "Nos idiomas dos seus usuários",
  "home.languages.body":
    "O texto é medido com os mesmos avanços com que é desenhado, e o fallback de fonte roda caractere a caractere, então latino, grego, cirílico e CJK se misturam em uma mesma string sem você escolher tipografia. Os métodos de entrada compõem dentro do campo, e a edição anda por cluster de grafema, então marcas combinantes e emoji de várias partes nunca são partidos ao meio. E a direita para a esquerda não é só shaping: a direção é um eixo que a subárvore herda, então uma janela em hebraico se espelha — e ainda pode conter um painel de código da esquerda para a direita.",
  "home.languages.alt":
    "A mesma tela capturada em japonês, chinês simplificado, coreano e russo, costurada em uma única janela.",
  "home.languages.link": "Leia o guia de texto",
  "home.languages.caption": "A mesma tela, capturada em quatro idiomas durante este build.",

  "home.limits.eyebrow": "Antes de se comprometer",
  "home.limits.heading": "O que o Limn não faz",
  "home.limits.body":
    "Todo toolkit troca alguma coisa. Estas são as trocas, ditas de saída, porque descobri-las na terceira semana é pior do que lê-las agora.",
  "home.limits.scripts.heading": "Somente texto horizontal",
  "home.limits.scripts.body":
    "O árabe, o hebraico, o devanágari e o tailandês têm shaping onde quer que haja texto, o leiaute é espelhado por um eixo herdado da direita para a esquerda, e dígitos, ordenação e maiúsculas seguem o idioma — os pacotes ar e he são publicados, com um Bold para cada escrita. O que não existe é escrita vertical: nada de colunas de cima para baixo, nenhum segundo eixo atravessando o leiaute. Linhas quebram; não são justificadas.",
  "home.limits.a11y.heading": "Sem ponte para leitores de tela",
  "home.limits.a11y.body":
    "A navegação por teclado e os anéis de foco estão completos, mas nada é exposto às APIs de acessibilidade da plataforma. Se um leitor de tela precisa funcionar, este ainda não é o toolkit para essa aplicação.",
  "home.limits.version.heading": "Pré-1.0",
  "home.limits.version.body":
    "A API ainda se move entre releases, e OpenGL é o único caminho de renderização. Fixe a sua versão e leia as notas de release.",

  "home.closing.heading": "Uma janela na tela em cinco minutos",
  "home.closing.body":
    "O guia de instalação termina com um programa rodando. Tudo depois disso é o guia, a galeria de componentes e a referência da API.",

  // ------------------------------------------------------------- components
  "components.title": "Limn: Componentes",
  "components.description":
    "Cada componente do Limn, renderizado pelo próprio toolkit nas duas paletas, ao lado do código que produziu cada imagem.",
  "components.eyebrow": "O conjunto",
  "components.heading": "Componentes",
  "components.lede":
    "Cada imagem daqui foi renderizada pelo toolkit durante este build, e cada trecho é o código que produziu a imagem ao lado.",
  "components.filterLabel": "Filtrar componentes",
  "components.filterPlaceholder": "Filtrar…",
  "components.empty": "Nada corresponde a isso.",
  "components.showCode": "Código",
  "components.play": "Reproduzir",
  "components.stop": "Parar",
  "components.videoNote":
    "A view de vídeo usa a fonte de teste em Java puro, então ela mostra o widget funcionando e não a cobertura de codecs. Nenhum decodificador nativo participa desta imagem.",

  // ---------------------------------------------------------------- showcase
  "showcase.title": "Limn: Telas",
  "showcase.description":
    "Telas inteiras renderizadas pelo toolkit: a aplicação de demonstração, a mesma janela espelhada da direita para a esquerda, o viewport 3D, um formulário e uma janela com layout.",
  "showcase.rtl.heading": "A mesma janela, da direita para a esquerda",
  "showcase.rtl.body":
    "A aplicação de demonstração sob o pacote árabe com o eixo de direção invertido: a barra de menus, as abas, os campos, as barras de rolagem e o rodapé de desempenho passam a ler a partir da direita, e o texto dentro deles se une e se reordena como o árabe pede. Nenhuma tela foi reescrita — a direção é um eixo herdado, definido como se define um tamanho de controle.",
  "showcase.eyebrow": "Telas inteiras",
  "showcase.heading": "Telas",
  "showcase.lede":
    "Não são recortes nem mock-ups. Cada uma delas é uma janela que o toolkit renderizou enquanto este site era construído.",
  "showcase.kitchen.heading": "A aplicação de demonstração",
  "showcase.kitchen.body":
    "Todos os componentes em uma janela, com barra de menus, abas, seletor de tema e um rodapé de desempenho ao vivo.",
  "showcase.forms.heading": "Um formulário",
  "showcase.forms.body":
    "Rótulos, um campo validado, uma escolha, um switch e a linha de ações: o exemplo completo do guia de formulários.",
  "showcase.layout.heading": "Uma janela com layout",
  "showcase.layout.body":
    "Uma barra de ferramentas, uma barra lateral ao lado de um painel de conteúdo e uma linha de status: o exemplo completo do guia de layout.",
  "showcase.threeD.heading": "O viewport 3D",
  "showcase.threeD.body":
    "Materiais fisicamente corretos sob três luzes, renderizados em um alvo linear de alta faixa dinâmica e compostos como uma camada 2D. Uma scroll view o recorta como recortaria qualquer outro widget. Arraste para orbitar, role para dar zoom.",

  "showcase.editor.heading": "O editor de tema, como um widget que você pode embarcar",
  "showcase.editor.body":
    "Uma paleta é dado, então editá-la é uma tela; esta é um módulo que sua aplicação pode embutir, não uma ferramenta que vive no nosso repositório. Arraste o slider de cantos e a janela se re-veste no mesmo frame: cada campo, botão e poço da imagem. O relatório ao lado mede cada tinta contra toda superfície onde ela pode cair, então uma paleta que falha em contraste falha à vista.",
  "showcase.density.heading": "Todos os passos de tamanho",
  "showcase.density.body":
    "Os mesmos cinco controles, cinco vezes, do XSMALL no topo até o XLARGE embaixo. Nenhum deles recebe largura, fonte ou padding: cada linha recebe um tamanho de controle e nada mais, e o padding, a tipografia, os raios de canto e as áreas de toque se movem juntos.",

  // ----------------------------------------------------------------- licence
  "licence.title": "Limn: Licença",
  "licence.description":
    "Apache-2.0, sob que licença estão os componentes embarcados e uma declaração honesta sobre a situação do FFmpeg.",
  "licence.eyebrow": "Termos",
  "licence.heading": "Licença",
  "licence.lede":
    "Apache License 2.0, incluindo concessão explícita de patentes. Uso comercial, modificação e redistribuição são todos permitidos.",
  "licence.core.heading": "O toolkit em si",
  "licence.core.body":
    "<code>limn-toolkit</code> não tem dependências além do JDK, então para ele a Apache-2.0 é a história inteira. O backend de renderização acrescenta a LWJGL, que é BSD-3-Clause.",
  "licence.fonts.heading": "Fontes",
  "licence.fonts.body":
    "Roboto e as fontes de fallback Noto são distribuídas sob a SIL Open Font License. Cada componente embarcado está listado com sua licença no arquivo NOTICE do projeto.",
  "licence.mp3.heading": "A decodificação de MP3 é LGPL",
  "licence.mp3.body":
    "O suporte a MP3 vem do JLayer, que é LGPL-2.1 e é mantido como um jar isolado atrás da interface de decodificação de áudio. Exclua essa única dependência se a sua distribuição precisar evitar obrigações LGPL; WAV e Ogg Vorbis continuam funcionando.",
  "licence.ffmpeg.heading": "Vídeo com FFmpeg, e o que vai junto",
  "licence.ffmpeg.body":
    "O decodificador H.264 opcional liga um FFmpeg reduzido dinamicamente, compilado como LGPL-2.1-ou-posterior. <b>Suas bibliotecas nativas vão em um classifier por alvo de desktop, e em <code>natives-all</code> para um pacote que serve em toda parte</b>, então uma distribuição que inclui <code>limn-video-ffmpeg</code> está distribuindo FFmpeg, e o jar carrega o texto da licença e o aviso exigido. Elas são ligadas dinamicamente e substituíveis, que é o que essa licença pede. Nada mais depende desse módulo: deixe-o fora e todo outro formato de mídia continua funcionando.",
  "licence.notAdvice":
    "Nada disto é aconselhamento jurídico. Leia as licenças e consulte o seu próprio advogado.",

  // ----------------------------------------------------------------- privacy
  "privacy.title": "Limn: Privacidade",
  "privacy.description":
    "O que este site guarda, o que ele não guarda e como mudar a sua escolha. Sem cookies e sem requisições a terceiros enquanto você não permitir a medição, que chega desligada.",
  "privacy.eyebrow": "Privacidade",
  "privacy.heading": "O que este site guarda",
  "privacy.lede":
    "Versão curta: com a medição desligada, que é como ela chega, não há cookies, não há requisições a terceiros e não há nada que identifique você. Se você permitir, o site carrega o Google Analytics, e só então. A versão longa está abaixo, porque uma versão curta só vale a leitura se a longa concordar com ela.",
  "privacy.storage.heading": "Três valores, no seu navegador",
  "privacy.storage.body":
    "O tema que você escolhe é guardado em <code>starlight-theme</code>, o idioma que você escolhe em <code>limn-language</code>, e a sua resposta ao aviso de privacidade em <code>limn-consent</code>. Os três vivem no armazenamento local deste navegador, os três são lidos apenas pelos scripts do próprio site, e limpar os dados do site os remove. Tudo aqui funciona com os três ausentes.",
  "privacy.language.heading": "Como o seu idioma é escolhido",
  "privacy.language.body":
    "Ao chegar a uma página em inglês, o site lê os idiomas que o seu navegador já anuncia a todos os sites que você visita e, se um deles for publicado aqui, leva você para essa tradução. Essa lista é lida uma vez, no seu navegador, para escolher um endereço: ela não é guardada nem transmitida. Escolher um idioma no cabeçalho é o que registra uma escolha, e a partir daí ela é usada no lugar da lista do navegador. De um endereço traduzido você nunca é redirecionado, então um link que alguém lhe enviar abre no idioma em que foi enviado.",
  "privacy.cookies.heading": "Cookies só se você permitir a medição",
  "privacy.cookies.body":
    "O site em si não define cookie de espécie alguma: com a medição desligada, nada é anexado a uma requisição e nada segue você até outro site. Se você permitir, o Google Analytics define os dele, <code>_ga</code> e <code>_ga_…</code>. Armazenamento local não é cookie: ele nunca é transmitido, e um servidor não pode pedi-lo.",
  "privacy.analytics.heading": "Medição, desligada até você permitir",
  "privacy.analytics.body":
    "O site usa o Google Analytics, e só com a sua permissão. A chave de medição no aviso de privacidade vem desligada, e esse desligado é imposto em vez de prometido: a tag é publicada como um bloco <code>text/plain</code>, que navegador nenhum executa, e vira um script em execução no momento em que você permite, não antes. Se você retirar a permissão, ela não é carregada de novo.",
  "privacy.thirdParty.heading": "Nada carregado de outro lugar, até você permitir a medição",
  "privacy.thirdParty.body":
    "Toda fonte, imagem, folha de estilo e script vem deste domínio. Sem serviço de webfont, sem CDN, sem vídeo embutido e sem widget social: com a medição desligada, ler uma página daqui conversa com exatamente um servidor. Se você permitir, a tag também é buscada em <code>googletagmanager.com</code>.",
  "privacy.hosting.heading": "O que a hospedagem enxerga",
  "privacy.hosting.body":
    "As páginas são arquivos estáticos em um serviço de hospedagem. Como qualquer servidor web, ele enxerga a própria requisição (um endereço IP, a página pedida, o user agent do navegador), e a política de registro dele governa isso. O projeto não roda servidor, não tem sistema de contas nem banco de dados, e não recebe nada disso.",
  "privacy.change.heading": "Mudar a sua resposta",
  "privacy.change.body":
    "A sua escolha pode ser alterada a qualquer momento, e passa a valer na hora. O mesmo link está no rodapé de todas as páginas.",
  "privacy.change.action": "Mudar as minhas escolhas de privacidade",
  "privacy.noScript":
    "Este botão precisa de JavaScript. Com o script desligado, nada opcional roda em primeiro lugar, e limpar os dados deste site no seu navegador remove os três valores guardados.",

  // --------------------------------------------------------------- footer/404

  "notFound.title": "Limn: página não encontrada",
  "notFound.eyebrow": "404",
  "notFound.heading": "Essa página não existe",
  "notFound.body":
    "O link pode estar desatualizado, ou a página pode ter mudado de lugar. Tudo o que o site tem é uma destas.",
  "notFound.home": "Ir para a página inicial",
  "notFound.destinationsLabel": "Para onde ir em vez disso",
  "notFound.components.heading": "Componentes",
  "notFound.components.body":
    "Cada widget, renderizado, com o código que produziu cada imagem.",
  "notFound.showcase.heading": "Telas",
  "notFound.showcase.body":
    "Telas inteiras que o toolkit renderizou enquanto este site era construído.",
  "notFound.docs.heading": "Documentação",
  "notFound.docs.body": "Instalação, layout, formulários, temas e publicação: o guia.",
  "notFound.api.heading": "Referência da API",
  "notFound.api.body": "Cada classe e cada método, gerados a partir do código-fonte.",

  // ------------------------------------------------------------------ moved
  "moved.title": "Limn: esta página mudou de lugar",
  "moved.eyebrow": "Movida",
  "moved.heading": "Esta página tem um novo endereço",
  "moved.body": "Começar agora faz parte do guia. Levando você até lá…",
  "moved.link": "Ir para o guia de instalação",
};
