# Installs PhotoFind on this PC: Python libraries, the USB tool, the phone app package,
# and a Start menu shortcut. The setup window then installs it on the phone.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Find-Adb {
    if ($env:PHOTOFIND_ADB -and (Test-Path $env:PHOTOFIND_ADB)) { return $env:PHOTOFIND_ADB }
    $candidates = @(
        (Join-Path $Root "tools\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    $props = Join-Path $Root "local.properties"
    if (Test-Path $props) {
        foreach ($line in Get-Content $props) {
            if ($line -like "sdk.dir=*") {
                $dir = ($line.Substring(8) -replace '\\\\', '\' -replace '\\:', ':').Trim()
                $candidates = @((Join-Path $dir "platform-tools\adb.exe")) + $candidates
            }
        }
    }
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    return $null
}

Write-Host "Installing PhotoFind on this PC"
Write-Host ""

$python = Join-Path $Root "tools\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    $py = Get-Command py -ErrorAction SilentlyContinue
    if (-not $py) {
        throw "Python 3.11 or newer is required. Install it from https://www.python.org/downloads/ and run this again."
    }
    Write-Host "Creating the Python environment..."
    & py -3 -m venv (Join-Path $Root "tools\.venv")
    if ($LASTEXITCODE -ne 0) { throw "Could not create the Python environment." }
}
Write-Host "Checking Python libraries..."
& $python -m pip install -r (Join-Path $Root "tools\requirements.txt")
if ($LASTEXITCODE -ne 0) {
    & $python -c "import litert_lm, PIL, pillow_heif, numpy"
    if ($LASTEXITCODE -ne 0) { throw "Could not install the Python libraries." }
    Write-Host "The package index was not reachable. Using the libraries already installed."
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host "Downloading the USB tool (Android platform-tools)..."
    $zip = Join-Path $env:TEMP "photofind-platform-tools.zip"
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath (Join-Path $Root "tools") -Force
    Remove-Item $zip
    $adb = Join-Path $Root "tools\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { throw "The USB tool did not download correctly." }
}
Write-Host "USB tool: $adb"

$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Building the phone app..."
& (Join-Path $Root "gradlew.bat") ":app:assembleDebug"
if ($LASTEXITCODE -ne 0) { throw "The phone app failed to build." }
if (-not (Test-Path $apk)) { throw "The build finished but the app package is missing." }
$dist = Join-Path $Root "tools\dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item $apk (Join-Path $dist "PhotoFind.apk") -Force
Write-Host "Phone app package is ready."

$model = Join-Path $Root "models\embeddinggemma-2-text-vision-440m.litertlm"
if (-not (Test-Path $model)) {
    Write-Host ""
    Write-Host "The scanning model is not in the models folder yet:"
    Write-Host "  $model"
    Write-Host "Put it there before scanning. The setup window will say the same."
}

$shell = New-Object -ComObject WScript.Shell
$target = Join-Path $Root "tools\PhotoFind-PC.bat"
foreach ($folder in @(
    [Environment]::GetFolderPath("Desktop"),
    (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs")
)) {
    $link = $shell.CreateShortcut((Join-Path $folder "PhotoFind.lnk"))
    $link.TargetPath = $target
    $link.WorkingDirectory = Join-Path $Root "tools"
    $link.Description = "PhotoFind setup and photo scanning"
    $link.Save()
}
Write-Host "Shortcut: PhotoFind, on the desktop and in the Start menu."
Write-Host ""
Write-Host "Opening PhotoFind. It will tell you how to allow USB debugging, then install the app on the phone."
Start-Process -FilePath $target
