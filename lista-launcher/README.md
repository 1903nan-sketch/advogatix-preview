# Lista — launcher minimalista para Android

Tela inicial em forma de lista, sem ícones: só o relógio, a data e os apps que você escolher.

**APK pronto:** [`apk/Lista-Launcher-1.0.apk`](apk/Lista-Launcher-1.0.apk) (~60 KB, Android 8.0 ou mais novo)

## Como instalar

1. Baixe o APK no celular e abra o arquivo.
2. Se pedir, permita "instalar apps desconhecidos" para o navegador ou gerenciador de arquivos.
3. Abra o **Lista** e toque no aviso "Toque para usar o Lista como tela inicial"
   (ou vá em Configurações → Apps → Apps padrão → App de tela inicial).

## Como usar

| Onde | Gesto | O que faz |
|---|---|---|
| Tela inicial | deslizar para cima (ou tocar na setinha) | abre a lista de todos os apps |
| Tela inicial | deslizar para baixo | abre as notificações |
| Tela inicial | tocar e segurar um espaço vazio | opções (apps ocultos, teclado, papel de parede) |
| Tela inicial | tocar no relógio / na data | abre alarmes / agenda |
| Tela inicial | tocar e segurar um app fixado | mover, renomear, remover, atalhos do app |
| Lista | digitar na busca | filtra na hora (ignora acentos, aceita iniciais: "gm" → Google Maps) |
| Lista | Enter no teclado | abre o primeiro resultado (ou busca na web se nada for encontrado) |
| Lista | arrastar o dedo na barra de letras à direita | pula direto para a letra |
| Lista | deslizar para baixo no topo / voltar | volta para a tela inicial |
| Lista | tocar e segurar um app | fixar na tela inicial, renomear, ocultar, informações, desinstalar |

Na primeira vez, Telefone, WhatsApp, Mensagens, Câmera e Navegador já aparecem fixados (se estiverem
instalados). A tela inicial comporta até 8 apps.

## Compilar

Requer JDK 17+ e o Android SDK (platform 36).

```bash
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew testReleaseUnitTest assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

O app é Kotlin puro, sem bibliotecas externas.

`app/debug.keystore` é uma chave de **depuração** (senha `android`) versionada de propósito, para que
qualquer build tenha a mesma assinatura e atualize o app já instalado. Não serve para publicar na
Play Store: para isso, crie uma chave própria e mantenha-a fora do repositório.
