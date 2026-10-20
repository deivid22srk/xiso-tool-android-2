# XISO Tool

[![Build APK](https://github.com/deivid22srk/xiso-tool-android/actions/workflows/build.yml/badge.svg)](https://github.com/deivid22srk/xiso-tool-android/actions/workflows/build.yml)

Aplicativo Android nativo para **extrair** e **criar** imagens XISO (XDVDFS) do **Xbox clássico (original)**:

- **Extrair ISO → pasta**: converte uma imagem XISO em uma pasta com os arquivos do jogo, preservando a estrutura de diretórios e o `default.xbe`.
- **Criar ISO ← pasta**: gera uma imagem XISO válida a partir da pasta do jogo — compatível com **xemu** e com o **Xbox modificado** (HD interno).

A interface é 100% em **português (Brasil)** e segue o **Material 3 Expressive** (Material You Expressive), com cores dinâmicas do papel de parede, indicadores de progresso ondulados e animações de mola.

---

## Recursos

| Área | Detalhes |
|------|----------|
| Núcleo | Rust (`cdylib`) usando a crate [`xdvdfs`](https://github.com/antangelo/xdvdfs) (v0.8.3) via JNI |
| Arquitetura Android | Kotlin + Jetpack Compose, ViewModel + StateFlow, Foreground Service |
| Progresso | Barra determinada na tela (circular ondulada + linear ondulada) e na notificação; arquivo atual, velocidade e tempo restante estimado |
| Cancelamento | Cooperativo, verificado a cada chunk de 1 MiB (tela + botão na notificação) |
| Segundo plano | `FOREGROUND_SERVICE_DATA_SYNC` + `PARTIAL_WAKE_LOCK`; continua com a tela bloqueada |
| Compatibilidade de leitura | Offsets RAW (0), XGD1, XGD2 e XGD3 detectados automaticamente (`OffsetWrapper` do xdvdfs) |
| Arquivos grandes | Leitura/escrita em streaming (buffer de 1 MiB) — jogos de 4 a 8 GiB sem carregar na memória |
| Segurança | Validação de volume XDVDFS antes de extrair; `default.xbe` obrigatório antes de criar; bloqueio de caminhos inválidos (`..`) na extração |
| ABIs | `arm64-v8a`, `armeabi-v7a` e `x86_64` |

---

## Arquitetura

```
┌─────────────────────────┐        ┌──────────────────────────────┐
│  UI (Compose, M3 Expr.) │◄──────►│  XisoTaskBus (StateFlow)     │
│  Home/Extrair/Criar/... │        └──────────────▲───────────────┘
└───────────┬─────────────┘                       │
            │ startForegroundService              │
┌───────────▼─────────────┐        ┌──────────────┴───────────────┐
│ XisoForegroundService   │        │  Notificação de progresso    │
│ (dataSync + WakeLock)   │        │  (determinada + Cancelar)    │
└───────────┬─────────────┘        └──────────────────────────────┘
            │ JNI (extractIso / createIso / cancel / checkXiso)
┌───────────▼─────────────────────────────────────────────────────┐
│ Núcleo Rust (libxiso.so) — crate xdvdfs 0.8.3 (features sync)   │
│ • thread de trabalho + thread observadora (callbacks throttled) │
│ • extração em streaming • criação com trait Filesystem própria  │
└─────────────────────────────────────────────────────────────────┘
```

Pontos importantes do núcleo Rust (`rust/`):

- **`extractIso(isoPath, outDir, callback)`** — duas passadas: a primeira soma os bytes para progresso determinado; a segunda copia em chunks de 1 MiB usando `BlockDeviceRead` sobre `OffsetWrapper` (que testa os offsets RAW/XGD1/XGD2/XGD3).
- **`createIso(srcDir, outIso, callback)`** — valida `default.xbe` na raiz, usa `xdvdfs::write::img::create_xdvdfs_image` com uma implementação própria da trait `Filesystem` que publica progresso byte-a-byte e verifica cancelamento dentro de `copy_file_in`/`read_dir`.
- **Cancelamento** — `AtomicBool` por operação; `cancel(opId)` sinaliza a operação ativa.
- **Progresso via JNI sem quebrar thread-safety** — o estado (`SharedProgress`, atomics + `Mutex<String>`) é compartilhado com uma *thread observadora* que chama `onProgress` na JVM (attach_current_thread) no máximo ~6×/s; a UI e o serviço fazem o throttle final da notificação (500 ms).
- **Sem pânico através da fronteira JNI** — `catch_unwind` converte falhas inesperadas em `onFinished(ERR_UNKNOWN, ...)`.

### Acesso a arquivos (Opção A — justificativa)

O app usa **`MANAGE_EXTERNAL_STORAGE`** (Android 11+) com tela explicativa e botão que leva às configurações; no Android 8–10 usa `READ/WRITE_EXTERNAL_STORAGE` em runtime. Essa opção foi escolhida em vez do SAF porque o núcleo Rust trabalha com **caminhos diretos e árvores de diretórios inteiras** (ex.: `file_tree` da imagem, `create_xdvdfs_image` lendo a pasta recursivamente). Passar file descriptors (`detachFd()`) por operação exigiria manter um FD aberto por arquivo da árvore e remapear tudo para um VFS virtual — complexidade desnecessária para um app sideload, sem ganho de privacidade real (nenhum dado sai do aparelho). O app ainda oferece um **seletor de arquivos/pastas integrado** (Material 3) para escolher origem e destino.

---

## Interface (Material 3 Expressive)

- `MaterialExpressiveTheme` + `MotionScheme.expressive()` (molas expressivas padrão do tema);
- **Cores dinâmicas** (Material You) no Android 12+ com fallback de esquema claro/escuro próprio;
- Indicadores **`CircularWavyProgressIndicator`** e **`LinearWavyProgressIndicator`** na tela de progresso;
- **`LoadingIndicator`/`ContainedLoadingIndicator`** expressivos;
- `LargeTopAppBar` expansível com scroll behavior, cartões hero com **`MaterialShapes`** (`Cookie9Sided`, `Burst`, `Pill`, `Circle`) via `toShape()`;
- Transições entre telas com `AnimatedContent` + molas; cartões com feedback de escala ao pressionar;
- Edge-to-edge; conteúdo da notificação em pt-BR; acessibilidade com `contentDescription` e alvos de toque ≥ 48 dp.

Telas: **Início** (2 cartões grandes) → **Extrair** (origem/destino + validação da ISO via `checkXiso`) → **Criar** (pasta do jogo com checagem de `default.xbe`, destino e nome da ISO) → **Progresso** (ondulado, %, arquivo, velocidade, ETA, cancelar) → **Resultado** (sucesso/erro + compartilhar/abrir pasta/copiar caminho).

---

## Build

> **Importante:** nenhuma compilação é feita localmente neste fluxo — o APK é construído pelo **GitHub Actions** (`.github/workflows/build.yml`), que:
> 1. instala JDK 21 (Temurin) e o Rust com os targets Android;
> 2. instala o NDK 27 e o `cargo-ndk`;
> 3. compila `libxiso.so` para as 3 ABIs direto em `app/src/main/jniLibs`;
> 4. roda `./gradlew assembleRelease` com uma keystore gerada no próprio runner (APK instalável);
> 5. publica o APK como **artefato** (e em **Release** quando há tag `v*`).

### Build manual (opcional, se você tiver o ambiente)

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=<caminho-do-ndk>

cd rust
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..

./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Requisitos: JDK 17+, Android SDK 36, NDK 27, Rust stable 1.86+ (MSRV do cargo-ndk 4.x).

### Versões principais

| Componente | Versão |
|------------|--------|
| AGP | 8.13.2 |
| Kotlin | 2.2.21 |
| Gradle | 8.14.3 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |
| Compose (ui/foundation/runtime) | 1.11.0 |
| material3 | 1.5.0-alpha16 (APIs Expressive) |
| xdvdfs (Rust) | 0.8.3 |
| NDK (CI) | 27.2.12479018 |

> Nota: as APIs Expressive (ondulados, LoadingIndicator, MaterialShapes etc.) exigem `material3` da linha **1.5.0-alpha** — as versões estáveis 1.4.x ainda não as incluem. A 1.5.0-alpha16 é a mais recente que aceita `compileSdk 36` + AGP 8.x.

---

## Formato XDVDFS (resumo)

- Setores de **2048 bytes**; volume descriptor no **setor 32** com a mágica `MICROSOFT*XBOX*MEDIA`;
- Árvore de diretórios binária (AVL na criação) com nomes codificados em Windows-1252;
- Detecção automática de deslocamento de imagem: RAW (0), XGD1, XGD2, XGD3;
- A criação escreve o volume descriptor no setor 32 e alinha o final da imagem em 32 setores — o resultado abre no xemu, no extract-xiso e no Xbox.

Créditos: [antangelo/xdvdfs](https://github.com/antangelo/xdvdfs) (biblioteca e CLI), e referências de comportamento: extract-xiso, Qwix e Xbox Image Browser.

---

## Instalação

1. Baixe o APK da última execução verde do Actions (artefato `XISO-Tool-APK`) ou da **Release**;
2. Instale permitindo "apps de fontes desconhecidas" (o APK é assinado com keystore gerada no CI);
3. Conceda o **acesso a todos os arquivos** na primeira execução;
4. (Opcional) permita notificações para acompanhar o progresso com a tela bloqueada.

## Limitações conhecidas

- Nomes de arquivo do XDVDFS são limitados a 42 bytes por entrada e ao charset Windows-1252 (imposto pelo formato, não pelo app);
- Arquivos individuais maiores que 4 GiB não são suportados pelo formato;
- Em Android 15+, serviços `dataSync` têm limite de ~6 h contínuas — o app trata o `onTimeout` e informa o usuário.
