# Offline fixtures for Windows PowerShell's empty ErrorDetails / HTTP response-stream path.
$ErrorActionPreference='Stop'
$tokens=$null;$parseErrors=$null
$scriptPath=Join-Path $PSScriptRoot '../test-gemini.ps1'
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $scriptPath),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Gemini diagnostic script has syntax errors.'}
$definition=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-GeminiDiagnosticError'},$true)
if(!$definition){throw 'Missing diagnostic error parser.'}
Invoke-Expression $definition.Extent.Text
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Text;
public sealed class GeminiFixtureResponse : WebResponse {
    private readonly string body;
    public HttpStatusCode StatusCode { get { return HttpStatusCode.NotFound; } }
    public GeminiFixtureResponse(string body) { this.body = body; }
    public override Stream GetResponseStream() { return new MemoryStream(Encoding.UTF8.GetBytes(body)); }
}
'@
function New-FixtureFailure([string]$Body){
    $response=[GeminiFixtureResponse]::new($Body)
    $exception=[Net.WebException]::new('HTTP failure',$null,[Net.WebExceptionStatus]::ProtocolError,$response)
    return [Management.Automation.ErrorRecord]::new($exception,'GeminiFixture',[Management.Automation.ErrorCategory]::InvalidOperation,$null)
}
function Assert-Fixture([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
$geminiKey='fixture-private-key/value'
$ShowProviderMessage=$true
$body=@{error=@{status='NOT_FOUND';message=('Model generation is unavailable. Key='+$geminiKey+' encoded='+[Uri]::EscapeDataString($geminiKey))}}|ConvertTo-Json -Depth 4
$failure=New-FixtureFailure $body
$result=Read-GeminiDiagnosticError $failure
Assert-Fixture ($result.Code -eq 'HTTP 404') 'HTTP status was lost.'
Assert-Fixture ($result.Status -eq ' (NOT_FOUND)') 'PowerShell 5 response-stream status was not parsed.'
Assert-Fixture ($result.ProviderMessage.Contains('generation is unavailable')) 'Provider explanation was lost.'
Assert-Fixture (!$result.ProviderMessage.Contains($geminiKey) -and !$result.ProviderMessage.Contains([Uri]::EscapeDataString($geminiKey))) 'A fixture key escaped redaction.'
$ShowProviderMessage=$false
$result=Read-GeminiDiagnosticError (New-FixtureFailure $body)
Assert-Fixture ([string]::IsNullOrEmpty($result.ProviderMessage)) 'Provider messages must be opt-in.'
$ShowProviderMessage=$true
$failure=New-FixtureFailure 'not JSON'
$failure.ErrorDetails=[Management.Automation.ErrorDetails]::new($body)
$result=Read-GeminiDiagnosticError $failure
Assert-Fixture ($result.Status -eq ' (NOT_FOUND)') 'Structured ErrorDetails was not preferred.'
$result=Read-GeminiDiagnosticError (New-FixtureFailure 'not JSON')
Assert-Fixture ([string]::IsNullOrEmpty($result.ProviderMessage)) 'Unparsed response bodies must not be printed.'
$body=@{error=@{status='NOT_FOUND';message=([char]27+'unsafe-control '+('x'*2000))}}|ConvertTo-Json -Depth 4
$result=Read-GeminiDiagnosticError (New-FixtureFailure $body)
Assert-Fixture (!$result.ProviderMessage.Contains([string][char]27) -and $result.ProviderMessage.Length -le 1503) 'Provider text was not bounded and cleaned.'
$result=Read-GeminiDiagnosticError (New-FixtureFailure '')
Assert-Fixture ([string]::IsNullOrEmpty($result.ProviderMessage)) 'Empty response must remain empty.'
Write-Output 'Gemini diagnostic offline fixtures passed: response-stream fallback, ErrorDetails, opt-in messages, key redaction, malformed/empty bodies and output bounds.'
