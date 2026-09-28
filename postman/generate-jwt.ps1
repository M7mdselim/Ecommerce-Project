param(
    [string]$Role = "ROLE_ADMIN",
    [string]$Subject = "user-123",
    [string]$Secret = "microservices-pro-course-secret-key-2024-minimum-256-bits"
)

function Base64UrlEncode([byte[]]$bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$headerJson = '{"alg":"HS256","typ":"JWT"}'
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$exp = $now + 86400 * 30 # 30 days

$roles = @($Role)
if ($Role -eq "ROLE_ADMIN") {
    $roles = @("ROLE_ADMIN", "ROLE_USER")
}
$rolesFormatted = ($roles | ForEach-Object { "`"$_`"" }) -join ","

$payloadJson = "{`"sub`":`"$Subject`",`"roles`":[$rolesFormatted],`"iat`":$now,`"exp`":$exp}"

$headerEncoded = Base64UrlEncode ([Text.Encoding]::UTF8.GetBytes($headerJson))
$payloadEncoded = Base64UrlEncode ([Text.Encoding]::UTF8.GetBytes($payloadJson))
$toSign = "$headerEncoded.$payloadEncoded"

$hmac = New-Object Security.Cryptography.HMACSHA256
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
$sigEncoded = Base64UrlEncode ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($toSign)))

$token = "$toSign.$sigEncoded"
Write-Host "Generated JWT for $Subject ($rolesFormatted):" -ForegroundColor Green
Write-Host $token
return $token
