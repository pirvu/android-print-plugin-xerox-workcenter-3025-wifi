@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Xerox 3025 Print Plugin - Windows Build
echo ===================================================

set "VERSION_NAME=%~1"
if "%VERSION_NAME%"=="" set "VERSION_NAME=dev"

set "VERSION_CODE=%~2"
if "%VERSION_CODE%"=="" set "VERSION_CODE=99999"

echo Target Version: %VERSION_NAME% (Code: %VERSION_CODE%)
echo.

:: 1. Locate Java
if "%JAVA_HOME%"=="" (
    for /d %%D in (
        "%USERPROFILE%\.jdk\*"
        "C:\Program Files\Android\Android Studio\jbr"
        "%LOCALAPPDATA%\Android\Sdk\jbr"
        "C:\Program Files\Eclipse Adoptium\*"
        "C:\Program Files\Microsoft\*"
        "C:\Program Files\Java\*"
        "%CD%\tools\*"
    ) do (
        if exist "%%~fD\bin\javac.exe" (
            set "JAVA_HOME=%%~fD"
            goto :found_java
        )
    )
)

:found_java
if not "%JAVA_HOME%"=="" (
    echo [OK] Using JAVA_HOME: %JAVA_HOME%
    set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
    where java >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Java 17+ is not found!
        echo Please run setup.bat first to configure your build environment.
        exit /b 1
    )
)

:: 2. Locate Android SDK
if "%ANDROID_HOME%"=="" (
    if "%ANDROID_SDK_ROOT%"=="" (
        for %%D in (
            "%LOCALAPPDATA%\Android\Sdk"
            "%USERPROFILE%\AppData\Local\Android\Sdk"
            "%USERPROFILE%\Android\Sdk"
            "%CD%\android-sdk"
        ) do (
            if exist "%%~fD\platform-tools" (
                set "ANDROID_HOME=%%~fD"
                goto :found_sdk
            )
        )
    ) else (
        set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
    )
)

:found_sdk
if not "%ANDROID_HOME%"=="" (
    echo [OK] Using ANDROID_HOME: %ANDROID_HOME%
    set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
    set "PATH=%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%"
    
    :: Generate local.properties if missing
    if not exist "local.properties" (
        set "ESCAPED_SDK=%ANDROID_HOME:\=\\%"
        echo(sdk.dir=!ESCAPED_SDK!> local.properties
        echo [OK] Generated local.properties
    )
) else (
    if not exist "local.properties" (
        echo [WARN] ANDROID_HOME not set and local.properties missing.
        echo If the build fails, please run setup.bat.
    )
)

echo.
echo Starting Gradle build...
call gradlew.bat assembleDebug -PVERSION_NAME=%VERSION_NAME% -PVERSION_CODE=%VERSION_CODE%
if errorlevel 1 (
    echo.
    echo ===================================================
    echo [ERROR] Build failed!
    echo If this is your first time building, please run setup.bat.
    echo ===================================================
    exit /b 1
)

:: Copy output APK to root
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /y "app\build\outputs\apk\debug\app-debug.apk" ".\app-debug.apk" >nul
    echo.
    echo ===================================================
    echo [SUCCESS] APK built successfully!
    echo Output saved to: %CD%\app-debug.apk
    echo ===================================================
) else (
    echo [WARN] Build completed but output APK not found at app\build\outputs\apk\debug\app-debug.apk
)

endlocal
