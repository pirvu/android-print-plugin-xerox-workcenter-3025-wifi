@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Xerox 3025 Print Plugin - Windows Setup Tool
echo ===================================================
echo This script sets up Java 17 and the Android SDK
echo required to build this project on Windows.
echo.

:: Ensure PowerShell is available
where powershell >nul 2>&1
if errorlevel 1 (
    echo [ERROR] PowerShell is required to run automated setup.
    exit /b 1
)

:: -------------------------------------------------------------------
:: 1. Check & Install OpenJDK 17
:: -------------------------------------------------------------------
echo [1/4] Checking Java 17+ installation...

set "FOUND_JAVA="
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "FOUND_JAVA=%JAVA_HOME%"
    )
)

if "%FOUND_JAVA%"=="" (
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
            set "FOUND_JAVA=%%~fD"
        )
    )
)

if not "%FOUND_JAVA%"=="" (
    echo [OK] Found JDK: !FOUND_JAVA!
    set "JAVA_HOME=!FOUND_JAVA!"
) else (
    echo [INFO] Java 17 not detected. Downloading portable OpenJDK 17...
    set "JDK_BASE=%USERPROFILE%\.jdk"
    if not exist "!JDK_BASE!" mkdir "!JDK_BASE!"
    
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference = 'SilentlyContinue';" ^
        "$url = 'https://aka.ms/download-jdk/microsoft-jdk-17.0.10-windows-x64.zip';" ^
        "$zip = [System.IO.Path]::Combine($env:TEMP, 'jdk17.zip');" ^
        "$dest = [System.IO.Path]::Combine($env:USERPROFILE, '.jdk');" ^
        "Write-Host 'Downloading OpenJDK 17...';" ^
        "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;" ^
        "(New-Object System.Net.WebClient).DownloadFile($url, $zip);" ^
        "Write-Host 'Extracting OpenJDK 17...';" ^
        "Expand-Archive -Path $zip -DestinationPath $dest -Force;" ^
        "Remove-Item $zip -Force -ErrorAction SilentlyContinue;" ^
        "$found = Get-ChildItem -Path $dest -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -First 1;" ^
        "if ($found) { Write-Host ('JDK extracted to: ' + $found.FullName) }"

    for /d %%D in ("%USERPROFILE%\.jdk\*") do (
        if exist "%%~fD\bin\javac.exe" set "JAVA_HOME=%%~fD"
    )
)

if "%JAVA_HOME%"=="" (
    echo [ERROR] Could not set up OpenJDK 17 automatically.
    exit /b 1
)

echo [OK] Configured JDK at: %JAVA_HOME%
setx JAVA_HOME "%JAVA_HOME%" >nul 2>&1
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: -------------------------------------------------------------------
:: 2. Check & Install Android SDK Command Line Tools
:: -------------------------------------------------------------------
echo.
echo [2/4] Setting up Android SDK...

if "%ANDROID_HOME%"=="" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

if not exist "%ANDROID_HOME%" mkdir "%ANDROID_HOME%"
set "CMDLINE_DIR=%ANDROID_HOME%\cmdline-tools\latest"

if not exist "%CMDLINE_DIR%\bin\sdkmanager.bat" (
    echo [INFO] Android Command-Line Tools not found. Downloading...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference = 'SilentlyContinue';" ^
        "$url = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip';" ^
        "$zip = [System.IO.Path]::Combine($env:TEMP, 'cmdline-tools.zip');" ^
        "$sdk = '%ANDROID_HOME:\=\\%';" ^
        "$dest = Join-Path $sdk 'cmdline-tools';" ^
        "Write-Host 'Downloading Android commandlinetools...';" ^
        "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;" ^
        "(New-Object System.Net.WebClient).DownloadFile($url, $zip);" ^
        "Write-Host 'Extracting Android tools...';" ^
        "New-Item -ItemType Directory -Path $dest -Force | Out-Null;" ^
        "Expand-Archive -Path $zip -DestinationPath $dest -Force;" ^
        "Remove-Item $zip -Force -ErrorAction SilentlyContinue;" ^
        "$extracted = Join-Path $dest 'cmdline-tools';" ^
        "$latest = Join-Path $dest 'latest';" ^
        "if (Test-Path $latest) { Remove-Item -Recurse -Force $latest };" ^
        "if (Test-Path $extracted) { Rename-Item -Path $extracted -NewName 'latest' }"
)

if not exist "%CMDLINE_DIR%\bin\sdkmanager.bat" (
    echo [ERROR] Failed to set up Android cmdline-tools.
    exit /b 1
)

echo [OK] Android SDK Tools ready at: %CMDLINE_DIR%
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
setx ANDROID_HOME "%ANDROID_HOME%" >nul 2>&1
setx ANDROID_SDK_ROOT "%ANDROID_HOME%" >nul 2>&1
set "PATH=%CMDLINE_DIR%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

:: -------------------------------------------------------------------
:: 3. Configure local.properties & Accept Licenses
:: -------------------------------------------------------------------
echo.
echo [3/4] Configuring project and licenses...

set "ESCAPED_SDK=%ANDROID_HOME:\=\\%"
echo(sdk.dir=!ESCAPED_SDK!> local.properties
echo [OK] Written local.properties

echo [INFO] Accepting Android SDK licenses...
set "LICENSES_DIR=%ANDROID_HOME%\licenses"
if not exist "%LICENSES_DIR%" mkdir "%LICENSES_DIR%"

echo 24333f8a63b6825ea9c5514f83c2829b004d1fee > "%LICENSES_DIR%\android-sdk-license"
echo 84831b9409646a918e30573bab4c9c91346d8abd >> "%LICENSES_DIR%\android-sdk-license"
echo d56f5187479451eabf01fb78af6dfcb131a6481e >> "%LICENSES_DIR%\android-sdk-license"
echo 89337d0c0b3f03e3a1f86fc2d1400e28eaddb340 > "%LICENSES_DIR%\android-sdk-preview-license"

:: -------------------------------------------------------------------
:: 4. Install Platforms & Build-Tools
:: -------------------------------------------------------------------
echo.
echo [4/4] Installing Android SDK packages (platforms;android-34, build-tools;34.0.0)...

(for /l %%i in (1,1,30) do @echo y) | call "%CMDLINE_DIR%\bin\sdkmanager.bat" --sdk_root="%ANDROID_HOME%" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

echo.
echo ===================================================
echo   [SUCCESS] Setup Completed Successfully!
echo ===================================================
echo JAVA_HOME:    %JAVA_HOME%
echo ANDROID_HOME: %ANDROID_HOME%
echo.
echo You can now build the project by running:
echo   build.bat
echo ===================================================

endlocal
