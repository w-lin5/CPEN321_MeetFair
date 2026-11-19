#!/usr/bin/env bash
#
# Run frontend hybrid multi-user E2E tests across two devices.
#
# Prerequisites: sign in inside the app on Device A (the emulator), and have Device B ready.
# Usage: ./scripts/run-frontend-hybrid-tests.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/frontend}"

source "$ROOT/scripts/lib/frontend-instrumented-test-common.sh"

resolve_test_root
app_id="$(application_id)"
[[ -n "$app_id" ]] || die "Could not resolve applicationId from $APP_BUILD."

ensure_backend
"$ROOT/scripts/run-frontend.sh"
configure_android_home
command -v adb >/dev/null 2>&1 || die "adb not found."

wait_for_app_sign_in
adb shell am force-stop "$app_id" >/dev/null 2>&1 || true

test_methods=(
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
class_filter="$(IFS=,; printf '%s' "${test_methods[*]}")"

info "Running frontend hybrid multi-user tests (${class_filter}) ..."
(
  cd "$FRONTEND_DIR"
  ./gradlew "$GRADLE_TASK" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$class_filter" \
    -Pandroid.testInstrumentationRunnerArguments.multi-user=true
)
info "Frontend hybrid multi-user tests finished."
