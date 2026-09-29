@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "VERSION_FILE=gradle.properties"

if not exist "%VERSION_FILE%" (
    echo ERROR: %VERSION_FILE% not found.
    exit /b 1
)

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo ERROR: This is not a Git repository.
    exit /b 1
)

for /f "delims=" %%A in ('git branch --show-current') do set "BRANCH=%%A"

if not defined BRANCH (
    echo ERROR: You are in detached HEAD state.
    exit /b 1
)

for /f "tokens=1,* delims==" %%A in ('findstr /b "mod_version=" "%VERSION_FILE%"') do (
    set "CURRENT_VERSION=%%B"
)

if not defined CURRENT_VERSION (
    echo ERROR: Could not find mod_version in %VERSION_FILE%.
    exit /b 1
)

echo.
echo ======================================
echo         Mod Release Script
echo ======================================
echo.
echo Current version : !CURRENT_VERSION!
echo Git branch      : !BRANCH!
echo.

set /p "NEW_VERSION=New version: "

if not defined NEW_VERSION (
    echo ERROR: Version cannot be empty.
    exit /b 1
)

if "!NEW_VERSION!"=="!CURRENT_VERSION!" (
    echo ERROR: New version is the same as the current version.
    exit /b 1
)

powershell -NoProfile -Command ^
    "if ('!NEW_VERSION!' -notmatch '^[0-9]+\.[0-9]+\.[0-9]+([.-].+)?$') { exit 1 }"

if errorlevel 1 (
    echo ERROR: Invalid version format.
    echo Example: 1.2.3 or 1.2.3-beta1
    exit /b 1
)

set "TAG=v!NEW_VERSION!"

set /p "COMMIT_MESSAGE=Commit message [Bump version to !NEW_VERSION!]: "
if not defined COMMIT_MESSAGE set "COMMIT_MESSAGE=Bump version to !NEW_VERSION!"

set /p "CUSTOM_TAG=Tag [!TAG!]: "
if defined CUSTOM_TAG set "TAG=!CUSTOM_TAG!"

echo.
echo --------------------------------------
echo Version: !CURRENT_VERSION! -^> !NEW_VERSION!
echo Commit : !COMMIT_MESSAGE!
echo Tag    : !TAG!
echo Branch : !BRANCH!
echo --------------------------------------
echo.

set /p "CONFIRM=Continue? [y/N]: "

if /i not "!CONFIRM!"=="y" (
    echo Cancelled.
    exit /b 0
)

echo.
echo Updating version...

powershell -NoProfile -Command ^
    "$p = '%VERSION_FILE%';" ^
    "$c = [System.IO.File]::ReadAllText($p);" ^
    "$c = [regex]::Replace($c, '(?m)^mod_version=.*$', 'mod_version=!NEW_VERSION!');" ^
    "[System.IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($false)));"

echo.
echo Changed files:
git diff -- "%VERSION_FILE%"

echo.
set /p "CONFIRM=Create commit and push release? [y/N]: "

if /i not "!CONFIRM!"=="y" (
    echo Version was changed locally, but no commit/tag was created.
    exit /b 0
)

echo.
echo Checking tag...

git rev-parse "refs/tags/!TAG!" >nul 2>&1
if not errorlevel 1 (
    echo ERROR: Tag !TAG! already exists locally.
    exit /b 1
)

git ls-remote --exit-code --tags origin "refs/tags/!TAG!" >nul 2>&1
if not errorlevel 1 (
    echo ERROR: Tag !TAG! already exists on origin.
    exit /b 1
)

echo Creating commit...

git add "%VERSION_FILE%"

git diff --cached --quiet
if not errorlevel 1 (
    echo ERROR: No staged changes.
    exit /b 1
)

git commit -m "!COMMIT_MESSAGE!"
if errorlevel 1 (
    echo ERROR: Git commit failed.
    exit /b 1
)

echo.
echo Creating tag !TAG!...

git tag -a "!TAG!" -m "!TAG!"
if errorlevel 1 (
    echo ERROR: Failed to create tag.
    exit /b 1
)

echo.
echo Pushing branch !BRANCH!...

git push origin "!BRANCH!"
if errorlevel 1 (
    echo ERROR: Failed to push branch.
    exit /b 1
)

echo.
echo Pushing tag !TAG!...

git push origin "!TAG!"
if errorlevel 1 (
    echo ERROR: Failed to push tag.
    exit /b 1
)

echo.
echo ======================================
echo Release completed successfully!
echo ======================================
echo Version : !NEW_VERSION!
echo Tag     : !TAG!
echo Branch  : !BRANCH!
echo.

endlocal
