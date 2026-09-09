<#
.SYNOPSIS
Sends one call_invite push straight to a device, bypassing the whole call stack.

.DESCRIPTION
Answers exactly one question: can FCM reach this handset at all? It skips identity, invitations,
the call state machine and signalling, so a failure here is the push channel and nothing else.
That separation is what makes it useful on a Xiaomi/HyperOS handset, where the interesting failure
("no usable Google Play Services", "the ROM froze the app") looks identical to a broken call from
the outside.

Get the token from the debug build:

    adb logcat -s ZiseePush

which prints `provider=fcm registered=<bool> token=<...>` on every launch.

.EXAMPLE
./send-test-push.ps1 -Token "cXy...:APA91b..."

.EXAMPLE
./send-test-push.ps1 -Token $t -CallerName "测试" -TtlSeconds 60
#>
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Project = 'zisee-app',
    [string]$CallerName = '推送测试',
    [int]$TtlSeconds = 30,
    # Reuse a call_id to exercise the client's de-duplication; pass a negative
    # ExpiresInSeconds to send an already-stale invite the client must refuse to ring.
    [string]$CallId,
    [int]$ExpiresInSeconds = 0
)

$ErrorActionPreference = 'Stop'

$accessToken = (gcloud auth print-access-token 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'gcloud has no usable credentials. Run: gcloud auth login'
}

$now = [DateTime]::UtcNow
if ([string]::IsNullOrWhiteSpace($CallId)) {
    $CallId = "test-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
}
if ($ExpiresInSeconds -eq 0) { $ExpiresInSeconds = $TtlSeconds }
$body = @{
    message = @{
        token   = $Token
        android = @{ priority = 'HIGH'; ttl = "${TtlSeconds}s" }
        data    = @{
            type         = 'call_invite'
            call_id      = $CallId
            caller_id    = 'zid_test'
            caller_name  = $CallerName
            media_type   = 'video'
            issued_at    = $now.ToString('yyyy-MM-ddTHH:mm:ssZ')
            expires_at   = $now.AddSeconds($ExpiresInSeconds).ToString('yyyy-MM-ddTHH:mm:ssZ')
            call_version = '1'
        }
    }
} | ConvertTo-Json -Depth 6 -Compress

Write-Output "call_id  $CallId"
Write-Output "expires  in ${ExpiresInSeconds}s"

try {
    $response = Invoke-RestMethod -Method Post -Uri "https://fcm.googleapis.com/v1/projects/$Project/messages:send" `
        -Headers @{ Authorization = "Bearer $accessToken" } -ContentType 'application/json; charset=utf-8' -Body $body
    Write-Output "accepted by FCM: $($response.name)"
    Write-Output 'FCM accepting it only means it was queued. Watch the handset with: adb logcat -s ZiseePush'
} catch {
    # FCM puts the actionable part (UNREGISTERED, SENDER_ID_MISMATCH, ...) in the response body,
    # never in the HTTP status. Windows PowerShell 5.1 usually leaves ErrorDetails empty on a failed
    # Invoke-RestMethod, so the body has to be read off the response stream or the only thing left
    # is a localized "(400) Bad Request" that says nothing.
    $detail = $_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($detail) -and $null -ne $_.Exception.Response) {
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $detail = $reader.ReadToEnd()
            $reader.Dispose()
        } catch { }
    }
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = $_.Exception.Message }
    Write-Output "rejected by FCM: $detail"
    Write-Output ''
    Write-Output 'UNREGISTERED / INVALID_ARGUMENT  the token is stale; relaunch the app to re-register'
    Write-Output 'SENDER_ID_MISMATCH               the token belongs to a different Firebase project'
    Write-Output 'PERMISSION_DENIED                this gcloud account cannot send for the project'
    exit 1
}
