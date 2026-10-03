param(
    [Parameter(Mandatory)][string]$Sender,
    [Parameter(Mandatory)][string]$Viewer,
    [string]$TestClass = 'com.secretvault.app.stream.StreamLiveUiDeviceTest#streamsActualCameraThroughUi',
    [ValidateSet('camerax', 'native')][string]$CameraSource = 'camerax',
    [ValidateRange(10, 300)][int]$StreamSeconds = 10,
    [switch]$SecurePairing,
    [switch]$CameraChanges,
    [switch]$RemotePhoto,
    [switch]$RemoteVideo,
    [ValidateSet('stop', 'disconnect', 'background')][string]$VideoExit = 'stop',
    [switch]$NoStreamPin,
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$logDirectory = Join-Path $workspace 'scratch'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$hostLog = Join-Path $logDirectory 'stream-live-host.log'
$viewerLog = Join-Path $logDirectory 'stream-live-viewer.log'
$runner = 'com.secretvault.app.test/androidx.test.runner.AndroidJUnitRunner'
$hostProcess = $null
$passed = $false
try {
    foreach ($device in @($Sender, $Viewer)) {
        & $Adb -s $device shell run-as com.secretvault.app rm -f cache/stream-test-invitation
        if ($LASTEXITCODE -ne 0) { throw 'Debug app is unavailable' }
    }
    $arguments = @('-s', $Sender, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass, '-e', 'streamRole', 'send', '-e', 'cameraSource', $CameraSource, '-e', 'streamSeconds', $StreamSeconds, '-e', 'cameraChanges', $CameraChanges.IsPresent.ToString().ToLowerInvariant(), '-e', 'remotePhoto', $RemotePhoto.IsPresent.ToString().ToLowerInvariant(), '-e', 'securePairing', $SecurePairing.IsPresent.ToString().ToLowerInvariant(), '-e', 'noStreamPin', $NoStreamPin.IsPresent.ToString().ToLowerInvariant(), $runner)
    $arguments = $arguments[0..($arguments.Length - 2)] + @('-e', 'remoteVideo', $RemoteVideo.IsPresent.ToString().ToLowerInvariant(), '-e', 'videoExit', $VideoExit, $runner)
    $hostProcess = Start-Process -FilePath $Adb -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $hostLog -RedirectStandardError (Join-Path $logDirectory 'stream-live-host-error.log')
    $pairingText = $null
    for ($attempt = 0; $attempt -lt 160; $attempt++) {
        $pairingText = (& $Adb -s $Sender exec-out run-as com.secretvault.app cat cache/stream-test-invitation 2>$null)
        if ($pairingText -match '^svstream(2)?://') { break }
        if ($hostProcess.HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($pairingText -notmatch '^svstream(2)?://') { throw 'Sender invitation unavailable; inspect the sender test log' }
    $pairingText | & $Adb -s $Viewer shell 'run-as com.secretvault.app sh -c "cat > cache/stream-test-invitation"'
    if ($LASTEXITCODE -ne 0) { throw 'Private invitation transfer failed' }
    $pairingText = $null
    & $Adb -s $Viewer shell am instrument -w -r -e class $testClass -e streamRole view -e streamSeconds $StreamSeconds -e cameraChanges $CameraChanges.IsPresent.ToString().ToLowerInvariant() -e remotePhoto $RemotePhoto.IsPresent.ToString().ToLowerInvariant() -e remoteVideo $RemoteVideo.IsPresent.ToString().ToLowerInvariant() -e videoExit $VideoExit -e noStreamPin $NoStreamPin.IsPresent.ToString().ToLowerInvariant() -e securePairing $SecurePairing.IsPresent.ToString().ToLowerInvariant() $runner | Tee-Object -FilePath $viewerLog
    if (!$hostProcess.WaitForExit(65000)) { throw 'Sender test did not finish' }
    Get-Content -LiteralPath $hostLog
    foreach ($log in @($hostLog, $viewerLog)) {
        if ((Get-Content -LiteralPath $log -Raw) -notmatch 'OK \(1 test\)') { throw "Live stream check failed: $log" }
    }
    $passed = $true
} finally {
    $pairingText = $null
    if ($hostProcess -and !$hostProcess.HasExited) { $hostProcess.Kill() }
    foreach ($device in @($Sender, $Viewer)) {
        if ($hostProcess) {
            if (!$passed) { & $Adb -s $device shell am force-stop com.secretvault.app }
            & $Adb -s $device shell am start -n com.secretvault.app/.MainActivity | Out-Null
        }
        & $Adb -s $device shell run-as com.secretvault.app rm -f cache/stream-test-invitation
    }
}
