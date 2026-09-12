# Zentra Clip

**Zentra Clip** é um app Android de *clipping instantâneo* (estilo Medal), otimizado para celulares: ele grava a tela do **aplicativo que você escolher** em um **buffer circular** na RAM e, com um toque no **botão flutuante discreto**, salva automaticamente os **últimos 30 s ou 60 s** como MP4.

[![Build Zentra Clip APK](https://github.com/neresbielxx2-ai/Zentra-XR-CLIPS/actions/workflows/build.yml/badge.svg)](https://github.com/neresbielxx2-ai/Zentra-XR-CLIPS/actions/workflows/build.yml)

## Como funciona

1. Abra o Zentra Clip e escolha o aplicativo alvo (na primeira abertura, se existir um app **ZENTRA XR**, ele já vem selecionado automaticamente).
2. Toque em **INICIAR GRAVAÇÃO**:
   - o Android pede a permissão de captura de tela (MediaProjection);
   - o Zentra Clip inicia a gravação em buffer circular (H.264 por hardware + AAC);
   - o aplicativo escolhido é aberto automaticamente;
   - um **botão flutuante minúsculo** aparece sobre o app (arraste para qualquer canto — a posição é lembrada).
3. Quando algo épico acontece, **toque no botão flutuante**: o clipe dos últimos instantes é salvo na hora, com o **som do clip** configurado.
4. **Segure o botão flutuante** para encerrar a gravação (ou use a notificação).

## Recursos

| Área | Detalhes |
|---|---|
| Seleção de app | Lista todos os apps instalados, com pesquisa; auto-seleção do **ZENTRA XR** na primeira abertura |
| Botão flutuante | Minúsculo (46 dp), translúcido, arrastável para qualquer canto, posição lembrada, fica sobre o app gravado; toque = salvar clipe, segurar = parar |
| Som do clip | Som padrão do Zentra Clip, desativado, **importar MP3** ou **extrair áudio de um vídeo** (decodificação para WAV), com prévia e confirmação |
| Configurações | Duração **30 s / 60 s** • Resolução **720p / 1080p** • FPS **30 / 60** • Áudio **interno / microfone / interno+microfone / sem áudio** |
| Desempenho | Buffer circular codificado em RAM (só os últimos segundos), codificação por **hardware** (MediaCodec H.264/AAC), captura por **MediaProjection**, sem escrita em disco durante a gravação |
| Meus Clips | Assistir (player), **renomear**, **excluir**, **compartilhar** — salvos em **Movies/Zentra Clip** (MP4, via MediaStore) |
| Interface | Tema Zentra: escuro, tecnológico, minimalista, botões grandes, responsivo |

## Requisitos

- **Android 10 (API 29) ou superior** (captura de áudio interno exige API 29+).
- Permissões concedidas em fluxo: captura de tela, notificações (Android 13+) e “Sobrepor outras janelas” (para o botão flutuante).

## Compilar (GitHub Actions)

O workflow **`.github/workflows/build.yml`** compila automaticamente a cada push:

- **`assembleRelease`** → APK assinado (keystore gerado na 1ª build e depois versionado em `app/zentra-release.keystore`);
- **`assembleDebug`** → APK de debug.

Os APKs ficam nos **Artifacts** da execução (`Zentra-Clip-APKs`). Em tags `v*` (ex.: `v1.0.0`) os APKs são anexados a uma **GitHub Release**.

Compilar localmente (com Android SDK 34 + JDK 17):

```bash
./gradlew :app:assembleRelease
```

## Notas técnicas / limitações

- **Áudio interno** usa `AudioPlaybackCapture` (Android 10+). Apps que bloqueiam captura (DRM/Netflix, chamadas) saem em silêncio — o vídeo continua normal.
- Em Android 14+ a gravação segue o fluxo oficial de consentimento por sessão (`MediaProjection` + serviço em primeiro plano do tipo `mediaProjection`).
- O botão flutuante requer a permissão de overlay; em MIUI/algumas skins pode ser preciso liberar também “Exibir janelas em segundo plano”.
- O clipe começa sempre em um keyframe (intervalo de 1 s), então o corte tem precisão de ~1 segundo.
- Limite de segurança do buffer: ~90 MB de vídeo em RAM; se a conexão de bitrate exceder, o clipe fica um pouco mais curto.

## Estrutura

```
app/src/main/java/com/zentra/clip/
├── MainActivity.kt          # tela inicial + fluxo de início da gravação
├── AppPickerActivity.kt     # lista/pesquisa de apps instalados
├── SettingsActivity.kt      # duração, resolução, FPS, áudio
├── SoundSettingsActivity.kt # som do clip (padrão/MP3/vídeo/prévia)
├── ClipsActivity.kt         # Meus Clips (assistir/renomear/excluir/compartilhar)
├── RecordingService.kt      # MediaProjection + MediaCodec + buffer circular + FAB
├── ClipsStore.kt            # MediaStore: salvar/listar/renomear/excluir
├── SoundManager.kt          # sons + extração de áudio de vídeo (WAV)
└── Prefs.kt                 # configurações persistidas
```
