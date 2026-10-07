# bTV Android

Application Android TV / Fire TV développée en Kotlin et Jetpack Compose.
Le projet porte sur Android une application IPTV auparavant disponible sur
Tizen.

## Fonctionnalités

- Connexion à un serveur compatible Xtream Codes.
- Parcours des chaînes en direct, films et séries.
- Guide des programmes et contenus disponibles en rediffusion, selon le serveur.
- Favoris, historique de lecture et reprise de lecture.
- Lecteur vidéo basé sur AndroidX Media3.
- Interface adaptée à la navigation avec une télécommande.
- Données de compte et données de lecture enregistrées localement.

L’application ne fournit ni chaînes, ni fichiers vidéo, ni identifiants de
service. L’accès aux contenus dépend du serveur et des droits de l’utilisateur.

## Compatibilité

- Android 8.0 (API 26) ou supérieur.
- Cible principale : Android TV et Amazon Fire TV.
- Vega OS n’est pas pris en charge par cette version Android.

## Technologies

- Kotlin 1.9.24 et Jetpack Compose.
- Android Gradle Plugin 8.7.2.
- AndroidX TV, Navigation Compose et Material 3.
- Media3 / ExoPlayer pour la lecture.
- Retrofit et OkHttp pour les communications réseau.
- Room et DataStore pour le stockage local.
- Hilt pour l’injection de dépendances.

## Préparer l’environnement

Installer Android Studio avec le SDK Android 35 et un JDK 17. Dans Android
Studio, ouvrir le dossier du dépôt, puis laisser Gradle synchroniser le projet.

Si nécessaire, configurer le chemin local du SDK Android dans
`local.properties`. Ce fichier est propre à chaque machine et n’est pas suivi
par Git.

## Compiler et tester

Depuis PowerShell, à la racine du dépôt :

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

L’APK debug est généré ici :

```text
app\build\outputs\apk\debug\app-debug.apk
```

Les tests instrumentés se trouvent dans `app/src/androidTest` et nécessitent un
appareil ou un émulateur Android connecté. Ils peuvent être lancés depuis
Android Studio.

## Déployer sur une Fire TV

Le script `deploy-firetv.ps1` compile l’application et l’installe sur la Fire TV
via ADB. L’adresse par défaut est `192.168.1.69`; elle peut être changée :

```powershell
.\deploy-firetv.ps1 -DeviceIp 192.168.1.50
```

Options disponibles :

```powershell
.\deploy-firetv.ps1 -DeviceIp 192.168.1.50 -OpenApp
.\deploy-firetv.ps1 -SkipBuild -ShowLogs
```

ADB doit être disponible et le débogage ADB activé sur l’appareil.

## Envoyer ses changements sur GitHub

Le script `push.ps1` ajoute les changements, demande un titre de commit, crée le
commit et le pousse sur la branche courante :

```powershell
.\push.ps1
```

## Structure

```text
app/       Application Android, ressources et tests
docs/      Notes de conception, état du projet et comptes rendus
gradle/    Configuration du wrapper Gradle
```

Les fichiers locaux, clés de signature, sorties de build et captures dans
`screenshots/` sont exclus du dépôt par `.gitignore`.
