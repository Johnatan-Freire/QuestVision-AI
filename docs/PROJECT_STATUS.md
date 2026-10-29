# QuestVision AI — Status do projeto

_Última atualização: 2026-10-24 (Fase 3.1 concluída e validada no aparelho) · Fase 1 concluída e validada no aparelho · Fase 2 (detecção automática) implementada e com build/testes ok; **validação no aparelho pendente**_

## 1. Objetivo

O **QuestVision AI** usa a câmera traseira do celular, apontada para o monitor de um computador,
para identificar questões de exercícios, interpretá-las com IA multimodal e enviar a resposta
explicada para um bot do Telegram.

A **Fase 1** constrói apenas a base Android: preview contínuo da câmera, foco, zoom, captura
manual legível de texto em tela, armazenamento privado e revisão da captura. **Não há** IA, OCR,
Telegram, captura automática nem backend nesta fase, e nenhuma imagem sai do aparelho.

## 2. Origem e licença

Fork de [android/camera-samples](https://github.com/android/camera-samples) (Apache License 2.0).
Todos os arquivos mantêm o cabeçalho Apache; o Spotless (CI) exige o cabeçalho de
`spotless/copyright.kt` em todo `.kt`/`.kts`, inclusive nos arquivos novos. Os samples originais
foram mantidos intactos e continuam no build.

## 3. Arquitetura

Mantida a arquitetura do repositório (ver `AGENTS.md` e `android_architecture.md`): Compose-first,
fluxo unidirecional, Hilt, e quatro peças por tela — `UiState` → `ViewModel` → `Controller` →
`Screen` — mais um repositório para a camada de dados.

```
:app                  Activity única + NavHost. Destino inicial: QuestVision.
                      O catálogo de samples só é acessível em builds debug.
:questvision-camera   NOVO — tela principal do QuestVision (CameraX)
:core-camera          reaproveitado — CameraXPreview, rotação de display, permissões
:core-ui              reaproveitado — scaffold de permissão, controles, overlays, revisão
:core-theme           reaproveitado — tema "Console"
:samples:*            mantidos (referência), sem alterações
```

### Módulo `:questvision-camera` (`com.questvision.camera`)

| Arquivo | Responsabilidade |
| --- | --- |
| `QuestVisionCameraUiState` | `Initial` · `Previewing` (status da câmera, zoom, captura pendente, revisão, histórico, monitoramento e estatísticas) · `Unsupported` (sem câmera traseira) · `Error` (falha ao vincular a câmera) |
| `QuestVisionCameraViewModel` | Estado único em `StateFlow`; regras de captura (bloqueio de duplo toque), revisão, descarte. Sem `Context`. Implementa `CameraSessionListener`. |
| `QuestVisionCameraController` | Ciclo de vida CameraX: `Preview` + `ImageCapture` na câmera traseira, observação de `CameraState`/`ZoomState`, foco por toque, zoom, gravação do JPEG. Único lugar com `Context`. |
| `QuestVisionCameraScreen` | Compose: permissão, preview, status, zoom, obturador, monitoramento, botão Histórico, revisão. |
| `CaptureHistory` | Compose: grade do histórico de capturas (sobreposta à câmera). |
| `CaptureRepository` | Arquivos em `noBackupFilesDir/captures` (privado, fora do backup em nuvem); retenção (abaixo); decodificação reduzida (≤ 2048 px) e orientada por EXIF, em `Dispatchers.IO`. |

### Retenção de capturas

| Pasta | Conteúdo | Política |
| --- | --- | --- |
| `no_backup/captures/` | capturas recentes (não decididas) | só as **20** mais recentes (`MAX_RECENT_CAPTURES`); as mais antigas são apagadas ao abrir a tela e após cada captura |
| `no_backup/captures/kept/` | capturas que o usuário escolheu **Manter** | **nunca** apagadas automaticamente |

Na prévia: **Manter** move o arquivo para `kept/`; **Descartar** apaga; **Voltar** (seta ou botão
voltar do sistema) apenas fecha, deixando a captura no conjunto recente sujeito à retenção. A captura
recém-feita é sempre a mais nova, portanto a retenção nunca a remove.

**Questões da IA (desde 2026-10-28):** toda captura cujo status de IA não é `NOT_REQUESTED`
(pendente, em processamento, com falha, respondida ou para revisão) fica fora da retenção, com a
foto e a linha. Só "Descartar" a remove.

**Capturas automáticas e retenção:** elas entram em `captures/` como as manuais, sem confirmação.
Uma captura automática **é apagada** quando passa a haver **20 capturas mais novas não mantidas**,
manuais ou automáticas. A remoção acontece na próxima captura salva ou ao abrir o app. Numa sessão
longa, a 21ª captura apaga a 1ª, e assim por diante. Para preservar uma captura, abra-a no
Histórico e toque em **Manter**. Fotos já existentes não foram movidas nem renomeadas.

### Captura otimizada para texto em monitor

- `CAPTURE_MODE_MAXIMIZE_QUALITY`, `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY`, JPEG qualidade 95.
- Flash sempre desligado (reflexo na tela).
- Preview e captura em 4:3, e o viewfinder não é recortado: o enquadrado é exatamente o capturado.
- Foco por toque com `FLAG_AF | FLAG_AE` (mede exposição na região tocada, evitando estourar o brilho
  do monitor); só habilitado quando `isFocusMeteringSupported`.
- O JPEG é gravado direto em arquivo pelo CameraX (fora da main thread) em resolução total; apenas a
  prévia é reduzida em memória.

### Preparação para as próximas fases

Separação prevista: **Camera** (este módulo) · **Detection** (Fase 2, pacote
`com.questvision.camera.detection`) · **Recognition** (OCR) · **Question** (modelo enunciado +
alternativas) · **Processing** (orquestração e deduplicação) · **AI** · **Telegram** · **Settings**.

Pontos de extensão existentes:

- `MonitoringSession` é onde entrará a confirmação por OCR: o candidato só vira captura ou envio
  depois do reconhecimento.
- `CaptureRepository` entrega arquivos JPEG de resolução total para OCR/IA.

## 3b. Fase 2: detecção automática de novas questões

### Componentes (pacote `com.questvision.camera.detection`)

| Arquivo | Responsabilidade | Thread |
| --- | --- | --- |
| `DetectionConfig` | Todos os limiares e intervalos (valores iniciais, ajustáveis) | — |
| `FrameSampler` | Amostragem por timestamp do quadro (padrão 500 ms ≈ 2 fps) | análise |
| `LumaFrameAnalyzer` | `ImageAnalysis.Analyzer`: amostra, reduz o plano Y e **sempre** fecha o `ImageProxy` (`finally`) | análise |
| `LumaFrame` | Assinatura compacta: grade 128×96, passa-alta normalizada e **mapa de estrutura** (energia local do texto); `structureDifference` / `fineDifference` | análise / main |
| `ScreenChangeDetector` | Mudança vs. referência, estabilidade, reversão, duplicidade (histórico) | main |
| `MonitoringSession` | Máquina de estados, tickets de captura, contagem, falhas, pausa | main |

O controller vincula **Preview + ImageCapture + ImageAnalysis** (640×480, 4:3,
`STRATEGY_KEEP_ONLY_LATEST`, executor próprio de uma thread). O analyzer só é instalado enquanto a
sessão está ativa (`setAnalyzer`/`clearAnalyzer`); fora disso o CameraX descarta os quadros de
análise. Se o aparelho não suportar os três casos de uso juntos, o controller revincula sem análise.
A captura manual continua funcionando e a UI informa que o monitoramento não é suportado. Nenhum JPEG
é decodificado ou comparado durante o monitoramento.

### Algoritmo

1. **Amostragem:** no máximo 1 quadro a cada `sampleIntervalMs` (500 ms); os demais são fechados
   imediatamente.
2. **Redução:** plano Y, ignorando 5% de cada borda, média por bloco em uma grade **128×96**.
3. **Normalização (tolerância a iluminação):** **passa-alta** (célula menos a média local 7×7,
   via imagem integral) remove gradientes de luz e reflexos difusos e mantém a estrutura de
   linhas/palavras. Em seguida, divisão pelo desvio-padrão (piso de 2 níveis de cinza), o que anula
   mudanças globais de brilho e contraste.
4. **Mapa de estrutura** (correção de 2026-10-21, seção 3d): |passa-alta| médio numa janela
   deslizante 2×2, dividido pela média. Registra *onde* há texto, não a fase subpixel das bordas
   das letras. **Todas as decisões usam esse mapa.** A comparação é a fração de células cuja energia
   muda mais de `structureCellThreshold` (0,5).
5. **Detecção** (`ScreenChangeDetector`):
   - **Movimento:** amostra anterior × atual, sem tolerância de deslocamento.
   - **Estabilidade por janela temporal:** a janela abre quando duas amostras seguidas coincidem
     (movimento ≤ limiar de entrada). A amostra anterior vira a **âncora**, e cada amostra seguinte
     precisa ficar dentro do limiar *em relação à âncora* (deriva). Isso pega rolagem lenta que
     parece parada quadro a quadro.
     - Tolera **1 amostra fora** por janela (piscada do AF, reflexo); essa amostra não conta como
       estável. A segunda reinicia a janela.
     - **Histerese:** permanecer tolera o limiar + 0,02.
     - **Limiar adaptativo limitado:** 2 × piso de ruído (percentil 25 das últimas 10 amostras),
       preso entre 0,04 e 0,08.
     - Quando o conteúdo **começa** a mudar, a janela recomeça. O tempo parado da tela anterior não
       conta para a nova.
   - **Mudança:** contra a referência (último conteúdo aceito), com tolerância de ±2 células
     (≈ 10 px); nova se > `changeThreshold` (0,05).

   Sequência: `WATCHING` → (mudança) `CHANGED` → (janela estável) `STABILIZING` → após
   `stabilizationMs` (1 s) de janela, decide:
   - **REVERTED:** voltou à referência (câmera esbarrada, janela que abriu e fechou); nada é capturado.
   - **DUPLICATE:** igual (≤ `duplicateThreshold`, 0,05) a uma das últimas 8 capturas (o
     *fingerprint* perceptual é o mapa de estrutura); vira a nova referência, sem captura.
   - **CANDIDATE:** captura automática. Só depois de salva ela vira a referência e entra no histórico.
6. **Sessão** (`MonitoringSession`):
   - `IDLE → MONITORING → CHANGE_DETECTED → STABILIZING → CAPTURING → MONITORING`, mais `PAUSED` e
     `ERROR`.
   - Uma captura por vez. Cada captura automática recebe um `CaptureTicket(sessionId, sequence)`;
     resultados com ticket de sessão encerrada são ignorados e não contam nem mudam o estado.
   - Quadros só são processados nos estados ativos, então callbacks antigos não disparam capturas.
   - Pausar durante a captura: o arquivo é salvo e contado, mas a sessão continua pausada.
   - 3 falhas seguidas de captura levam a `ERROR`; "Iniciar monitoramento" reinicia.
   - Se uma captura manual estiver em andamento, o candidato espera e é oferecido de novo na próxima
     amostra.
   - Quando a câmera fecha (app em segundo plano, saída da tela), a sessão **pausa** e não retoma
     sozinha.
7. **Ao iniciar a sessão**, ainda não há referência: a tela atual é capturada assim que fica 1 s
   parada. A sessão pressupõe que o usuário inicia o monitoramento com a primeira questão visível.

### Interface

- Barra de monitoramento acima do zoom: **Iniciar monitoramento**; durante a sessão **Pausar /
  Retomar** e **Encerrar**, mais o contador `Auto: n`.
- O chip de status mostra o estado da sessão (Monitorando, Alteração detectada, Estabilizando,
  Captura automática, Monitoramento pausado, Monitoramento interrompido). Problemas da câmera têm
  prioridade.
- Contorno **âmbar** no preview em "alteração detectada/estabilizando" e **branco** durante a captura
  automática.
- Aviso "Captura automática salva (n)" por 3 s. Capturas automáticas **não** abrem a prévia; o botão
  de galeria abre a última. A prévia de uma captura manual age sobre o arquivo exibido, mesmo que
  capturas automáticas aconteçam enquanto ela está aberta.
- Capturas automáticas vão para `captures/` e seguem a retenção (20 recentes; "Manter" preserva).

### Métricas para ajuste (logcat, tag `QuestVisionDetect`)

```bash
adb logcat -s QuestVisionDetect:D
```

| Linha | Significado |
| --- | --- |
| `frame 640x480 reduced in Nµs` | custo da redução na thread de análise |
| `detector step Nµs` | custo do detector na main thread |
| `t=… motion=… median=… fine=… drift=… floor=… thr=… change=… stable=…ms outliers=… luma=… sharp=… phase=… outcome=…` | valores por amostra (abaixo) |
| `auto capture #n requested / saved` | ciclo de captura automática |

| Campo | Significado | Como usar |
| --- | --- | --- |
| `motion` / `median` | estrutura vs. amostra anterior / mediana das últimas 3 | cena parada deve ficar < `thr` |
| `fine` | métrica **anterior** (passa-alta fino), só diagnóstico | `fine` alto com `motion` baixo indica ruído, subpixel ou foco, não movimento real |
| `drift` | estrutura vs. âncora da janela | cresce com rolagem lenta |
| `floor` / `thr` | piso de ruído medido / limiar em vigor | se `floor` ficar perto de 0,04, o aparelho é mais ruidoso que o simulado |
| `change` | estrutura vs. conteúdo aceito (1,0 = ainda não há referência, normal no início da sessão) | troca de questão deve ficar > 0,05 |
| `stable` / `outliers` | duração da janela estável / amostras toleradas | — |
| `luma` / `sharp` | brilho médio / nitidez (desvio do passa-alta) | `luma` variando: exposição/iluminação; `sharp` oscilando: autofoco caçando |

Ajuste os valores em `DetectionConfig`. Os padrões vêm das cenas renderizadas da seção 3d.

## 3c. Layout adaptativo (correção de landscape, 2026-10-20)

**Problema relatado** no Galaxy M52 durante o teste da Fase 2: em landscape o preview ficava
minúsculo e os controles ocupavam quase toda a tela.

**Causa:** `CameraContent` era sempre uma `Column` (barra de título, preview com `weight(1f)`,
controles). A pilha de controles (aviso, monitoramento, `ZoomControls` ≈ 130 dp e
`CameraControlsBar` com `padding(bottom = 48.dp)` + obturador de 70 dp) somava ≈ 330–350 dp de
altura, mais a barra de título. Em landscape o M52 tem ≈ 387 dp de altura útil, e o preview 4:3
recebia ≈ 40–60 dp. Não era problema do `CameraSampleScaffold` nem do `aspectRatio`.

**Correção** (só no módulo `:questvision-camera`; componentes compartilhados intactos):

- `CameraLayout` (novo): um `Layout` próprio com dois arranjos.
  - **Painel lateral** de 248 dp à direita, com o preview usando toda a altura restante, sempre que
    o espaço disponível é mais largo que alto (`BoxWithConstraints`: vale para rotação, janelas
    divididas e tablets).
  - **Empilhado** (barra em cima, preview, controles embaixo) nos demais casos. Em portrait o
    visual não mudou.
- O preview é **sempre o primeiro filho** do layout. Trocar de arranjo não descarta o
  `CameraXViewfinder`; recriá-lo com o mesmo `SurfaceRequest`, que só pode ser atendido uma vez,
  deixaria o preview preto após girar.
- O preview mantém 4:3 sem corte nem distorção (`aspectRatio`) e ocupa o máximo da área. Em
  landscape é limitado pela altura da tela.
- Painel lateral, de cima para baixo: título (+ atalho dos samples em debug), chip de status,
  aviso, botões de monitoramento (`FlowRow`, quebram linha se não couberem) e contador; zoom no
  meio; obturador e "última captura" embaixo. Os atalhos de zoom (0,5×/1×/2×/5×) somem quando o
  painel tem menos de 420 dp de altura, como no M52 em landscape; o slider continua. O painel só
  rola se a janela for baixa demais para tudo caber.
- Áreas seguras: o scaffold já aplica as barras do sistema; os controles também respeitam o recorte
  da tela (`WindowInsets.displayCutout`, sem contar de novo o que as barras já cobrem).
- **Rotação de 180°** (landscape → landscape invertido) não gera mudança de configuração, e o
  `rememberDisplayRotation()` do `:core-camera` não a percebia: preview e fotos ficariam de cabeça
  para baixo. O módulo agora usa `rememberLiveDisplayRotation()`, baseado em
  `DisplayManager.DisplayListener`.

**Verificações:** `spotlessCheck` ✅, `:questvision-camera:testDebugUnitTest` ✅ 27/27,
`:app:assembleDebug` ✅. **Validação visual pendente**: o aparelho não estava conectado ao ADB no
momento do build.

Roteiro no aparelho:

1. Portrait: o layout deve continuar igual ao anterior.
2. Girar para landscape: preview grande à esquerda, painel à direita, nada sobreposto. Obturador,
   zoom, Iniciar/Pausar/Encerrar e "última captura" devem responder ao toque.
3. Girar 180° (landscape invertido): o preview deve continuar em pé, sem travar; uma foto tirada
   assim deve sair na orientação certa.
4. Voltar para portrait durante o monitoramento: a sessão continua e o preview não fica preto.
5. Tocar para focar em landscape: a retícula deve aparecer no ponto tocado.
6. Opcional: tela dividida (multi-janela). O arranjo segue o espaço da janela.

## 3d. Correção da estabilização (2026-10-21)

**Sintoma no Galaxy M52:** com a questão parada no monitor, `motion` variou entre 0,0165 e 0,385,
`stable` ficou sempre em 0 ms, `phase` em `CHANGED` e nenhuma captura ocorreu. `change=1.0000` era
esperado: no início da sessão ainda não existe referência. O bloqueio era a estabilidade: com o
limiar de 0,015, a cena nunca era considerada parada.

**Causa:** a métrica de movimento comparava o **passa-alta com sinal** em 128×96. Cada célula tem
≈ 4,5 px do quadro de análise e as letras têm ≈ 3–4 px. Vibração subpixel, rolling shutter,
"respiração" do autofoco e exposição mudam o sinal do passa-alta nas bordas das letras. Com a tela
parada, isso basta para que ~1,5–5% das células passem de 0,5σ. Os testes da Fase 2 não pegaram o
problema porque usavam blocos alinhados à grade. Reproduzido com o novo `RenderedScreens`, que
desenha texto a 640×480 com essas perturbações e passa pelo mesmo `LumaFrame.fromYPlane` do
aparelho:

| Cena renderizada (consecutivas) | Métrica antiga (`fine`) | **Nova (estrutura)** |
| --- | --- | --- |
| Questão parada (subpixel ±0,5 px, foco, exposição ±4%, ruído ±4) | 0,000–**0,055** | 0,000–**0,020** |
| Rolagem 1 px/amostra | 0,183–0,222 | 0,101–0,120 |
| Rolagem 2 px/amostra | 0,435–0,477 | 0,289–0,324 |
| Rolagem 4 px/amostra | **0,050–0,083** | 0,263–0,293 |
| Rolagem 25 px/amostra | 0,444–0,503 | 0,452–0,471 |
| Troca de questão (±2 células) | — | 0,128–0,142 |

Na métrica antiga, o máximo da cena parada (0,055) **se sobrepõe** à rolagem de 4 px (0,050). Nenhum
limiar fixo separa os dois casos, então só aumentar o limiar não resolveria. Na nova, parada
≤ 0,020 < limiar base 0,04 ≤ teto adaptativo 0,08 < rolagem mais lenta 0,10.

**Correção:** mapa de estrutura e estabilidade por janela temporal (seção 3b, itens 4–5). Variantes
avaliadas e descartadas: blocos 2×2 não sobrepostos (não toleram deriva de meia célula) e janela
3×3 (suaviza demais; troca de questão caía para 0,057–0,070, colada no limiar).

**Métricas antes × depois:** os números acima são **simulados**. O "antes" real é o log do M52
(motion 0,0165–0,385, stable 0 ms). O "depois" real ainda **não foi medido**: o aparelho não estava
conectado ao ADB. O log agora traz `fine` (métrica antiga) ao lado de `motion` (nova) no mesmo
quadro, permitindo a comparação direta no próximo teste.

## 3e. Histórico de capturas e auditoria da automação (2026-10-22)

### Fluxo automático (verificado no código)

`MONITORING → CHANGE_DETECTED → STABILIZING → CAPTURING → (salva) → MONITORING`, sem nenhuma ação do
usuário depois de **Iniciar monitoramento**:

1. O `ImageAnalysis` entrega um `LumaFrame` a `ViewModel.onFrameAnalyzed`.
   `MonitoringSession.onFrame` devolve um `CaptureTicket`, e o ViewModel cria o arquivo e publica
   `pendingCapture` (estado `CAPTURING`).
2. Um `LaunchedEffect(controller, pendingCapture)` chama `controller.takePicture(file)`. É a mesma
   ponte estado → controller do zoom, não um clique simulado. O CameraX grava o JPEG fora da main
   thread.
3. `onImageSaved` chama `ViewModel.onCaptureSaved`, que **confere o arquivo no disco** (existe e não
   está vazio). Só então chama `onCaptureFinished(success)`, a sessão volta a `MONITORING`, aparece o
   aviso discreto "Captura automática salva (n)" e a retenção é aplicada.
4. **Não há** prévia, confirmação, "Manter" nem clique em nenhum ponto. A captura manual continua
   disponível e é a única que abre a prévia.

O estado "SAVED" do fluxo pedido corresponde ao passo 3: a captura é confirmada no disco e a sessão
volta direto a `MONITORING`. Não foi criado um estado separado só para esse instante.

Lacunas encontradas e corrigidas nesta etapa:

- O contador somava no `onImageSaved` mesmo que o arquivo não existisse ou estivesse vazio.
  Agora isso conta como **falha**, e o arquivo vazio é apagado.
- As capturas não registravam origem, sessão nem número.
- Só havia atalho para a última foto. Agora existe o Histórico.
- A sessão não aparecia na UI por causa de uma ambiguidade de nomes no ViewModel (encontrada pelos
  testes e corrigida).

### Metadados no nome do arquivo (sem banco de dados, sem migração)

- Manual: `capture_<yyyyMMdd_HHmmss_SSS>_manual.jpg`
- Automática: `capture_<yyyyMMdd_HHmmss_SSS>_auto_<sessão>_<n>.jpg`. A sessão é o horário de início
  (`yyyyMMdd-HHmmss`); `n` é o número da captura automática na sessão. **Lacunas na numeração indicam
  capturas que falharam.**
- Arquivos anteriores (`capture_<…>.jpg`) continuam válidos e aparecem como **Anterior**. Nenhum
  arquivo existente foi renomeado, movido ou apagado.

### Histórico

- Botão **Histórico** (ícone de galeria) no lugar do antigo "última captura": embaixo à esquerda do
  obturador em portrait e ao lado do obturador no painel lateral em landscape. O tamanho do preview
  não muda.
- Abre **sobre a câmera**, não em outra tela. A câmera e a sessão de monitoramento continuam rodando, e
  capturas automáticas feitas com o histórico aberto entram na lista na hora.
- Grade da mais recente para a mais antiga, com miniatura (decodificada reduzida, em cache), data e
  hora, selo **Auto nº n** / **Manual** / **Anterior** e marcador de **Mantida**. Tem estado vazio.
- Tocar abre a foto em tamanho de tela, com **Manter**, **Descartar** e **Voltar**. Isso funciona
  também para capturas automáticas.
- Voltar (seta ou botão do sistema) retorna à câmera.
- Cabeçalho com a contagem de fotos, as estatísticas da sessão e um lembrete da retenção.

### Auditoria (`MonitoringStats`)

| Contador | Significado |
| --- | --- |
| `changesDetected` | vezes que a tela começou a diferir do conteúdo aceito (inclui a primeira tela) |
| `capturesRequested` | capturas automáticas iniciadas |
| `capturesSaved` | confirmadas no disco; **é o `Auto: n`** |
| `capturesFailed` | falha do CameraX ou arquivo ausente/vazio; nunca contam como salvas |
| `duplicatesIgnored` | conteúdo repetido não capturado |
| `changesReverted` | mudanças que voltaram ao conteúdo aceito (esbarrão, mão na frente) |
| `staleResultsIgnored` | resultados de sessões já encerradas |

Invariante: `capturesRequested = capturesSaved + capturesFailed` (fora a captura ainda em andamento).
`capturesSaved` = número de fotos "Auto" da sessão no histórico, desde que a retenção ainda não
tenha apagado alguma. Aparecem no cabeçalho do histórico e no log (`adb logcat -s
QuestVisionDetect`, linhas `auto capture … MonitoringStats(...)`).

### Testes (JVM)

| Suíte | Cobre |
| --- | --- |
| `QuestVisionCameraViewModelTest` (8) | o teste só faz o papel da câmera (quadros, gravação, callback) e nunca chama ações de usuário após iniciar: captura automática sem interação; salvamento com sucesso; falha de gravação; arquivo vazio = falha; contador = arquivos "Auto" no histórico com resultados mistos; duas questões geram duas imagens e o monitoramento continua; persistência após "reinício" (novo ViewModel sobre o mesmo disco); captura manual durante o monitoramento; resultado de sessão encerrada mantém o arquivo sem contar |
| `CaptureRepositoryTest` (6) | nomes ↔ metadados; arquivos antigos como "Anterior"; histórico com várias fotos, da mais nova para a mais antiga, incluindo mantidas; persistência entre instâncias; retenção apaga só as antigas não mantidas; arquivo vazio/ausente não é captura completa |
| `MonitoringSessionTest` (+1) | estatísticas de auditoria |

Para isso, o repositório recebe a pasta e o dispatcher de IO por um construtor interno (o de
produção continua injetado pelo Hilt). Foi adicionada a dependência **apenas de teste**
`kotlinx-coroutines-test` (mesma versão das corrotinas do projeto) e `unitTests.isReturnDefaultValues`
no módulo, para `Log`/`LruCache` funcionarem na JVM.

### Resultados (2026-10-22)

| Comando | Resultado |
| --- | --- |
| `spotlessApply` / `spotlessCheck` | ✅ |
| `:questvision-camera:testDebugUnitTest` | ✅ **52/52** |
| `:app:assembleDebug` | ❌ na 1ª tentativa por **disco cheio** (partição `/` em 100%); ✅ depois de apagar o cache de build local do Gradle (`~/.gradle/caches/build-cache-1`, 731 MB, regenerável) e compilar com `--no-build-cache` |
| Instalação no Galaxy M52 | ✅ `adb install` ok; o app abre (`am start` ok, 2,3 s), sem crash, permissão de câmera concedida |
| Automação e histórico no aparelho | **Não testados**: no momento outro app estava em primeiro plano; não foram simulados toques. As 8 fotos que já existiam foram preservadas e serão listadas como "Anterior" |

**Atenção ao espaço em disco:** restam ≈ 660 MB livres. Novas builds podem falhar de novo. Libere
espaço ou mantenha `--no-build-cache`.

## 3f. Capturas extras no teste de 2026-10-22 (investigação e correção)

**Relato:** 3 questões exibidas, 4 capturas automáticas salvas (0 falhas, 0 duplicatas ignoradas):
nº 2 às 02:30:20 e nº 3 às 02:30:22. Uma decisão foi registrada com `motion=0.1901`, `thr=0.1000`
e `stable=1602ms`.

**Conteúdo real conferido.** As fotos da sessão `20261022-022929` foram copiadas do aparelho e
comparadas visualmente:

| Captura | Conteúdo |
| --- | --- |
| nº 1 (02:29:35) | Questão 01 — Sistemas Operacionais |
| nº 2 (02:30:20) | Questão 02 — Segurança da Informação |
| **nº 3 (02:30:22)** | **Questão 02 de novo**: mesmo texto e posição; só brilho e borda do monitor variam |
| nº 4 (02:30:45) | Questão 03 — Banco de Dados |

A nº 3 é uma duplicata da nº 2. A página estava em **tema escuro, com pouca luz**, o que explica
`thr=0.1000` (limiar adaptativo no teto). A transferência da foto nº 4 foi interrompida pela
desconexão do aparelho; a parte recebida bastou para identificar a questão. O log completo não pôde
ser copiado pelo mesmo motivo.

**Causa: três defeitos do `ScreenChangeDetector` que se combinam.**

1. **Janela de estabilidade reaproveitada após a captura (principal).** Durante `CAPTURING` a
   sessão ignora quadros. `accept()` trocava a referência, mas mantinha a âncora, o `stableFor` e
   a amostra anterior de antes da foto. Na primeira amostra pós-captura, uma diferença > 0,05 (a
   exposição/foco se reajustando após a captura de qualidade máxima) já encontrava a janela "estável
   há 1,5–3,5 s" e gerava outro candidato **na hora**: 2 s depois, que é a latência da foto mais uma
   amostra.
2. **Decisão numa amostra em movimento.** O candidato podia sair logo após um outlier tolerado, com
   `motion` acima do limiar (o `motion=0,19 > 0,10` do log).
3. **Limiares incoerentes em cena ruidosa.** A tolerância de estabilidade sobe até 0,10, mas
   "conteúdo novo" e "não duplicado" usavam 0,05 fixos. Variações tratadas como ruído para a
   estabilidade valiam como questão nova.

**Reprodução.** `CaptureSequenceTest` simula a sessão de campo:
- página em tema escuro, ruído alto;
- **latência de captura** de 3 amostras, com os quadros ignorados como no aparelho;
- **transiente de exposição** de 2 amostras após a foto;
- **refoco** após cada foto.

Antes da correção: uma questão parada gerou `[2, 2]`, três questões geraram `[1, 1, 2, 2, 3]`, e
houve decisão com `motion=0,075 > 0,06`. O rastro mostrou `stable=3500ms` na primeira amostra
pós-captura, ou seja, a janela reaproveitada.

**Medição** (cenas renderizadas, comparação com ±2 células):

| Cena | Mesmo conteúdo em outro foco/exposição | Questões diferentes (mínimo) |
| --- | --- | --- |
| Clara | ≤ 0,009 | 0,089 |
| Escura | ≤ 0,033 | 0,220 |

O foco ou a exposição sozinhos não explicam a duplicata. O que a explica é o transiente pós-captura
(que satura o texto claro) **combinado** com a janela reaproveitada.

**Correção** (só no detector; a métrica e a sessão não mudaram):

1. `accept()` encerra a janela: limpa âncora, outliers e amostra anterior. Qualquer diferença
   posterior precisa assentar numa **janela nova** de 1 s.
2. A decisão só ocorre numa amostra **ela própria parada** (`motion` e deriva ≤ limiar) e que **não
   segue um outlier**. Caso contrário fica retida (`hold=MOTION` / `AFTER_OUTLIER`) e é tentada na
   amostra seguinte.
3. Os limiares de mudança e duplicidade em vigor nunca ficam abaixo da tolerância de estabilidade
   (`max(0,05, thr)`). Pela medição acima, questões diferentes continuam bem acima (≥ 0,089 / ≥ 0,22).

A referência continua sendo o quadro analisado no momento da decisão. A foto sai ~1–1,5 s depois
(latência do `MAXIMIZE_QUALITY`). Se o conteúdo mudar nesse intervalo, a diferença aparece como
mudança nova e passa pelo ciclo normal (janela nova + duplicidade). O log agora mede essa latência.

**Instrumentação nova** (tag `QuestVisionDetect`):

- Por amostra: `changeThr`, `dupThr`, `nearest` (menor diferença para as capturas lembradas, na
  decisão) e `hold` (por que uma decisão foi adiada).
- Por captura: `auto capture #n requested … because change=…>… stable=…ms motion=… drift=…<=…
  outliers=… nearestCapture=…>…`, ou seja, o **motivo** da captura.
- Ao salvar: `latency=…ms` e as estatísticas da sessão.

**Testes:** 59/59.

- `CaptureSequenceTest` (6):
  - questão parada não é fotografada de novo após latência e transiente;
  - três questões estáveis = três capturas;
  - quatro questões seguidas de ~3,5 s cada = quatro capturas;
  - foco/exposição após a captura não viram questão nova;
  - rolagem contínua em tema escuro não captura;
  - decisões nunca acima do limiar nem logo após outlier.
- `ScreenChangeDetectorTest`: janela não reaproveitada após a captura.
- Os testes do ViewModel continuam provando a automação sem confirmação humana.

`spotlessCheck` ✅ · `:app:assembleDebug` ✅. **Validação no aparelho: pendente**; ele não estava
conectado ao ADB ao final.

## 3g. Fase 2.5: validação de novas questões por OCR (2026-10-22)

### Auditoria: 12 capturas para 3 questões

Feita antes de implementar o OCR. O APK instalado no M52 às 14:10 é **idêntico** (SHA-256) ao da
correção 3f. As 12 capturas aconteceram com a correção aplicada. Log (`QuestVisionDetect`) e fotos
foram copiados do aparelho e comparados:

| Fotos | Conteúdo real | `change` / `nearest` (limiar 0,06) |
| --- | --- | --- |
| nº 1–6 | Questão 01 (permissões Linux) — **6 fotos** | 0,09–0,17 / 0,066–0,116 |
| nº 7 | **Transição**: ChatGPT gerando a resposta e rolando, Questão 02 só começando | 0,50 |
| nº 8–10 | Questão 02 (DNS) — **3 fotos** | 0,26 / 0,066 e 0,073 |
| nº 11–12 | Questão 03 (2FA) — 2 fotos, a 12ª com a página um pouco rolada | 0,27 / 0,14 |

Todas as decisões ocorreram com a cena parada (`motion` ≤ 0,03, ~1 s, sem outliers). **A máquina de
estados funcionou; o limite é a assinatura visual em condições reais:**

- Reflexo forte e névoa de luz no monitor mudam o contraste local de forma desigual a cada ajuste de
  exposição.
- Somam-se cursor, relógio da barra de tarefas com segundos e pop-ups.
- Fotos da mesma questão diferem de 0,07 a 0,17, sobrepondo-se às diferenças entre questões
  distintas (0,14–0,27). Várias duplicatas passaram rente ao limiar (0,0657 contra 0,060).
- O texto gerado aos poucos, com rolagem automática, produz pausas "estáveis" (nº 7 e 12).

Nenhum limiar visual separa essas faixas.

### Desenho

- **A visão propõe, o texto decide.** A assinatura visual de baixa resolução continua sendo o filtro
  inicial (só candidatos estáveis). O OCR roda **só nos candidatos**, nunca nos quadros da câmera.
- **OCR na foto de alta resolução já salva:** ML Kit Text Recognition (modelo latino **embutido**,
  offline, nada sai do aparelho). Nos quadros de 640×480 as letras têm ~4 px. O texto avaliado é o
  da foto realmente salva, sem desalinhamento pela latência da captura.
- **Fluxo:** `… STABILIZING → CAPTURING → VERIFYING → MONITORING`. Em `VERIFYING` a sessão não
  processa quadros: **um candidato por vez**. O OCR roda fora da main thread (decodificação em IO,
  ML Kit nas threads próprias), com tempo limite de 15 s.
- **Decisão** (`MonitoringSession.onTextRecognized`):

  | Veredito | Foto | Conta? | Referência visual |
  | --- | --- | --- | --- |
  | Questão nova | **mantida** | sim (`Auto: n`) | passa a ser esta |
  | Repetida | apagada | não | passa a ser esta (não é reproposta) |
  | Ilegível (< 8 palavras confiáveis) | apagada | não | passa a ser esta |
  | Falha / tempo esgotado do OCR | apagada | **não** (nunca vira captura válida) | descartada: a mesma tela é tentada de novo após nova estabilização; 3 falhas seguidas → `ERROR` |
  | Sessão encerrada durante o OCR | apagada (OCR cancelado) | não | — |

  Pausar durante a leitura aplica o resultado e mantém a pausa. Tudo sem confirmação humana. Avisos
  discretos: "Questão repetida — foto descartada", "Texto ilegível — foto descartada", "Não foi
  possível ler o texto — tentando de novo".
- **Comparação textual** (`recognition/QuestionText.kt`):
  - **Normalização:** Unicode NFKD, sem acentos, minúsculas, só alfanuméricos. Palavras de 1
    caractere (letras das alternativas) e texto volátil (horários como `14:12:24`, datas) são
    descartados.
  - **Novidade:** palavras confiáveis (confiança do ML Kit ≥ 0,5) da captura que não existem na
    questão aceita mais próxima. Palavras longas toleram erros de OCR (1 edição até 7 letras, 2
    acima); curtas precisam ser iguais.
  - **Questão nova** se houver ≥ 3 palavras novas **seguidas numa mesma linha** (por exemplo, uma
    alternativa reescrita) ou ≥ 4 palavras novas que sejam ≥ 8% do texto. Assim, ruídos isolados
    do OCR não bastam.
  - ***Fingerprint*:** SimHash de 64 bits por captura (log e atalho de igualdade exata), e
    histórico de textos aceitos **por sessão**. Não depende de hash exato.
- **Histórico e contador:** só fotos de questões novas ficam salvas. O histórico de uma sessão mostra
  uma foto por questão, e `Auto: n` = novas confirmadas. O cabeçalho mostra fotos tiradas, novas,
  repetidas (visual + texto), ilegíveis e falhas.

### Log (tag `QuestVisionOcr`)

`<arquivo> NEW_QUESTION|DUPLICATE|UNREADABLE ocr=<ms> words=<n> novel=<n> ratio=<x> run=<n>
closest=<i> simhash=<hex> text="<início>" MonitoringStats(...)`.

### Testes (JVM): 86/86

| Suíte | Cobre |
| --- | --- |
| `QuestionTextMatcherTest` (12) | normalização Unicode/acentos/pontuação e texto volátil; mesma questão com brilho diferente; com enquadramento diferente (linhas cortadas, fragmento novo); com pequenas diferenças de OCR (acento, erro em palavra longa, palavra perdida, ruído de baixa confiança); questões diferentes com estrutura visual idêntica; alteração de uma alternativa; vazio/ilegível/baixa confiança; só o relógio muda; voltar a uma questão anterior; três questões = três novas; **limitação documentada**: troca de uma única palavra curta (FTP→SFTP) não é distinguida |
| `TextVerificationSessionTest` (7) | foto espera o texto antes de contar e nenhum quadro é processado em `VERIFYING`; repetida não conta e vira referência; ilegível; falha de OCR é tentada de novo após nova janela; 3 falhas → `ERROR`; resultado após encerrar é ignorado; três questões = três capturas |
| `QuestVisionCameraViewModelTest` (+7) | três questões → três capturas e três itens no histórico; candidato visual com o mesmo texto (exposição/reflexo após a captura) → descartado; ilegível; falha de OCR nunca conta e a tela é retentada; um candidato por vez durante o OCR; encerrar durante o OCR cancela e não guarda nada; pausar durante o OCR |
| Visuais anteriores | rolagem contínua não captura, variações de exposição após a captura etc. (`CaptureSequenceTest`, `RenderedStabilityTest`) |

### Validação com as fotos reais (executada em 2026-10-22, Galaxy M52 / Android 13)

`RealCapturesOcrTest` rodou o **ML Kit real no aparelho** sobre as 12 fotos de campo (cópias
idênticas às do app, conferidas por tamanho), na ordem, com a mesma comparação da sessão.

**1ª execução: divergência.** Mantidas nº 1, **3, 4, 5**, 7, 8 e 11 (7 em vez de 4). As nº 3, 4 e 5
são a Questão 01 de novo, aceitas por `run=3` com só 7–13 palavras novas (4–8%). A lista das
palavras novas, adicionada ao relatório para investigar, mostrou que **nenhuma vinha da questão**:

| Foto | Sequência "nova" | Origem |
| --- | --- | --- |
| nº 3 | `chatqptcomgqp6acfe2a1 2e308191b75d… 57fecd34e125` | URL da barra de endereço, lida em pedaços e com erros diferentes |
| nº 4 | `brb engenharia darry` | barra de favoritos lida separada; nas outras fotos, `brbengenharia` grudado |
| nº 5 | `central it gaf` | barra de favoritos: `centralit g4f` em outra foto |

Causa: a etapa "esta palavra já existia?" não tratava dois artefatos genéricos de OCR,
**segmentação diferente** (palavras grudadas/separadas) e **fragmentos de URL/ids**. Correção, sem
alterar nenhum limiar de decisão:

1. Uma palavra conta como já vista se aparecer dentro do texto contínuo (sem espaços) de uma linha
   da referência.
2. Tokens de ≥ 10 caracteres com ≥ 3 dígitos e ≥ 3 letras (URL, hash, id) são descartados como
   voláteis, como o relógio.

Testes de regressão com esses artefatos reais foram adicionados
(`chromeReadWithDifferentWordSplits_isNotNewText`, `urlAndIdFragments_areVolatile`). Os testes de
alternativa alterada e de questões com o mesmo layout continuam passando.

**2ª execução: resultado esperado.** Mantidas **nº 1, 7, 8 e 11**:

| Foto | Veredito | Palavras novas / proporção / sequência | Texto reconhecido (trecho) |
| --- | --- | --- | --- |
| nº 1 | **nova** | 170 / 1,000 / 13 (primeira) | "questao 01 sistemas operacionais… desta vez faremos teste no mesmo tema escuro…" |
| nº 2 | repetida | 0 / 0,000 / 0 | "questao 0 sistmas operacionais…" (erros de OCR tolerados) |
| nº 3 | repetida | 0 / 0,000 / 0 | idem Questão 01 |
| nº 4 | repetida | 1 / 0,006 / 1 (`nlano`) | idem |
| nº 5 | repetida | 1 / 0,006 / 1 (`gaf`) | idem |
| nº 6 | repetida | 1 / 0,006 / 1 (`prmesa`) | "…questao 01 sistemas operacionals" |
| nº 7 | **nova** | 57 / 0,373 / 6 | transição: "quando escrever outra enviarei questao 02… primeira questao… resultado esperado" |
| nº 8 | **nova** | 41 / 0,315 / 3 | "questao 02 redes de computadores… qual servico de rede responsavel por traduzir nomes de dominio…" |
| nº 9 | repetida | 3 / 0,023 / 1 | idem Questão 02 |
| nº 10 | repetida | 5 / 0,038 / 2 | idem (`141255`: relógio lido sem os dois-pontos) |
| nº 11 | **nova** | 37 / 0,253 / 4 | "questao 03 seguranca da informacao… das alternativas apresenta um exemplo de autenticacao de dois fatores (2fa)" |
| nº 12 | repetida | 0 / 0,000 / 0 | idem Questão 03 |

Margem: repetidas têm ≤ 5 palavras novas (≤ 3,8%, sequência ≤ 2); novas têm ≥ 37 (≥ 25%,
sequência ≥ 3). O OCR levou 0,6–1,7 s por foto de 12 MP (a primeira inclui o carregamento do
modelo). A nº 7, de transição, continua contando: o texto dela é de fato diferente (limitação já
documentada). Validação **das fotos**: o fluxo ao vivo com OCR ainda não foi testado.

### Teste ao vivo com OCR (2026-10-22 18:43, Galaxy M52)

Sessão `20261022-184335`, três questões no monitor, log contínuo (`QuestVisionDetect` +
`QuestVisionOcr`), **sem quedas, sem crashes, sem falhas de OCR**:

| Candidato visual | Motivo visual (`change`) | OCR | Veredito | Foto |
| --- | --- | --- | --- | --- |
| nº 10 (18:43:38) | 1,00 (início da sessão) | 1,6 s · 143 palavras | **nova** | chave estrangeira (banco de dados) |
| nº 11 (18:43:45) | 0,128 | 1,5 s · 2 novas | repetida | apagada |
| nº 12 (18:44:03) | 0,149 | 1,3 s · 0 novas | repetida | apagada |
| nº 13 (18:44:17) | 0,458 | 1,2 s · 39 novas | **nova** | Questão 04 (`ps aux`) |
| nº 14 (18:44:29) | 0,143 | 1,2 s · 0 novas | repetida | apagada |
| nº 15 (18:44:45) | 0,359 | 1,2 s · 44 novas | **nova** | Questão 05 (busca binária) |
| nº 16 (18:44:49) | 0,130 | 1,2 s · 1 nova | repetida | apagada |
| nº 17 (18:44:58) | 0,093 | 1,2 s · 0 novas | repetida | apagada |

Resultado: **3 fotos para 3 questões**, conferidas visualmente. A camada visual continua propondo
candidatos extras no ambiente com reflexo (8 para 3), e o OCR descartou todos os 5 repetidos. Cada
candidato extra custa ~1,1 s de foto + ~1,2 s de OCR, sem efeito para o usuário além do aviso
discreto. Uma sessão anterior às 18:34 (`20261022-183452`, sem log porque o ADB estava sem
autorização) também guardou 3 fotos.

**Defeito encontrado e corrigido:** a numeração das capturas não recomeçava a cada sessão (a sessão
das 18:43 começou no nº 10, continuando a das 18:34). `MonitoringSession.start()` agora zera a
numeração (teste `captureNumbering_restartsWithEachSession`; 89/89). **Correção ainda não instalada
no aparelho:** no encerramento o Galaxy M52 não estava conectado ao USB. Com o OCR, lacunas na numeração indicam candidatos descartados (repetidos/ilegíveis)
ou falhas.

### Resultados (2026-10-22)

`spotlessCheck` ✅ · testes unitários ✅ 88/88 (após a correção acima) · `:app:assembleDebug` ✅ ·
`:questvision-camera:assembleDebugAndroidTest` ✅. APK de debug ≈ 279 MB, ou seja, **+42 MB** do
modelo embutido (todas as ABIs). Nenhuma permissão nova. Instalado no Galaxy M52 em 2026-10-22
15:23 (abre sem crash). **Fluxo ao vivo com OCR: ainda não testado.** Para acompanhar:
`adb logcat -v time QuestVisionDetect:I QuestVisionOcr:I AndroidRuntime:E '*:S'`.

### Limitações

- O OCR lê **a tela inteira** (abas, barra lateral, outros apps). Um bloco de texto novo fora da
  questão, como uma notificação ou pop-up com 3+ palavras, pode contar como "novo".
- **Texto em geração/rolagem** (chat que escreve aos poucos): uma pausa no meio pode ser capturada
  como "nova", porque o texto é de fato diferente, como a nº 7. O OCR resolve repetições, não
  capturas intermediárias.
- **Mudança de uma única palavra curta** (FTP → SFTP) não é distinguida de um erro de OCR.
- Cada candidato custa uma foto (~1 s) + OCR da foto em alta resolução (tempo real a medir no
  aparelho; log `ocr=<ms>`). As fotos repetidas são apagadas logo após a leitura.

## 3h. Encerramento das Fases 2 e 2.5 (2026-10-22)

**Aprovado no teste real (Galaxy M52, 18:43):** 3 questões diferentes reconhecidas e salvas, 5
candidatos duplicados rejeitados pelo OCR, 0 falhas de leitura, nenhuma confirmação manual.

Entregue nestas fases:

- detecção visual (assinatura de estrutura, janela de estabilidade, sessão de monitoramento);
- histórico de capturas e auditoria;
- layout adaptativo portrait/landscape;
- validação de novas questões por OCR on-device, com decisão por texto.

Verificação final antes do commit: `spotlessCheck` ✅ · testes unitários **89/89** ✅ ·
`:app:assembleDebug` ✅.

Pendente para o próximo contato com o aparelho:

1. Instalar o APK com a correção da numeração por sessão.
2. Validar que uma nova sessão começa em `Auto: 0` com capturas numeradas a partir de 1, e que o
   histórico anterior continua intacto.

Fotos e logs dos testes de campo **não** foram versionados; ficam só no aparelho e em cópias locais
temporárias.

## 3i. Fase 3.1: configuração e proteção da chave da OpenAI (2026-10-22, concluída em 2026-10-24)

Decisões aprovadas para a Fase 3:
- arquitetura **local-first, sem backend**, uso pessoal no Galaxy M52;
- OpenAI, com `gpt-6-luna` como principal e `gpt-6.1-sol` sob demanda;
- Flex desligado e orçamento planejado de US$ 5/mês;
- capturas automáticas processadas automaticamente; manuais só sob comando.

Esta etapa entrega **só a configuração**: nenhuma chamada à OpenAI, Room ou WorkManager. **Nenhum
crédito é consumido.**

### Implementado (pacote `com.questvision.camera.settings`)

| Arquivo | Responsabilidade |
| --- | --- |
| `ApiKey.kt` | `ApiKey` (`value class` cujo `toString()` mostra só `sk-…1234`), validação local do formato (prefixo `sk-`, tamanho, caracteres) e máscara |
| `SecretCipher.kt` | `AndroidKeystoreSecretCipher`: **AES-256-GCM** com chave gerada no **Android Keystore** (StrongBox quando houver, senão TEE), não exportável, IV aleatório por cifragem, tag de 128 bits; formato `SealedSecret` versionado |
| `OpenAiKeyRepository.kt` | Salva só o texto cifrado em `noBackupFilesDir/secure/openai_api_key.bin` (gravação atômica). Estados `Missing` / `Stored(máscara)` / `Unreadable`. `clear()` apaga o arquivo **e** a chave do Keystore. `useKey { }` para a Fase 3.3 |
| `AiSettings.kt` | `AiModel` com preços oficiais (2026-10-22) para estimativas; `AiSettings` (principal, avançado, automático, Flex, orçamento) em SharedPreferences, que não guarda segredos |
| `AiSettingsViewModel.kt` | Estado da tela (só a máscara, nunca a chave), salvar, substituir, remover, escolha de modelos; módulo Hilt |
| `AiSettingsPanel.kt` | Seção **OpenAI** e **Modelos** no `SettingsOverlay` do `:core-ui`, aberta pelo botão ⚙ na barra superior (portrait) ou no painel lateral (landscape) |

Proteções aplicadas:
- **Campo da chave:** em modo senha, sem corretor; guardado só na memória (`remember`, não
  `rememberSaveable`), para nunca ir ao estado salvo da Activity; limpo logo após salvar.
- **Captura de tela:** `FLAG_SECURE` enquanto o painel está aberto (bloqueia capturas, gravação e a
  miniatura de recentes).
- **Remoção** com confirmação.
- **Sem logs da chave:** nenhum ponto do código registra a chave, e mensagens de erro e
  `toString()` só mostram a máscara (coberto por testes).
- **Backup:** `data_extraction_rules.xml` (Android 12+, nuvem e transferência entre aparelhos) e
  `backup_rules.xml` excluem `no_backup/` e os bancos de dados. A chave do Keystore nunca vai para
  backup.
- **Nada no APK:** nenhuma chave no código, no Git, no APK nem em `BuildConfig` (que nem é gerado).
  Verificado no APK: nenhuma string `sk-…` nos `.dex`.
- **Permissões:** nenhuma nova (`INTERNET` só será declarada pelo app na Fase 3.3).

### Riscos residuais (deliberados e documentados no aviso da tela)

- A chave fica decifrada **na memória** durante o uso; não há como evitar numa chamada direta à API.
- **O APK de debug** permite, com o celular desbloqueado e depuração USB ativa, acoplar um depurador
  e chamar a decifração. Para o dia a dia: **APK de release assinado sem debug** e depuração USB
  desligada.
- A chave do Keystore **não exige biometria nem aparelho desbloqueado**, para o processamento em
  segundo plano funcionar. Celular desbloqueado nas mãos de outra pessoa = app utilizável.
- Root ou malware com root podem ler a memória do processo.
- O que realmente limita o prejuízo:
  - **projeto dedicado na OpenAI com limite rígido mensal** (US$ 5): ao atingir, a API responde 429
    `project_spend_limit_exceeded`, com pequena tolerância;
  - chave criada dentro desse projeto, com permissões restritas;
  - créditos pré-pagos sem recarga automática;
  - revogação imediata se houver suspeita.

### Como configurar o limite na OpenAI

1. platform.openai.com → **Projects** → criar "QuestVision".
2. No projeto: **Limits** → limite **rígido** (hard limit) mensal de US$ 5 e alertas.
3. **API keys** do projeto → criar chave com permissões **Restricted** (só o necessário para
   Responses, se o painel permitir).
4. **Billing:** créditos pré-pagos, recarga automática desligada.
5. Colar a chave no app (⚙ → OpenAI → Salvar chave).

### Testes

- **JVM (107/107, 18 novos):**
  - `ApiKeyTest`: validação, máscara, chave ausente de `toString()` e erros, formato
    `SealedSecret`, padrões aprovados;
  - `OpenAiKeyRepositoryTest`: só texto cifrado no disco, sobrevive a reinício, substituição,
    remoção destrói arquivo e chave, entrada inválida não grava, dados indecifráveis ou
    corrompidos viram `Unreadable` sem crash, falha do Keystore não grava nada;
  - `AiSettingsViewModelTest`: estado só com a máscara, erro de entrada preserva a chave anterior,
    modelos persistidos.
- **Instrumentado** (`AndroidKeystoreSecretCipherTest`, 4 testes): Keystore real, IV diferente a cada
  cifragem, adulteração rejeitada, chave destruída invalida dados antigos, arquivo em `no_backup`
  sem texto puro.

`spotlessCheck` ✅ · `:questvision-camera:testDebugUnitTest` ✅ · `:app:assembleDebug` ✅.

### Validação no aparelho (relatada pelo usuário, Galaxy M52 SM-M526B, Android 13)

- **5 testes instrumentados** executados no aparelho físico, **sem falhas**: os 4 de
  `AndroidKeystoreSecretCipherTest` mais o `RealCapturesOcrTest`.
- Validação manual do app confirmada.
- **Nenhuma chamada à API da OpenAI** e **nenhum crédito consumido**.

**Fase 3.1 concluída.** Próxima: Fase 3.2 (Room/SQLite), seção 3j.

## 3j. Fase 3.2: persistência local com Room/SQLite (2026-10-24, aprovada em 2026-10-27)

Aprovada para versionamento em 2026-10-27, após a validação no aparelho. **Nenhuma chamada à OpenAI ou ao Telegram**, nenhuma
permissão nova e nenhum crédito consumido. Os algoritmos de detecção e OCR não mudaram.

### Arquitetura

- **Banco** `questvision.db` (Room 2.8.5, SQLite) na pasta privada `databases/`, já excluída de
  backup e transferência pelas regras da Fase 3.1 (inalteradas).
- **Fotos continuam arquivos** em `noBackupFilesDir/captures` (e `kept/`). O banco guarda só o
  **caminho relativo** (`capture_….jpg` ou `kept/capture_….jpg`), **nunca JPEG como BLOB**.
- **`QuestionRepository`** (`@Singleton`) é o único ponto de escrita. Tudo roda no `Dispatchers.IO`,
  serializado por um `Mutex`; a câmera e a thread principal nunca esperam o banco.
- **O banco é o índice do histórico.** O nome do arquivo continua registrando origem, sessão e
  sequência, e por isso um arquivo sem linha sempre pode ser recuperado.
- **Sem migração destrutiva:** o builder não tem `fallbackToDestructiveMigration`. Uma mudança de
  esquema sem `Migration` faz o app falhar em vez de apagar o histórico.
- **Esquema exportado** em `questvision-camera/schemas/…/1.json`, para versionar e validar as
  migrações futuras.

### Tabelas

**`capture_sessions`**: `id` (PK, rótulo `yyyyMMdd-HHmmss` usado nos nomes de arquivo),
`started_at`, `ended_at`.

**`questions`**:

| Coluna | Conteúdo |
| --- | --- |
| `id` | UUID (PK) |
| `session_id` | FK → `capture_sessions.id` (`RESTRICT`); nulo em captura manual fora de sessão |
| `sequence` | número da captura automática na sessão |
| `question_number` | "Questão 05" → 5, quando o OCR encontra uma linha que começa assim |
| `origin` | `MANUAL` / `AUTOMATIC` / `UNKNOWN` |
| `image_path` | caminho relativo, **único** |
| `captured_at`, `updated_at` | epoch ms |
| `ocr_text` | texto do ML Kit, linha a linha (nulo sem OCR) |
| `dedup_key` | SimHash do texto normalizado (16 hex), para achar a mesma questão entre sessões |
| `ai_status` | `NOT_REQUESTED`, `PENDING`, `PROCESSING`, `ANSWERED`, `NEEDS_REVIEW`, `FAILED` |
| `ai_response`, `ai_model`, `ai_answered_at` | reservados para a Fase 3.3/3.4 (nulos hoje) |
| `attempts`, `last_error` | tentativas e **código** do último erro (nunca chave ou payload) |
| `telegram_status` | `NOT_REQUESTED`, `PENDING`, `SENT`, `FAILED` (fase futura) |

Os índices cobrem `image_path` (único), `session_id`, `captured_at`, `dedup_key` e `ai_status`.

### Fluxo

- **Questão automática nova** (OCR confirmou): a linha é gravada como `AUTOMATIC` / **`PENDING`**,
  com texto, número e `dedup_key`. O aviso "salva" só aparece depois da gravação.
- **Duplicada ou ilegível:** a foto é apagada como antes e **nunca gera linha**.
- **Captura manual:** `MANUAL` / **`NOT_REQUESTED`** (só será enviada sob comando, como decidido).
- **Manter:** move o arquivo para `kept/` e depois atualiza o caminho.
- **Descartar:** apaga o arquivo e depois a linha.
- **Retenção:** as 20 recentes, como antes, mas fotos `PENDING`/`PROCESSING` são **protegidas**
  até a IA vê-las, assim como as fotos **sem linha no banco** (gravação falha), até o próximo
  reconcile importá-las. *(Desde 2026-10-28, toda questão com status diferente de
  `NOT_REQUESTED`, inclusive respondida, é protegida: ver seção 3k, "Primeira chamada real".)* As linhas das fotos apagadas são removidas junto.
- **Ao abrir o app, `reconcile()`:**
  - importa arquivos sem linha como `NOT_REQUESTED` (nunca enviados sozinhos);
  - reaponta linhas cujo arquivo foi movido (`kept/`);
  - remove linhas cujo arquivo sumiu.

  Log: `QuestVisionDetect: database reconciled: ReconcileReport(...)`.
- **Falha ao gravar:** uma nova tentativa. Se persistir, o aviso `RECORD_FAILED` aparece, a foto
  fica no disco e é importada no próximo início. O log registra só a classe da exceção.

### Consistência após interrupções

| Interrupção | Estado deixado | Reparo |
| --- | --- | --- |
| Entre salvar a foto e gravar a linha | arquivo sem linha | importado no próximo início |
| Entre apagar o arquivo e a linha | linha órfã | removida no próximo início |
| Entre mover para `kept/` e atualizar o caminho | caminho antigo | reapontado pelo nome do arquivo, sem perder estado nem texto |
| No meio de uma transação | nada (rollback do SQLite) | coberto por teste instrumentado |

### Compatibilidade

- Na primeira abertura após a atualização, todas as fotos existentes, incluindo `kept/` e os nomes
  antigos `capture_<timestamp>.jpg`, entram no banco como `NOT_REQUESTED`. **Nada é apagado** e nada
  é enviado à IA.
- O histórico mostra os mesmos itens, na mesma ordem (mais recente primeiro).

### Arquivos

- **Novos** (`com.questvision.camera.data`):
  - `QuestionEntities.kt`;
  - `QuestionDao.kt`;
  - `QuestVisionDatabase.kt` (inclui o módulo Hilt e `TransactionRunner`);
  - `QuestionRepository.kt`.
- **Esquema:** `questvision-camera/schemas/com.questvision.camera.data.QuestVisionDatabase/1.json`.
- **Alterados:**
  - `CaptureRepository.kt`: a retenção aceita caminhos protegidos e devolve os apagados; novos
    `relativePath` e `resolve`;
  - `QuestVisionCameraViewModel.kt`: reconcile ao iniciar, sessões, gravação e histórico vindo do
    banco;
  - `QuestVisionCameraUiState.kt` e `QuestVisionCameraScreen.kt`: aviso `RECORD_FAILED`;
  - `strings.xml` (en e pt-BR);
  - `gradle/libs.versions.toml` e `questvision-camera/build.gradle.kts` (Room via KSP).

### Testes

- **JVM: 129/129** (22 novos), com DAO em memória (`FakeQuestionDao`) que reproduz unicidade, FK e
  falhas de escrita injetadas:
  - **`QuestionRepositoryTest` (18):**
    - inserção e leitura de todos os campos;
    - gravação idempotente;
    - persistência entre instâncias;
    - estados de IA;
    - `dedup_key` entre sessões;
    - consulta por sessão;
    - ordem do histórico;
    - exclusão consistente;
    - exclusão e preservação interrompidas;
    - retenção que protege `PENDING`;
    - importação do histórico antigo;
    - falha temporária (nova tentativa) e persistente (aviso e recuperação);
    - foto com gravação falha nunca apagada pela retenção antes de ser importada;
    - mesma questão em outra sessão gravada de novo;
    - número da questão e texto.
  - **`QuestVisionCameraViewModelTest` (+4):**
    - questão nova gravada `PENDING` com texto;
    - manual gravada `NOT_REQUESTED`;
    - duplicada sem linha;
    - falha do banco gera o aviso e a foto é recuperada ao reiniciar.
- **Instrumentados no Galaxy M52 (Android 13), 11/11 sem falhas (2026-10-24):**
  - `QuestionDaoTest` (7): Room/SQLite real, todos os campos, unicidade do caminho, FK, ordenação e
    filtros, atualizações e exclusões, dados após fechar e reabrir o arquivo, rollback de transação,
    e o repositório de ponta a ponta com arquivos reais (retenção que protege `PENDING`, reinício,
    reconcile sem reparos);
  - `AndroidKeystoreSecretCipherTest` (4), como regressão da Fase 3.1.
- `spotlessCheck` ✅ · `:questvision-camera:testDebugUnitTest` ✅ · `:app:assembleDebug` ✅.
- APK sem strings `sk-…`. Regras de backup inalteradas.
- **APK instalado no M52 por cima da versão anterior:** o app abriu, criou `databases/questvision.db`
  e registrou `ReconcileReport(imported=0, repointed=0, removed=0)`. O aparelho **não tinha fotos
  salvas** (a pasta `captures` estava vazia), então a importação do histórico antigo **só foi testada
  na JVM**, não com fotos reais.

### Revisão pré-commit (2026-10-24)

1. **Validações manuais no M52:** não puderam ser feitas em 2026-10-24 porque o aparelho se
   desconectou. Foram **executadas em 2026-10-26**: ver "Validação no aparelho" abaixo.
2. **Deduplicação no banco:**
   - `dedup_key` tem índice **não único** (`index_questions_dedup_key`, `unique=false` no
     `1.json`) e o fluxo de captura nunca o consulta. Ele **não impede** gravar uma questão.
   - A deduplicação que descarta fotos continua sendo a da sessão (`acceptedTexts`, limpa em
     `MonitoringSession.start()`). A mesma questão numa sessão nova é gravada de novo, com o mesmo
     `dedup_key` ligando as duas linhas.
   - Coberto por `aLegitimateQuestionAgainInANewSession_isRecordedAgain` (JVM) e por
     `queries_orderAndFilter` (SQLite real, duas linhas com a mesma chave).
   - Ressalva para o cache da Fase 3.4: SimHash igual não garante texto igual. Antes de reaproveitar
     uma resposta, é preciso confirmar com `QuestionTextMatcher`.
3. **Falhas de persistência não apagam fotos:**
   - `record()` não apaga nada em caso de falha.
   - **Defeito encontrado e corrigido:** durante a sessão, uma foto sem linha não estava protegida
     e contava para a retenção das 20. Agora `prune()` protege todo arquivo sem linha até o
     reconcile.
   - **Questões recuperadas sem OCR:** entram como `NOT_REQUESTED`, sem `ocr_text`,
     `question_number` nem `dedup_key`, e com origem, sessão e sequência lidas do nome do arquivo.
     Não são enviadas à IA automaticamente e, a partir daí, seguem a retenção normal das 20
     recentes, como uma captura manual.
4. **`PENDING` protegidas:** confirmado no código (`AiStatus.keepsPhoto`) e nos testes JVM e
   instrumentado.
   - **Risco para a Fase 3.4:** o armazenamento cresce sem limite enquanto a fila não for processada
     (cada questão nova é um JPEG em resolução máxima, da ordem de MB).
   - A Fase 3.4 deve processar a fila e definir um teto (quantidade, idade ou espaço livre), com
     aviso ao usuário.
5. **Esquema e migração:**
   - `questvision-camera/schemas/com.questvision.camera.data.QuestVisionDatabase/1.json` está no
     working tree, não é ignorado pelo Git e deve entrar no commit;
   - `room.schemaLocation` está configurado;
   - nenhuma ocorrência de `fallbackToDestructiveMigration`, `dropAllTables` ou `deleteDatabase` no
     código do app.
6. **Testes refeitos após a correção:** `spotlessCheck` ✅ · `testDebugUnitTest` 129/129 ✅ ·
   `:app:assembleDebug` ✅ · `assembleDebugAndroidTest` ✅. Os instrumentados foram reexecutados no
   M52 em 2026-10-26: **11/11** ✅.

### Validação no aparelho (2026-10-26, Galaxy M52 SM-M526B, Android 13)

APK com a correção de retenção, instalado por cima da versão anterior. Em cada etapa, o script de
evidências copiou o banco (via `run-as`) e listou os arquivos, e o logcat foi gravado. A cada
reabertura, a comparação usou `force-stop` seguido de novo lançamento (processo novo).

**Antes de começar:**
- **Instrumentados:** `QuestionDaoTest` (7) e `AndroidKeystoreSecretCipherTest` (4): **11/11**
  ✅, com o APK de teste gerado depois da correção.
- **Estado inicial:** `ReconcileReport(imported=0, repointed=0, removed=0)`, banco vazio, nenhuma
  foto.

| # | Esperado | Observado | Resultado |
| --- | --- | --- | --- |
| 1 | Questões do monitor gravadas `AUTOMATIC`/`PENDING` com OCR; os mesmos itens após reabrir | Ver detalhes abaixo | ✅ |
| 2 | Manter: arquivo em `kept/` e linha reapontada, sem perder estado. Descartar: arquivo e linha removidos. Persistência após reabrir | Ver detalhes abaixo | ✅ |
| 3 | Manual gravada `MANUAL`/`NOT_REQUESTED`, sem sessão nem OCR; persiste após reabrir | Linha `capture_…144353_657_manual.jpg` com esses valores; histórico com 13 fotos (manual primeiro) após reabrir; banco idêntico | ✅ |
| 4 | Encerramento forçado logo após captura: banco e arquivos consistentes na reabertura, nada perdido | Ver detalhes abaixo | ✅ |

**1. Monitoramento e persistência após reabrir:**
- **Sessão `20261026-143646`:** 6 questões novas.
- **Sessão `20261026-143906`:** 7 novas e 4 duplicadas descartadas. As duplicadas não geraram linha
  e seus arquivos foram apagados.
- **No banco:** 13 linhas `PENDING` com OCR (497–1331 caracteres) e `dedup_key`; 13 arquivos; as
  duas sessões com `ended_at`.
- **Após reabrir:** `ReconcileReport(0,0,0)`, banco idêntico ao de antes, histórico com "13 fotos".
- Nenhum `could not record` e nenhum crash.

**2. Manter e descartar:**
- **Descartar nº 9:** arquivo e linha removidos.
- **Manter nº 10:** a linha passou a apontar para `kept/…_10.jpg` e manteve `PENDING`, OCR e
  `question_number=6`.
- **Após reabrir:** `ReconcileReport(0,0,0)`, banco idêntico, histórico com 12 fotos e nº 10
  marcado como mantido.

**4. Encerramento forçado:**
- **12 execuções** de captura manual + `force-stop`, com atrasos de 0,2 s a 1,8 s:
  - até 1,2 s: a foto ainda não estava gravada, e não ficou nem arquivo nem linha;
  - 1,4–1,8 s: arquivo e linha já gravados, `ReconcileReport(0,0,0)`;
  - **em 1,3 s, interrompido entre o arquivo e a linha:** reabertura com
    `ReconcileReport(imported=1, …)` e foto importada como `MANUAL`/`NOT_REQUESTED`.
- **Exclusão e preservação interrompidas**, simuladas com `run-as rm` e `mv` para `kept/` com o app
  parado: `ReconcileReport(imported=0, repointed=1, removed=1)`.
- Ao final, 17 linhas = 17 arquivos, e nenhum arquivo de 0 byte.

**Retenção com `PENDING` (verificação adicional):**
- **Execução:** 22 capturas manuais seguidas.
- **Resultado:** os **mesmos 12 caminhos `PENDING`** antes e depois; exatamente **20** manuais não
  mantidas; 33 linhas = 33 arquivos.
- **Armazenamento:** a pasta `captures` já ocupa **111 MB** (fotos de 2–4,8 MB). É uma evidência
  concreta do risco de crescimento para a Fase 3.4.

**Atualização do app sem perda:** depois da correção do texto do histórico, o APK foi reinstalado
com `install -r`. O banco ficou idêntico e o reconcile não fez nada.

**Problemas encontrados no aparelho:**
- **Texto do histórico desatualizado (corrigido):** dizia que só as 20 recentes ficam guardadas,
  mas as fotos que aguardam a IA também ficam. Novo texto em en e pt-BR: "…além das que aguardam a
  IA". Conferido na tela. Só muda texto.
- **Capturas extras de telas intermediárias:** a página rolou com duas questões visíveis, e
  houve linhas a mais do que questões:
  - nº 4 da sessão 1: tela parcial, 24 palavras, sem número;
  - nº 9 e nº 10 da sessão 2: a mesma Questão 06, primeiro incompleta e depois completa;
  - nº 1 da sessão 2: sem número lido, não foi possível determinar se é a Questão 01.

  Esses casos vêm da detecção e do OCR das Fases 2 e 2.5, não da persistência, e ficam para
  avaliação futura. Nenhum algoritmo foi alterado.
- **Fotos manuais escuras:** as capturas manuais saíram pretas porque a câmera estava apontada para
  uma área escura. Não tem relação com a persistência.
- **Avisos `SQLite…` no logcat** são de outros processos (Play Services e framework), não do app.

### Riscos e limitações

- **Fotos `PENDING` acumulam até a Fase 3.4**, porque a retenção não as apaga. Cada sessão
  automática soma suas questões ao armazenamento. Mitigação futura: processar a fila ou limitar o
  tempo na fila.
- **Linha perdida numa falha de gravação:** a foto nunca é apagada antes de ser importada, mas é
  recuperada como `NOT_REQUESTED`, sem o texto do OCR, e não é enviada à IA automaticamente. Depois
  disso segue a retenção normal das 20 recentes. O usuário é avisado por `RECORD_FAILED`.
- **Mudanças de esquema** exigem `Migration` e incremento de versão. O JSON exportado deve ir para o
  Git junto com o código.
- **Teste de migração:** ainda não há `MigrationTestHelper`. Ele só faz sentido a partir da versão 2
  do esquema.
- **Busca por `dedup_key`** é exata (mesmo SimHash). A comparação aproximada entre sessões fica para
  o cache de respostas.

### Pendências futuras (registradas na aprovação, 2026-10-27)

1. **Questões incompletas e capturas extras:** telas parciais ou em rolagem geram linhas a mais
   (ver "Problemas encontrados no aparelho"). Avaliar na detecção e no OCR (Fases 2 e 2.5).
2. **Crescimento do armazenamento:** fotos `PENDING` não entram na retenção. A Fase 3.4 deve
   processar a fila e definir um teto (quantidade, idade ou espaço livre), com aviso ao usuário.
3. **Recuperação de questões sem OCR:** fotos importadas pelo reconcile após uma falha ficam
   `NOT_REQUESTED`, sem texto. Definir como reprocessar o OCR e quando enviá-las à IA.
4. **Similaridade textual antes de reutilizar respostas:** um `dedup_key` igual não garante texto
   igual. Antes de reaproveitar uma resposta da IA, confirmar com `QuestionTextMatcher`.

A Fase 3.3 só começa com nova autorização.

### Preparação para as Fases 3.3/3.4

A fila da IA é `questionsWithAiStatus(PENDING)`, em ordem de captura. Os resultados vão por
`updateAiStatus` (tentativas e erro) e `saveAiResult` (resposta, modelo, data), e o status do
Telegram por `updateTelegramStatus`.

## 3k. Fase 3.3: integração manual com a API da OpenAI (2026-10-27, aprovada em 2026-10-28, `ef20f8d`)

Sem commit nem push. **Nenhuma chamada real à OpenAI foi feita e nenhum crédito foi consumido**: toda
a validação usou um cliente falso e um servidor HTTP local. O envio é **só manual** (botão), sem fila
automática, e nada é enviado ao abrir o app. A captura automática e o OCR das Fases 2 e 2.5 não
foram alterados. O Telegram não faz parte desta fase.

### Arquitetura (pacote `com.questvision.camera.ai`)

| Arquivo | Responsabilidade |
| --- | --- |
| `HttpTransport.kt` | `UrlConnectionTransport`: POST com `HttpURLConnection` da plataforma (nenhuma biblioteca de rede nova), timeouts de conexão e leitura, corpo limitado a 2 MB, cancelamento imediato. `setFixedLengthStreamingMode` impede que a pilha HTTP do Android reenvie o corpo sozinha; redirecionamentos desligados. Não registra nada em log |
| `OpenAiClient.kt` | Responses API (`POST https://api.openai.com/v1/responses`) com Structured Outputs. Uma requisição por chamada, **nunca repete**. Mapeia HTTP, `status`, recusa, `usage` e `service_tier` |
| `AiPrompt.kt` | Instruções em português (questões objetivas de concursos, informática, programação, matemática e conhecimentos gerais), proteção contra instruções escritas na tela e esquema JSON estrito |
| `AiAnswer.kt` | Valida a resposta e força `needs_review` quando a resposta é incoerente, "nenhuma correta", "indeterminada" ou de confiança baixa. JSON canônico guardado no Room |
| `ExtractedQuestion.kt` | Modo texto: recorta do OCR **só a última questão da tela** (título "Questão N", enunciado e ≥ 2 alternativas). Se ela estiver incompleta, não envia nada; nunca troca por uma questão anterior |
| `QuestionImageEncoder.kt` | Modo imagem: foto na orientação correta (EXIF), reduzida ao lado máximo configurado, JPEG 85, enviada como data URL. O arquivo original não é alterado |
| `QuestionAnalyzer.kt` | Orquestra: guarda em memória, início atômico (limite + *claim* + registro) no SQLite, uso da chave só durante a chamada, persistência do resultado ou da falha, cancelamento |
| `CostEstimate.kt` | Estimativa **antes** do envio (aproximada) e custo **observado** depois (tokens informados × preço publicado da modalidade que atendeu) |
| `AiAnalysisViewModel.kt` / `AiAnalysisPanel.kt` | Painel "Análise por IA" (botão ✨ na revisão da foto): modelo, modo Texto/Imagem, Analisar / Analisar novamente, progresso e Cancelar, resposta, explicação, aviso de revisão, confirmações, tokens, custo observado e envios das últimas 24 h |
| `AiError.kt` | Códigos de erro persistidos (com a marca de "resultado desconhecido") e motivos de recusa local |

Fluxo: revisão da foto → ✨ → painel (não envia nada) → **Analisar** → confirmação única da primeira
chamada real (modelo, modo, estimativa) e, se o último envio desta questão terminou sem resposta,
confirmação de que reenviar é uma nova cobrança → requisição → resposta salva e exibida.

### Revisão da API (documentação oficial, consultada em 2026-10-28)

Fontes: páginas do modelo `gpt-6-luna`, Pricing, Images and vision, Flex processing, Error codes e a
referência de `POST /v1/responses` em developers.openai.com.

- **`gpt-6-luna`:** disponível na API, *snapshot* único `gpt-6-luna`.
  - Endpoints: inclui Responses (`v1/responses`).
  - Modalidades: texto na entrada e saída; imagem só na entrada.
  - Structured Outputs: suportado. `reasoning.effort`: `none`, `low`, `medium` (padrão), `high`,
    `xhigh`, `max`; o app usa `low`.
  - Janela de 1.050.000 tokens; saída máxima 128.000.
  - **Não está disponível no nível gratuito** (*Free: Not supported*): a conta precisa estar em
    nível pago.
- **Preços por 1M de tokens** (entrada / entrada em cache / saída):
  - Standard US$ 0,10 / 0,01 / 0,50;
  - Flex e Batch US$ 0,05 / 0,005 / 0,25 (50%);
  - Fast (relatado como `priority`) US$ 0,20 / 0,02 / 1,00 (2×);
  - escrita em cache: 1,25× a entrada (US$ 0,125 na janela de 30 min);
  - prompts acima de 272 mil tokens custam mais (não se aplica aqui);
  - processamento regional: +10%.
- **Corpo da requisição:** `instructions`, `input` com `input_text` / `input_image` (`image_url`
  como data URL base64, `detail`), `text.format` `{type: "json_schema", name, strict: true,
  schema}`, `reasoning.effort`, `max_output_tokens` (inclui os tokens de raciocínio), `store` e
  `service_tier`.
- **Resposta:** `status` (`completed`, `failed`, `in_progress`, `cancelled`, `queued`,
  `incomplete`), `incomplete_details.reason` (`max_output_tokens`, `content_filter`, …), itens
  `message` com `output_text` ou `refusal`, `service_tier` efetivo e `usage`:
  - `input_tokens`, com `input_tokens_details.cached_tokens` e `cache_write_tokens`;
  - `output_tokens`, com `output_tokens_details.reasoning_tokens`;
  - `total_tokens`.
- **Erros:** 401 chave; 403 região ou permissão; 429 de ritmo; 429 de cobrança com `error.code`
  `credit_balance_exhausted`, `project_spend_limit_exceeded`, `organization_spend_limit_exceeded`,
  `organization_usage_limit_exceeded` (tipo ainda pode ser `insufficient_quota`). A documentação
  diz que repetir erros de cobrança não restaura o acesso. No Flex, 429 *Resource Unavailable* não é
  cobrado.
- **Imagens:** o guia de visão **não menciona o `gpt-6-luna`** (só `gpt-5.6-luna` e outros). Não
  existe contagem oficial de tokens de imagem para este modelo.

#### Divergências encontradas e corrigidas

1. **`service_tier` omitido.** Sem o campo, o padrão é `auto`, que segue a configuração do projeto
   e pode cair numa modalidade mais cara (Fast, 2×). Agora o app envia sempre `"default"` (ou
   `"flex"`, se ligado) e registra a modalidade informada na resposta.
2. **Multiplicador 1,2 para imagens removido.** Era uma suposição. A estimativa do modo imagem agora
   **não inclui** os tokens da foto e diz isso na tela; o valor real aparece no custo observado do
   primeiro envio com imagem.
3. **`cache_write_tokens`** (novo campo de `usage`, cobrado a 1,25× a entrada) passou a ser lido,
   persistido e incluído no custo observado.
4. **Timeout do Flex:** 300 s → 900 s, como recomenda o guia do Flex. Diminui o risco de desistir
   de uma requisição que seria respondida (e cobrada).

### Custo: estimado × observado

- **Estimado (antes do envio, aproximado, não é teto):**
  - entrada de texto: instruções + esquema + questão a ~3 caracteres por token, sem cache;
  - saída: `max_output_tokens` × preço de saída, o máximo cobrável de saída;
  - modo imagem: **sem** os tokens da foto (não documentados para o modelo).
  - Valores típicos com `gpt-6-luna` Standard e saída máxima de 1.500 tokens: ≈ US$ 0,0008 por
    envio em texto (≈ US$ 0,00075 é o teto da saída). Em imagem, ≈ US$ 0,0008 **mais** a foto.
- **Observado (depois do envio):** tokens informados pela API × preços publicados da modalidade
  que atendeu (`default`, `flex` ou `priority`). Se faltar contagem ou a modalidade for
  desconhecida, o app mostra "indisponível" em vez de inventar um valor. **O painel de uso da
  OpenAI é a referência**; o valor do app é um cálculo.

### Persistência no Room (versão 2)

- **Estados** (reaproveitados da Fase 3.2): `PENDING`/`NOT_REQUESTED`/`FAILED`/`ANSWERED`/
  `NEEDS_REVIEW` → `PROCESSING` → `ANSWERED` (equivale ao `COMPLETED` pedido) ou `NEEDS_REVIEW`; em
  erro, `PROCESSING` → `FAILED`. Só se entra em `PROCESSING` pelo *claim* atômico, e só se sai dele
  por resposta, falha, cancelamento ou recuperação após reinício. Criar um `COMPLETED` duplicaria
  `ANSWERED`, que já existia no esquema.
- **`questions`** (sem mudança de colunas):
  - `ai_response`: JSON validado (resposta, explicação, confiança, revisão, modo);
  - `ai_model`, `ai_answered_at`;
  - `attempts`: somado a cada tentativa;
  - `last_error`: código, nunca mensagem nem payload.
- **Respostas e falhas:**
  - uma falha **mantém a resposta anterior**, a foto e o OCR;
  - `FAILED` passou a **proteger a foto da retenção**, assim como `PENDING` e `PROCESSING`.
- **Nova tabela `ai_requests`:** registro local de uso.
  - Campos: questão, modelo, modo, início e fim, resultado (`SENT`, `SUCCEEDED`, `FAILED`,
    `CANCELLED`, `INTERRUPTED`), código de erro, HTTP, `response_id`, tokens (entrada, cache,
    escrita em cache, saída, raciocínio) e `service_tier`.
  - Não tem chave estrangeira, para o histórico de consumo sobreviver à exclusão da questão.
  - Nunca guarda chave, prompt ou imagem.
- **Migração 1 → 2:** `AutoMigration` aditiva (só cria a tabela), sem migração destrutiva.
  - O esquema `schemas/…/2.json` foi exportado; as tabelas da versão 1 ficaram idênticas
    (verificado por comparação dos JSON).
  - As duas colunas novas (`cache_write_tokens`, `service_tier`) entraram na própria versão 2,
    porque a versão 2 nunca foi instalada em aparelho nem versionada.
  - `QuestVisionMigrationTest` cria um banco v1 com dados, migra e valida.
- **Concorrência:**
  - guarda em memória no `QuestionAnalyzer`;
  - `beginAiRequest`: numa única transação, conta as requisições das últimas 24 h, faz o
    `UPDATE … WHERE ai_status != 'PROCESSING'` e registra o envio como `SENT`;
  - o painel analisa uma questão por vez.

### Interrupções e resultado desconhecido

- Se a requisição saiu e o app fechou antes da resposta, **não se sabe se a OpenAI a processou**.
  A falta de resposta não é tratada como prova de que nada foi processado ou cobrado.
- **Recuperação:** uma vez por processo, antes de qualquer *claim*, o que ficou em voo de um
  processo anterior vira `INTERRUPTED` (`SENT` → `INTERRUPTED`; `PROCESSING` → `FAILED`), **sem
  reenviar**.
  - Antes, isso rodava a cada `reconcile()`, ou seja, a cada criação do ViewModel da câmera. Se o
    ViewModel fosse recriado com um envio em curso no mesmo processo, esse envio seria marcado como
    interrompido por engano e a questão poderia ser reivindicada de novo. Corrigido e coberto por
    teste.
- **Erros de resultado desconhecido:** `NETWORK`, `TIMEOUT`, `CANCELLED`, `INTERRUPTED` e
  `UNEXPECTED`. As mensagens dizem que a requisição pode ter sido processada e cobrada (a de rede
  dizia "nada foi perdido", o que sugeria que nada chegou à OpenAI; corrigida).
- **Reenvio:** para uma questão nesse estado, **Analisar novamente pede confirmação explícita** de
  que é uma nova requisição, cobrada à parte. Falhas que a própria OpenAI informou (401, 429, 400…)
  não pedem.
- Fotos, OCR e resposta anterior são preservados em todos os casos.

### Segurança

- **Chave:**
  - vem do `OpenAiKeyRepository` (Fase 3.1) pela interface `ApiKeySource`, cifrada com o Android
    Keystore;
  - fica decifrada só durante a chamada e vai só no cabeçalho `Authorization`, nunca no corpo,
    em log ou no banco (coberto por testes).
- **Logs:**
  - o log `QuestVisionAi` registra só modo, modelo, modalidade, resultado, HTTP e contagens de
    tokens;
  - `AiInput` e `EncodedImage` não imprimem conteúdo;
  - nenhum log da integração tem chave, enunciado ou imagem.
  - **Log de diagnóstico do OCR corrigido (autorizado em 2026-10-28):** registrava as 12 primeiras
    palavras do texto reconhecido. Veja "Validação final" abaixo.
- **Rede:**
  - só HTTPS para `api.openai.com`;
  - o manifest do módulo declara `INTERNET`;
  - o tráfego sem TLS (cleartext) continua bloqueado (padrão do targetSdk 37);
  - sem telemetria, analytics ou serviço intermediário.
- **Risco residual:** uma chave usada diretamente num app móvel pode ser extraída (depuração,
  root, memória do processo), mesmo com o Keystore. A arquitetura é aceita por ser de uso pessoal,
  **com limite rígido de gastos no projeto da OpenAI**.

### Controle financeiro (local, não é teto de gastos)

- **Limite de requisições por 24 horas:** 5, 10 (padrão), 20 ou 50, nas Configurações.
  - Antes era por sessão, em memória, e zerava ao reabrir o app. Agora é contado em `ai_requests`
    (janela móvel de 24 h): **reiniciar o app não zera**, apagar fotos ou questões não libera, e
    envios com falha ou cancelados também contam.
  - Limitação: apagar os dados do app zera o registro.
- **Saída máxima:** 800, 1.500 (padrão) ou 3.000 tokens.
- **Imagem:** 1.280, 1.600 ou 2.048 px (padrão).
- **Confirmações:** primeira chamada real (uma vez, com modelo, modo e estimativa); reenvio após
  resultado desconhecido (sempre).
- **Antes de cada envio:** o painel mostra o modelo, os envios das últimas 24 h e a estimativa
  (com o aviso de que exclui a foto no modo imagem).
- **Sem repetição automática**, em nenhum erro:
  - 429 por ritmo (`RATE_LIMIT`) vira mensagem;
  - 429 de créditos ou limite (`QUOTA`) vira outra mensagem, que manda verificar o faturamento;
  - nenhum envio automático na abertura do app ou após reinício.
- **Tokens:** registrados em `ai_requests` quando a API informa, inclusive em respostas
  inutilizáveis.
- **O teto real tem de ser configurado na plataforma da OpenAI:**
  - limite mensal rígido no projeto;
  - créditos pré-pagos sem recarga automática;
  - alertas de uso.

### Testes

- **JVM: 178/178** na revisão (180/180 na validação final, abaixo), com cliente OpenAI falso e servidor HTTP local, sem internet. Execução final
  com `--rerun-tasks` em 2026-10-28:
  - **`QuestionAnalyzerTest` (16):**
    - texto e imagem bem-sucedidos e persistência completa, inclusive modalidade e escrita em cache;
    - resposta para revisão;
    - resposta inválida ou incompleta;
    - sem chave;
    - 401, 429 (ritmo e cota), rede, timeout e 5xx, uma única chamada e dados preservados;
    - resposta anterior mantida após falha e foto `FAILED` protegida da retenção;
    - cancelamento;
    - reinício durante o processamento;
    - novo `reconcile()` no mesmo processo não interrompe o envio em curso;
    - o primeiro *claim* após reinício fecha antes as tentativas interrompidas, sem reenviar;
    - chamadas duplicadas (mesma instância e outra instância);
    - limite de 24 h: conta falhas e sobrevive a reinício; janela móvel; apagar questões não libera;
    - OCR insuficiente e foto ilegível;
    - chave nunca armazenada.
  - **`OpenAiClientTest` (7):** formato da requisição (Structured Outputs estrito, `store: false`,
    `service_tier` sempre explícito, chave só no cabeçalho), imagem como data URL, Flex, mapeamento
    HTTP, respostas inutilizáveis com uso cobrado, rede e timeout.
  - **`CostEstimateTest` (3):** custo observado por modalidade e com escrita em cache; nunca
    inventado (modalidade ou contagem ausentes); estimativa limita a saída e sinaliza a exclusão da
    imagem.
  - **`UrlConnectionTransportTest` (6):** sucesso, corpo de erro e `Retry-After`, timeout, porta
    fechada e cancelamento imediato (inclusive antes de conectar, 20 tentativas).
  - **`AiAnswerTest` (7)** e **`ExtractedQuestionTest` (4).**
  - **`AiAnalysisViewModelTest` (6):** nada é enviado ao iniciar o app nem ao abrir o painel;
    confirmação da primeira chamada; custo observado; texto como padrão; cancelamento pelo painel;
    confirmação de reenvio após resultado desconhecido (e não após falha conhecida); foto sem
    registro.
- **Defeito encontrado e corrigido na implementação:** o cancelamento não interrompia uma leitura
  HTTP bloqueada (esperava o timeout de 30 s). Agora a E/S bloqueante roda separada do chamador: ao
  cancelar, o socket é fechado e o chamador retorna em menos de 0,5 s.
- **Instrumentados:** compilados (`assembleDebugAndroidTest`), usando bancos de teste próprios
  (não tocam nos dados do app):
  - `QuestVisionMigrationTest`: migração 1 → 2 com dados;
  - `QuestionDaoTest`: +3 testes (*claim* atômico e recuperação após reinício; resultado e uso
    persistidos com modalidade, registro sobrevivendo à questão; `beginAiRequest` atômico com dois
    repositórios concorrentes e limite).

  **Não foram executados: o Galaxy M52 não estava conectado** em nenhum momento da revisão. Ficam
  pendentes, **não aprovados**, assim como as validações manuais no aparelho (abertura após a
  atualização, histórico e fotos preservados, painel com cliente simulado, painel sem requisição).
  A espera automática pelo ADB (tarefa `bxfzb69if`) foi encerrada, e o servidor ADB também; nenhum
  processo ou instalador automático ficou ativo.
- `spotlessCheck` ✅ · `:questvision-camera:testDebugUnitTest` ✅ · `:app:assembleDebug` ✅ ·
  `:questvision-camera:assembleDebugAndroidTest` ✅.
- **APK:** nenhuma string `sk-…`; único endpoint `api.openai.com`; `INTERNET` declarada.

### Limitações e riscos

- **Modo texto:**
  - só para questões objetivas com título "Questão N" e ≥ 2 alternativas;
  - uma alternativa final que quebra em duas linhas perde a continuação;
  - fórmulas e figuras exigem o modo imagem.
- **Custo de imagem:** desconhecido até o primeiro envio real com imagem (não documentado para o
  `gpt-6-luna`).
- **Estimativa de texto:** aproximada (~3 caracteres por token); só a parte da saída é um limite.
- **Requisição de resultado desconhecido:** rede, timeout, cancelamento, interrupção e erro
  inesperado podem ter sido cobrados sem resposta. Ficam registrados como tais e só se repetem com
  confirmação do usuário.
- **Fotos `FAILED`** agora ficam fora da retenção. Isso aumenta o crescimento de armazenamento,
  já registrado para a Fase 3.4.
- **Limite de 24 h:** é um freio local; apagar os dados do app o zera. Não substitui o limite
  rígido da OpenAI.
- **Painel:** uma análise por vez. Se o painel for destruído (tela fechada), a análise em curso é
  cancelada e registrada como `CANCELLED`.

### Validação final (2026-10-28)

#### Correção de privacidade no log do OCR (autorizada)

- **Antes:** `logDecision` (`QuestVisionCameraViewModel`) gravava no logcat, com a tag do OCR,
  `text="…"` com as 12 primeiras palavras reconhecidas (`TextFingerprint.preview()`).
- **Agora:** a linha é montada por `ocrDecisionLog(...)` e traz só indicadores técnicos:
  - arquivo, veredito e tempo do OCR;
  - número de tokens e de linhas;
  - métricas de comparação (`words`, `novel`, `ratio`, `run`, `closest`);
  - `simhash` de 64 bits (identifica o conjunto de palavras sem revelá-lo);
  - contadores da sessão.
- `TextFingerprint.preview()` ficou sem uso e foi removido. Os algoritmos de detecção e de OCR não
  mudaram.
- **Teste novo:** `OcrDecisionLogTest` (2) confere que nenhuma palavra reconhecida (≥ 3 letras) de
  duas telas aparece na linha, e que as métricas continuam lá.
- **APK:** o formato antigo (`text=`) não está mais no dex; o novo está.
- **Ainda existe, fora do app:** `RealCapturesOcrTest` (teste instrumentado de diagnóstico, só
  executado manualmente sobre capturas reais) grava texto reconhecido no logcat e num relatório
  local. Não faz parte do APK do app e não foi executado; ajustá-lo depende de autorização.

#### Galaxy M52 (SM-M526B), 2026-10-28

O aparelho foi conectado e autorizado; tudo foi feito à mão, sem processos de espera, e o servidor
ADB foi encerrado no fim. Nada destrutivo: nenhuma desinstalação, nenhum `pm clear`, banco de
produção só lido (cópias via `run-as … cat`).

- **Estado antes:** app instalado em 2026-10-26 (banco Room v1). 33 questões (21 `NOT_REQUESTED`,
  12 `PENDING`, 12 com OCR), 2 sessões, 33 fotos (2 em `kept/`). **Nenhuma chave salva**
  (`no_backup/secure` inexistente): uma chamada real era impossível.
- **Testes instrumentados:** APK de teste instalado com `install -r` e executado com
  `am instrument`, sem `RealCapturesOcrTest` (diagnóstico que grava texto do OCR) e sem desinstalar
  nada. Primeira execução: 14/15. `beginAiRequest_isAtomic_overRealSqlite` falhou por **erro do
  teste**: `reconcile()` também remove linhas cuja foto não existe, e o teste inseria as questões
  antes. Corrigido (a recuperação roda antes de inserir; o teste de limite usa o mesmo
  repositório). Segunda execução: **15/15** ✅, incluindo `version1To2_keepsAllData` e a reserva
  atômica em SQLite real.
- **Instalação sobre a anterior (`install -r`) e migração 1 → 2 com dados reais:** ✅
  - `user_version` = 2 e `identity_hash` igual ao de `schemas/…/2.json`;
  - `ai_requests` criada com `cache_write_tokens` e `service_tier`, vazia;
  - `PRAGMA integrity_check` = ok, `foreign_key_check` vazio;
  - todas as linhas de `questions` e `capture_sessions` idênticas byte a byte às de antes;
  - MD5 das 33 fotos idêntico.
- **Histórico:** ✅ "33 fotos" na tela; capturas manuais e automáticas abrem normalmente.
- **Painel de análise:** ✅ abre e fecha; mostra `gpt-6-luna`, texto (OCR) como padrão, "0 de 10"
  envios nas 24 h e a estimativa. "Analisar" mostrou a confirmação da primeira chamada real; foi
  tocado **"Agora não"** (sem "Enviar", para a primeira chamada real manter esse aviso). Nada foi
  enviado, nenhuma linha em `ai_requests`, nenhuma preferência gravada.
- **Reinício (force-stop + abertura):** ✅ banco, fotos e contagem iguais; `ai_requests` vazia;
  reconciliação `imported=0, repointed=0, removed=0, interrupted=0`; nada enviado sozinho.
- **Logcat do processo do app:** ✅ sem `sk-`, `Bearer`, `Authorization`, `openai` nem texto de
  questão. `netstats`: nenhum tráfego registrado para o UID do app.

**Não validado no aparelho:**

- **Linha nova do log do OCR:** exigiria iniciar o monitoramento, que cria capturas e pode
  acionar a limpeza das não mantidas além das 20 mais recentes. Coberta por `OcrDecisionLogTest` e
  pela inspeção do dex.
- **Respostas e erros da API e limite de 24 h com envios reais registrados:** sem chave não há
  envio, e com chave seria chamada real. Cobertos só nos testes JVM (cliente falso) e no teste
  instrumentado do repositório. Um teste instrumentado com o cliente falso fica como pendência.
- **Fluxo `NO_KEY` no aparelho:** exigiria tocar em "Enviar" na confirmação.

**Observação:** o banco `com.google.android.datatransport.events` (registro de uso do ML Kit) existe
desde 2026-10-26. É anterior a esta fase e não houve tráfego do app; avaliar se o ML Kit envia
telemetria fica para uma revisão à parte.

#### Testes finais (`--rerun-tasks`, 2026-10-28)

- `spotlessCheck` ✅
- `:questvision-camera:testDebugUnitTest` ✅: **180/180** (2 novos do log do OCR).
- `:app:assembleDebug` ✅ · `:questvision-camera:assembleDebugAndroidTest` ✅
- Instrumentados no M52: **15/15** ✅ (após a correção do teste descrita acima).
- APK: nenhuma string `sk-…`.

### Primeira chamada real (2026-10-28)

Autorizada pelo usuário e feita por ele no M52: chave salva em ⚙ → OpenAI, uma única questão
(sessão `20261026-143906`, captura 2: "Questão 02 — Sistemas Operacionais", `ps aux`), modo
**Texto (OCR)**, confirmação de primeira chamada aceita na interface (com a estimativa de cerca de
US$ 0,0008). Nenhuma chamada foi feita pelo agente.

- **Resultado:** HTTP 200, `SUCCEEDED`, `gpt-6-luna`, `service_tier` = `default`, 3,5 s,
  `response_id` `resp_01db…`. Resposta **C** (`ps aux`), confiança alta, `needs_review` falso,
  explicação coerente. Questão `ANSWERED`, `attempts` = 1.
- **Uso:** 461 tokens de entrada (0 em cache, 0 escritos no cache), 75 de saída (0 de raciocínio).
  **Custo observado:** (461 × 0,10 + 75 × 0,50) / 10⁶ ≈ **US$ 0,000084**, abaixo da estimativa
  (que considera o teto de saída). Conferir com o painel de uso da OpenAI.
- **Contabilização:** uma linha em `ai_requests`; contador de 24 h = 1 de 10.
- **Segurança:** chave só em `no_backup/secure/openai_api_key.bin` (194 bytes, cifrada; sem `sk-` em
  texto puro). Logcat do processo do app: uma linha `QuestVisionAi` (modo, modelo, tier, resultado,
  HTTP, tokens); nenhuma ocorrência de `sk-`, `Bearer`, `Authorization`, enunciado ou resposta. O
  logcat do aparelho inteiro também não tem `sk-`.

#### Perda de dados encontrada e corrigida

- **O que aconteceu:** na reabertura seguinte, a retenção apagou a **foto e a linha** da questão
  respondida (33 → 32 questões). O registro em `ai_requests` sobreviveu (sem chave estrangeira).
- **Causa:** `AiStatus.keepsPhoto` protegia só `PENDING`, `PROCESSING` e `FAILED`. Ao virar
  `ANSWERED`, a questão (mais antiga que as 20 recentes não mantidas) passou a contar para a
  retenção, que remove o arquivo e a linha. Defeito da regra da Fase 3.2, exposto pela primeira
  resposta real.
- **Irrecuperável:** a foto (armazenamento privado, fora de backup). **Preservados fora do app:** a
  resposta, o texto do OCR e o registro da chamada, na cópia do banco feita antes do reinício
  (scratchpad local do agente; não versionada).
- **Correção:** `keepsPhoto` = tudo exceto `NOT_REQUESTED`. Texto do histórico atualizado ("além das
  que aguardam ou já passaram pela IA"). Testes:
  - JVM `prune_keepsEveryPhotoTheAiIsInvolvedWith_andRemovesRowsOfDeletedPhotos`: respondida, para
    revisão e com falha sobrevivem com a resposta;
  - instrumentado `anOldAnsweredQuestion_survivesTheCleanup_andARestart`: reproduz o caso real no
    SQLite do aparelho.
- **Instalação da correção** (`install -r`) e duas aberturas: 32 questões, 2 sessões e
  `ai_requests` idênticos; fotos com o mesmo MD5; chave mantida; `integrity_check` ok.

#### Testes no M52 (2026-10-28)

- **Instrumentados: 21/21** ✅ (sem o diagnóstico `RealCapturesOcrTest`):
  - `QuestionAnalyzerDeviceTest` (6, novo): analisador, `OpenAiClient`, repositório, Room e arquivos
    reais; só a rede é simulada (`HttpTransport` roteirizado). Cobre resposta salva e mantida após
    reinício; 9 erros (401, cota, 429, 500, incompleta, JSON inválido, sem rede, conexão
    interrompida, tempo esgotado), cada um com uma requisição, foto e OCR preservados; limite de 10
    em 24 h, mantido após reinício e liberado após 24 h; envio em voo ao morrer o processo vira
    `INTERRUPTED` sem reenvio; questão respondida antiga sobrevive à retenção; logcat do processo
    sem chave, enunciado ou explicação.
  - `QuestionDaoTest` (10), `QuestVisionMigrationTest` (1), `AndroidKeystoreSecretCipherTest` (4).
- **`RealCapturesOcrTest`:** o logcat recebe só métricas; as palavras vão só para o relatório
  privado do app de teste.

#### Telemetria do ML Kit

- O OCR usa o modelo **embutido** (`com.google.mlkit:text-recognition` 16.0.1): imagem e texto
  ficam no aparelho.
- O ML Kit envia **métricas** ao Google (`firebaselogging.googleapis.com`, HTTPS, via
  `datatransport`/CCT). Fila no M52: 16 eventos `FIREBASE_ML_SDK` de 2026-10-26, nunca enviados
  (`num_attempts` = 0). Cada um traz pacote do app, versão do SDK, idioma, um UUID por instalação,
  números (tempo, tamanhos) e metadados do aparelho: país, MCC/MNC, modelo, fingerprint do sistema,
  fuso horário. Nenhum texto reconhecido nem imagem.
- O Google **não documenta** forma de desativar. Redução possível, não oficial e **não aplicada**:
  remover do manifesto o `CctBackendFactory` (`tools:node="remove"`). Requer autorização e teste do
  OCR no M52.

### Auditoria antes do commit (2026-10-28)

**Exclusões automáticas: todos os caminhos que apagam fotos ou linhas foram revistos.**

| Caminho | Gatilho | Questão analisada pela IA |
| --- | --- | --- |
| `prune()` (retenção das 20) | abrir o app; após cada captura | **protegida** (`keepsPhoto` = tudo exceto `NOT_REQUESTED`), foto e linha |
| `delete()` ("Descartar") | ação do usuário | apagada só por pedido explícito; **recusado em `PROCESSING`** (novo) |
| `reconcile()` | abrir o app | remove só linhas cuja foto não existe; com a retenção corrigida, a foto de uma questão analisada só some por "Descartar" |
| monitoramento (duplicada, ilegível, falha) | sessão automática | apaga só a foto recém-capturada, antes de existir linha |
| sessões | — | nunca apagadas; FK `RESTRICT` |
| `ai_requests` | — | nunca apagada; sem FK, sobrevive à exclusão da questão |

- **Novo achado, corrigido:** "Descartar" apagava uma questão em análise (`PROCESSING`). A resposta,
  possivelmente cobrada, chegaria sem linha. Agora `delete()` devolve `false` e não apaga nada nesse
  estado. Teste JVM `discarding_isRefusedWhileTheAiIsProcessing`.
- **Corridas:** `prune()`, `delete()`, `reconcile()`, `beginAiRequest()`, `completeAi()` e
  `failAi()` passam pelo mesmo mutex do repositório (singleton), e a reserva é atômica no SQLite. Não
  há janela em que uma questão analisada fique sem proteção: `NOT_REQUESTED`/`PENDING` →
  `PROCESSING` (reserva) → `ANSWERED`/`NEEDS_REVIEW`/`FAILED`, todos protegidos.
- **Arquivos órfãos:** foto sem linha é protegida da retenção até o `reconcile()` importá-la como
  `NOT_REQUESTED`.
- **Migração:** só `AutoMigration(1, 2)`, sem `fallbackToDestructiveMigration`.
- **Risco residual (não crítico):** se a foto de uma questão analisada sumir por fora do app (falha de
  armazenamento), `reconcile()` remove a linha e a resposta. O registro em `ai_requests` fica. Manter
  essas linhas exigiria suporte a "foto ausente" no histórico: proposta para a próxima fase.

**OpenAI (sem novas chamadas):**

- O modelo `gpt-6-luna` é válido e está disponível: a chamada real respondeu HTTP 200 com
  `status: completed`.
- `service_tier` sempre explícito (`default`; `flex` só se ativado). A resposta confirmou `default`.
- Structured Outputs: `json_schema` estrito, e a resposta real foi interpretada pelo esquema (tipo,
  resposta, confiança, revisão).
- Campos de uso lidos da resposta real: `input_tokens`, `cached_tokens`, `cache_write_tokens`,
  `output_tokens`, `reasoning_tokens`.
- **Custo estimado ≠ observado ≠ cobrado:**
  - a estimativa antes do envio usa o teto de saída (≈ US$ 0,0008);
  - o observado aplica a tabela de preços aos tokens informados (≈ US$ 0,000084);
  - a cobrança oficial é a do painel de uso da OpenAI, e o valor do app não a substitui.
- O limite de 10 envios em 24 h e a ausência de reenvio após interrupção não mudaram e estão
  cobertos pelos testes JVM e instrumentados.

**Recuperação da questão perdida:**

- **Foto:** irrecuperável (armazenamento privado do app, sem backup).
- **Preservado fora do repositório**, em `~/QuestVision-backups/2026-10-28-banco-antes-da-perda/`
  (somente leitura):
  - banco antes da perda;
  - banco v1 anterior à atualização;
  - MD5 das fotos;
  - `questao-perdida-exportada.json` com OCR, resposta, modelo e uso.
- **Já no aparelho:** o registro da chamada (`ai_requests`, `resp_01db…`).
- **Reinserir a linha no banco do app não funciona hoje:** sem a foto, o próximo `reconcile()` a
  removeria. As opções exigem autorização:
  - (a) manter só o JSON exportado;
  - (b) implementar suporte a questão sem foto e então reinserir a linha.
- Nada foi alterado no banco do aparelho.

**Telemetria:**

- Não há opção oficial documentada para reduzir ou desativar as métricas do ML Kit.
- Os termos do ML Kit só exigem informar o usuário.
- O contorno conhecido (remover `CctBackendFactory` do manifesto) é não oficial e afeta todo SDK que
  use `datatransport`, por isso não foi aplicado.
- Medidas documentadas possíveis:
  - informar o usuário sobre as métricas;
  - não empacotar os samples (código de barras, rótulos, segmentação) no APK do QuestVision, para
    que só o reconhecimento de texto inicialize. Na fila havia um evento do SDK 17.3.0
    (barcode-scanning).

**Testes finais:**

- `spotlessCheck` ✅
- `:questvision-camera:testDebugUnitTest` ✅ **181/181** (`--rerun-tasks`)
- `:app:assembleDebug` ✅ · `:questvision-camera:assembleDebugAndroidTest` ✅
- **M52 (instrumentados): 21/21** ✅
- **M52, atualização final (`install -r`, duas aberturas):** questões, sessões e `ai_requests`
  idênticos byte a byte; 32 fotos e a chave cifrada com o mesmo MD5; `integrity_check` ok;
  reconciliação sem remoções; Logcat sem `sk-`.

### Push e decisões (2026-10-28)

- **Push:** `ef20f8d` enviado para `origin/questvision/fase-1` e confirmado com `git ls-remote`. A
  `main` continua em `2f10e46`, sem merge.
- **Telemetria:** a configuração do ML Kit foi mantida, sem bloqueios nem mudanças não documentadas.
- **Questão perdida:** os backups ficam como estão, somente leitura; nenhuma reinserção. Melhoria
  futura registrada em "Pendências".

### Telemetria observada e limites conhecidos

- **O que sai:** métricas do ML Kit para `firebaselogging.googleapis.com` (HTTPS, `datatransport`/CCT),
  com:
  - pacote e versão do app, versão do SDK, idioma e um UUID por instalação;
  - números de desempenho e de configuração (tempo, formato, resolução, tamanhos, códigos de erro);
  - país, MCC/MNC, modelo, `fingerprint` do sistema e fuso horário.
- **O que não sai:** imagens e texto reconhecido (documentação do ML Kit; nenhum texto nos 16
  eventos lidos no M52).
- **Limites do que se sabe:**
  - a fila nunca foi enviada até 2026-10-28 (`num_attempts` = 0), mas o envio pode acontecer
    quando o agendador do `datatransport` rodar com rede;
  - o conteúdo binário dos eventos foi lido só como texto legível, sem decodificar o protobuf;
  - o Google não documenta forma de desativar nem prazo de retenção desses dados.

### Samples e dependências que o QuestVision não usa (lista para decisão; nada removido)

O `:app` inclui os 31 samples com `implementation`, portanto eles entram também no APK de release; a
navegação só os expõe em builds depuráveis. O `:questvision-camera` usa apenas `:core-camera`,
`:core-ui`, `text-recognition`, Room, Hilt e `kotlinx-serialization`.

| Remoção | O que sai do APK | Efeito na telemetria | Risco |
| --- | --- | --- | --- |
| `camerax-qrscanner`, `camera2-qrscanner` | `mlkit:barcode-scanning` 17.3.0 | deixa de registrar o `BarcodeRegistrar` (houve 1 evento da 17.3.0 na fila) | nenhum para o QuestVision |
| `camerax-imagelabeling` | `mlkit:image-labeling` | menos um registrador de ML Kit | nenhum |
| `camerax-greenscreen` | `mlkit:segmentation-selfie` | menos um registrador de ML Kit | nenhum |
| `camerax-featurecombination` | `camera-feature-combination-query-play-services` | sem consulta ao Play Services | nenhum |
| `camerax-media3effects` | Media3 `common`/`effect`, `camera-media3-effect` | — | nenhum |
| demais 25 samples (CameraX e Camera2) | só código das demos; `camera-video`, `camera-extensions` | — | nenhum |

- **Forma segura sugerida:** trocar `implementation(project(":samples:…"))` por `debugImplementation`,
  com a lista do catálogo filtrada no release. Assim o release fica só com o QuestVision e o debug
  mantém o catálogo.
- **Remoção completa:** apagar módulos, `settings.gradle.kts`, `SampleCatalog.kt` e as strings do
  catálogo. Precisa de ajuste no `AGENTS.md`, que descreve o catálogo.
- **O que não muda:** o `text-recognition` continua enviando as próprias métricas. A remoção reduz a
  superfície, mas não zera a telemetria.

### Auditoria específica (2026-10-28)

Testado no código e nos testes indicados; nada mudou no app.

| Cenário | Comportamento | Onde está provado |
| --- | --- | --- |
| Painel fechado ou outra foto aberta com a análise em andamento | o `AiAnalysisViewModel` pertence à tela, não ao painel: a análise continua e a resposta tardia é salva na questão certa. A tela só mostra o resultado se a mesma questão estiver aberta (`questionId == id`) | código; `AiAnalysisViewModelTest` |
| "Cancelar" | o socket é fechado; tentativa `CANCELLED`, questão `FAILED`; uma resposta que chegasse depois não é lida (pode ser cobrada); reenviar pede confirmação | `cancelling_recordsTheAttemptAsCancelled…`, `resendingAfterAnUnknownOutcome…` (JVM) |
| Saída da tela (Activity finalizada) | `viewModelScope` cancelado: mesmo caminho do "Cancelar" | código |
| Processo morto durante o envio | próxima abertura: `INTERRUPTED`, sem reenvio, conta no limite de 24 h | JVM e M52 (`aRequestInFlightWhenTheAppDied…`) |
| Descarte durante a análise | recusado (`delete()` = `false`), foto e linha intactas | `discarding_isRefusedWhileTheAiIsProcessing` (JVM). Limite: a interface fecha a revisão sem avisar que o descarte foi recusado |
| Foto ausente, modo imagem | `IMAGE_UNAVAILABLE`: nada enviado, nada reservado, nada registrado | JVM (`incompleteOcr_orUnreadablePhoto_sendNothing`) e M52 com o codificador real (`aMissingPhoto_inImageMode_sendsNothing`, novo) |
| Foto ausente, ao abrir o app | `reconcile()` remove a linha, inclusive com resposta da IA; o `ai_requests` fica | código; risco residual (melhoria futura) |
| Codificação da imagem | JPEG 85 %, sem EXIF, orientado. A decodificação usa `inSampleSize` em potência de 2 até ≤ 2048 px e depois reduz até a configuração. O tamanho enviado pode ficar abaixo da configuração (foto de 3000 px → 1500 px) | código; M52 (`imageMode_sendsTheRealPhoto_downscaled`, novo: 2000×1500 → 1600×1200) |

### Teste real de imagem (autorizado, pendente)

Uma única chamada autorizada em 2026-10-28, ainda **não executada**:

- **Bloqueio de privacidade:** todas as capturas existentes mostram a barra lateral do ChatGPT,
  com títulos de conversas pessoais. No modo imagem a tela inteira vai para a OpenAI.
- **Bloqueio de retenção:** há exatamente 20 capturas não mantidas e não protegidas. Uma captura
  nova faria a limpeza apagar a mais antiga.
- **Antes do envio:**
  - modelo `gpt-6-luna` (Standard);
  - estimativa do app ≈ US$ 0,0008, **sem a imagem**;
  - não há fórmula oficial de tokens de imagem para esse modelo; cada 1.000 tokens de entrada custam
    US$ 0,0001;
  - a confirmação de primeira chamada não aparece mais: "Analisar" envia direto.

### Pendências

1. Decidir sobre a telemetria do ML Kit: aviso ao usuário e/ou tirar os samples do APK.
2. **Melhoria futura:** preservar a resposta da IA mesmo quando o arquivo da foto estiver ausente
   (hoje `reconcile()` remove a linha). Exige suporte a "foto ausente" no histórico e na revisão.
3. **Melhoria futura:** avisar na interface quando um descarte for recusado durante a análise.
4. Medir o modo imagem (tokens reais da foto): autorizada uma chamada; ver "Teste real de imagem".
5. Conferir o custo observado com o painel de uso da OpenAI.
6. O painel de análise mostra o custo observado só enquanto a questão existe; o registro em
   `ai_requests` não aparece na interface depois que a questão é descartada.

## 3l. Fase 3.4: detecção e análise automática de questões (2026-10-28, em desenvolvimento, não commitada)

**Autorização desta etapa:** fluxo automático completo, mas **envios automáticos reais à OpenAI
desativados**. Validado só com clientes simulados. Chamadas reais continuam apenas pelo "Analisar"
manual, com as confirmações da Fase 3.3. Sem Telegram, sem commit.

### Diagnóstico do que já existia (reaproveitado sem reescrever)

| Peça | Situação |
| --- | --- |
| Câmera e estabilidade (`ScreenChangeDetector`, `MonitoringSession`) | Detecta mudança de tela e só captura com a imagem parada (Fases 2 e 2.5). Mantido. |
| OCR local (ML Kit, modelo embutido) | Lê cada captura automática. Mantido. |
| Repetidas na sessão (`QuestionTextMatcher`) | Compara o texto com as questões já aceitas na sessão. Mantido. |
| Sessões e histórico (Room v2) | `capture_sessions`, `questions`, `ai_requests`. Sem mudança de esquema nesta fase. |
| OpenAI (`QuestionAnalyzer`, `OpenAiClient`) | Uma requisição por chamada, reserva atômica, sem novas tentativas, limite de 24 h. Mantido. |
| Proteção de dados | Retenção protege tudo que passou pela IA; descarte recusado em `PROCESSING`; recuperação `INTERRUPTED`. Mantido. |

### O que foi implementado

1. **Validação local (`ai/QuestionAssessment.kt`, `assessQuestion`).** Avalia a questão mais recente
   da tela e aponta um problema (`AssessmentIssue`) sempre que houver dúvida:
   - sem título "Questão N";
   - sem enunciado;
   - menos de duas alternativas (e nenhum Certo/Errado);
   - alternativas fora de sequência (A, C… ou não começando em A/1);
   - lista possivelmente cortada (a última alternativa é a última linha da tela);
   - pouca confiança no OCR (mais de 15 % das palavras abaixo de 0,5: desfoque, reflexo, movimento).

   **Formatos:** letras (A–E), números (1–5) e julgamento (Certo/Errado, Verdadeiro/Falso).

   **Conteúdo visual:** detectado por palavras como figura, gráfico, tabela, imagem, diagrama, esquema,
   mapa, charge, tirinha, ilustração e circuito, ou por 6 ou mais símbolos matemáticos.

2. **Sessão (`MonitoringSession`).** O novo veredito `INCOMPLETE` grava a captura, mas não a usa como
   referência da questão, então uma captura melhor (rolada, mais nítida) ainda conta como nova.
   Contador `incompleteCaptures`.

3. **Repetidas entre sessões.** Antes de gravar, `findSentToAi` compara o bloco da questão (não a
   tela inteira) com as questões que já passaram pela IA, usando o mesmo comparador da sessão. Se
   for repetida, a foto nova é descartada (como as repetidas da sessão), nada é enviado e aparece o
   aviso "já analisada".

4. **Coordenador (`ai/AutoAnalysisCoordinator.kt`).**
   - **Fila:** em memória, um envio por vez, na ordem de captura. Cada questão entra uma vez por
     sessão e só é enviada se ainda estiver `PENDING`.
   - **Para de enviar sozinho** quando (na rodada 2 o monitoramento deixou de pausar junto; ver
     "Rodada 2"):
     - atinge o limite da sessão ou o de 24 h;
     - falta a chave;
     - há perda de conexão (resultado desconhecido: nada é reenviado);
     - há erros que se repetiriam (401, 403, cota, 429, 400).
   - **Continua para a próxima questão** em 500, resposta inválida, incompleta ou recusada (a questão
     fica `FAILED`).
   - **Encerrar a sessão:** esvazia a fila; o envio em andamento termina e é gravado.
   - **Reinício:** a fila não sobrevive, e nenhuma questão `PENDING` antiga é enviada sozinha.
   - **Trava de envio real:** `AutoSendPolicy`, ligada no app a `LockedAutoSendPolicy`
     (`realRequestsAllowed = false`). Com a trava, a questão validada fica "pronta para Analisar".

5. **Interface.**
   - Status da IA abaixo dos controles: aguardando, analisando, pausada (com o motivo), desativada
     nesta versão, último evento, análises da sessão/limite, custo observado e última resposta
     (letra e texto da alternativa).
   - Novos avisos: "já analisada" e "incompleta".
   - Nova configuração: "Análises automáticas por sessão" (3, 5 ou 10; padrão 10). O limite de 10 por
     24 h não foi alterado.

6. **Modo imagem.** O pedido passa a nomear a questão quando o número foi lido ("Responda somente a
   Questão N."), porque a tela fotografada costuma mostrar o fim da questão anterior.

### Estratégia OCR × imagem (recomendação)

**Opção C, já implementada na escolha do modo:** texto do OCR quando a questão tem alternativas com
letras e nenhum sinal de conteúdo visual; foto quando há gráfico, tabela, fórmula ou imagem, ou
quando o formato (números, Certo/Errado) não é levado pelo modo texto.

- **Motivo:** o texto custou 461 tokens de entrada na chamada real. Não há fórmula oficial de
  tokens de imagem para o `gpt-6-luna` nem medição real; o teste de imagem continua pendente.
- **Não implementado:** nenhuma chamada dupla de comparação e nenhuma troca automática de texto
  para imagem depois de uma resposta incerta (custaria uma segunda requisição sem autorização).

### Testes (todos com clientes simulados; nenhuma chamada real)

| Item exigido | Teste |
| --- | --- |
| 1. Questão nova e completa | `aNewCompleteQuestion_isSentOnce_andAnswered`, `aSession_detectsValidatesAndAnalyzes_withoutAnyTouch`, `aCompleteLetteredQuestion_isUsable_inTextMode` |
| 2. Questão repetida | `aQuestionAnalyzedInAnEarlierSession_isNotSentAgain`, `aQuestionAlreadyAnalyzed_isNeverSentAgain`, `sameQuestion_ignoresTheRestOfTheScreen_andOcrNoise` e os testes de repetidas da sessão (já existentes) |
| 3. Questão incompleta | `alternativesEndingTheScreen_mayBeCut_andWait`, `anIncompleteQuestion_isKept_butNotSent`, `anIncompleteQuestion_isKept_butDoesNotBlockABetterCapture` |
| 4. Imagem desfocada ou instável | `aBlurredCapture_isNotLegibleEnough`; instabilidade: testes do detector (Fases 2 e 2.5: não captura em movimento) |
| 5. Alternativas ausentes | `missingOrSkippedAlternatives_areNotComplete`, `anIncompleteQuestion_isNotQueued` |
| 6. Gráfico ou imagem | `visualContent_switchesToTheImage`, `aQuestionWithAChart_goesAsTheImage` |
| 7. Duas questões em sequência rápida | `twoQuestionsInQuickSuccession_goOneAtATime_inOrder` |
| 8. Reinício durante a análise | `aRestartDuringAnAnalysis_isNeverResentAutomatically` (e os da Fase 3.3) |
| 9. Perda de internet | `aLostConnection_pausesTheSession_andIsNeverRetried` |
| 10. Resposta inválida | `anInvalidAnswer_failsThatQuestion_andTheNextStillGoes` |
| 11. Limite diário | `theDailyLimit_pausesTheSession_andSendsNothing`, `theDailyLimit_pausesTheMonitoringToo` (+ limite da sessão) |
| 12. Pausa e retomada | `pausing_holdsTheQueue_andResumingSendsIt` |
| 13. Fotos e histórico | `analyzedQuestions_andTheirPhotos_surviveARestart` (+ testes de retenção e descarte) |
| 14. Chamadas duplicadas | `aNewCompleteQuestion_isSentOnce_andAnswered` (envio repetido), `aQuestionAlreadyAnalyzed_isNeverSentAgain` |
| 15. Resposta após a troca de questão | `anAnswerArrivingAfterTheQuestionChanged_isStoredOnItsOwnQuestion` |
| Trava do envio real | `withRealAutomaticRequestsLocked_nothingLeavesTheDevice`, `withoutAuthorizationForRealRequests_nothingIsSent` |

**Resultados:**

- **JVM:** `:questvision-camera:testDebugUnitTest` ✅ **215/215** (eram 181). Novos:
  - `QuestionAssessmentTest` (11);
  - `AutoAnalysisCoordinatorTest` (16);
  - 6 em `QuestVisionCameraViewModelTest`;
  - 1 em `TextVerificationSessionTest`.

  `OcrDecisionLogTest` passou a comparar palavras inteiras: o novo contador `incompleteCaptures`
  contém "com", sem vazamento.
- **Build:** `:app:assembleDebug` ✅, `:questvision-camera:assembleDebugAndroidTest` ✅, `spotlessApply`.
- **Instrumentados:** o novo `anAutomaticSession_sendsEachQuestionOnce_andNothingAfterARestart`
  (SQLite e arquivos reais, cliente real com respostas roteirizadas) compila, mas **não foi
  executado**: o M52 não estava conectado.

### Sessão com 20 questões (comportamento esperado)

- **Como o app está hoje (trava ligada):**
  - cada questão completa e nova é capturada, lida, validada e gravada como `PENDING`, e aparece
    "pronta para Analisar";
  - incompletas são gravadas e marcadas; repetidas de sessões anteriores, descartadas;
  - **nenhuma requisição sai**; as 20 podem ser analisadas à mão, até 10 por 24 h.
- **Com o envio automático autorizado (padrões: 10 por sessão, 10 por 24 h, nenhum envio anterior
  nas 24 h):** comportamento atualizado na rodada 2 (ver "Rodada 2", modo somente captura).

### Consumo da API (sem preços ou medições inventados)

- **Única medição real (modo texto):** 461 tokens de entrada e 75 de saída, ≈ US$ 0,000084
  (Standard).
- **Teto por requisição de texto,** pela configuração (1.500 tokens de saída, incluindo raciocínio):
  ≈ US$ 0,0008. **Máximo de 10 requisições por 24 h** ⇒ teto local ≈ US$ 0,008 por dia em modo
  texto. Isso é um freio local, não um teto financeiro garantido.
- **Modo imagem:** desconhecido. Não há fórmula oficial para o modelo nem medição.

### Riscos identificados

- **Validação conservadora demais ou de menos:**
  - questões sem "Questão N" nunca são enviadas sozinhas;
  - uma questão cuja última alternativa encosta no fim da tela espera rolagem;
  - duas questões que diferem só em poucas palavras podem ser tomadas como a mesma.
- **Modo imagem envia a tela inteira:** inclui a barra lateral do ChatGPT (títulos de conversas
  pessoais).
- **Confiança do ML Kit no M52:** não medida; o limiar de 0,5 vem da Fase 2.5.
- ~~Limite atingido pausa também a captura~~: corrigido na rodada 2 (modo somente captura).
- **Contagem da sessão:** um envio em andamento ao encerrar e reabrir uma sessão conta na sessão
  nova (conservador).
- **Pendências herdadas:** foto ausente remove a linha com resposta; descarte recusado sem aviso.

### O que falta para ativar o envio automático real

1. Rodar os instrumentados no M52 e testar a sessão no aparelho com a trava ligada (só validação).
2. Teste real de imagem (pendente) para conhecer o custo do modo imagem.
3. Autorização explícita para trocar `LockedAutoSendPolicy` por uma política liberada, com o
   aviso de consentimento ao iniciar a sessão.
4. Decidir o comportamento ao atingir o limite (pausar tudo ou só o envio).
5. Uma sessão real curta e acompanhada (por exemplo, 3 questões com limite de sessão 3).

### Rodada 2 (2026-10-28): correções pedidas na avaliação

Envio automático real **continua travado** (`LockedAutoSendPolicy`). Nenhuma chamada real, nenhum
commit. Sem mudança no esquema do banco: nada exigiu migração.

**Defeitos corrigidos:**

1. **Limites paravam a câmera.**
   - **Agora:** o limite da sessão ou de 24 h, a falta de chave, a perda de conexão e os erros da API
     param **só o envio**. A câmera continua monitorando, e as questões novas continuam sendo
     capturadas, guardadas como `PENDING` e mostradas como pendentes (modo somente captura).
   - **Pausas por limite ou erro são definitivas na sessão:** "Pausar/Retomar" não as levanta, e a
     liberação da janela de 24 h também não. Só uma nova sessão iniciada pelo usuário volta a enviar.
2. **Dependência do título "Questão N".**
   - **Agora:** a questão é localizada pelo conteúdo: o último grupo completo de respostas e, acima
     dele, o início.
   - **Formatos:**
     - A–E ou A–D, nunca presumindo cinco alternativas;
     - números de 1 a 5;
     - Certo/Errado e Verdadeiro/Falso;
     - com ou sem número.
   - **Sem título,** a linha logo acima das respostas precisa perguntar (terminar em "?" ou ":"; numa
     assertiva de Certo/Errado, "."), e o enunciado sobe enquanto estiver na mesma coluna e no mesmo
     parágrafo, pelas posições das linhas que o ML Kit fornece. No texto guardado, sem posições, ele
     para na frase anterior ou numa linha curta.
   - **Na dúvida não envia** (`NO_QUESTION_START`): enunciado no topo da foto, sem fronteira clara, ou
     mais de 8 linhas.
   - **Sem título vai sempre como imagem recortada,** porque o texto guardado não marca com segurança
     onde o enunciado começa.
   - **Corte:** a questão é tratada como possivelmente cortada se encostar na borda de cima ou de
     baixo da foto (3 %) ou se a última resposta for a última linha.
3. **Questões quase iguais tomadas como repetidas.**
   - O comparador tolera ruído de OCR (1–2 letras), então "correta" e "incorreta" pareciam iguais, e
     "incorreta" contém "correta". Uma repetida da sessão tem a foto apagada.
   - **Agora** cada questão tem uma assinatura (`QuestionSignature`) que precisa coincidir
     exatamente:
     - negações (não, exceto, incorreta, falsa, errada…);
     - números;
     - operadores (+, ×, =, <, %…; "-" e "/" só entre dígitos);
     - alternativas (no máximo 1 caractere diferente a cada 8).
   - Vale na sessão (comparador) e entre sessões (`sameQuestion`). O título fica fora, porque a mesma
     questão pode ter outro número.
   - Assinatura desconhecida não diferencia nada: o comportamento antigo continua valendo.
4. **Modo imagem enviava a tela inteira.**
   - **Agora:** `JpegQuestionImageEncoder` endireita a foto e roda o OCR **no mesmo bitmap** que será
     recortado (as caixas e os pixels coincidem, sem conversão por EXIF), localiza a questão e recorta
     a união das suas linhas, do início à última resposta. Figuras e tabelas entre elas ficam dentro.
   - **Margem:** 2 % na horizontal; com conteúdo visual tenta 10 % e cai para 2 %.
   - **Recusa o recorte** se qualquer outra linha (título de conversa, barra lateral, navegador)
     entrar mais de 5 % na região, se faltar caixa ou se a região for pequena demais.
   - **No envio automático** (`RegionPolicy.QUESTION_ONLY`), sem região segura **nada é enviado**: a
     questão fica `PENDING`, com a rejeição `QUESTION_REGION_NOT_FOUND`.
   - **No "Analisar" manual com Imagem** (`QUESTION_OR_WHOLE`), recorta quando acha a região; senão
     envia a foto inteira, como antes, por escolha explícita do usuário.
   - A foto original nunca é alterada.
5. **Duas análises simultâneas** (uma manual e uma automática em questões diferentes) eram possíveis.
   - **Agora** há uma requisição em andamento por vez no app inteiro (`ANOTHER_IN_PROGRESS`). A
     automática espera a vez sem perder o lugar na fila.
6. **Armazenamento.**
   - No modo somente captura as pendentes (protegidas da retenção) se acumulam. A retenção nunca apaga
     pendentes nem analisadas, e as capturas automáticas não empurram as 20 manuais para fora.
   - **Novo:** abaixo de 300 MB livres o monitoramento pausa com aviso, antes de uma captura falhar.
     A verificação acontece a cada 5 s.

### Qualidade do OCR: o que é medido e o que é heurística

| Métrica | Origem | Uso |
| --- | --- | --- |
| Confiança por palavra (`Text.Element.getConfidence()`) | **ML Kit (real)**. Documentada só como valor em [0, 1], não como probabilidade calibrada | `LOW_CONFIDENCE`: mais de 15 % das palavras da questão abaixo de 0,5 (**heurística nossa**) |
| Caixa de cada linha (`Text.Line.getBoundingBox()`) | **ML Kit (real)** | enquadramento, corte nas bordas, coluna e parágrafo do enunciado, região do recorte (**heurísticas nossas**) |
| Estabilidade | **heurística nossa** (`ScreenChangeDetector`: movimento entre quadros de baixa resolução, tempo parado) | só captura com a imagem parada |
| Nitidez (`LumaFrame.sharpness`, desvio de passa-alta) | **heurística nossa** | apenas registrada nos logs; o desfoque aparece na confiança do ML Kit |
| Alternativas, sequência, enunciado, conteúdo visual | **heurísticas nossas** | `assessQuestion` |
| Repetição em quadros consecutivos | **não implementada** | o OCR roda uma vez por captura; a estabilidade vem dos quadros |

Nenhuma dessas heurísticas é uma pontuação oficial de qualidade.

### Testes da rodada 2

- **JVM:** `:questvision-camera:testDebugUnitTest` ✅ **230/230** (eram 215). Novos:
  - `QuestionAssessmentTest`: sem título (conteúdo e layout), sem pergunta, texto guardado,
    Certo/Errado, corte no topo, 4 alternativas, recorte sem a barra lateral, recorte recusado com
    invasão ou sem caixas, margem maior para conteúdo visual, e negação, número, operador e
    alternativa diferentes ⇒ questões diferentes;
  - `AutoAnalysisCoordinatorTest`:
    - limite da sessão definitivo (inclusive após pausar e retomar);
    - limite de 24 h que não se libera sozinho;
    - imagem automática só com região, senão nada;
    - manual em andamento faz a automática esperar;
    - conexão: só uma nova sessão volta a enviar;
  - `QuestVisionCameraViewModelTest`:
    - no limite a câmera continua capturando;
    - pausa e retomada;
    - variante negada não é repetida.
- **Build:** `:app:assembleDebug` ✅, `:questvision-camera:assembleDebugAndroidTest` ✅, `spotlessApply`.
- **Instrumentados novos** (compilam; **não executados**: M52 desconectado):
  - `QuestionOnScreenDeviceTest`: OCR real do ML Kit em telas desenhadas com barra lateral, cobrindo:
    - questão completa com e sem título;
    - alternativas cortadas;
    - repetida × negada;
    - recorte sem nenhum título da barra lateral, com o OCR da imagem enviada;
    - sem questão ⇒ nada automático.
  - `anAutomaticSession_sendsEachQuestionOnce_andNothingAfterARestart`.

### Sessão com 20 questões (rodada 2)

- **Trava ligada (como está):**
  - todas as questões completas e novas são capturadas e validadas, e ficam pendentes;
  - incompletas também ficam guardadas, sem envio;
  - repetidas de questões já analisadas são descartadas;
  - nada é enviado.
- **Envio autorizado (10/sessão, 10/24 h):**
  - as 10 primeiras válidas são enviadas, uma de cada vez, só o recorte quando for imagem;
  - depois o envio para pelo resto da sessão, e as questões 11 a 20 continuam sendo capturadas e
    ficam pendentes para análise posterior;
  - perda de conexão: o envio para sem reenvio, e a câmera segue.

### Riscos de armazenamento e bateria

- **Armazenamento:** cerca de 3,3 MB por foto no M52 (32 fotos = 107 MB). Pendentes e analisadas
  nunca são apagadas pela retenção, então um uso longo cresce sem limite até a pausa por pouco
  espaço (300 MB). Falta uma ferramenta de limpeza manual de pendentes antigas.
- **Bateria e calor:**
  - quadros de análise de baixa resolução o tempo todo durante a sessão;
  - uma captura em resolução cheia e um OCR (≈ 0,5 s) por mudança de tela;
  - no modo imagem, um segundo OCR no envio, para o recorte.

  Uma sessão longa com a tela do celular ligada consome bastante. Não foi medido no M52.

### Auditoria final (rodada 2)

- **Gastos indevidos:**
  - uma requisição por vez no app;
  - cada questão no máximo uma vez;
  - pendente de sessão anterior nunca é enviada sozinha;
  - pausa definitiva por limite ou erro;
  - nenhum reenvio após falha ambígua;
  - imagem automática só com recorte seguro;
  - trava do envio real ligada.
- **Perda de dados:**
  - pendentes e analisadas protegidas da retenção;
  - descarte recusado em `PROCESSING`;
  - repetida só é descartada quando a assinatura coincide;
  - foto original nunca alterada.
- **Riscos que permanecem:**
  - linha sem foto ainda é removida no `reconcile()` (melhoria registrada);
  - descarte recusado sem aviso na tela;
  - um envio em andamento ao encerrar e reabrir a sessão conta na sessão nova;
  - o recorte manual pode cair para a foto inteira;
  - sem título, uma resposta manual em modo texto exige o título (o manual usa imagem).
- **Logcat:** nenhuma linha nova com texto, chave ou resposta; o coordenador não registra nada.
- **Pendente para concluir a Fase 3.4:** validação no M52 (instrumentados, instalação por cima,
  sessão real com a trava ligada). **A Fase 3.4 não está concluída.**

### Rodada 3 (2026-10-28): validação final — **bloqueada no M52**

**Aparelho:** `adb devices` sem nenhum dispositivo, em três verificações durante a rodada. Por isso
**não foram executados**, e nenhum está aprovado:

- backup dos dados reais e conferência de integridade;
- instalação da nova versão por cima;
- testes instrumentados (inclusive os com OCR real);
- sessão automática simulada no aparelho;
- integridade após reiniciar.

O app instalado no M52 continua a versão da Fase 3.3 com as correções da auditoria (antes da Fase 3.4), e os
dados dele não foram tocados nesta rodada. Nenhum processo de espera foi deixado; o servidor ADB foi
encerrado.

**Formatos de enunciado (feito sem o aparelho, testes JVM):**

| Enunciado (linha logo acima das alternativas) | Com título, antes | Sem título, antes | Sem título, agora |
| --- | --- | --- | --- |
| "Qual das alternativas está correta?" | ✅ | ✅ | ✅ |
| "Assinale a alternativa correta." | ✅ | ❌ falso negativo | ✅ |
| "Analise as afirmativas e marque a opção adequada." | ✅ | ❌ falso negativo | ✅ |
| "Considerando o texto apresentado, é correto afirmar que" | ✅ | ❌ falso negativo | ✅ |
| "… é correto afirmar que..." | ✅ | ❌ falso negativo | ✅ |
| "Julgue o item a seguir." + assertiva + Certo/Errado | ✅ | ✅ | ✅ |
| "Os tópicos foram discutidos em sala." (não pergunta) | — | rejeitado | rejeitado (correto) |

**Correções, com base nesse resultado** (sem afrouxar o resto):

- **A linha que pede a resposta** aceita, além de "?" e ":", reticências ("…", "...") e um comando de
  prova (assinale, marque, indique, escolha, selecione, julgue, identifique, aponte) ou "… afirmar
  que". Uma frase comum acima de uma lista continua rejeitada.
- **Coluna:** linhas recuadas (células de tabela) passam a contar como parte da coluna da questão. A
  barra lateral e as barras do navegador começam mais à esquerda e continuam fora.
- **Parágrafos:** um vão de até 2,0 alturas de linha une parágrafos do mesmo enunciado; um vão maior
  encerra (mensagem anterior). **O valor 2,0 não foi medido em telas reais do ChatGPT:** é uma
  estimativa a validar no M52.
- **Limite de linhas sem título:** 30 com posições das linhas; 8 no texto guardado (sem posições).
  **Limitação:** no texto guardado, um enunciado sem título com mais de 8 linhas fica como "início não
  identificado". Na captura, as posições existem, então isso só afeta a comparação de repetidas e o
  texto da alternativa mostrado.

**Recorte (testes JVM com layouts sintéticos; no aparelho, só compilado):**

| Situação | Resultado |
| --- | --- |
| Questão centralizada | recorte da questão |
| Barra lateral do ChatGPT | lateral fora (região começa à direita dela) |
| Barra e abas do navegador | fora (região começa abaixo delas) |
| Tabela entre enunciado e alternativas | dentro; modo imagem |
| Gráfico / fórmula | margem maior (10 %) quando nada está ao lado |
| Questão parcialmente fora da tela | `POSSIBLY_CUT`: não é enviada automaticamente |
| Título da lateral invadindo a região | recorte recusado ⇒ nada enviado no modo automático |

**Testes:**

- **JVM:** **243/243** ✅. Novos: `QuestionFormatsTest` (13).
- **Build:** `spotlessCheck` ✅, `:app:assembleDebug` ✅, `:questvision-camera:assembleDebugAndroidTest` ✅.
- **Instrumentados:** `QuestionOnScreenDeviceTest` ganhou o caso com comando sem título, tabela e
  barra do navegador, com o OCR da imagem enviada. **Nenhum instrumentado foi executado nesta fase.**
- **Sessão automática simulada:** coberta só por testes JVM (`aSession_detectsValidatesAndAnalyzes_withoutAnyTouch`,
  `atTheLimit_theCameraKeepsCapturing_andNothingIsSent`) e por um instrumentado não executado.
- **Precisão observada:** só sobre os exemplos sintéticos acima; **não há medição em capturas
  reais**.
- **Bateria e desempenho:** **não medidos.**

**Para aprovar a Fase 3.4 falta:**

1. Conectar o M52 e fazer backup, integridade, instalação por cima, instrumentados e reinício.
2. Uma sessão real com a trava ligada (validação, recorte e pendentes), conferindo os vãos entre
   parágrafos e mensagens do ChatGPT real (o limiar de 2,0).
3. Medir a bateria numa sessão de 15 a 30 minutos.

### Rodada 4 (2026-10-29): validação no Galaxy M52

**Preparação** (nada destrutivo, nenhuma chamada real):

- **Estado inicial:** app de 2026-10-28 12:15; 32 questões, 2 sessões, 1 registro em `ai_requests`,
  32 fotos e a chave cifrada, idênticos à referência de 28/10; `integrity_check` ok; banco v2.
- **Backup novo, somente leitura:** `~/QuestVision-backups/2026-10-29-antes-da-fase-3.4/` (banco,
  configurações, as 32 fotos com MD5 conferido e a lista de MD5). Os backups anteriores não foram
  tocados.

**Instalação e testes:**

- **Atualização por cima** (`install -r`) e duas aberturas, duas vezes (antes e depois das correções
  abaixo): banco, sessões, `ai_requests`, fotos, chave e configurações sempre idênticos; reconciliação
  sem remoções; nenhum `sk-` no Logcat.
- **Instrumentados: 31/31** na versão inicial e **33/33** na final (sem `RealCapturesOcrTest`).
  Incluem:
  - OCR real em telas desenhadas: com e sem título, cortada, repetida × negada, recorte sem barra
    lateral e sem barra do navegador, tabela e comando sem título;
  - fotos giradas 90°/180°/270° e EXIF 6 de cabeça para baixo;
  - sessão automática sobre SQLite real;
  - migração, DAO e Keystore.
- **JVM: 251/251.**

**Sessão real com a trava ligada (2026-10-29 01:47–01:50):** o usuário passou por 7 questões no
ChatGPT. **Nenhuma requisição à OpenAI** (`ai_requests` continuou com 1 linha).

- **Defeito crítico encontrado:** **as 13 capturas foram classificadas como incompletas.**
  - **Causa:** o celular estava na horizontal com o app em retrato. As fotos têm EXIF 6, e depois da
    rotação o texto da tela fica de cabeça para baixo. O ML Kit lê, mas a ordem das linhas e as
    posições ficam invertidas (alternativas E…A antes do enunciado).
  - **Consequência adicional:** como incompletas não viram referência, nada era reconhecido como
    repetido, e cada rolagem gerava mais uma captura pendente.
  - As 13 capturas ficaram gravadas como `PENDING` (dados novos, nada apagado).
- **Correções** (validadas reprocessando **as mesmas 13 fotos** no M52 por um teste de diagnóstico
  que registra só métricas, sem texto):
  1. **Orientação do texto:** pelo ângulo das linhas que o ML Kit informa
     (`Text.Line.getAngle()`). Com 90°, 180° ou 270°, a imagem é girada e relida; o recorte usa a
     mesma rotação. Detectado 270° em todas as 13.
  2. **Título "Questāo3-"** (mácron lido pelo OCR) e maiúsculas acentuadas ("QUESTÃO"): o padrão do
     título não as aceitava (`IGNORE_CASE` só cobre ASCII).
  3. **Botão de opção (◯) colado ao rótulo** ("OC)") é tolerado. **Uma letra perdida no meio da
     lista** (B, "OO Scrum…", D) é inferida pela linha entre as vizinhas: uma vez por lista, nunca a
     primeira.
  4. **"Diagrama de Casos de Uso"** deixou de contar como conteúdo visual: a palavra visual precisa de
     "abaixo", "observe", "a seguir"…
  5. **Incompletas repetidas** na sessão são descartadas (comparadas só entre si); a captura completa
     continua sendo comparada só com as completas.
  6. **Recorte:**
     - linhas fora de lugar na ordem de leitura não entram (o ML Kit pôs a bolha "pergunta para
       teste", do topo, entre as alternativas B e C);
     - a margem para antes das linhas vizinhas;
     - só controles minúsculos (1 palavra) dentro da questão são tolerados ("Pergunte ao" da barra
       flutuante deixou de ser);
     - a coluna aceita recuo de até 15 %, não a bolha alinhada à direita;
     - a última alternativa quebrada em duas linhas fica inteira.

**Resultado nas 13 fotos reais (versão final):**

| Foto | Conteúdo (conferido nas imagens) | Antes | Agora |
| --- | --- | --- | --- |
| #1, #2 | "Pergunta de teste" completa (sem "Questão N"; "C)" lido como "OO") | incompleta | **utilizável**, imagem recortada |
| #3, #4, #6 | início da próxima questão (só título/enunciado/A) | incompleta | incompleta ✔ |
| #5 | Questão 3 completa | incompleta | **utilizável** |
| #7, #8 | Questão 4 completa (afirmativas I–IV, A–D) | incompleta | **utilizável** |
| #9 | só o fim das alternativas anteriores | incompleta | incompleta ✔ |
| #10 | Questão 5 completa | incompleta | **utilizável** |
| #11, #12 | Questão 6 completa | incompleta | **utilizável** |
| #13 | Questão 7 completa | incompleta | **utilizável** |

- **Contagem:** 9 verdadeiros positivos, 4 verdadeiros negativos, **0 falsos positivos, 0 falsos
  negativos**, numa amostra pequena (13 fotos, 7 questões, uma tela e uma iluminação).
- **Recortes das 13 fotos:**
  - nenhum dos itens externos procurados (favoritos do navegador, "Colinha", barra "Pergunte ao
    ChatGPT", relógio, bolha da mensagem) aparece na imagem que seria enviada;
  - conferência visual de #1, #10 e #13: só a questão, com a última alternativa inteira;
  - a releitura do recorte encontra as mesmas alternativas (5/5 ou 4/4), exceto quando o OCR perde
    um rótulo (#1, #11).

**Desempenho:**

- **CPU** (`top`, a cada 30 s): 14–28 % monitorando; até 57 % durante captura e OCR.
- **Memória:** 180–254 MB.
- **Bateria:** **não medida** (carregando pelo USB). Temperatura de 39,2 a 40,0 °C.
- **OCR:** 0,7–1,4 s por foto na sessão; 1,5–3,6 s com a releitura girada (no diagnóstico).
- **Fotos:** cerca de 4,7 MB nesta posição (3024×4032).

**Pendências:**

- Uma **segunda sessão real** com a versão corrigida, para ver no fluxo ao vivo a detecção, as
  repetidas e as incompletas repetidas. A validação acima reprocessa as mesmas fotos.
- **Cópias temporárias** das 13 fotos ficaram no aparelho, fora do app: `/data/local/tmp/qv_field/`
  e, no app de teste, `files/field_captures/` e `files/field_sent/`. A remoção foi bloqueada pela
  verificação de segurança e fica a critério do usuário.
- **Bateria** numa sessão fora do carregador.

### Rodada 5 (2026-10-29 02:56–02:58): segunda sessão real, versão corrigida

Trava do envio real ligada; mesmo jeito de segurar o celular da sessão 1 (EXIF 6, tela de cabeça
para baixo). Registros temporários com timeout de 20 min, só métricas.

| Captura | Conteúdo (conferido nas fotos) | Veredito |
| --- | --- | --- |
| #1 | Questão 8 completa (A–E) | nova ✔ |
| #2 | Questão 9 completa | nova ✔ |
| #3 | Questão 9 de novo | repetida ✔ (foto descartada) |
| #4 | Questão 10 completa | nova ✔ |
| #5 | Questão 11 completa | nova ✔ |
| #6 | Questão 11 de novo | repetida ✔ (foto descartada) |
| #7 | Questão 12 completa | nova ✔ |

- **Nenhuma requisição à OpenAI** (`ai_requests` = 1). As 5 questões ficaram `PENDING`, prontas
  para "Analisar".
- **Nenhuma captura incompleta nesta sessão:** a câmera só fotografa com a imagem estável, e nenhuma
  parada ocorreu com a lista cortada. **O caso "incompleta ao vivo" foi validado só pelas fotos da
  sessão 1 e pelos testes.**
- **CPU** 18–61 % (pico na captura com OCR); temperatura de 40,4 a 41,0 °C, carregando (**bateria
  não medida**).
- **Integridade após reiniciar:**
  - 50 questões, 4 sessões, `integrity_check` ok, reconciliação sem remoções, nenhum `sk-`;
  - as 32 questões e fotos originais, a chave e as configurações estão idênticas ao backup de
    2026-10-29;
  - dados novos: só as capturas das duas sessões (13 + 5).
- **Juntando as duas sessões** (versão corrigida aplicada às 13 fotos da 1 e ao vivo na 2):
  - 14 questões completas reconhecidas;
  - 4 incompletas recusadas;
  - 2 repetidas descartadas;
  - **0 falsos positivos, 0 falsos negativos.**

## 3m. Fase 3.5: primeira chamada real com imagem (2026-10-29)

**Autorização:** uma única chamada manual com imagem, executada por toques via ADB no M52, com a
trava do envio automático ligada. A confirmação dos controles de gasto no painel da OpenAI foi do
usuário; o agente não tem acesso ao painel.

**Preparação:**

- Push de `31b4f76` para `questvision/fase-1` (a `main` continua em `2f10e46`).
- Chave só cifrada (`openai_api_key.bin`, 194 bytes).
- Nenhum retry no código (corpo de tamanho fixo, sem seguir redirecionamentos; tempo limite de 15 s
  para conectar e 90 s para ler).
- O recorte das 5 capturas da sessão 2 foi conferido com o mesmo código, em cópias temporárias
  (removidas depois, arquivo por arquivo). A Questão 12 deu um recorte de 1165×737 só com a questão.

**A chamada:**

| Item | Valor |
| --- | --- |
| Questão | AUTO Nº 7, sessão 20261029-025608 (Questão 12, padrão Singleton) |
| Toque em "Analisar" | 03:15:28,98 (um único toque) |
| Início / fim (`ai_requests`) | 03:15:30,99 / 03:15:34,44: **3,45 s** |
| Modelo / nível | `gpt-6-luna` / `default` |
| HTTP / resultado | 200 / `SUCCEEDED` |
| `response_id` | `resp_00bb357e783a4436016ae2e484e65087d2a4cb2a4f66df77b5` |
| Tokens | entrada 1.486 (0 em cache, **1.483 gravados no cache**), saída 56, raciocínio 0 |
| Imagem enviada | recorte da questão, 1165×737 (medido no diagnóstico com o mesmo código e a mesma foto) |
| Resposta | **D** (Singleton), confiança "alta", sem revisão: **correta** |
| Custo estimado antes do envio | ≈ US$ 0,0008, **sem a imagem** (aviso exibido) |
| Custo observado (local) | (3 × 0,10 + 1.483 × 0,125 + 56 × 0,50) / 10⁶ ≈ **US$ 0,00021** (o app mostra US$ 0,0002) |

- **Tokens da imagem:** a chamada de texto de 28/10 teve 461 tokens de entrada (enunciado completo
  em texto). Por diferença, a imagem recortada respondeu por **cerca de 1.100 a 1.200 tokens** de
  entrada. É uma estimativa por subtração, não uma contagem oficial.
- **Cache:** quase toda a entrada foi gravada no cache de prompt (`cache_write_tokens`). Pela
  tabela, a escrita custa 1,25× a entrada, e o app a contabiliza assim. Conferir no painel se a
  cobrança bate.
- **Duplicidade:** uma única linha em `ai_requests` para a questão, `attempts` = 1, uma única linha
  `QuestVisionAi` no Logcat. Nenhum reenvio. O contador de 24 h foi para 2 de 10.
- **Logcat** (16.033 linhas de todo o aparelho durante o teste): nenhuma ocorrência de `sk-`,
  `Bearer`, `Authorization`, trechos do enunciado ou da resposta, `base64` ou `/9j/`.
- **Integridade:** 50 questões, 4 sessões, banco íntegro; todas as outras questões idênticas a antes;
  as 50 fotos e a chave com o mesmo MD5. A questão analisada virou `ANSWERED`, protegida da retenção.
- **Testes reais interrompidos** depois desta chamada.

**A conferir pelo usuário no painel da OpenAI:** o uso de 2026-10-29 por volta de 03:15 (horário de
Brasília): 1 requisição de `gpt-6-luna`, cerca de 1.486 tokens de entrada (1.483 em escrita de cache) e
56 de saída, custo da ordem de US$ 0,0002.

### Fase 3.5: correções técnicas (2026-10-29, sem chamadas reais, não commitadas)

**Críticas:**

1. **Recorte obrigatório em todo envio com imagem.**
   - `QuestionImageEncoder.encode` devolve `ImageEncoding`: `Encoded`, `Unreadable` ou
     `QuestionNotFound`.
   - A opção "foto inteira" (`RegionPolicy.QUESTION_OR_WHOLE`) foi **removida**: sem a questão
     localizada, o "Analisar" manual também é bloqueado (`QUESTION_REGION_NOT_FOUND`, mensagem "A área
     da questão não foi encontrada na foto; nada foi enviado").
2. **Confirmação em cada envio manual.** Depois da primeira chamada (que tem o aviso mais completo),
   todo envio pede `Confirmation.SEND`, com:
   - modelo e modo;
   - aviso de consumo de créditos;
   - estimativa, que no modo imagem avisa que a imagem não entra nela e que só o recorte é enviado.

   Reenvio após resultado desconhecido continua pedindo uma segunda confirmação.
3. **Respostas tardias.** Depois que a resposta chega, gravá-la roda em `NonCancellable`: um
   "Cancelar" nesse instante não perde mais uma resposta possivelmente paga (antes, ela virava
   `CANCELLED`).
   - Tempos limite (15 s para conectar, 90 s para ler, limite total por chamada), cancelamento que
     fecha o socket e uma requisição por vez no app continuam como antes.
4. **Sem retry; resultado desconhecido explícito.** Novo `AiRequestOutcome.UNKNOWN`, usado quando
   nenhuma resposta chegou (rede, tempo esgotado, erro inesperado ao gravar). Antes eram `FAILED`, o
   que sugeria "não cobrado". Nenhum retry automático existe.

**Planejadas:**

5. **Resposta sem foto.**
   - `reconcile()` não apaga mais a linha de uma questão que passou pela IA quando a foto some
     (`ReconcileReport.keptWithoutPhoto`). Pendente nunca enviada sem foto continua sendo removida.
   - Na revisão, uma tela "foto não encontrada" permite abrir a resposta salva pelo botão da IA, sem
     "Descartar".
6. **Aviso de descarte.** `delete()` devolve `DeleteResult`: `DELETED`, `IN_ANALYSIS` (aviso
   "Não descartada: a IA está analisando esta questão…") ou `FAILED` (o arquivo não pôde ser apagado;
   nada removido, aviso).
7. **Limpeza manual de pendentes.** Botão "Limpar pendentes" no histórico:
   - **Prévia:** quantidade, MB e período.
   - **Confirmação:** "Apagar N".
   - **Alcance:** só capturas `PENDING`, nunca enviadas (sem registro em `ai_requests`) e não
     mantidas. Respondidas, em análise, com falha, já enviadas e mantidas nunca entram.
   - **Na confirmação,** cada questão é verificada de novo: uma enviada ou mantida depois da prévia
     fica.
8. **Telemetria do ML Kit:** sem mudança de comportamento; ver "Telemetria observada" (3l). Não há
   opção oficial para desligar. Opções documentadas: aviso ao usuário e tirar os samples do APK.
9. **Tamanho do recorte enviado.** `ai_requests` ganhou `image_width`, `image_height` e
   `image_bytes`. A linha `QuestVisionAi` do Logcat ganhou `image=LxA/NB` e `cacheWrite12h`. Nunca o
   conteúdo.

**Banco versão 3** (migração automática 2→3, só colunas novas e anuláveis em `ai_requests`):

- `cache_write_12h_tokens`, `image_width`, `image_height` e `image_bytes`.
- **Por quê:** o custo correto das escritas de cache de 12 h e a auditoria do tamanho enviado.
- **Testes:** `QuestVisionMigrationTest.version2To3_keepsAllData_andTheRequestLog` (no aparelho,
  pendente); o esquema `3.json` foi exportado.

**Auditoria de custo (10–12):**

- Pela tabela oficial (página de preços e guia de cache de prompt, consultados em 2026-10-29), no
  `gpt-6-luna` Standard:
  - entrada US$ 0,10/M; entrada em cache US$ 0,01/M; saída US$ 0,50/M;
  - escrita de cache de **30 min** US$ 0,125/M (1,25× a entrada); de **12 h** US$ 0,25/M (2,5×).
- Os tokens gravados fazem parte de `input_tokens`; a entrada comum é `input − cached −
  cache_write`. Sem pedir retenção, o padrão no GPT-6 é 30 min (o app não pede 12 h).
- **Os 1.483 tokens gravados** custam 1.483 × 0,125 / 10⁶ = US$ 0,000185. Total da chamada:
  (3 × 0,10 + 1.483 × 0,125 + 56 × 0,50) / 10⁶ = **US$ 0,000213675**, igual ao que o app calculou.
- **Correção:** o app passou a ler `cache_write_tokens_details.ttl_12h_tokens` e a cobrar essa
  parte a 2,5×. Antes, uma escrita de 12 h seria subestimada.
- **A diferença de ~1.025 tokens** entre a chamada de imagem (1.486) e a de texto (461) **não é
  contagem oficial** de tokens da imagem: as duas mensagens diferem também no texto enviado.
- **Conciliação com o painel de uso da OpenAI: pendente de confirmação do usuário.**

**Testes:**

- **JVM: 264/264** ✅ (eram 251). Novos:
  - recorte obrigatório no manual;
  - cancelamento depois da resposta;
  - `UNKNOWN` em perda de conexão;
  - tamanho do recorte registrado;
  - custo da primeira chamada de imagem;
  - escrita de 12 h;
  - leitura do detalhamento por retenção;
  - confirmação em cada envio;
  - resposta mantida sem foto;
  - limpeza com prévia e nova verificação;
  - aviso de descarte recusado;
  - revisão sem foto.
- **Build:** `:app:assembleDebug` ✅, `:questvision-camera:assembleDebugAndroidTest` ✅, `spotlessApply`.
- **Instrumentados:** atualizados (recorte obrigatório com OCR real, tamanho do recorte em SQLite
  real, migração 2→3), **não executados**: o M52 não estava conectado.
- **Dados do M52:** não verificados nesta rodada. A instalação desta versão migra o banco real
  2→3; fazer backup antes.

### Fase 3.5: validação no M52 (2026-10-29)

- **Backup novo, somente leitura:** `~/QuestVision-backups/2026-10-29-antes-da-migracao-v3/`
  (187 MB): banco v2, configurações e 50 fotos com MD5 conferido. Estado de antes: 50 questões, 4
  sessões, 2 registros em `ai_requests`, 50 fotos e a chave.
- **Instrumentados: 35/35** ✅ (sem `RealCapturesOcrTest`). Na primeira execução, 34/35: um teste do
  aparelho ainda esperava `FAILED` em vez de `UNKNOWN` para falha de rede (corrigido; o código estava
  certo). Inclui:
  - **migração 2→3** (`version2To3_keepsAllData_andTheRequestLog`);
  - **recorte obrigatório com OCR real** (foto sem questão ⇒ nada enviado, nem no manual);
  - **tamanho do recorte gravado em SQLite real.**
- **Atualização por cima com migração real 2→3:**
  - `user_version` = 3 e `identity_hash` igual ao de `3.json`; `integrity_check` ok;
  - questões e sessões idênticas byte a byte ao backup;
  - as colunas originais de `ai_requests` idênticas; as novas vazias (`NULL`);
  - 50 fotos e chave com o mesmo MD5; configurações iguais;
  - reconciliação sem remoções (`keptWithoutPhoto=0`); nenhum `sk-` no Logcat.
- **Validação visual** (antes de cada toque, conferidos app em primeiro plano, ausência de chamada e
  de janela flutuante; um primeiro intento foi interrompido sem toque porque havia uma ligação ativa,
  um app bancário em primeiro plano e uma janela flutuante de vídeo):
  1. **Prévia de "Limpar pendentes":** "27 capturas pendentes, nunca enviadas à IA (113,8 MB, de
     26/10/2026 14:37 a 29/10/2026 02:57)…", com "Apagar 27" e "Cancelar". Das 28 `PENDING`, a única
     mantida (`kept/`) ficou de fora. Tocado **Cancelar**: nada apagado.
  2. **Confirmação da Questão 12 em modo imagem:** "Enviar esta questão à OpenAI (gpt-6-luna,
     Imagem)? Consome créditos da sua conta: até cerca de US$ 0,0008 por envio (aproximado, não é um
     teto). Mais a imagem, que não entra na estimativa. Só o recorte da questão é enviado." Tocado
     **Agora não**: nenhuma requisição (nenhuma linha `QuestVisionAi`).
     - **Defeito visual encontrado e corrigido:** faltava o espaço antes de "Mais a imagem" (o Android
       remove o espaço inicial de um recurso de texto; agora ele vai entre aspas). A versão corrigida
       foi instalada e a confirmação conferida de novo.
  3. **Tela de foto ausente:** não exibida, porque exigiria remover ou mover uma foto real. Coberta
     pelo teste JVM `anAnsweredQuestionWithoutItsPhoto_stillOpens`.
  4. **Integridade final** (depois de duas instalações por cima):
     - banco v3, `integrity_check` ok;
     - 50 questões, 4 sessões e 2 registros em `ai_requests` idênticos byte a byte ao backup;
     - 50 fotos e a chave com o mesmo MD5; configurações iguais; nenhum `sk-` no Logcat.

## 4. Funcionalidades implementadas

- Identidade QuestVision AI (nome do app, `applicationId` `com.questvision.ai`, título na tela).
- Pedido de permissão de câmera com justificativa; estado "negado permanentemente" com atalho para as
  Configurações; nova checagem ao voltar das Configurações.
- Câmera traseira por padrão; `UnsupportedView` se o aparelho não tiver câmera traseira.
- Preview em tempo real (`CameraXViewfinder` via `CameraXPreview`).
- Indicador de estado: iniciando / pronta / capturando / pausada / em uso por outro app / desativada /
  indisponível (a partir do `CameraState` do CameraX).
- Foco por toque com retícula, quando suportado.
- Zoom por slider e atalhos (0.5×/1×/2×/5× dentro do alcance do aparelho); oculto em lentes sem zoom;
  o zoom é reaplicado após pausar/retomar o app.
- Captura manual (obturador desabilitado enquanto a câmera não está pronta ou já capturando).
- Armazenamento privado com retenção (seção 3). A captura **manual** abre a prévia automaticamente,
  com **Manter**, **Descartar** ou **Voltar**. A automática não abre nada (seção 3e).
- **Histórico** de todas as capturas (seção 3e), também após reiniciar o app.
- Mensagem de erro de captura (some em 3 s) e tela de erro com "Tentar novamente" se a câmera não vincular.
- Câmera desvinculada em `ON_PAUSE` e liberada ao sair da tela.
- Strings em resources, com tradução **pt-BR** (`values-pt-rBR`) e inglês como padrão.

### Alterações em código compartilhado

- `:core-ui` `CameraSampleScaffold`: `api` agora aceita `null` (oculta o selo "CameraX"). Samples
  existentes não mudam.
- `:core-ui`: textos fixos em inglês do scaffold, `ErrorView` e `CapturedImagePreview`/
  `CapturedVideoPreview` movidos para `strings.xml` (+ pt-BR), cumprindo a regra de "sem strings
  soltas" do `AGENTS.md`.
- `:core-ui` `CapturedImagePreview`: parâmetros opcionais `onBack` (padrão `onDone`) e `doneLabel`
  (padrão "Done"); os samples não mudam.
- `:app` manifesto: `RECORD_AUDIO` e `WRITE_EXTERNAL_STORAGE` saíram de `src/main` para
  `src/debug/AndroidManifest.xml` (só os samples de vídeo, acessíveis apenas em debug, os usam).
- `:app` `CatalogApp`: destino inicial QuestVision; o pedido de permissão do catálogo passou para
  dentro do destino do catálogo, para não concorrer com o pedido da tela QuestVision (o Android
  descarta um de dois pedidos simultâneos).

## 5. Ambiente de build (Linux)

Ambiente instalado em 2026-10-20 nesta máquina (Debian 13 x86_64, 8 núcleos, 7,4 GiB RAM), tudo no
diretório do usuário, sem sudo:

| Componente | Local | Observação |
| --- | --- | --- |
| JDK 17.0.20.1 (Eclipse Temurin) | `~/.local/opt/jdk-17.0.20.1+1` | SHA-256 conferido com a API da Adoptium |
| Android cmdline-tools 13114758 | `~/Android/Sdk/cmdline-tools/latest` | SHA-256 `7ec96528…` |
| `platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools` 37.0.1 | `~/Android/Sdk` | licença do SDK aceita pelo usuário |
| Gradle 9.6.0 + dependências | `~/.gradle` | baixados pelo wrapper |
| `JAVA_HOME`, `ANDROID_HOME`, `PATH` | bloco marcado `QuestVision Android env` no `~/.bashrc` | abrir um novo terminal |
| `local.properties` (`sdk.dir`) | raiz do repositório | ignorado pelo Git |

A máquina tinha só ~1,2 GiB de RAM livre e ~6 GB de disco. Para não estourar memória, o Gradle foi
executado com limites passados pela linha de comando (o `gradle.properties` não foi alterado):

```bash
./gradlew --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process <tarefas>
```

### Comandos

```bash
./gradlew spotlessApply                          # formata (ktlint + cabeçalhos)
./gradlew spotlessCheck                          # verificação de formato (igual ao CI)
./gradlew :app:assembleDebug                     # APK debug
./gradlew :questvision-camera:testDebugUnitTest  # testes unitários do módulo
```

### Instalar no celular

1. No celular: **Configurações → Sobre o telefone →** tocar 7× em "Número da versão"; depois
   **Opções do desenvolvedor → Depuração USB**.
2. Conectar por USB (modo "Transferência de arquivos" ajuda alguns aparelhos) e aceitar a chave RSA.
3. `adb devices -l` deve listar o aparelho como `device`. Se aparecer `no permissions`, o Debian
   precisa de regras udev (pacote `android-sdk-platform-tools-common` e usuário no grupo `plugdev`),
   o que exige sudo.
4. Instalar e abrir:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.questvision.ai/com.android.camera.catalog.MainActivity
adb logcat --pid=$(adb shell pidof com.questvision.ai) | grep -E "QuestVision|CameraX|AndroidRuntime"
adb shell run-as com.questvision.ai ls -l no_backup/captures no_backup/captures/kept
```

## 6. Resultado da compilação (2026-10-20)

| Comando | Resultado |
| --- | --- |
| `spotlessApply` | ✅ BUILD SUCCESSFUL. Só reformatou dois blocos `when` em `QuestVisionCameraScreen.kt` |
| `spotlessCheck` | ✅ BUILD SUCCESSFUL |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL (primeira execução: 8m56s, 773 tarefas) |
| `:questvision-camera:testDebugUnitTest` | ✅ 5/5 testes (`CalculateInSampleSizeTest` 3, `PruneOldestTest` 2) |
| `:app:processReleaseMainManifest` | ✅ (usado para inspecionar as permissões de release) |

Nenhum erro de compilação nos arquivos novos. O único aviso do Kotlin no build (`@OptIn` sem efeito em
`core-ui/.../VideoPlayer.kt`) já existia no upstream.

**APK:** `app/build/outputs/apk/debug/app-debug.apk` (≈ 236 MB em debug, sem minificação; a maior
parte vem das dependências dos samples, como ML Kit e Media3).

### Permissões no APK

| Permissão | debug | release | Origem |
| --- | --- | --- | --- |
| `CAMERA` | ✅ | ✅ | QuestVision (`app/src/main`) |
| `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE` (≤ 28) | ✅ | — | samples (`app/src/debug`) |
| `INTERNET` | ✅ | ✅ | ML Kit `vision-internal-vkp` / `datatransport` (samples de ML) |
| `ACCESS_NETWORK_STATE`, `WAKE_LOCK` | ✅ | ✅ | Media3 ExoPlayer (`:core-ui`, prévia de vídeo dos samples) |

O código do QuestVision não usa rede e não envia imagens. As permissões de bibliotecas só somem do
release quando os samples (e o ExoPlayer do `:core-ui`) deixarem de ser empacotados no release. Ver
seção 8.

### Validação

| Item | Situação |
| --- | --- |
| Compilação, formatação, testes unitários | ✅ Executados (acima) |
| Instalação no aparelho | ✅ Feita pelo usuário em um Samsung Galaxy M52 5G via ADB (2026-10-20) |
| Inicialização, preview, foco, zoom, captura, revisão, retenção | ✅ "Tudo funcionou", segundo o usuário (2026-10-20) |
| Permissões (conceder / negar / negar permanentemente) | ✅ Roteiro abaixo executado pelo usuário (2026-10-20) |

O teste no aparelho foi executado e relatado pelo usuário, sem acompanhamento via ADB nesta sessão.
Por isso não há logs registrados aqui.

### Roteiro de teste no aparelho (requer interação humana)

1. Primeira abertura → diálogo de permissão. Negar → cartão com "Conceder permissão". Negar de novo
   ("não perguntar") → "Abrir Configurações"; conceder lá e voltar → preview aparece.
2. Status passa de "Iniciando câmera" para "Câmera pronta".
3. Tocar no texto do monitor → retícula e foco; verificar nitidez.
4. Zoom pelo slider e atalhos; pausar/retomar o app e verificar que o zoom permanece.
5. Capturar → status "Capturando" → prévia. Conferir a legibilidade do texto.
6. **Manter** → `kept/`; **Descartar** → arquivo apagado; **Voltar** → fica em `captures/`.
7. Fazer mais de 20 capturas sem manter → só as 20 mais recentes permanecem; as de `kept/` intactas.
8. Fechar e reabrir o app → o Histórico continua mostrando todas as capturas.
9. Abrir outro app de câmera em paralelo → status "Câmera em uso por outro app".
10. Girar o aparelho, bloquear a tela e voltar: o preview deve retornar sem travar.

## 7. Revisão técnica (2026-10-20)

- **Câmera/ciclo de vida:** vinculação em `ON_RESUME`, desvinculação em `ON_PAUSE`, `release()` ao sair
  da composição, observadores de `CameraState`/`ZoomState` removidos antes de revincular, escopo de
  corrotinas cancelado. Captura com câmera fechada é reportada como erro pelo CameraX (sem crash).
- **Foco (bug corrigido):** `CameraXPreview` registra o toque com `pointerInput(surfaceRequest)`, então
  guarda os callbacks da primeira composição. A versão anterior passava `null` até o suporte a foco ser
  detectado, e o foco por toque podia nunca funcionar. Agora os callbacks são fixos e leem o estado
  atual (`rememberUpdatedState`) no momento do toque.
- **Zoom:** limitado ao alcance reportado pelo `ZoomState`; reaplicado após cada revinculação.
- **Armazenamento:** JPEG gravado pelo CameraX fora da main thread; decodificação reduzida em IO;
  arquivo parcial apagado em falha; retenção implementada.
- **Falhas:** sem câmera traseira → `Unsupported`; falha de inicialização/vinculação → `Error` com
  "Tentar novamente"; erros do `CameraState` → indicador de status; falha de captura → aviso por 3 s;
  arquivo de revisão ausente/ilegível → volta para a captura anterior.

## 8. Limitações conhecidas

### Fase 2 (detecção)

- **Não validada em condições reais.** O primeiro teste no M52 revelou a falha de estabilização
  (seção 3d). A correção foi validada só com cenas renderizadas, que aproximam, mas não reproduzem,
  a câmera real (moiré do monitor, rolling shutter, AF real). Os limiares precisam ser conferidos
  com os logs (`floor`, `thr`, `fine` × `motion`) no aparelho.
- **Autofoco caçando** de forma contínua impede a estabilidade de propósito: a foto sairia borrada.
  Se isso ocorrer muito, travar o foco por toque antes de monitorar ajuda.
- **Questões muito parecidas:** a assinatura de 128×96 enxerga a disposição de linhas e palavras,
  não os caracteres. Duas questões com o mesmo layout e texto de comprimento quase igual podem
  passar por "duplicadas" (sem captura), e uma alteração pequena, como trocar só uma alternativa,
  pode ficar abaixo do limiar de mudança (0,05). A distinção semântica fica para a fase de OCR, que confirmará ou
  descartará candidatos.
- **Conteúdo em movimento contínuo** (vídeo, animação, relógio grande, rolagem) impede a
  estabilização e, portanto, a captura. Cursor piscando e relógios pequenos ficam abaixo dos limiares.
- **Câmera solta na mão:** deslocamentos maiores que ~2 células (≈ 10 px do quadro de análise)
  contam como conteúdo novo se a câmera não voltar à mesma posição. O uso pretendido é com o celular apoiado ou em suporte.
- **Moiré e cintilação** do monitor são atenuados pelo passa-alta e pelo mapa de estrutura, mas
  podem aumentar o `motion` em alguns monitores. O limiar adaptativo absorve até 0,08.
- A captura manual não alimenta o histórico de duplicidade: a mesma tela pode ser capturada de novo
  automaticamente.
- Uma captura automática ao iniciar a sessão é intencional (seção 3b, item 7).
- Pausa automática ao sair do app: a sessão não retoma sozinha.

### Gerais

- O release ainda empacota os samples, e com eles `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` e
  o `MlKitInitProvider`, que inicializa no startup. A solução definitiva é tornar os samples
  `debugImplementation` e mover o catálogo para `src/debug`, ou removê-los do app.
- APK debug grande (≈ 236 MB) pelo mesmo motivo.
- A prévia usa `ContentScale.Fit`, sem pinça para ampliar.
- O pacote/namespace do `:app` continua `com.android.camera.catalog` (só o `applicationId` mudou).
- O cabeçalho de licença exigido pelo Spotless atribui também os arquivos novos ao "The Android Open
  Source Project"; para mudar, edite `spotless/copyright.kt`.

## 9. Próximas etapas

1. ~~Teste da Fase 1 no aparelho~~: concluído pelo usuário em 2026-10-20 (Galaxy M52 5G).
2. ~~Rodar `RealCapturesOcrTest` sobre as 12 fotos de campo~~ (feito: mantém 1, 7, 8 e 11).
   **Repetir o teste ao vivo das 3 questões** com `adb logcat -v time QuestVisionDetect:I
   QuestVisionOcr:I AndroidRuntime:E '*:S'`.
3. Opcional: tirar os samples do build de release (seção 8).
4. **Recognition/Question**: OCR on-device para detectar e deduplicar questões antes de qualquer envio.
5. **AI/Telegram/Settings**: integração com modelo multimodal e Telegram Bot API, com tokens fora do
   código-fonte (configuração do usuário em armazenamento criptografado).

## 10. Fase 2: resultados e como testar a detecção

### Resultados

**2026-10-21 (correção da estabilização):** `spotlessApply` / `spotlessCheck` ✅,
`:app:assembleDebug` ✅ e `:questvision-camera:testDebugUnitTest` ✅ **37/37**:

| Suíte | Testes | O que cobre |
| --- | --- | --- |
| `RenderedStabilityTest` (novo) | 9 | cenas 640×480 renderizadas com subpixel, foco, exposição e ruído: questão parada estabiliza e é capturada **uma vez** em 20 amostras; a métrica antiga reproduz a falha; rolagem contínua de 1/2/4/25 px por amostra **nunca** captura; rolar e parar → uma captura 1 s depois; troca de questão; iluminação; piscada de AF tolerada; questão repetida = duplicada; esbarrão |
| `StructureMetricSeparationTest` (novo) | 1 | tabela antes × depois e margens dos limiares |
| `ScreenChangeDetectorTest` | 9 | cenários da Fase 2 com telas sintéticas |
| `MonitoringSessionTest` | 9 | estados, pausa durante captura, callbacks de sessão encerrada |
| `FrameReductionTest` | 4 | redução do plano Y, normalização, sampler |
| anteriores | 5 | `CalculateInSampleSizeTest`, `PruneOldestTest` |

Instalação no Galaxy M52: **não feita**, nenhum aparelho conectado ao ADB.

**2026-10-20 (Fase 2 inicial):** 27/27 testes. O teste no aparelho mostrou a falha de estabilização
corrigida acima.

**Desempenho:** ainda **não medido no aparelho**. Estimativa: ~2 reduções de 640×480 por segundo
(≈ 250 mil leituras de byte cada); por amostra, 2 comparações de estrutura 128×96 sem deslocamento,
1 com ±2 células (25 deslocamentos) e a métrica fina de diagnóstico (9 deslocamentos), cada uma com
≈ 12 mil células (≈ 110 mil operações
cada, 2 por amostra), ou seja, poucos milissegundos por segundo. O stream de análise de 640×480
convive com o preview e a captura. Os números reais saem das linhas `reduced in` e `detector step`
do log.

### Como testar a detecção com questões no monitor

1. Celular **apoiado ou em suporte**, apontado para o monitor, com o enquadramento cobrindo a área
   da questão. Ajuste o zoom e toque para focar no texto.
2. Em outro terminal: `adb logcat -s QuestVisionDetect:D`.
3. Abra a primeira questão e toque **Iniciar monitoramento**. Após ~1–1,5 s parado: contorno branco,
   "Captura automática salva (1)" e `Auto: 1`.
4. **Sem mexer em nada** por 30 s: nenhuma captura nova; `motion` e `change` devem ficar baixos.
5. **Próxima questão:** contorno âmbar (alteração detectada / estabilizando) e, ~1 s depois de a
   página parar, nova captura (`Auto: 2`).
6. **Mudanças rápidas:** avance várias questões em menos de 1 s cada. Deve haver só uma captura,
   da última.
7. **Volte para uma questão já capturada:** não deve capturar (`outcome=DUPLICATE` no log).
8. **Iluminação:** acenda ou apague uma luz do ambiente, ou mude o brilho do monitor levemente. Não
   deve capturar.
9. **Movimento breve:** esbarre de leve no celular ou passe a mão na frente. Deve aparecer o âmbar e
   depois `outcome=REVERTED`, sem captura.
10. **Pausar** durante "Captura automática": a foto é salva, o contador sobe e a sessão continua
    pausada. **Retomar** e **Encerrar** funcionam. Mande o app para segundo plano: a sessão fica
    pausada ao voltar.
11. **Captura manual** durante o monitoramento continua funcionando (abre a prévia).
12. **Histórico durante o monitoramento:** abra o Histórico com a sessão ativa e troque a questão
    no monitor. Uma nova miniatura **Auto nº n** deve aparecer na lista sem fechar o histórico.
    Volte à câmera: a sessão continua.
13. **Conferência:** com N questões diferentes exibidas, o Histórico deve ter N fotos **Auto** da
    sessão, numeradas 1..N (lacunas = falhas). `Auto: n`, "salvas" no cabeçalho e N devem bater.
    Abra cada foto para conferir que são as questões certas.
14. **Persistência:** feche o app (remova dos recentes) e reabra. O Histórico mostra as mesmas fotos.
15. Confira os arquivos: `adb shell run-as com.questvision.ai ls -l no_backup/captures`.
16. Anote `motion`/`change` dos casos que falharem: são os dados para ajustar `DetectionConfig`.
