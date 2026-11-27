# Requirements and Design

## 1. Change History

| **Change Date**   | **Modified Sections** | **Rationale** |
| ----------------- | --------------------- | ------------- |
| 2026-10-19 | All Sections | Initial submission for CP1 |
| 2026-11-04 | 3.1-3.7, 4.1-4.7 | CP2: updated requirements, use cases, APIs, and architecture for group management, meetup scheduling and participation, Places/Routes recommendations, weighted voting, Fair Credit settlement, and live-trip tracking; clarified validation, permissions, privacy, refresh, notifications, and edge cases; added approximate screen mock-ups; documented the `settlements` collection; updated the dependency and sequence diagrams; and fixed a typo in feature 5. |
| 2026-11-19 | 3.1-3.2, 3.5, 3.7, 4.1, 4.3, 4.5-4.7 | CP3: Addressed TA feedback by moving refresh and notifications to usability design, making Create Group Formal Use Case 2 and updating diagrams, and removing Health Check from the business component decomposition. Replaced the accessibility validation plan with a viewport/font-scale matrix, TalkBack workflows and explicit pass/fail criteria. Added venue-local opening-hours filtering before recommendation ranking, insufficient confirmed-open venue handling and Google field/billing implications |
| 2026-11-27 | 3.7, 4.7 | M3: Removed Response Time and Usability and Accessibility from the formal NFRs and their design descriptions in response to TA feedback. Retained Security and Access Control and Location Privacy, both covered by the recorded backend NFR tests. |
---

## 2. Project Description

MeetFair is a group meetup planning app designed to make repeated meetups more convenient and fair. It recommends feasible meeting locations using travel-time estimates and user preferences, lets the group vote on the final choice, and tracks actual travel burden during the meetup. Over time, the app converts that burden into Fair Credit, giving users who previously travelled more greater influence in future decisions.

---

## 3. Requirements Specification

### **3.1. List of Features**

1. **Sign in with Google**: Users sign in with their Google account. The back end verifies the Google ID token and issues a MeetFair session that lasts 7 days or until the user signs out.
2. **Groups and meetups**: Any user can create a group and invite others with its 6-character invite code. Any member can create a meetup with a title and a future time selected using a calendar followed by hour and minute wheels. Joining a group does not automatically join its meetups: a member participates by submitting their starting location, travel mode (walk, transit or drive) and preferences. Starting locations are selected on a map rather than entered as coordinates. Members can leave eligible meetups or groups; organizers can remove eligible members and delete the groups or meetups they manage, with confirmation before deletion.
3. **Fair spot recommendation**: The back end collects candidate places (Google Places API), excludes businesses that are not operational or cannot be confirmed open at the scheduled meetup time in the venue's timezone, gets each member's travel time to each remaining candidate (Google Routes API), then scores every candidate with our own fairness and preference algorithm and returns the top 3. Voting does not start if fewer than 3 usable places are found.
4. **Weighted voting**: Members vote on the top 3 and lock their vote. A locked vote is weighted by the member's Fair Credit in the group (how much more than the group average they travelled in past meetups): 1 + 0.1 per 30 minutes of credit, between 1 and 1.5. Votes appear live; the organizer finalizes the vote, and each member can add the meetup at the winning place to their own Google Calendar.
5. **Live commute tracking**: From the scheduled meetup time, participants tap "I am leaving" and share live location; the group sees who is on the way. The commute ends when they tap "I arrived", which records their actual commute time.

### **3.2. Use Case Diagram**

![Use Case Diagram](images/Use_Case_Diagram.png)

### **3.3. Actors Description**

1. **Member**: A signed-in user who belongs to a group. A member creates or joins groups, creates meetups, enters their start location and preferences, votes, adds finalized meetups to their calendar and shares their live location on the way so their actual commute time is recorded.
2. **Organizer**: A group organizer is the user who created the group and can remove eligible members or delete that group. A meetup organizer is the member who created the meetup and can start voting, finalize the vote or delete that meetup. These roles are separate: being the group organizer does not automatically grant organizer permissions over another member's meetup. When a departing or removed member manages an unsettled meetup, its organizer role is transferred to the group organizer.
3. **Google Sign-In**: Google's identity service. It authenticates the user for sign-in and, with the user's consent, issues the short-lived access token used to add events to their Google Calendar.
4. **Google Maps Platform**: Finds candidate places (Places API) and returns every member's travel time to every candidate (Routes API).
5. **Google Calendar**: Stores the finalized meetup as an event in the member's own calendar.

### **3.4. Use Case Description**

- Use cases for feature 1: Sign In

1. **Sign In**: A user authenticates into the app using their Google account to access their groups and past fairness history.
2. **Sign out**: A user securely ends their active session on the device.
3. **Edit Profile**: A member changes the name other members see, or goes back to their Google name.

- Use cases for feature 2: Groups and Meetups

1. **Create Group**: A member creates a new group in the app and receives a code to invite other members.
2. **Create Meetup**: A member creates a new meetup in their group with a title and the agreed time, and becomes its organizer. The member first selects a date from a calendar, then chooses hours and minutes using two scrolling wheels in the device's time zone. Cancelling preserves the previous value. Both the app and backend reject times at or before the current time; members can then add their details.
3. **Join Group**: A member uses a code to associate their account with an existing group.
4. **Enter Start Location & Preferences**: A member submits their start location, travel mode and meetup preferences for a meetup in SETUP. The supported preference options are `COFFEE`, `FOOD`, `OUTDOORS` and `SOCIAL`. The map supports panning, zooming, tapping a location or named place, and dragging the marker. Confirming applies the position; cancelling preserves the previous selection. A place name or resolved address is displayed when available. Submitting or changing details updates connected meetup pages without requiring manual refresh.
5. **Leave Meetup**: A participant may leave only while the meetup is in SETUP. After confirmation, their participation details are removed and are no longer available to subsequent place generation or voting. Connected meetup pages refresh silently; no Snackbar is shown for leaving.
6. **Leave Group**: A member other than the group organizer may leave unless a meetup they have joined is in VOTING. Unrelated voting meetups do not block leaving; joined meetups in FINALIZED or SETTLED do not block it either. After confirmation, membership and participation details in SETUP meetups are removed, while completed voting history is retained. Any ACTIVE trips in this group, including paused sharing, become CANCELLED and their latest locations are cleared. Cancellation does not record an arrival or a completed commute duration; settlement uses the existing estimated travel-time fallback for these participants. Previously completed ARRIVED trips retain their actual durations. A cancelled trip cannot be resumed after rejoining. Leaving does not display Snackbars to the departing member or others.
7. **Remove Member**: Only the group organizer can remove another member. Removal is blocked if that member submitted details for any meetup in VOTING or FINALIZED; those meetups must first reach SETTLED. Otherwise, after confirmation, the system removes membership and that member's participation entries and votes within the group. Unrelated
8. **Delete Meetup**: Only the meetup organizer can delete the meetup, in any status. A confirmation dialog includes an additional warning when the meetup is in VOTING. The system removes the meetup, its recommendations and votes. Existing Google Calendar events are not removed.
9. **Delete Group**: Only the group organizer can delete the group, in any status. A confirmation dialog includes an additional warning if any meetup in the group is in VOTING. The system removes the group and its associated meetup data. Connected group members receive a deletion notice, and deleted pages are closed or refreshed.

- Use cases for feature 3: Fair Spot Recommendation

1. **Get Recommendations**: The organizer starts voting; the system collects the members' locations and preferences, calculates commute fairness, and outputs the top 3 candidate locations.

- Use cases for feature 4: Voting and Fairness

1. **Vote on a Candidate and Lock Vote**: A member casts a vote for their preferred spot from the top 3 recommendations, may change it before locking, and makes it final by locking; locked votes count with a weight based on Fair Credit, and every member sees the tally update live.
2. **Finalize Meetup**: The organizer closes the voting and the system determines the winning location.
3. **Add Meetup to Google Calendar**: A member adds the finalized meetup at the winning location to their own Google Calendar.
4. **View Fairness History**: A member checks their profile and, for a specific group, every member's Fair Credit and their own past credit changes.

- Use cases for feature 5: Live Location

1. **Share Live Location**: A member opts in on the day of the event to share their real-time device location with the group and to keep track of their actual commute time. Locations are sent over the REST API, and connected members are told over WebSockets to reload them.
2. **View Members' Location**: A member opens the live map to check the current transit progress of other group members heading to the finalized spot.

### **3.5. Formal Use Case Specifications (5 Most Major Use Cases)**

#### Use Case 1: Vote on a Candidate and Lock Vote

**Description**: A member votes for one of the three candidate places of a meetup and may change the vote until they lock it. A locked vote counts with a weight from the member's Fair Credit, and every member sees the tally update live.

**Primary actor(s)**: Member
    
**Main success scenario**:

1. The member opens a meetup in VOTING and selects "Vote on a spot".
2. The app shows the three places, each with its number of picks, locked weight and share, and how many participants have locked their vote. While live updates are connected it shows "Live: votes update automatically".
3. The member selects "Vote" on one place.
4. The system records the vote and sends the updated tally to every member who has the voting screen open.
5. The member may select "Change vote" on another place; the system replaces the vote and step 4 repeats.
6. The member selects "Submit vote" to lock their vote.
7. The system sets the vote's weight from the member's Fair Credit in the group (1 + 0.1 per 30 minutes of credit, between 1 and 1.5), locks the vote and sends the updated tally to every member who has the voting screen open.
8. The app shows "Your vote is locked" with the vote's weight.

**Failure scenario(s)**:

- 1a. The member has not entered their details for this meetup.
  - 1a1. The app shows the places and the tally without vote buttons, with "Only members who entered their details can vote."

- 2a. Live updates cannot be connected.
  - 2a1. The app shows a "Refresh results" button instead of the live indicator.
  - 2a2. The tally is refreshed after each of the member's own votes and whenever they select "Refresh results".

- 3a. The organizer finalized the meetup before the vote arrived.
  - 3a1. The app shows "Voting is not open for this meetup" and reloads the meetup, which shows that voting is closed.

- 3b. The member's device has no network connection.
  - 3b1. The app shows a "Network error" message and the member's previous vote stays unchanged.
  - 3b2. The member may vote again when connectivity is restored.

- 6a. The vote was already locked, for example from another device.
  - 6a1. The app shows "Your vote is already locked".

#### Use Case 2: Create Group

**Description**: A signed-in user creates a new group, becomes its organizer and receives an invite code to share with other users.

**Primary actor(s)**: Member

**Main success scenario**:

1. The user selects "Create group" on the group list.
2. The user enters a group name and selects "Create".
3. The system creates the group in the database with the user as its only member and organizer.
4. The system generates a unique 6-character invite code for the group.
5. The system displays the group page with the group name, the invite code for sharing and the member list.

**Failure scenario(s)**:

- 2a. The user enters an empty group name (or only spaces).
  - 2a1. The "Create" button stays disabled until the name contains a non-space character.

- 2b. The user enters a group name longer than 60 characters.
  - 2b1. The system displays "Invalid request: name must be a non-empty string of at most 60 characters".
  - 2b2. The user shortens the name and selects "Create" again.

- 3a. The user's device has no network connection.
  - 3a1. The system displays a "Network error" message and stays on the form so the user can try again when reconnected.

#### Use Case 3: Get Recommendations

**Description**: The meetup organizer requests candidate meeting locations based on participants' starting locations, travel modes, preferences, and Fair Credit. The system provides candidate locations and opens voting.

**Primary actor(s)**: Organizer, Google Maps Platform
    
**Main success scenario**:

1. The organizer opens a meetup in SETUP and selects "Start voting".
2. The system loads participants' starting locations, travel modes, preferences, and group-specific Fair Credit.
3. The system searches for suitable candidate places using Google Places and filters them by operational status and opening hours at the scheduled meetup instant in each venue's timezone. When available, dated current hours take priority within Google's seven-day window; otherwise the regular weekly schedule is used. Missing or invalid availability data is excluded.
4. The system obtains estimated travel times for the participants and candidates using Google Routes.
5. The system evaluates feasible candidates using the fairness and preference scoring rules.
6. The system selects the three highest-ranked candidates.
7. The system stores the recommendations, moves the meetup to VOTING and publishes the change to the meetup's live updates; the organizer's app opens the voting screen with the candidates.

**Failure scenario(s)**:

- 1a. The user is not the meetup's organizer.
  - 1a1. The app does not offer "Start voting", and the system rejects such a request with "Only the meetup organizer can search for places".

- 1b. The meetup is no longer in SETUP.
  - 1b1. The system rejects the request with "Places can only be searched while the meetup is in SETUP" to prevent replacing candidates after voting has started.
  - 1b2. The organizer may reload the meetup to view its current state.

- 2a. No participant has submitted participation details.
  - 2a1. The system displays "At least one member must submit their starting location before searching for places." and does not generate recommendations.
  - 2a2. The organizer may ask members to submit their details and select "Retry".

- 2b. The organizer has not submitted their own details.
  - 2b1. The system displays "The organizer must submit their starting location and preferences before searching for places." and does not generate recommendations.

- 2c. A participant's starting location is invalid.
  - 2c1. The system displays "A member has an invalid starting location. Please submit it again." and does not generate recommendations.

- 3a. Fewer than three nearby places can be confirmed open at the meetup time.
  - 3a1. The system displays "Fewer than 3 nearby places could be confirmed open at the meetup time. Try a different time, starting location or preference."
  - 3a2. The meetup remains in SETUP so participants can revise their inputs.

- 3b. Google Places fails.
  - 3b1. The system displays "Unable to retrieve nearby places. Please try again later."
  - 3b2. The meetup remains in SETUP, and the organizer may retry.

- 4a. Google Routes fails.
  - 4a1. The system displays "Unable to estimate travel times. Please try again later."
  - 4a2. The meetup remains in SETUP, and the organizer may retry.

- 5a. Fewer than three places have usable travel times.
  - 5a1. The system displays "Not enough nearby places found. At least 3 are needed to start voting."
  - 5a2. The meetup remains in SETUP, and the organizer may retry.

- 7a. Storing the recommendations fails.
  - 7a1. The system reports an error and returns the meetup from VOTING to SETUP so the organizer can retry.

#### Use Case 4: Finalize Meetup

**Description**: The Organizer closes the voting process, allowing the system to determine the winning location from the locked, weighted votes. Each member can then add the finalized meetup at the winning location to their own Google Calendar.

**Primary actor(s)**: Organizer, Member, Google Calendar
    
**Main success scenario**:

1. The Organizer selects "Finalize meetup" on the voting screen.
2. The system tallies the locked, weighted votes and determines the winning location (the largest share of locked vote weight).
3. The system saves the winning location as the finalized destination in the database and marks the meetup as finalized.
4. The system sends the result to every member who has the voting screen open, which then shows "Voting is closed" and marks the winner; the meetup page displays the winning location under "MEETING SPOT".
5. A member who entered their details selects "Add to Google Calendar" on the meetup page.
6. The app obtains a short-lived Google Calendar access token for the member from Google, asking for the member's consent the first time, and sends it to the system.
7. The system sends the meetup details (title, time, winning location) to the Google Calendar API, which creates the event in the member's primary calendar; the token is not stored.
8. The app displays "Added to your Google Calendar".

**Failure scenario(s)**:

- 1a. Not every participant has locked their vote.
  - 1a1. The system presents a "Finalize early?" prompt explaining that unlocked votes do not count. If the Organizer selects "Finalize anyway", the system proceeds to step 2. If they select "Keep voting", the system returns to the active voting screen.
- 1b. The Organizer's device has no network connection.
  - 1b1. The system displays a "Network error" message and leaves the poll open so the Organizer can try again when reconnected.
- 2a. Nobody has locked a vote.
  - 2a1. The system displays "Cannot finalize with no locked votes; pick a winner" and shows all three locations under "Pick the winner".
  - 2a2. The Organizer picks a location and the system proceeds to step 3 with it.
- 2b. A tie occurs in the weighted voting tally.
  - 2b1. The system selects, among the tied locations, the one whose single heaviest locked vote has the highest weight, which favours the members with more Fair Credit.
  - 2b2. If the locations are still tied, the system shows only the tied locations under "Pick the winner"; the Organizer picks one and the system proceeds to step 3.
- 3a. The meetup was already finalized, for example from another device at the same time.
  - 3a1. The system displays "Only a meetup in VOTING can be finalized" and keeps the first result.
- 6a. The member does not grant Google Calendar permission.
  - 6a1. The app displays "Google Calendar permission request was canceled." and keeps the "Add to Google Calendar" button so the member can try again.
- 7a. The Google Calendar API rejects the request or is unavailable (for example, the permission was revoked or the rate limit was reached).
  - 7a1. The system displays the reason, e.g. "Google Calendar API rate limit reached. Please retry in a few minutes.", and records nothing; the member may try again.
- 7b. The meetup is already in the member's calendar.
  - 7b1. The system displays "This meetup is already in your calendar" and does not create a second event.

#### Use Case 5: Share Live Location

**Description**: On the meetup day, a participant shares their location while travelling. The system shows their progress to other group members and records commute duration for later Fair Credit settlement.

**Primary actor(s)**: Member
    
**Main success scenario**:

1. The participant opens the finalized meetup at or after its scheduled time.
2. The participant selects "I am leaving" and confirms "Share and start trip" in the dialog explaining who can see their location.
3. The system requests location permission if it has not already been granted.
4. The participant grants permission.
5. The system records the commute start time and begins receiving location updates.
6. The system verifies the participant's access and shares location updates with authorized group members viewing the meetup.
7. Other group members view the participant's latest location and travel status.
8. The participant selects "I arrived," and the system records the arrival time, calculates commute duration, and stops location sharing.
9. During settlement, the system compares each participant's travel burden with the group average and updates Fair Credit.

**Failure scenario(s)**:

- 1a. The meetup's scheduled time has not arrived.
  - 1a1. The app does not offer "I am leaving", and the system rejects such a request with "You can start your trip when the meetup time arrives".

- 4a. The participant denies location permission.
  - 4a1. The system displays "Location permission is required to share your trip. No trip was started."
  - 4a2. The participant may grant permission later.

- 6a. The realtime connection is lost.
  - 6a1. The system displays "Live connection unavailable. Refreshing periodically."

- 6b. A location update fails.
  - 6b1. The system displays "Location update failed. Retrying..." and tries again while the trip is still active.

- 9a. Settlement is requested more than once.
  - 9a1. The system prevents duplicate Fair Credit changes for the same participant and meetup.

### **3.6. Screen Mock-ups**

The following mock-ups are visual references for selected group and meetup screens. They are similar to, but not exact representations of, the implemented UI. The current app is authoritative for appearance; Section 3.4 is authoritative for specified interactions and behavior.

| Group list | Group details |
|:---:|:---:|
| ![Group list mock-up](mockups/group_list.png) | ![Group details mock-up](mockups/group_details.png) |

| Meetup before leaving | Meetup on the way |
|:---:|:---:|
| ![Meetup before leaving mock-up](mockups/meetup_before_leaving.png) | ![Meetup on the way mock-up](mockups/meetup_on_the_way.png) |

| Meetup arrived |
|:---:|
| ![Meetup arrived mock-up](mockups/meetup_arrived.png) |

### **3.7. Non-Functional Requirements**

1. **Security and Access Control**
    - **Description**: All requests containing user data must use HTTPS in deployment. The backend must verify the user's session token and check group or meetup membership before returning protected data or accepting changes. Only the meetup organizer may start or finalize voting. Google access tokens must not be stored in the database.
    - **Justification**: MeetFair handles account information, group membership, travel details, and potentially live locations. Unauthorized users must not be able to view or change this information.

2. **Location Privacy**
    - **Description**: Live location sharing must require the user's explicit action and device permission. Only authorized members of the relevant group may receive location updates. Sharing must stop when the trip ends, and the app must make the sharing state visible to the user.
    - **Justification**: A person's current location is sensitive. Users need control over when it is shared and who can see it.

---

## 4. Designs Specification

### **4.1. Main Components**

1. **Users & Groups**
    - **Purpose**: Owns users, groups and meetups (the `users`, `groups` and `meetups` collections): Google sign-in and MeetFair sessions, profiles, groups and invite codes, meetups, each member's participation entry and the meetup status (SETUP -> VOTING -> FINALIZED -> SETTLED). We kept users, groups and meetups in one component rather than three, because every membership check needs all three and splitting them would create circular dependencies. Every other component depends on this one for access control.
    - **Interfaces**: 
        1. `POST /api/auth/google` — body `{ idToken: String }` -> `200 { token: String, user: User }`
            - **Purpose**: Verifies the Google ID token, creates or updates the user and returns a 7-day MeetFair session token.
        2. `GET /api/users/me` -> `200 User`
            - **Purpose**: Returns the signed-in user's account.
        3. `POST /api/groups` — body `{ name: String }` -> `201 Group`
            - **Purpose**: Creates a group with the caller as organizer and a unique 6-character invite code.
        4. `GET /api/groups` -> `200 List<Group>`
            - **Purpose**: Lists the caller's groups, newest first.
        5. `GET /api/groups/:groupId` -> `200 GroupDetail`
            - **Purpose**: Returns one group with each member's display name and role (members only).
        6. `POST /api/groups/join` — body `{ inviteCode: String }` -> `200 Group`
            - **Purpose**: Adds the caller to the group with that invite code.
        7. `POST /api/groups/:groupId/meetups` — body `{ title: String, scheduledAt: ISO-8601 String }` -> `201 Meetup`
            - **Purpose**: Creates a meetup in SETUP; the caller becomes its organizer. The scheduled time must be strictly in the future; invalid or past times return 400.
        8. `GET /api/groups/:groupId/meetups` -> `200 List<Meetup>`
            - **Purpose**: Lists the group's meetups.
        9. `GET /api/meetups/:meetupId` -> `200 Meetup`
            - **Purpose**: Returns one meetup with its participants, status and winner.
        10. `PUT /api/meetups/:meetupId/participation` — body `{ startLocation: { lat, lng, label }, travelMode: WALK | TRANSIT | DRIVE, preferences: List<Preference> }` -> `200 Meetup`
            - **Purpose**: Adds or replaces the caller's participation entry while the meetup is in SETUP.
        11. `Group requireMembership(String groupId, String userId)`
            - **Purpose**: Loads a group and checks that the user belongs to it (not found / forbidden otherwise); the access check used by every component.
        12. `Meetup getForMember(String meetupId, String userId)`
            - **Purpose**: Loads a meetup and checks that the user belongs to its group.
        13. `boolean transitionStatus(String meetupId, MeetupStatus from, MeetupStatus to)`
            - **Purpose**: Atomically moves a meetup from one status to the next; returns false if it was no longer in `from`, so concurrent requests cannot both succeed.
        14. `Meetup finalize(String meetupId, Place winner)`
            - **Purpose**: Atomically stores the winner and moves the meetup from VOTING to FINALIZED; returns null if it was no longer in VOTING.
        15. `boolean recordCalendarEvent(String meetupId, String userId, String eventId)`
            - **Purpose**: Records the Google Calendar event created for one participant; returns false if one was already recorded.
        16. `List<User> findByIds(List<String> userIds)` / `String displayNameOf(User user)`
            - **Purpose**: Resolves members' names for other components.
        17. `Profile getProfile(String userId)` / `Profile updateProfile(String userId, String displayName)`
            - **Purpose**: Account information and groups for the profile screen; a null display name returns to the Google name.
        18. `DELETE /api/groups/:groupId/members/:memberId` -> `204`
            - **Purpose**: Group organizer only; removes another member under the rules in Section 3.4. Returns 409 if the target participates in a VOTING or FINALIZED meetup.
        19. `POST /api/groups/:groupId/leave` -> `204`
            - **Purpose**: Leaves the group and removes the caller's participation details from SETUP meetups. The group organizer cannot leave; participation in a VOTING meetup returns 409.
        20. `DELETE /api/meetups/:meetupId/participation` -> `204`
            - **Purpose**: Removes the caller's participation while the meetup is in SETUP; otherwise returns 409. Publishes a silent participation update.
        21. `DELETE /api/groups/:groupId` -> `204`
            - **Purpose**: Group organizer only; deletes the group and associated data regardless of meetup statuses, and publishes deletion events.
        22. `DELETE /api/meetups/:meetupId` -> `204`
            - **Purpose**: Meetup organizer only; deletes the meetup and its recommendations and votes regardless of status, and publishes deletion events.
2. **Recommendation Engine**
    - **Purpose**: Owns the `recommendations` collection. It builds the meetup's candidate places from Google Places and Google Routes, scores them with our fairness algorithm, stores the top 3 and opens voting. All slow and failure-prone Google Maps calls are kept in this one component, and the scoring itself is pure functions (search area, preference mapping, scoring) that can be tested without Google.
    - **Interfaces**:
        1. `POST /api/meetups/:meetupId/recommendations/from-places` -> `201 Recommendation`
            - **Purpose**: Organizer only; finds places with Google Places, gets travel times with Google Routes, stores the top 3 candidates and moves the meetup from SETUP to VOTING.
        2. `POST /api/meetups/:meetupId/places/search` -> `200 { candidates: List<Place>, message: String | null }`
            - **Purpose**: Organizer only; previews nearby places without storing them or changing the meetup's status.
        3. `GET /api/meetups/:meetupId/recommendations` -> `200 Recommendation`
            - **Purpose**: Returns the meetup's candidates with their score breakdown (members only).
        4. `Recommendation getByMeetupId(String meetupId)`
            - **Purpose**: Gives Voting & Fairness the candidates a vote must refer to and the data of the winning place.
3. **Voting & Fairness**
    - **Purpose**: Owns the `votes`, `fairCredits`, `fairCreditEntries` and `settlements` collections: casting, changing and locking votes, the weighted tally, finalizing, adding the meetup to Google Calendar, and Fair Credit (balances plus a ledger explaining every change). Vote weights come from Fair Credit and settlement changes Fair Credit, so keeping both in one component keeps all fairness rules in one place. The rules themselves (vote weight, tally, credit change) are pure functions.
    - **Interfaces**:
        1. `PUT /api/meetups/:meetupId/vote` — body `{ placeId: String }` -> `200 Vote`
            - **Purpose**: Creates or changes the caller's vote while it is unlocked and the meetup is in VOTING.
        2. `POST /api/meetups/:meetupId/vote/lock` -> `200 Vote`
            - **Purpose**: Locks the caller's vote and stores its weight from their Fair Credit.
        3. `GET /api/meetups/:meetupId/votes` -> `200 { meetupId, candidates: [ { placeId, voteCount, weight, share } ], totalWeight, lockedCount, participantCount, myVote }`
            - **Purpose**: The current tally plus the caller's own vote.
        4. `POST /api/meetups/:meetupId/finalize` — optional body `{ placeId: String }` -> `200 Meetup`
            - **Purpose**: Organizer only; picks the winner by locked weight (the body names the winner only when nobody locked a vote or a tie remains) and moves the meetup to FINALIZED.
        5. `POST /api/meetups/:meetupId/calendar-event` — body `{ accessToken: String }` -> `201 { eventId, htmlLink }`
            - **Purpose**: Creates the finalized meetup in the caller's primary Google Calendar using their short-lived token, which is never stored.
        6. `GET /api/users/me/profile` / `PUT /api/users/me/profile` — body `{ displayName: String | null }` -> `200 Profile`
            - **Purpose**: The profile with the caller's Fair Credit in each group; updating changes the display name.
        7. `GET /api/groups/:groupId/fairness` -> `200 { groupId, members: [ { userId, name, balance } ], ledger: List<FairCreditEntry> }`
            - **Purpose**: Every member's Fair Credit in the group and the group's ledger, newest first.
        8. `double getVoteWeight(String userId, String groupId)` / `double getBalance(String userId, String groupId)` / `Map<String, double> getBalances(String groupId, List<String> userIds)`
            - **Purpose**: Members' Fair Credit in a group and the resulting vote weight; the Recommendation Engine uses the balances for fairness scoring.
        9. `List<FairCreditEntry> settleMeetup(String groupId, String meetupId, List<Trip> trips)`
            - **Purpose**: Turns each member's actual trip time into a credit change against the group average; a meetup can be settled only once. The Live Location component calls this during meetup settlement.
4. **Realtime Gateway**
    - **Purpose**: The Socket.IO server on the same host and port as the REST API. It authenticates each connection with the MeetFair session token, lets only group members join a meetup's room, and delivers the other components' updates to the members of that room. We chose a persistent socket over polling (slower and more wasteful) and over Firebase push notifications, which change the phone's state rather than the app's and may not be used for major functionality. Components publish through a small publisher interface, so they do not depend on Socket.IO.
    - **Interfaces**:
        1. Client -> server `meetup:join` / `meetup:leave` — `{ meetupId }`, acknowledged with `{ ok: true }` or `{ ok: false, error }`
            - **Purpose**: Start or stop receiving a meetup's live updates (members of its group only).
        2. Server -> client `vote:updated` (the tally), `meetup:status` (`{ meetupId, status, winner? }`), `recommendations:ready` (`{ meetupId }`)
            - **Purpose**: Live changes pushed to everyone in the meetup's room.
        3. Server -> client `trip:updated` — `{ meetupId }`
            - **Purpose**: Tells the room that trips changed. It carries no coordinates: clients reload them with `GET /api/meetups/:meetupId/trips`, which rechecks membership.
        4. `void publish(String room, String event, Object payload)`
            - **Purpose**: Lets the other components send an update to a meetup's room.
        5. Server -> client `meetup:updated` — `{ meetupId }`
            - **Purpose**: Requests a reload after participation is submitted, edited or removed. Connected meetup pages update without a Snackbar.
        6. Server -> client `meetup:deleted` — `{ meetupId, participantIds }`
            - **Purpose**: Invalidates the deleted meetup for everyone viewing it. The app uses the participant IDs to show a deletion notice only to members who submitted details; the meetup room is then closed.
        7. Server -> client `group:deleted` — `{ groupId }`; `group:meetup-deleted` — `{ groupId, meetupId, notifyParticipant }`
            - **Purpose**: Updates group pages through authenticated private user rooms, which the server joins automatically. Group deletion notifies group members; meetup deletion refreshes affected lists but shows a notice only when `notifyParticipant` is true.
        8. `void revokeUser(String userId)` / `void closeRoom(String room)`
            - **Purpose**: Disconnects a departing or removed member's existing sockets, or removes subscriptions to a deleted meetup. Reconnection and later API requests must pass membership checks again.
5. **Live Location**
    - **Purpose**: Owns the `trips` collection: each participant's commute timing and latest shared location for a finalized meetup. It stores only the latest coordinate, which is cleared when sharing stops. Locations are sent over REST, so every update and every read goes through the session and membership checks.
    - **Interfaces**:
        1. `POST /api/meetups/:meetupId/trip/start` -> `200 Trip`
            - **Purpose**: Starts the caller's trip, or resumes sharing on an active one; the server sets the start time once. Allowed for participants of a FINALIZED meetup from its scheduled time. A trip cancelled by leaving the group cannot be restarted, even after rejoining.
        2. `PUT /api/meetups/:meetupId/trip/location` — body `{ lat: Number, lng: Number, accuracyM: Number }` -> `200 Trip`
            - **Purpose**: Saves the caller's latest position while their sharing is active.
        3. `POST /api/meetups/:meetupId/trip/arrive` -> `200 Trip`
            - **Purpose**: Ends the caller's trip, computes the duration on the server and clears the location; repeating it changes nothing.
        4. `POST /api/meetups/:meetupId/trip/stop` -> `204`
            - **Purpose**: Pauses sharing and clears the location; the commute timer keeps running.
        5. `GET /api/meetups/:meetupId/trips` -> `200 List<Trip>`
            - **Purpose**: Lists the meetup's trips for group members, with a location only for members who are actively sharing.
        6. `POST /api/meetups/:meetupId/settle` -> `200 Meetup`
            - **Purpose**: Meetup organizer only; settles Fair Credit after the finalized meetup time has arrived and all started trips have ended. Before checking active trips, it cancels legacy ACTIVE trips whose users are no longer group members, preventing an unreachable arrival action from blocking settlement. Current members' ACTIVE trips still block settlement, including paused sharing. Uses tracked commute durations when available and stored or calculated travel estimates otherwise, saves those durations as a settlement snapshot so a retry uses the same values, then moves the meetup to SETTLED. Repeated successful requests are harmless.

### **4.2. Databases**

1. **MongoDB 7** (self-hosted, the `mongo` service in `docker-compose.yml`)
    - **Purpose**: Stores users, groups, meetups (with participants and winner), recommendations, votes, Fair Credit balances, the Fair Credit ledger, settlement snapshots and trips. Unique indexes enforce one vote per member per meetup, one recommendation per meetup, unique invite codes and settling a meetup at most once. MongoDB's nested documents (members in a group, participants in a meetup, candidates in a recommendation) match our data better than relational tables, and Mongoose provides schemas and validation.

### **4.3. External Modules**

1. **Google Sign-In / Google Identity Services**
    - **Purpose**: Signs users in. The Android Credential Manager returns a Google ID token, which the back end verifies with `google-auth-library`. The Identity `AuthorizationClient` also gives the app a short-lived `calendar.events` access token. We use Google because every target user has a Google account and we need the same account for Google Calendar.
2. **Google Maps Platform (Places API (New), Routes API)**
    - **Purpose**: Nearby Search finds candidate places that match the meetup type; Routes `computeRouteMatrix` returns every member's travel time to every candidate in one batched request per travel mode. It provides travel times for all three modes, including transit.
    - **Opening-hours filtering**: The Places field mask additionally requests business status, timezone ID, and regular/current opening periods. The backend checks the meetup's stored instant in the place-local timezone, including overnight periods and exclusive closing times; it does not substitute `openNow` for the scheduled time. Opening-hours fields trigger the [Nearby Search Enterprise SKU](https://developers.google.com/maps/documentation/places/web-service/nearby-search). Availability is based on Google's published data, not a guarantee of actual access. Dates beyond the current-hours window use the regular schedule and cannot account for unpublished future exceptions. Previously stored recommendations are not automatically regenerated by this change.
3. **Google Calendar API**
    - **Purpose**: `events.insert` adds the finalized meetup to the member's own primary calendar, using the member's token, so MeetFair never needs calendar credentials of its own.
4. **Google Maps SDK for Android and Android Geocoder**
    - **Purpose**: The Maps SDK displays the starting-location picker and supports map gestures, named-place taps and marker dragging. Android Geocoder resolves a selected coordinate to a readable address when available; address resolution failure does not prevent coordinate selection. The Android Maps key is configured separately from backend credentials.

### **4.4. Frameworks and Libraries**

1. **Express 5**
    - **Purpose**: HTTP server and routing for the REST API.
    - **Reason**: Minimal and widely used. Version 5 forwards errors from async handlers to our error handler, so routes need no try/catch.
2. **Mongoose**
    - **Purpose**: MongoDB access, schemas and indexes.
    - **Reason**: Schemas and validation over a schemaless database; atomic conditional updates for status changes.
3. **Socket.IO** (server) and **socket.io-client** (Android, Java client)
    - **Purpose**: Live updates over WebSockets with rooms per meetup.
    - **Reason**: Rooms, acknowledgements and automatic reconnection, on the same port as the REST API.
4. **google-auth-library**
    - **Purpose**: Verifies Google ID tokens (signature, expiry, audience).
    - **Reason**: Google's official library, so we do not implement token verification ourselves.
5. **googleapis** (Calendar only)
    - **Purpose**: Calls Google Calendar `events.insert`.
    - **Reason**: Google's official client: typed requests and error codes.
6. **dotenv**
    - **Purpose**: Loads `backend/.env`.
    - **Reason**: Keeps secrets out of the code.
7. **Jest, ts-jest, Supertest, @shelf/jest-mongodb**
    - **Purpose**: Back-end unit and API tests against a real in-memory MongoDB.
    - **Reason**: Required by the course; jest-mongodb removes the need for a separate test database.
8. **Kotlin, Jetpack Compose, Material Design 3**
    - **Purpose**: Android UI.
    - **Reason**: Required by the course; declarative UI with unidirectional data flow.
9. **OkHttp**
    - **Purpose**: HTTP client for the REST API (JSON built and parsed with the Android SDK's `org.json`).
    - **Reason**: Small and reliable; an interceptor adds the session token and signs the user out on 401.
10. **androidx.credentials, credentials-play-services-auth, googleid**
    - **Purpose**: Google Sign-In with Credential Manager.
    - **Reason**: Google's current recommended sign-in API.
11. **play-services-auth (Identity AuthorizationClient), kotlinx-coroutines-play-services**
    - **Purpose**: Gets the Google Calendar access token and awaits Play Services tasks from coroutines.
    - **Reason**: The official way to request an OAuth scope on Android without handling credentials ourselves.
12. **Docker Compose**
    - **Purpose**: Runs the back end with MongoDB on the cloud VM.
    - **Reason**: One-command deployment for the team and TAs.
13. **Maps Compose**
    - **Purpose**: Embeds the Google Maps SDK in the Compose participation form's location picker.
    - **Reason**: Provides map, camera and marker interactions within the existing Compose UI.
14. **Material 3 DatePicker and PullToRefreshBox; Android NumberPicker**
    - **Purpose**: Calendar date selection, pull-to-refresh on data pages, and separate scrolling hour and minute wheels.
    - **Reason**: Uses existing UI components for date, time and refresh interactions without requiring users to type dates or times.

### **4.5. Dependencies Diagram**

![Dependencies diagram](images/Dependency_Diagram.png)

An arrow A -> B means A calls B's interface. No two back-end components depend on each other, so every arrow points one way:

- **Voting & Fairness** is shown as its two modules. **Fair Credit** (balances, ledger, settlement, vote weight) depends only on Users & Groups, and the Recommendation Engine reads balances from it. **Voting** reads the candidates from the Recommendation Engine. So the Recommendation Engine never depends on Voting.
- **Realtime Gateway** is also shown as its two modules. Users & Groups, the Recommendation Engine, Voting & Fairness and Live Location only call the **Event Publisher**, which has no dependencies. Users & Groups calls it when members leave or are removed, participation is withdrawn, or groups and meetups are deleted. The **Socket.IO server** registers itself with the publisher (`setTransport`) and uses Users & Groups to check room membership, so Users & Groups never depends on the socket layer.

The SVG source is [`res/Dependency_Diagram.svg`](res/Dependency_Diagram.svg).

### **4.6. Use Case Sequence Diagram (5 Most Major Use Cases)**

1. [**Vote on a Candidate and Lock Vote**](#use-case-1-vote-on-a-candidate-and-lock-vote)\
![Vote on a Candidate and Lock Vote sequence diagram](images/SD_UC1_Vote_on_a_Candidate.png)
2. [**Create Group**](#use-case-2-create-group)\
![Create Group sequence diagram](images/SD_UC2_Create_Group.png)
3. [**Get Recommendations**](#use-case-3-get-recommendations)\
![Get Recommendations sequence diagram](images/SD_UC3_Get_Recommendations.png)
4. [**Finalize Meetup**](#use-case-4-finalize-meetup)\
![Finalize Meetup sequence diagram](images/SD_UC4_Finalize_Meetup.png)
5. [**Share Live Location**](#use-case-5-share-live-location)\
![Share Live Location sequence diagram](images/SD_UC5_Share_Live_Location.png)

The Mermaid sources of these diagrams are in [`res/`](res/).

### **4.7. Design of Non-Functional Requirements**

1. [**Security and Access Control**](#37-non-functional-requirements)
    - **Implementation**: The back end serves HTTPS (and Socket.IO over WSS) on its port when a TLS certificate and private key are installed in `backend/certs/`; for deployment, the app's `API_BASE_URL` is set to the server's `https://` address. The Android app sends the MeetFair session token with protected API requests; the backend verifies the token and checks group or meetup membership before accessing protected resources. Organizer-only operations perform an additional role check. Google Calendar access tokens are used for individual requests and are not stored in the database.

2. [**Location Privacy**](#37-non-functional-requirements)
    - **Implementation**: Request Android location permission only when a participant starts sharing their commute, after a confirmation dialog explains who can see their location. Send location updates over the authenticated REST API, and check group membership for every update and read. Realtime events carry no coordinates. Show when sharing is active with a system notification, stop updates when the trip ends, and store only the latest coordinate plus the data needed to calculate travel time.
