# QuestVision AI — Status do projeto

_Última atualização: 2026-10-20 · Fase 1 (fundação do app Android) — build validado; teste no aparelho pendente_

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
| `QuestVisionCameraUiState` | `Initial` · `Previewing` (status da câmera, zoom, captura pendente, última captura, revisão) · `Unsupported` (sem câmera traseira) · `Error` (falha ao vincular a câmera) |
| `QuestVisionCameraViewModel` | Estado único em `StateFlow`; regras de captura (bloqueio de duplo toque), revisão, descarte. Sem `Context`. Implementa `CameraSessionListener`. |
| `QuestVisionCameraController` | Ciclo de vida CameraX: `Preview` + `ImageCapture` na câmera traseira, observação de `CameraState`/`ZoomState`, foco por toque, zoom, gravação do JPEG. Único lugar com `Context`. |
| `QuestVisionCameraScreen` | Compose: permissão, preview, status, zoom, obturador, acesso à última captura, revisão. |
| `CaptureRepository` | Arquivos em `noBackupFilesDir/captures` (privado, fora do backup em nuvem); retenção (abaixo); decodificação reduzida (≤ 2048 px) e orientada por EXIF, em `Dispatchers.IO`. |

### Retenção de capturas

| Pasta | Conteúdo | Política |
| --- | --- | --- |
| `no_backup/captures/` | capturas recentes (não decididas) | só as **20** mais recentes (`MAX_RECENT_CAPTURES`); as mais antigas são apagadas ao abrir a tela e após cada captura |
| `no_backup/captures/kept/` | capturas que o usuário escolheu **Manter** | **nunca** apagadas automaticamente |

Na prévia: **Manter** move o arquivo para `kept/`; **Descartar** apaga; **Voltar** (seta ou botão
voltar do sistema) apenas fecha, deixando a captura no conjunto recente sujeito à retenção. A captura
recém-feita é sempre a mais nova, portanto a retenção nunca a remove.

### Captura otimizada para texto em monitor

- `CAPTURE_MODE_MAXIMIZE_QUALITY`, `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY`, JPEG qualidade 95.
- Flash sempre desligado (reflexo na tela).
- Preview e captura em 4:3, e o viewfinder não é recortado: o enquadrado é exatamente o capturado.
- Foco por toque com `FLAG_AF | FLAG_AE` (mede exposição na região tocada, evitando estourar o brilho
  do monitor); só habilitado quando `isFocusMeteringSupported`.
- O JPEG é gravado direto em arquivo pelo CameraX (fora da main thread) em resolução total; apenas a
  prévia é reduzida em memória.

### Preparação para as próximas fases (ainda não criado de propósito)

Separação prevista: **Camera** (este módulo) · **Detection** (mudança de cena / nova questão via
`ImageAnalysis`) · **Recognition** (OCR) · **Question** (modelo enunciado + alternativas) ·
**Processing** (orquestração e deduplicação) · **AI** · **Telegram** · **Settings**.

Máquina de estados futura:
`IDLE → MONITORING → QUESTION_DETECTED → CAPTURING → PROCESSING → SENT → MONITORING`, com pausa manual
e tratamento de falhas. Pontos de extensão já existentes:

- `QuestVisionCameraController` é onde entra um caso de uso `ImageAnalysis` para a detecção.
- `CaptureRepository` já entrega arquivos JPEG de resolução total para OCR/IA.
- `CameraStatus` (estado real da câmera) alimentará o estado de monitoramento.

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
- Armazenamento privado com retenção (seção 3); prévia automática após capturar; botão para reabrir a
  última captura (também após reiniciar o app). Na prévia: **Manter**, **Descartar** ou **Voltar**.
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
| Instalação no aparelho | **Pendente**: nenhum aparelho conectado ao ADB |
| Inicialização, preview, foco, zoom, captura, revisão, retenção | **Pendente** (aparelho físico) |
| Permissões (conceder / negar / negar permanentemente) | **Pendente** (aparelho, interação humana) |

### Roteiro de teste no aparelho (requer interação humana)

1. Primeira abertura → diálogo de permissão. Negar → cartão com "Conceder permissão". Negar de novo
   ("não perguntar") → "Abrir Configurações"; conceder lá e voltar → preview aparece.
2. Status passa de "Iniciando câmera" para "Câmera pronta".
3. Tocar no texto do monitor → retícula e foco; verificar nitidez.
4. Zoom pelo slider e atalhos; pausar/retomar o app e verificar que o zoom permanece.
5. Capturar → status "Capturando" → prévia. Conferir a legibilidade do texto.
6. **Manter** → `kept/`; **Descartar** → arquivo apagado; **Voltar** → fica em `captures/`.
7. Fazer mais de 20 capturas sem manter → só as 20 mais recentes permanecem; as de `kept/` intactas.
8. Fechar e reabrir o app → o botão de última captura continua disponível.
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

- Preview, foco, zoom, captura e retenção **ainda não foram testados em aparelho**.
- O release ainda empacota os samples, e com eles `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` e
  o `MlKitInitProvider`, que inicializa no startup. A solução definitiva é tornar os samples
  `debugImplementation` e mover o catálogo para `src/debug`, ou removê-los do app.
- APK debug grande (≈ 236 MB) pelo mesmo motivo.
- A prévia usa `ContentScale.Fit`, sem pinça para ampliar.
- O pacote/namespace do `:app` continua `com.android.camera.catalog` (só o `applicationId` mudou).
- O cabeçalho de licença exigido pelo Spotless atribui também os arquivos novos ao "The Android Open
  Source Project"; para mudar, edite `spotless/copyright.kt`.

## 9. Próximas etapas

1. **Teste no aparelho** (seção 6): conectar o celular com depuração USB, instalar o APK, executar o
   roteiro e coletar `logcat`.
2. Opcional antes da Fase 2: tirar os samples do build de release (seção 8).
3. Fase 2 — **Detection**: `ImageAnalysis` no controller com um detector barato de mudança de cena
   (diferença de luminância/hash perceptual em baixa resolução) + estabilização (cena parada por N
   quadros) antes de disparar a captura automática; introduzir a máquina de estados
   `IDLE/MONITORING/...` e a pausa manual. As capturas automáticas caem na retenção de 20 recentes.
4. **Recognition/Question**: OCR on-device para detectar e deduplicar questões antes de qualquer envio.
5. **AI/Telegram/Settings**: integração com modelo multimodal e Telegram Bot API, com tokens fora do
   código-fonte (configuração do usuário em armazenamento criptografado).
