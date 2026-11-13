[CmdletBinding()]
param(
    [ValidateSet('label', 'init', 'status', 'export')]
    [string]$Action = 'label',
    [string]$Images = (Join-Path $env:USERPROFILE 'Downloads/samples'),
    [string]$Annotations,
    [string]$Output,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Annotations) { $Annotations = Join-Path $projectRoot '.local/label-samples/annotations.json' }
if (-not $Output) { $Output = Join-Path $projectRoot '.local/label-samples/labels.json' }
$labelScript = Join-Path $PSScriptRoot 'label_samples.py'
& $Python $labelScript $Action --images $Images --annotations $Annotations --output $Output
exit $LASTEXITCODE
