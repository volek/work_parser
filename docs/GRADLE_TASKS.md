# Gradle цели проекта

Актуальный список целей получен из `./gradlew tasks --all` для текущего проекта.

## Application tasks

- `run` - запускает приложение как JVM-процесс.

## Build tasks

- `assemble` - собирает артефакты проекта.
- `build` - полная сборка и тесты.
- `buildDependents` - сборка/тесты проекта и зависящих от него проектов.
- `buildKotlinToolingMetadata` - сборка metadata для Kotlin tooling.
- `buildNeeded` - сборка/тесты проекта и его зависимостей.
- `classes` - сборка main-классов.
- `clean` - очистка каталога `build`.
- `jar` - сборка JAR c main-классами.
- `kotlinSourcesJar` - сборка JAR с исходниками Kotlin.
- `testClasses` - сборка test-классов.

## Build Setup tasks

- `init` - инициализация нового Gradle-проекта.
- `updateDaemonJvm` - обновление критериев JVM для Gradle Daemon.
- `wrapper` - генерация/обновление Gradle wrapper.

## Distribution tasks

- `assembleDist` - сборка distribution-артефактов.
- `distTar` - упаковка distribution в tar.
- `distZip` - упаковка distribution в zip.
- `installDist` - установка distribution в локальный каталог.
- `linuxHostBundle` - ZIP для Linux-host (fat JAR + scripts + query + messages + config + markdown).

## Documentation tasks

- `javadoc` - генерация Javadoc для main-кода.

## Help tasks

- `buildEnvironment` - зависимости buildscript.
- `dependencies` - зависимости проекта.
- `dependencyInsight` - подробности по конкретной зависимости.
- `help` - общая справка.
- `javaToolchains` - найденные Java toolchains.
- `kotlinDslAccessorsReport` - отчёт по Kotlin DSL accessors.
- `outgoingVariants` - исходящие variants проекта.
- `projects` - список подпроектов.
- `properties` - свойства проекта.
- `resolvableConfigurations` - resolvable-конфигурации.
- `tasks` - список доступных Gradle-задач.

## Query tasks (custom)

- `generateQueries` - генерация SQL для всех стратегий по `scripts/query-manifest.json`.
- `generateCompcomQueries` - генерация SQL только для `compcom`.

## Verification tasks

- `check` - агрегирующая проверка проекта.
- `checkKotlinGradlePluginConfigurationErrors` - проверка ошибок конфигурации Kotlin Gradle Plugin.
- `test` - запуск тестов.
- `verifyLinuxHostBundleScriptModes` - проверка, что `.sh` в `linuxHostBundle` имеют права `0755`.
- `verifyQueryManifest` - проверка консистентности `query/*` относительно manifest (`--check`, без записи файлов).

## Other tasks

- `compileJava` - компиляция main Java.
- `compileKotlin` - компиляция main Kotlin.
- `compileTestJava` - компиляция test Java.
- `compileTestKotlin` - компиляция test Kotlin.
- `components` - компоненты проекта (deprecated).
- `dependentComponents` - зависимые компоненты (deprecated).
- `mainClasses` - сборка main-классов.
- `model` - модель проекта (deprecated).
- `prepareKotlinBuildScriptModel` - подготовка модели для Kotlin build script.
- `processResources` - обработка main-ресурсов.
- `processTestResources` - обработка test-ресурсов.
- `startScripts` - генерация платформенных стартовых скриптов.

## Rules

- `clean<TaskName>` - очистка выходных файлов конкретной задачи.
- `build<ConfigurationName>` - сборка артефактов указанной configuration.

## Примеры (чаще всего используемые)

### Windows (PowerShell/cmd)

```powershell
.\gradlew.bat clean build
.\gradlew.bat generateQueries
.\gradlew.bat verifyQueryManifest
.\gradlew.bat linuxHostBundle verifyLinuxHostBundleScriptModes
```

### Linux/macOS

```bash
./gradlew clean build
./gradlew generateQueries
./gradlew verifyQueryManifest
./gradlew linuxHostBundle verifyLinuxHostBundleScriptModes
```

## Выбор Python для query-целей

Для `generateQueries`, `generateCompcomQueries`, `verifyQueryManifest` можно задать бинарник через `PYTHON_BIN`.

- По умолчанию: Windows -> `python`, Linux/macOS -> `python3`.
- Пример:

```bash
PYTHON_BIN=python ./gradlew verifyQueryManifest
```

