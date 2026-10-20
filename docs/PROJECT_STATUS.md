# QuestVision AI — Status do projeto

_Última atualização: 2026-10-20 · Fase 1 (fundação do app Android)_

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
| `CaptureRepository` | Arquivos em `noBackupFilesDir/captures` (privado, fora do backup em nuvem); decodificação reduzida (≤ 2048 px) e orientada por EXIF, em `Dispatchers.IO`. |

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
- Armazenamento privado; prévia automática após capturar; botão para reabrir a última captura (também
  após reiniciar o app). Na prévia: **Concluir** mantém o arquivo, **Descartar** o apaga.
- Mensagem de erro de captura (some em 3 s) e tela de erro com "Tentar novamente" se a câmera não vincular.
- Câmera desvinculada em `ON_PAUSE` e liberada ao sair da tela.
- Strings em resources, com tradução **pt-BR** (`values-pt-rBR`) e inglês como padrão.

### Alterações em código compartilhado

- `:core-ui` `CameraSampleScaffold`: `api` agora aceita `null` (oculta o selo "CameraX"). Samples
  existentes não mudam.
- `:core-ui`: textos fixos em inglês do scaffold, `ErrorView` e `CapturedImagePreview`/
  `CapturedVideoPreview` movidos para `strings.xml` (+ pt-BR), cumprindo a regra de "sem strings
  soltas" do `AGENTS.md`.
- `:app` `CatalogApp`: destino inicial QuestVision; o pedido de permissão do catálogo passou para
  dentro do destino do catálogo, para não concorrer com o pedido da tela QuestVision (o Android
  descarta um de dois pedidos simultâneos).

## 5. Como executar (Linux)

### Pré-requisitos (não presentes nesta máquina em 2026-10-20 — ver seção 6)

1. **JDK 17** (o build usa `jvmToolchain(17)`). O Android Studio já inclui um JBR utilizável.
2. **Android SDK** com a plataforma **API 37** (`platforms;android-37.0`) e build-tools/platform-tools.
3. O wrapper baixa o **Gradle 9.6.0** automaticamente na primeira execução (precisa de internet).

### Android Studio (recomendado)

1. Instale o Android Studio (versão compatível com AGP 9.2.x) e, no SDK Manager, a plataforma
   Android 17 / API 37.
2. **File → Open** → pasta do repositório. O Studio cria `local.properties` com `sdk.dir` (arquivo
   ignorado pelo Git).
3. No celular: **Configurações → Sobre o telefone →** toque 7× em "Número da versão"; depois
   **Opções do desenvolvedor → Depuração USB** (ou Depuração por Wi-Fi).
4. Conecte via USB, aceite a chave RSA no celular, selecione o aparelho e rode a configuração `app`.

### Linha de comando

```bash
export JAVA_HOME=/caminho/para/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk          # ou crie local.properties com sdk.dir=...
./gradlew spotlessApply                          # formata (ktlint + cabeçalhos)
./gradlew assembleDebug spotlessCheck            # build + verificação de formato (igual ao CI)
./gradlew :questvision-camera:testDebugUnitTest  # testes unitários do módulo
adb devices                                      # o celular deve aparecer como "device"
./gradlew :app:installDebug
adb shell am start -n com.questvision.ai/com.android.camera.catalog.MainActivity
```

Para inspecionar capturas num build debug:
`adb shell run-as com.questvision.ai ls no_backup/captures`.

## 6. Resultado da compilação

**Não executado.** O ambiente em que a Fase 1 foi implementada **não possui**:

- `java`/JDK (nenhum em `PATH`, `JAVA_HOME` vazio, `/usr/lib/jvm` inexistente);
- Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` vazios, `~/Android/Sdk` inexistente, sem `adb`);
- Android Studio;
- cache do Gradle (`~/.gradle` inexistente).

Nada foi instalado, por exigir autorização. Consequentemente **não foram executados**: `assembleDebug`,
`spotlessCheck`/`spotlessApply`, testes unitários e instalação no celular. O código foi escrito contra
as APIs efetivamente presentes no fork (CameraX 1.6.1, componentes de `:core-*` lidos no código) e
revisado manualmente, mas **pode haver erros de compilação ou de formatação ktlint** a corrigir no
primeiro build. O workflow `.github/workflows` do repositório (JDK 17 + SDK 37 + `spotlessCheck` +
`assembleDebug`) também valida o projeto se o branch for enviado ao GitHub.

### Validação

| Item | Situação |
| --- | --- |
| Compilação | **Pendente** (sem JDK/SDK) |
| Formatação Spotless | **Pendente** |
| Teste unitário `CalculateInSampleSizeTest` | Escrito, **não executado** |
| Preview, foco, zoom, captura, revisão no celular | **Pendente** (depende do aparelho físico) |
| Permissões (conceder / negar / negar permanentemente) | **Pendente** (aparelho) |

### Roteiro de teste no aparelho

1. Primeira abertura → diálogo de permissão. Negar → cartão com "Conceder permissão". Negar de novo
   ("não perguntar") → "Abrir Configurações"; conceder lá e voltar → preview aparece.
2. Status passa de "Iniciando câmera" para "Câmera pronta".
3. Tocar no texto do monitor → retícula e foco; verificar nitidez.
4. Zoom pelo slider e atalhos; pausar/retomar o app e verificar que o zoom permanece.
5. Capturar → status "Capturando" → prévia. Ampliar visualmente e conferir a legibilidade do texto.
6. "Concluir" → volta à câmera; botão de galeria reabre a captura. "Descartar" apaga.
7. Fechar e reabrir o app → botão de última captura continua disponível.
8. Abrir outro app de câmera em paralelo → status "Câmera em uso por outro app".
9. Girar o aparelho, bloquear a tela e voltar: o preview deve retornar sem travar.

## 7. Limitações conhecidas

- Build e testes não executados (seção 6).
- O manifesto ainda declara `RECORD_AUDIO` e `WRITE_EXTERNAL_STORAGE` (≤ API 28) porque os samples de
  vídeo mantidos no app precisam delas. O QuestVision só solicita `CAMERA`. Remover quando os samples
  saírem do app.
- O catálogo de samples continua empacotado no APK (só acessível em debug), aumentando o tamanho e as
  dependências (ML Kit, Media3...).
- Não há limite de retenção para as capturas em `no_backup/captures`.
- A prévia é exibida em `ContentScale.Fit`, sem pinça para ampliar.
- O pacote/namespace do `:app` continua `com.android.camera.catalog` (só o `applicationId` mudou).
- O cabeçalho de licença exigido pelo Spotless atribui também os arquivos novos ao "The Android Open
  Source Project"; ajustar `spotless/copyright.kt` se o projeto quiser um cabeçalho próprio.

## 8. Próximas etapas

1. **Configurar o ambiente** (JDK 17 + Android SDK API 37 ou Android Studio), rodar
   `./gradlew spotlessApply assembleDebug spotlessCheck :questvision-camera:testDebugUnitTest`,
   corrigir o que surgir e executar o roteiro da seção 6 no aparelho.
2. Fase 2 — **Detection**: `ImageAnalysis` no controller com um detector barato de mudança de cena
   (diferença de luminância/hash perceptual em baixa resolução) + estabilização (cena parada por N
   quadros) antes de disparar a captura automática; introduzir a máquina de estados
   `IDLE/MONITORING/...` e a pausa manual.
3. **Recognition/Question**: OCR on-device para detectar e deduplicar questões antes de qualquer envio.
4. **AI/Telegram/Settings**: integração com modelo multimodal e Telegram Bot API, com tokens fora do
   código-fonte (configuração do usuário em armazenamento criptografado).
