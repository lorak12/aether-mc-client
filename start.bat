@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Aether
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  Aether dev starter: backend + launcher in one go.
rem
rem    start.bat               backend (watch mode) + launcher (cargo tauri dev)
rem    start.bat --release     build the release launcher exe and run that
rem    start.bat --build       rebuild the client jars (gradle) first
rem    start.bat --no-backend  launcher only
rem    start.bat --help
rem
rem  Flags can be combined. The backend opens in its own window and is closed
rem  again when the launcher exits (unless it was already running).
rem ---------------------------------------------------------------------------

for /f %%a in ('echo prompt $E^| cmd') do set "ESC=%%a"
set "C_DIM=%ESC%[90m"
set "C_ACC=%ESC%[96m"
set "C_OK=%ESC%[92m"
set "C_WARN=%ESC%[93m"
set "C_ERR=%ESC%[91m"
set "C_OFF=%ESC%[0m"

set "MODE=dev"
set "BUILD_RUNTIME=0"
set "WITH_BACKEND=1"
:args
if "%~1"=="" goto args_done
if /i "%~1"=="--release"    set "MODE=release"     & shift & goto args
if /i "%~1"=="--build"      set "BUILD_RUNTIME=1"  & shift & goto args
if /i "%~1"=="--no-backend" set "WITH_BACKEND=0"   & shift & goto args
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h"     goto usage
echo %C_ERR%Unknown option: %~1%C_OFF%
goto usage
:args_done

echo.
echo %C_ACC%    /\     ___  _____ _   _ ___ ___%C_OFF%
echo %C_ACC%   /  \   ^| __^|^|_   _^| ^|_^| ^| __^| _ \%C_OFF%
echo %C_ACC%  / /\ \  ^| _^|   ^| ^| ^|  _  ^| _^|^|   /%C_OFF%
echo %C_ACC% /_/  \_\ ^|___^|  ^|_^| ^|_^| ^|_^|___^|_^|_\%C_OFF%
echo %C_DIM%  mode: %MODE%   backend: %WITH_BACKEND%   build runtime: %BUILD_RUNTIME%%C_OFF%
echo.

rem --- prerequisites ---------------------------------------------------------
call :need node   "Node.js 22.5+ (https://nodejs.org)" || goto fail
call :need npm    "npm (comes with Node.js)"           || goto fail
call :need cargo  "Rust (https://rustup.rs)"           || goto fail
if "%MODE%"=="dev" (
  cargo tauri --version >nul 2>&1 || (
    echo %C_ERR%[x]%C_OFF% Tauri CLI missing. Install it with: cargo install tauri-cli --version "^2"
    goto fail
  )
)

rem --- dependencies (first run only) -----------------------------------------
if "%WITH_BACKEND%"=="1" if not exist "backend\node_modules" (
  call :step "Installing backend dependencies"
  pushd backend & call npm install --no-fund --no-audit & set "ERR=!errorlevel!" & popd
  if not "!ERR!"=="0" goto fail
)
if not exist "launcher\ui\node_modules" (
  call :step "Installing launcher UI dependencies"
  pushd launcher\ui & call npm install --no-fund --no-audit & set "ERR=!errorlevel!" & popd
  if not "!ERR!"=="0" goto fail
)

rem --- client jars -----------------------------------------------------------
if "%BUILD_RUNTIME%"=="1" (
  call :step "Building client jars (gradle)"
  pushd runtime & call gradlew.bat :v1_21_11:build :v26_1:build :v26_1_1:build :v26_1_2:build :v26_2:build :v26_3:build & set "ERR=!errorlevel!" & popd
  if not "!ERR!"=="0" goto fail
) else (
  set "JARS=0"
  for %%f in (runtime\v1_21_11\build\libs\aether-*.jar runtime\v26_1\build\libs\aether-*.jar runtime\v26_1_1\build\libs\aether-*.jar runtime\v26_1_2\build\libs\aether-*.jar runtime\v26_2\build\libs\aether-*.jar runtime\v26_3\build\libs\aether-*.jar) do set "JARS=1"
  if "!JARS!"=="0" echo %C_WARN%[warn]%C_OFF% No client jars built yet; Play won't install the Aether client. Run: start.bat --build
)

rem --- backend ---------------------------------------------------------------
set "PORT=8787"
if exist "backend\.env" for /f "usebackq eol=# tokens=1,* delims==" %%a in ("backend\.env") do (
  if /i "%%a"=="PORT" set "PORT=%%b"
)
set "STARTED_BACKEND=0"
if "%WITH_BACKEND%"=="0" goto launcher

set "AETHER_BACKEND_URL=http://localhost:%PORT%"
call :health && (
  echo %C_OK%[ok]%C_OFF% Backend already running on :%PORT%, reusing it.
  goto launcher
)
if not exist "backend\.env" echo %C_WARN%[warn]%C_OFF% backend\.env not found; the backend starts with defaults (no offline sign-in).
call :step "Starting backend on :%PORT%"
set "BACKEND_PID="
for /f %%p in ('powershell -NoProfile -Command "(Start-Process cmd -ArgumentList '/c','title Aether Backend && npm run dev' -WorkingDirectory '%~dp0backend' -WindowStyle Minimized -PassThru).Id"') do set "BACKEND_PID=%%p"
if not defined BACKEND_PID goto fail
set "STARTED_BACKEND=1"
set /a "TRIES=0"
:wait_backend
call :health && goto backend_up
set /a "TRIES+=1"
if !TRIES! geq 30 (
  echo %C_ERR%[x]%C_OFF% Backend didn't answer on :%PORT% within 30s. Check the "Aether Backend" window.
  goto fail
)
ping -n 2 127.0.0.1 >nul
goto wait_backend
:backend_up
echo %C_OK%[ok]%C_OFF% Backend up at %AETHER_BACKEND_URL%

rem --- launcher --------------------------------------------------------------
:launcher
if "%MODE%"=="release" (
  call :step "Building release launcher"
  pushd launcher\src-tauri & call cargo tauri build --no-bundle & set "ERR=!errorlevel!" & popd
  if not "!ERR!"=="0" goto fail
  call :step "Running launcher"
  "target\release\aether-launcher.exe"
) else (
  call :step "Starting launcher (cargo tauri dev) - first build can take a few minutes"
  pushd launcher\src-tauri & call cargo tauri dev & popd
)

call :stop_backend
echo.
echo %C_DIM%Launcher closed. Bye.%C_OFF%
endlocal
exit /b 0

rem --- helpers ---------------------------------------------------------------
:fail
call :stop_backend
echo.
echo %C_ERR%Startup failed.%C_OFF%
pause
endlocal
exit /b 1

:stop_backend
if "%STARTED_BACKEND%"=="1" (
  echo %C_DIM%Stopping backend...%C_OFF%
  taskkill /PID %BACKEND_PID% /T /F >nul 2>&1
  set "STARTED_BACKEND=0"
)
exit /b 0

:health
curl -fs -m 2 "http://localhost:%PORT%/v1/health" >nul 2>&1
exit /b %errorlevel%

:need
where %1 >nul 2>&1 && exit /b 0
echo %C_ERR%[x]%C_OFF% %~1 not found on PATH. Install %~2.
exit /b 1

:step
echo %C_ACC%==^>%C_OFF% %~1
exit /b 0

:usage
echo.
echo Usage: start.bat [--release] [--build] [--no-backend]
echo.
echo   (no flags)     backend in watch mode + launcher with hot reload (cargo tauri dev)
echo   --release      build the release launcher exe and run it
echo   --build        rebuild the Aether client jars (1.21.11, 26.2, 26.3) before starting
echo   --no-backend   start only the launcher
echo.
endlocal
exit /b 0
