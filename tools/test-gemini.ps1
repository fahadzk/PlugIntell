<# Diagnose Gemini access with the same environment key as the generated app. No documents are uploaded.
   -TestGeneration makes one minimal generation call and may incur provider charges. #>
[CmdletBinding()]
param(
    [string]$Model='',
    [switch]$ListModels,
    [switch]$TestGeneration,
    [switch]$ShowProviderMessage,
    [ValidateRange(1,600)][int]$TimeoutSeconds=60
)
$ErrorActionPreference='Stop'
$geminiKey=[Environment]::GetEnvironmentVariable('GEMINI_API_KEY')
if([string]::IsNullOrWhiteSpace($geminiKey)){
    Write-Output 'GEMINI_API_KEY is not set in this process. Set it in the shell that launches this command. Do not paste the key into chat.'
    exit 1
}
if(!$ListModels -and [string]::IsNullOrWhiteSpace($Model)){
    Write-Output 'Supply -Model <configured-model-id> or -ListModels.'
    exit 1
}
if($Model.StartsWith('models/')){$Model=$Model.Substring(7)}
if($Model -and ($Model -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$' -or $Model.Contains($geminiKey))){
    Write-Output 'Invalid model ID. Supply the ID only, without a URL, credentials or generation method.'
    exit 1
}
# Do not enable verbose HTTP logging or print headers/full provider bodies.
function Read-GeminiDiagnosticError([System.Management.Automation.ErrorRecord]$Failure){
    $code='transport failure'
    if($Failure.Exception.Response){try{$code='HTTP '+[int]$Failure.Exception.Response.StatusCode}catch{}}
    $body=[string]$Failure.ErrorDetails.Message
    if([string]::IsNullOrWhiteSpace($body) -and $Failure.Exception.Response){
        # Windows PowerShell 5.1 can leave ErrorDetails empty; read the bounded response stream.
        $reader=$null
        try{
            $reader=New-Object System.IO.StreamReader($Failure.Exception.Response.GetResponseStream())
            $buffer=New-Object char[] 16384
            $read=$reader.ReadBlock($buffer,0,$buffer.Length)
            $body=[string]::new($buffer,0,$read)
        }catch{}finally{if($reader){$reader.Dispose()}}
    }
    $status='';$message=''
    try{
        if($body.Length -le 16384){
            $parsed=$body|ConvertFrom-Json;$candidate=$parsed.error.status
            if(@('NOT_FOUND','INVALID_ARGUMENT','UNAUTHENTICATED','PERMISSION_DENIED','RESOURCE_EXHAUSTED','FAILED_PRECONDITION','UNAVAILABLE','INTERNAL','DEADLINE_EXCEEDED') -contains $candidate){$status=' ('+$candidate+')'}
            if($ShowProviderMessage -and $parsed.error.message -is [string]){
                # This probe never sends documents. Show only error.message, with known/recognizable keys removed.
                $message=$parsed.error.message.Replace($geminiKey,'<redacted>').Replace([Uri]::EscapeDataString($geminiKey),'<redacted>')
                $message=[regex]::Replace($message,'(?i)AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{12,}','<redacted>')
                $message=[regex]::Replace($message,'[\p{Cc}\p{Cf}]',' ')
                if($message.Length -gt 1500){$message=$message.Substring(0,1500)+'...'}
            }
        }
    }catch{}
    return [pscustomobject]@{Code=$code;Status=$status;ProviderMessage=$message}
}
function Write-GeminiDiagnosticFailure($Response){
    Write-Output $Response.Error
    if($ShowProviderMessage){
        if($Response.ProviderMessage){Write-Output ('Provider message (key redacted): '+$Response.ProviderMessage)}
        else{Write-Output 'Provider message was unavailable from this HTTP response.'}
    }
}
function Invoke-GeminiDiagnostic([string]$Path,[string]$Operation,[object]$Payload=$null){
    try{
        $arguments=@{Uri=('https://generativelanguage.googleapis.com/v1beta/'+$Path);Headers=@{'x-goog-api-key'=$geminiKey};TimeoutSec=$TimeoutSeconds;ErrorAction='Stop';MaximumRedirection=0;Verbose=$false;Debug=$false}
        if($null -eq $Payload){$arguments.Method='GET'}else{$arguments.Method='POST';$arguments.ContentType='application/json';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Payload|ConvertTo-Json -Depth 30 -Compress))}
        $result=Invoke-RestMethod @arguments
        Write-Output -NoEnumerate ([pscustomobject]@{Success=$true;Data=$result})
    }catch{
        $failure=Read-GeminiDiagnosticError $_
        Write-Output -NoEnumerate ([pscustomobject]@{Success=$false;Error=($Operation+': '+$failure.Code+$failure.Status+'. Full provider error bodies and credentials are omitted.');ProviderMessage=$failure.ProviderMessage})
    }
}
if($ListModels){
    $response=Invoke-GeminiDiagnostic 'models?pageSize=1000' 'Model listing'
    if(!$response.Success){Write-GeminiDiagnosticFailure $response;exit 1}
    @($response.Data.models)|Where-Object {$_.supportedGenerationMethods -contains 'generateContent'}|ForEach-Object {
        $name=[string]$_.name
        if($name -match '^models/[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$' -and !$name.Contains($geminiKey)){Write-Output $name}
    }
    if($response.Data.nextPageToken){Write-Output 'Model listing was capped at 1000 entries.'}
    Write-Output 'Listed models advertise generateContent; this does not guarantee generation access for this project/key.'
    if(!$Model){exit 0}
}
$metadata=Invoke-GeminiDiagnostic ('models/'+$Model) 'Model metadata'
if(!$metadata.Success){Write-GeminiDiagnosticFailure $metadata;exit 1}
Write-Output ('Model metadata: HTTP 200 for '+$Model)
Write-Output ('Advertises generateContent: '+[bool]($metadata.Data.supportedGenerationMethods -contains 'generateContent'))
if(!$TestGeneration){Write-Output 'No generation was attempted. Add -TestGeneration to make one minimal structured-output API call.';exit 0}
$payload=@{
    contents=@(@{role='user';parts=@(@{text='Return a JSON object with ok set to true.'})})
    generationConfig=@{maxOutputTokens=1024;responseMimeType='application/json';responseJsonSchema=@{type='object';properties=@{ok=@{type='boolean'}};required=@('ok');additionalProperties=$false}}
}
$generated=Invoke-GeminiDiagnostic ('models/'+$Model+':generateContent') 'Generation' $payload
if(!$generated.Success){
    Write-GeminiDiagnosticFailure $generated
    Write-Output 'Metadata was readable but inference failed. Confirm model availability and generation access for this project/API key. Use -ListModels to inspect IDs; do not treat listing as proof of inference access.'
    exit 1
}
try{
    if(@($generated.Data.candidates).Count -ne 1 -or $generated.Data.candidates[0].finishReason -ne 'STOP'){throw 'Incomplete response'}
    $text=(@($generated.Data.candidates[0].content.parts)|Where-Object {!$_.thought}|ForEach-Object {$_.text}) -join ''
    $result=$text|ConvertFrom-Json
    if($result.ok -ne $true){throw 'Unexpected probe output'}
    Write-Output 'Generation: HTTP 200 with valid structured output. No document text was sent.'
}catch{Write-Output 'Generation returned HTTP 200 but did not complete the minimal JSON probe. Check model/schema support and output budget.';exit 1}
