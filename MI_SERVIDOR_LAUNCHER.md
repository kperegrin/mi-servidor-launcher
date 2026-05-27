# BarrilMC Launcher

Launcher personalizado basado en HMCL para `BarrilMC Server`.

Valores temporales usados:

- Launcher: `BarrilMC Launcher`
- Servidor: `BarrilMC Server`
- IP: `play.BarrilMC Server.com`
- Minecraft: `1.21.1`
- Loader: `Fabric`
- Fabric loader: `0.18.4`
- Carpeta de instancia: `.BarrilMC Server`
- Manifest: `https://kperegrin.github.io/mi-servidor-launcher/launcher/manifest.json`
- Logo: `HMCL/src/main/resources/assets/branding/logo.png`
- Icono: `HMCL/src/main/resources/assets/branding/icon.ico`

## Legal y auth

Este fork mantiene el flujo oficial Microsoft de HMCL. No falsifica cuentas premium, no rompe OAuth, no roba tokens y no convierte cuentas offline en premium.

Para Microsoft premium necesitas un Client ID OAuth propio. HMCL lo lee desde:

```powershell
$env:MICROSOFT_AUTH_ID="TU_CLIENT_ID"
```

O en runtime:

```powershell
java -Dhmcl.microsoft.auth.id=TU_CLIENT_ID -jar .\HMCL\build\libs\BarrilMC-Launcher-1.0.0.jar
```

Redirect URIs que HMCL usa: `http://localhost:29111/auth-response` hasta `http://localhost:29115/auth-response`.

El modo offline/no premium se deja activado. El servidor debe usar `online-mode=false` y proteger identidades con AuthMe + FastLogin.

HMCL es GPL. Si distribuyes el launcher, conserva licencia y ofrece el codigo fuente de tu fork.

## Analisis HMCL

Modulos principales:

- `HMCL`: interfaz JavaFX, recursos, branding, paginas, empaquetado y entrada visual.
- `HMCLCore`: autenticacion, modelos de juego, repositorios, descargas, Fabric/Forge/Quilt, lanzamiento.
- `HMCLBoot`: bootstrap del launcher.

Puntos tocados o relevantes:

- Nombre de app: `HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java`
- Iconos de ventana: `HMCL/src/main/java/org/jackhuang/hmcl/ui/FXUtils.java`
- Icono taskbar: `HMCL/src/main/java/org/jackhuang/hmcl/EntryPoint.java`
- Pantalla principal: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/MainPage.java`
- Root/sidebar de HMCL: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/RootPage.java`
- Login: `HMCL/src/main/java/org/jackhuang/hmcl/ui/account/*`
- Microsoft auth: `HMCLCore/src/main/java/org/jackhuang/hmcl/auth/microsoft/*`
- Offline auth: `HMCLCore/src/main/java/org/jackhuang/hmcl/auth/offline/*`
- Cuentas/config: `HMCL/src/main/java/org/jackhuang/hmcl/setting/Accounts.java`, `GlobalConfig.java`
- Instancias/perfiles: `HMCL/src/main/java/org/jackhuang/hmcl/setting/Profile.java`, `Profiles.java`
- Repositorio de juego: `HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameRepository.java`
- Fabric install: `HMCL/src/main/java/org/jackhuang/hmcl/download/DefaultDependencyManager.java`, `HMCLCore/src/main/java/org/jackhuang/hmcl/download/fabric/*`
- Lanzamiento y quick play: `HMCL/src/main/java/org/jackhuang/hmcl/ui/versions/Versions.java`, `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java`
- Sistema de descarga existente: `HMCLCore/src/main/java/org/jackhuang/hmcl/download/*`

## Archivos modificados

- `config/project.properties`: version base `1.0.0`.
- `HMCL/build.gradle.kts`: nombre de artefacto `BarrilMC-Launcher`, version configurable `BarrilMC Server_LAUNCHER_VERSION`, icono deb temporal.
- `HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java`: nombre, URLs y carpetas de datos.
- `HMCL/src/main/java/org/jackhuang/hmcl/theme/ThemeColor.java`: morado por defecto.
- `HMCL/src/main/java/org/jackhuang/hmcl/setting/Config.java`: tema oscuro por defecto.
- `HMCL/src/main/java/org/jackhuang/hmcl/setting/GlobalConfig.java`: offline/no premium activado.
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/FXUtils.java`: icono de ventana.
- `HMCL/src/main/java/org/jackhuang/hmcl/EntryPoint.java`: icono de taskbar.
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/AboutPage.java`: icono branding.
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/FeedbackPage.java`: icono branding.
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/MainPage.java`: home del servidor integrada y boton launcher antiguo oculto.
- `HMCL/src/main/resources/assets/css/root.css`: estilos negro/morado/blanco.

## Archivos nuevos

- `HMCL/src/main/java/org/jackhuang/hmcl/server/ServerLauncherConfig.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/ServerManifest.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/ServerFileEntry.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/LauncherUpdater.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/LauncherVersionInfo.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/LauncherSelfUpdateService.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/HashUtils.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/ServerStatusService.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/server/ServerInstanceManager.java`
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/server/ServerHomeController.java`
- `HMCL/src/main/resources/assets/branding/logo.png`
- `HMCL/src/main/resources/assets/branding/icon.png`
- `HMCL/src/main/resources/assets/branding/icon.ico`
- `HMCL/image/BarrilMC Server.png`
- `launcher/manifest.json`
- `launcher/news.json`
- `launcher/version.json`

## Fragmentos clave

Branding central:

```java
public static final String NAME = "BarrilMC Launcher";
public static final String FULL_NAME = "BarrilMC Launcher";
```

Manifest remoto:

```java
public static final String MANIFEST_URL = System.getProperty(
        "BarrilMC Server.manifest.url",
        System.getenv().getOrDefault(
                "BarrilMC Server_MANIFEST_URL",
                "https://kperegrin.github.io/mi-servidor-launcher/launcher/manifest.json"));
```

Fabric automatico:

```java
profile.getDependency()
        .gameBuilder()
        .name(ServerLauncherConfig.INSTANCE_NAME)
        .gameVersion(manifest.getMinecraftVersion())
        .version(ServerLauncherConfig.LOADER, manifest.getLoaderVersion())
        .buildAsync();
```

Conexion directa:

```java
setting.setServerIp(manifest.getServer().getAddress());
profile.setSelectedVersion(ServerLauncherConfig.INSTANCE_NAME);
```

## FASE 1: preparar entorno y compilar HMCL limpio

Instalar Git y JDK en Windows:

```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
```

Clonar:

```powershell
cd G:\launcher
git clone https://github.com/HMCL-dev/HMCL.git
cd G:\launcher\HMCL
```

Compilar limpio:

```powershell
$env:GRADLE_USER_HOME="G:\launcher\HMCL\.gradle-user-home"
.\gradlew.bat -g .gradle-user-home :HMCL:shadowJar --no-daemon --stacktrace
```

En este workspace la compilacion limpia ya paso.

## FASE 2: branding

Cambiar logo:

1. Sustituye `HMCL/src/main/resources/assets/branding/logo.png`.
2. Sustituye `HMCL/src/main/resources/assets/branding/icon.png`.
3. Sustituye `HMCL/src/main/resources/assets/branding/icon.ico`.
4. Sustituye `HMCL/image/BarrilMC Server.png` si quieres icono en paquetes Linux/deb.

Colores principales en `root.css`:

- Fondo negro: `#08080c`
- Morado: `#8b2dff`
- Texto: `white`

## FASE 3: manifest JSON y descargas

Base en `launcher/manifest.json`:

```json
{
  "launcherVersion": "1.0.0",
  "minecraftVersion": "1.21.1",
  "loader": "fabric",
  "loaderVersion": "0.18.4",
  "server": {
    "name": "BarrilMC Server",
    "ip": "play.BarrilMC Server.com",
    "port": 17818
  },
  "files": [
    {
      "path": "mods/example.jar",
      "url": "https://github.com/kperegrin/mi-servidor-launcher/releases/download/v1.0/example.jar",
      "sha256": "HASH_AQUI",
      "size": 123456,
      "required": true
    }
  ],
  "delete": [
    "mods/mod-viejo.jar"
  ],
  "news": [
    {
      "title": "Bienvenido",
      "body": "Nueva version del servidor disponible.",
      "date": "2026-05-27"
    }
  ]
}
```

El updater:

- Lee `manifest.json` por HTTPS.
- Intenta leer `news.json` al lado del manifest.
- Intenta leer `version.json` al abrir la home para detectar un launcher mas nuevo.
- Valida rutas relativas seguras.
- Compara `size` y SHA-256.
- Descarga a `*.download`.
- Mueve atomico al destino final.
- Borra rutas listadas en `delete`.
- Instala o actualiza Fabric.

## FASE 4: instancia Fabric preconfigurada

Al abrir la home se crea perfil `BarrilMC Server` con carpeta `.BarrilMC Server`.

Al actualizar/jugar crea:

- `.BarrilMC Server/mods`
- `.BarrilMC Server/resourcepacks`
- `.BarrilMC Server/shaderpacks`
- `.BarrilMC Server/config`
- `.BarrilMC Server/options.txt`
- `.BarrilMC Server/servers.dat`

La instancia se llama `BarrilMC Server`, usa Minecraft `1.21.1` y Fabric `0.18.4`.

## FASE 5: pantalla principal personalizada

Clase: `ServerHomeController`.

Incluye:

- Logo grande.
- Estado online/offline.
- Jugadores conectados.
- Version detectada/recomendada.
- Cuenta seleccionada.
- Boton `Jugar servidor`.
- Boton `Actualizar archivos`.
- Boton `Configuracion`.
- Botones `Microsoft` y `Offline`.
- Barra de progreso.
- Noticias.

## FASE 6: boton Jugar servidor

Flujo:

1. Obtiene o crea el perfil `BarrilMC Server`.
2. Descarga manifest remoto.
3. Carga `news.json` si existe.
4. Verifica y sincroniza archivos.
5. Borra obsoletos.
6. Instala Minecraft + Fabric si falta.
7. Aplica `serverIp` para quick play.
8. Lanza `BarrilMC Server`.

En paralelo, la home comprueba `version.json`. Si `latest` es mayor que `Metadata.VERSION`, muestra `Actualizar launcher`; al pulsarlo descarga el asset de GitHub Releases, valida SHA-256 y lo deja junto al launcher actual.

Para cambiar el manifest sin recompilar:

```powershell
$env:BarrilMC Server_MANIFEST_URL="https://kperegrin.github.io/mi-servidor-launcher/launcher/manifest.json"
.\HMCL\build\libs\BarrilMC-Launcher-1.0.0.exe
```

## FASE 7: login premium/offline

Premium:

- Boton `Microsoft`.
- Usa `MicrosoftAccountLoginPane`.
- OAuth oficial Microsoft.
- Requiere `MICROSOFT_AUTH_ID` real.

Offline/no premium:

- Boton `Offline`.
- Usa `CreateAccountPane(Accounts.FACTORY_OFFLINE)`.
- No finge premium.

## FASE 8: empaquetar Windows

Compilar EXE:

```powershell
cd G:\launcher\HMCL
$env:GRADLE_USER_HOME="G:\launcher\HMCL\.gradle-user-home"
$env:MICROSOFT_AUTH_ID="TU_CLIENT_ID"
$env:BarrilMC Server_LAUNCHER_VERSION="1.0.0"
.\gradlew.bat -g .gradle-user-home :HMCL:clean :HMCL:makeExecutables --no-daemon --stacktrace
```

Artefactos:

- `HMCL/build/libs/BarrilMC-Launcher-1.0.0.exe`
- `HMCL/build/libs/BarrilMC-Launcher-1.0.0.jar`
- `HMCL/build/libs/BarrilMC-Launcher-1.0.0.exe.sha256`

Hash actual de esta build:

```text
b79ea02c3f6bde02cf78556e986b734f5978a1e0332792c5f00aff4f08fc3bd1
```

Probar JAR:

```powershell
java -jar .\HMCL\build\libs\BarrilMC-Launcher-1.0.0.jar
```

Probar EXE:

```powershell
.\HMCL\build\libs\BarrilMC-Launcher-1.0.0.exe
```

## FASE 9: pruebas

Checklist:

- Abre ventana con titulo `BarrilMC Launcher`.
- Home muestra logo, estado, botones y noticias.
- `Offline` crea cuenta offline.
- `Microsoft` abre OAuth oficial si hay Client ID valido.
- `Actualizar archivos` falla claramente si el manifest tiene `HASH_AQUI`.
- Con hashes reales, descarga mods/configs/shaders.
- Se crea `.BarrilMC Server/servers.dat`.
- Se instala Fabric si no existe.
- `Jugar servidor` lanza Minecraft y pasa quick play al servidor.

## GitHub Pages gratis

Crear repo:

```powershell
cd G:\launcher\HMCL
git remote add origin https://github.com/kperegrin/mi-servidor-launcher.git
git add launcher
git commit -m "Add launcher hosting files"
git push -u origin main
```

Activar Pages:

1. En GitHub, abre el repo.
2. Settings -> Pages.
3. Source: `Deploy from a branch`.
4. Branch: `main`.
5. Folder: `/root`.
6. Guarda.

URLs:

- `https://kperegrin.github.io/mi-servidor-launcher/launcher/manifest.json`
- `https://kperegrin.github.io/mi-servidor-launcher/launcher/news.json`
- `https://kperegrin.github.io/mi-servidor-launcher/launcher/version.json`

## GitHub Releases gratis

Subir archivos grandes:

1. GitHub -> repo -> Releases.
2. Draft a new release.
3. Tag: `v1.0`.
4. Upload assets: mods `.jar`, resource packs `.zip`, shaders `.zip`, configs `.zip`, launcher `.exe`.
5. Publish release.

URL directa:

```text
https://github.com/kperegrin/mi-servidor-launcher/releases/download/v1.0/example.jar
```

## Hashes y sizes

SHA-256:

```powershell
Get-FileHash -Algorithm SHA256 .\mods\example.jar
```

Size:

```powershell
(Get-Item .\mods\example.jar).Length
```

Actualizar entrada del manifest:

```json
{
  "path": "mods/example.jar",
  "url": "https://github.com/kperegrin/mi-servidor-launcher/releases/download/v1.0/example.jar",
  "sha256": "PEGA_EL_HASH_SHA256",
  "size": 123456,
  "required": true
}
```

## Actualizar mods sin recompilar

1. Sube el nuevo `.jar` a Releases.
2. Calcula SHA-256 y size.
3. Edita `launcher/manifest.json`.
4. Si quieres borrar un mod viejo, agregalo a `delete`.
5. Haz commit y push.
6. Los usuarios pulsan `Actualizar archivos` o `Jugar servidor`.

Ejemplo:

```json
"delete": [
  "mods/mod-viejo.jar",
  "config/mod-viejo.toml"
]
```

## Cambiar noticias

Edita `launcher/news.json`:

```json
[
  {
    "title": "Nueva temporada",
    "body": "Ya esta activo el nuevo spawn.",
    "date": "2026-05-27"
  }
]
```

Commit y push. El launcher lo lee en la proxima actualizacion.

## Subir nueva version del launcher

1. Cambia version:

```powershell
$env:BarrilMC Server_LAUNCHER_VERSION="1.0.1"
```

2. Compila:

```powershell
.\gradlew.bat -g .gradle-user-home :HMCL:clean :HMCL:makeExecutables --no-daemon --stacktrace
```

3. Sube `BarrilMC-Launcher-1.0.1.exe` a GitHub Releases.
4. Edita `launcher/version.json`:

```json
{
  "latest": "1.0.1",
  "downloadUrl": "https://github.com/kperegrin/mi-servidor-launcher/releases/download/v1.0.1/BarrilMC-Launcher-1.0.1.exe",
  "sha256": "HASH_DEL_EXE",
  "notes": "Actualizacion del launcher."
}
```

El launcher detecta ese `version.json`, muestra un aviso en la home y descarga la nueva version verificada por SHA-256. En Windows, cierra el launcher viejo y ejecuta el archivo descargado.

## Errores comunes

- `HASH_AQUI`: cambia el hash por uno SHA-256 real de 64 caracteres hex.
- `HTTP 404`: la URL de Release no existe o el asset tiene otro nombre.
- `Only HTTPS URLs are allowed`: usa `https://`, no `http://`.
- `SHA-256 mismatch`: el archivo remoto no coincide con el manifest; recalcula hash y size.
- Microsoft no abre login: falta `MICROSOFT_AUTH_ID` o redirect URI no registrada.
- Offline no entra al servidor: revisa `online-mode=false`, AuthMe y FastLogin.
- SmartScreen Windows: normal en EXE no firmado; firma con certificado Authenticode si vas a distribuir masivamente.
- Fabric no instala: revisa conexion y version `0.18.4`.
- JavaFX faltante: ejecuta con JDK/JRE moderno o deja que HMCL descargue dependencias JavaFX.
- Rutas raras en manifest: no uses rutas absolutas ni `..`.
