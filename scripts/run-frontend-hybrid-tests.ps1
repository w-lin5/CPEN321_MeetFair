#!/usr/bin/env pwsh
<#
Run frontend hybrid multi-user E2E tests across two devices.

Prerequisites: sign in inside the app on Device A (the emulator), and have Device B ready.
Usage: .\scripts\run-frontend-hybrid-tests.ps1

NOTE ON EXECUTION POLICY:
  Windows blocks script execution by default. If running this script fails
  with a message about execution policies, either:
    - run once: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
    - or launch with: pwsh -ExecutionPolicy Bypass -File .\scripts\run-frontend-hybrid-tests.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')).Path
$FrontendDir = if ($env:FRONTEND_DIR) { $env:FRONTEND_DIR } else { Join-Path $Root 'frontend' }
$env:FRONTEND_DIR = $FrontendDir
. (Join-Path $Root 'scripts\lib\frontend-instrumented-test-common.ps1')

# Run the same backend, emulator, app install/launch, and sign-in setup as the
# standard E2E runner. Device B remains the manual companion device.
Resolve-TestRoot
$appId = Get-ApplicationId
if (-not $appId) { Die "Could not resolve applicationId from $($script:AppBuild)." }
Ensure-Backend
$frontendScript = Join-Path $Root 'scripts\run-frontend.ps1'
& $frontendScript
if ($LASTEXITCODE -ne 0) { Die "Frontend startup failed." }
Configure-AndroidHome
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $adb = $adbCmd.Source }
    else { Die "adb not found." }
}
Wait-ForAppSignIn
& $adb shell am force-stop $appId 2>$null | Out-Null

$testMethods = @(
    'com.example.cpen321application.e2e.CreateJoinGroupTest#joinGroup_success'
    'com.example.cpen321application.e2e.CreateJoinGroupTest#leaveGroup_nonOrganizerCanLeave'
    'com.example.cpen321application.e2e.CreateJoinGroupTest#removeMember_success'
    'com.example.cpen321application.e2e.VotingTest#vote_otherMemberVotesAppearLive'
    'com.example.cpen321application.e2e.VotingTest#vote_nonParticipantSeesNoVoteButtons'
    'com.example.cpen321application.e2e.VotingTest#vote_afterFinalizeShowsNotOpenAndClosesVoting'
    'com.example.cpen321application.e2e.FinalizeMeetupTest#finalize_earlyCountsOnlyLockedVotes'
    'com.example.cpen321application.e2e.FinalizeMeetupTest#finalize_unresolvedTieOffersOnlyTiedCandidates'
    'com.example.cpen321application.e2e.ShareLiveLocationTest#shareLocation_anotherMemberSeesTripStatus'
)
$classFilter = $testMethods -join ','
$gradleWrapper = Join-Path $FrontendDir 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    Die "Gradle wrapper not found at $gradleWrapper"
}

Info "Running frontend hybrid multi-user tests ($classFilter) ..."
Push-Location $FrontendDir
$exitCode = 0
try {
    & $gradleWrapper ':app:connectedDebugAndroidTest' `
        "-Pandroid.testInstrumentationRunnerArguments.class=$classFilter" `
        '-Pandroid.testInstrumentationRunnerArguments.multi-user=true'
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { Die "Frontend hybrid multi-user tests failed." }
}
finally {
    Pop-Location
}
Info "Frontend hybrid multi-user tests finished."
exit $exitCode
