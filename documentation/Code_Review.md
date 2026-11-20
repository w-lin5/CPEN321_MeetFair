# Code Review

## 1. Change History

| **Change Date**   | **Modified Sections** | **Rationale** |
| ----------------- | --------------------- | ------------- |
| 2026-11-04 | 2.1-2.4 | CP2 updates: documented the Codacy automated code review results, including the remaining unfixed issues per category and code pattern, with justifications. |
| 2026-11-19 | 2.1-2.4, 3.1, 3.3 | Updated the Codacy results, commit hash and screenshots. Recorded Peer Review with Ledger, its scores and reported faults, along with the fixes, regression tests and commit references.

---

## 2. Automated Code Review Results

### 2.1. Commit Hash Where Codacy Ran

`007f4557c6db951b38db8e6599a6d03a48c1b761`

### 2.2. Unfixed Issues per Codacy Category

![Codacy's Category Breakdown table in Overview](images/Codacy_Category_Breakdown.PNG)

### 2.3. Unfixed Issues per Codacy Code Pattern

![Codacy's Issues page](images/Codacy_Issues.PNG)

### 2.4. Justifications for Unfixed Issues

- **No unfixed issues**

---

## 3. Manual Code Review Results

### 3.1. Review You Performed

- **Peer group reviewed:** Ledger
- **Commit SHA reviewed:** `01eb5991413990534a6c675b182d452e2bd7445e`
- **Date the artifacts were exchanged:** 2026-11-04
- **Review report submitted:** `CP3_Ledger_Review.pdf`

| **Section**          | **Item**                                                                                                                                             | **Score** |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| Manual code review   | Code is maintainable (well-documented, uses self-explanatory variable names, does not have complicated and long methods, magic numbers, etc.), efficient, handles error cases well | 7/10      |
| Manual test review   | Tests are complete for one end-to-end feature and all interfaces of one component, errors and edge cases are thoroughly tested, correct assertions are used | 6/10      |
| Manual test review   | Test code is maintainable and well-structured                                                                                                          | 6/10      |
| Manual test review   | Test implementation matches the requirements and design                                                                                                | 5/10      |
| Manual test review   | Non-functional requirements are tested well                                                                                                            | 5/10      |
| Manual test review   | Tests achieve high coverage and cases of < 100% coverage are well-justified                                                                            | 4/10      |
| Manual test review   | All back-end tests can be run automatically                                                                                                            | 8/10      |
| Automated code review| Codacy runs with the required setup                                                                                                                    | 6/10      |
| Automated code review| All remaining Codacy issues are well-justified                                                                                                          | 10/10     |

**Major faults reported**

#### Fault 1: Editing a historical expense requires changing its allocation after a member leaves

- **Description:** After member B leaves a group, member A cannot change only the description of an existing expense while preserving its original split. The editor requires removing the former participant before saving. For a CAD 60 expense originally split equally, this changes A's allocation from CAD 30 to CAD 60. Editing descriptive information should preserve the financial allocation of a historical expense.
- **Steps to reproduce:**
  1. Create a group with members A and B.
  2. Add a CAD 60 expense, paid by A and split equally: CAD 30 each.
  3. Have B leave the group.
  4. As A, edit the expense and change only its description.
  5. Observe that saving requires removing the former participant.
  6. Remove B from the draft and observe that A's allocated share changes from CAD 30 to CAD 60.
- **Screenshots:** The remaining member's share changes from CAD 30.00 (left) to CAD 60.00 (right) after removing the former participant from the draft.

  ![Fault 1: Historical expense allocation changes after removing a former participant](images/Fault1.png)

- **Severity:** High.

#### Fault 2: Google sign-in fails after biometric unlock is enabled

- **Description:** With biometric unlock enabled, Google sign-in opens account selection, then displays "Ledger is locked" and "Sign-in request cancelled by Ledger". Unlocking returns the user to the sign-in page instead of completing Google sign-in. Username-and-password sign-in still works.
- **Steps to reproduce:**
  1. Navigate to Me -> Security -> Require biometric unlock and enable the setting.
  2. Ensure the user is signed out.
  3. Select Sign In with Google and choose a Google account.
  4. Observe the "Ledger is locked" screen requiring biometric unlock.
  5. Unlock and observe that the app returns to the sign-in page without completing Google sign-in.
- **Screenshots:** The biometric lock screen (left) and the sign-in page shown after unlocking (right).

  ![Fault 2: Biometric unlock returns the user to the sign-in page](images/Fault2.png)

- **Severity:** High.

### 3.2. Review You Received

- **Reviewing group:** Ledger
- **Commit SHA that was reviewed:** `5a1db43039f4dde53e37ae97aa2f3441e6749cc9`
- **Review report received:** `CP3_MeetFair_Review.pdf`

| **Section**          | **Item**                                                                                                                                             | **Score** |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| Manual code review   | Code is maintainable, efficient, handles error cases well                                                                                               | 7/10      |
| Manual test review   | Tests are complete, errors and edge cases are thoroughly tested, correct assertions are used                                                            | 7/10      |
| Manual test review   | Test code is maintainable and well-structured                                                                                                          | 8/10      |
| Manual test review   | Test implementation matches the requirements and design                                                                                                | 8/10      |
| Manual test review   | Non-functional requirements are tested well                                                                                                            | 6/10      |
| Manual test review   | Tests achieve high coverage and cases of < 100% coverage are well-justified                                                                            | 6/10      |
| Manual test review   | All back-end tests can be run automatically                                                                                                            | 4/10      |
| Automated code review| Codacy runs with the required setup                                                                                                                    | 6/10      |
| Automated code review| All remaining Codacy issues are well-justified                                                                                                          | 8/10      |

**Major faults reported against your project**

**Fault 1**
- **Description:** A closed restaurant can be selected as the final meetup venue. MeetFair recommended a restaurant (in our example, Ding Tai Feng at 1132 Alberni St, Vancouver) for a meetup scheduled at 01:03 a.m. Vancouver Time, which would bevclosed at the time. Both participants were subsequently able to vote for the restaurantvand have it become the meetup venue. This is a functional expectation arising from MeetFair’s goal of recommending feasible
meeting locations.
- **Severity:** High (core functional requirement failure)

**Fault 2**
- **Description:** MeetFair allows members to leave a group while a meetup is FINALIZED (Section 3.4, Requirements and Design) and describes Fair Credit settlement after trips are completed (Section 4.1, Requirements and Design). In implementation, leaving the group disables location sharing but does not end the ACTIVE trip. Settlement rejects any meetup containing an ACTIVE trip, and the member cannot click “Arrival” after leaving the group.
- **Severity:** High (blocks a core backend business process for all members)

### 3.3. Actions Taken in Response to the Review

| **Finding** | **Action Taken** | **Commit / Location in Git** |
| ----------- | ---------------- | ---------------------------- |
| **Fault 1: A closed restaurant can become the final meetup venue.** | Passed the meetup's scheduled time to the Places client and filtered venues before scoring using their local timezone, business status and structured opening hours. The filter handles overnight periods, closing boundaries and available dated exceptions; closed venues and venues with unknown availability are excluded. Fewer than three confirmed-open venues produce a clear error and leave the meetup in SETUP. Regression tests reproduce the 01:03 Vancouver case and verify that the excluded restaurant cannot receive votes or be finalized. | Commit [`1e4e379`](https://github.com/w-lin5/CPEN321_MeetFair/commit/1e4e379). |
| **Fault 2: Leaving a group during an ACTIVE trip can prevent settlement.** | Kept the existing permission to leave after voting ends. Leaving now revokes membership before trip cleanup, marks only the departing member's unfinished trips in that group as CANCELLED, and clears sharing and coordinates. It does not fabricate arrival or a completed commute duration; settlement uses the existing estimated-duration fallback. Completed ARRIVED trips retain their tracked durations. Settlement also repairs legacy ACTIVE trips belonging to former members, while still blocking on current members' ACTIVE trips, including paused sharing. The app explains cancellation and prevents restarting or marking arrival on a cancelled trip after rejoining. | Commit [`d330d1c`](https://github.com/w-lin5/CPEN321_MeetFair/commit/d330d1c). |

**Findings not acted on:** None of the two major faults listed in Section 3.2 was left unaddressed.
