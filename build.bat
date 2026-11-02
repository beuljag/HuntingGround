@echo off
rem Compiles against the server's libraries (..\..\1.20.1). Output: build\HuntingGround-Plugin.jar
setlocal
cd /d "%~dp0"

set "JDK=%JAVA_HOME%"
if not exist "%JDK%\bin\javac.exe" set "JDK=%ProgramFiles%\Java\jdk-25.0.4.1"
if not exist "%JDK%\bin\javac.exe" (
    echo javac.exe not found. Set JAVA_HOME to a JDK 21+ folder.
    exit /b 1
)

set "S=..\..\..\..\..\1.20.1"
set "L=%S%\libraries"
set "K=%L%\net\kyori"
set "CP=%L%\com\destroystokyo\paper\paper-mojangapi\1.21.1-R0.1-SNAPSHOT\paper-mojangapi-1.21.1-R0.1-SNAPSHOT.jar"
set "CP=%CP%;%S%\plugins\MythicMobsPremium-5.13.0.jar"
set "CP=%CP%;%K%\adventure-api\4.17.0\adventure-api-4.17.0.jar"
set "CP=%CP%;%K%\adventure-key\4.17.0\adventure-key-4.17.0.jar"
set "CP=%CP%;%K%\adventure-text-serializer-legacy\4.17.0\adventure-text-serializer-legacy-4.17.0.jar"
set "CP=%CP%;%K%\examination-api\1.3.0\examination-api-1.3.0.jar"
set "CP=%CP%;%L%\org\joml\joml\1.10.5\joml-1.10.5.jar"
set "CP=%CP%;%L%\com\google\guava\guava\32.1.2-jre\guava-32.1.2-jre.jar"
set "CP=%CP%;%L%\net\md-5\bungeecord-chat\1.20-R0.2-deprecated+build.18\bungeecord-chat-1.20-R0.2-deprecated+build.18.jar"
set "CP=%CP%;%L%\org\jetbrains\annotations\24.1.0\annotations-24.1.0.jar"
set "CP=%CP%;%L%\org\jspecify\jspecify\1.0.0\jspecify-1.0.0.jar"
set "CP=%CP%;%L%\org\checkerframework\checker-qual\3.33.0\checker-qual-3.33.0.jar"

if exist build rmdir /s /q build
mkdir build\classes

pushd src\main\java
"%JDK%\bin\javac.exe" -encoding UTF-8 --release 21 -Xlint:-options -cp "%CP%" -d ..\..\..\build\classes kr\server\huntingground\*.java
set "RESULT=%ERRORLEVEL%"
popd
if not "%RESULT%"=="0" exit /b %RESULT%

"%JDK%\bin\jar.exe" cf build\HuntingGround-Plugin.jar -C build\classes . -C src\main\resources .
if errorlevel 1 exit /b 1
echo Built build\HuntingGround-Plugin.jar
