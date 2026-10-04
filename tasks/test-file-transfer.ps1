param(
    [Parameter(Mandatory)][string]$Sender,
    [Parameter(Mandatory)][string]$Receiver,
    [Parameter(Mandatory)][string]$SenderName,
    [Parameter(Mandatory)][string]$ReceiverName,
    [ValidateSet('success', 'decline', 'sender-cancel', 'receiver-cancel')][string]$Case = 'success',
    [switch]$RequirePin,
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$prefix = Join-Path $workspace "scratch/transfer-$Case-$($RequirePin.IsPresent)-$($Sender.Split(':')[0])"
$receiverLog = "$prefix-receiver.log"
$senderLog = "$prefix-sender.log"
$runner = 'com.secretvault.app.test/androidx.test.runner.AndroidJUnitRunner'
$test = 'com.secretvault.app.transfer.TransferDeviceTest#transfersGeneratedFilesAcrossWifi'
& $Adb -s $Sender shell input keyevent KEYCODE_WAKEUP
& $Adb -s $Receiver shell input keyevent KEYCODE_WAKEUP
function PeerArgument([string]$name) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($name)) }
$hostArgs = @('-s', $Receiver, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $test,
    '-e', 'transferBackendOnly', 'true',
    '-e', 'transferRole', 'receiver', '-e', 'transferPeerBase64', (PeerArgument $SenderName),
    '-e', 'transferCase', $Case, '-e', 'transferPin', $RequirePin.IsPresent.ToString().ToLowerInvariant(), $runner)
$receiverProcess = Start-Process -FilePath $Adb -ArgumentList $hostArgs -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $receiverLog -RedirectStandardError "$prefix-receiver-error.log"
try {
    $readyUntil = [DateTime]::UtcNow.AddSeconds(25)
    do {
        if ($receiverProcess.HasExited) { throw "Receiver stopped before ready; inspect $receiverLog" }
        if (Test-Path -LiteralPath $receiverLog) {
            if (Select-String -LiteralPath $receiverLog -SimpleMatch 'receiver-ready' -Quiet) { break }
        }
        if ([DateTime]::UtcNow -gt $readyUntil) { throw "Receiver did not become ready; inspect $receiverLog" }
        Start-Sleep -Milliseconds 200
    } while ($true)
    & $Adb -s $Sender shell am instrument -w -r -e class $test -e transferRole sender `
        -e transferBackendOnly true `
        -e transferPeerBase64 (PeerArgument $ReceiverName) -e transferCase $Case `
        -e transferPin $RequirePin.IsPresent.ToString().ToLowerInvariant() $runner | Tee-Object -FilePath $senderLog
    if (!$receiverProcess.WaitForExit(60000)) { throw 'Receiver did not finish' }
    Get-Content -LiteralPath $receiverLog
    foreach ($log in @($receiverLog, $senderLog)) {
        if (!(Select-String -LiteralPath $log -SimpleMatch 'OK (1 test)' -Quiet)) { throw "Transfer test failed: $log" }
    }
} finally {
    if (!$receiverProcess.HasExited) {
        Stop-Process -Id $receiverProcess.Id -ErrorAction SilentlyContinue
        & $Adb -s $Receiver shell am force-stop com.secretvault.app
    }
    & $Adb -s $Sender shell am start -n com.secretvault.app/.MainActivity
    & $Adb -s $Receiver shell am start -n com.secretvault.app/.MainActivity
}
