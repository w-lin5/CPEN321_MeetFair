# Requirements and Design

## 1. Change History

| **Change Date**   | **Modified Sections** | **Rationale** |
| ----------------- | --------------------- | ------------- |
| _Nothing to show_ |

---

## 2. Project Description

[WRITE_PROJECT_DESCRIPTION_HERE]

---

## 3. Requirements Specification

### **3.1. List of Features**
1. **Sign in with Google**: Users log in with their Google account.
2. **Groups and meetups**: Any user can create a group and invite others; each user enters start location, meeting purpose (meal, study, coffee chat, outdoors, fun) and optional place types.
3. **Fair spot recommendation**: Backend collects candidate places (Places API), gets each user's travel time (Distance Matrix), then scores every candidate with our own fairness + preference algorithm and returns the top 3 (if there exist 3).
4. **Weighted voting**: Users vote on the top 3, weighted by each user's fairness debt (how much extra they actually travelled in past meetups). Votes appear live; the winner becomes a Google Calendar event.
5. **Live commute tracking**: On meetup day, members tap "I'm leaving" and share live location; the group sees who is on the way. The commute ends when they tap "I'm here," or automatically when their location reaches the venue. The actual commute time updates their fairness debt for the next meetup. If someone never starts tracking, we fall back to the Google Maps estimate.
### **3.2. Use Case Diagram**
![Use Case Diagram](images\Use_Case_Diagram.jpg)

### **3.3. Actors Description**
1. **User**: A signed-in user who creates or joins a meetup, enters start location and preferences, votes, and shares live location on the way so their actual commute time is recorded.

### **3.4. Use Case Description**
- Use cases for feature 1: Sign In
1. **Sign In**: A user authenticates into the app using their Google account to access their groups and past fairness history.
2. **Sign out**: A user securely ends their active session on the device.
- Use cases for feature 2: Groups and Meetups
1. **Create Group**: An Organizer creates a new group in the app and generates a code to invite other members.
2. **Create Meetup**: An organizer creates a new meetup associated with their group, allowing members to add their preferences.
3. **Join Group**: A member uses a code to associate their account with an existing group.
4. **Enter Start Location & Preferences**: A member inputs their starting address, transit mode, and meetup preferences (e.g., cafe, library) for an upcoming meetup.
4. **Import Calendar**: A member syncs their Google Calendar with ther user profile to automatically block out busy periods.
- Use cases for feature 3: Fair Spot Recommendation
1. **Get Recommendations**: The system collects all group member's locations and preferences, calculates commute fairness, and outputs the top 3 candidate locations.
- Use cases for feature 4: Voting and Fairness
1. **Vote on a Candidate**: A member casts a vote for their preferred spot from the top 3 recommendations, with their vote's weight adjusted by their current fairness debt.
2. **Finalize Meetup**: The organizer closes the voting, the system logs the winning location as a Google Calendar event.
3. **View Fairness History**: A member checks their profile for a specific group to see their accumulated fairness debt and past travel sacrifices.
- Use cases for feature 5: Live Location
1. **Share Live Location**: A member opts in on the day of the event to broadcast their real-time device location to the group via WebSockets and to keep track of their actual commute time.
2. **View Members' Location**: A member opens the live map to check the current transit progress of other group members heading to the finalized spot.

### **3.5. Formal Use Case Specifications (5 Most Major Use Cases)**
<a name="uc1"></a>

#### Use Case 1: Sign In

**Description**: ...

**Primary actor(s)**: ... 
    
**Main success scenario**:
1. ...
2. ...

**Failure scenario(s)**:
- 1a. ...
    - 1a1. ...
    - 1a2. ...

- 1b. ...
    - 1b1. ...
    - 1b2. ...
                
- 2a. ...
    - 2a1. ...
    - 2a2. ...

<a name="uc2"></a>

#### Use Case 2: Create or Join Group

**Description**: A user creates a new group and receives an invite code to share, or joins an existing group by entering an invite code, associating their account with that group so they can participate in future meetups.

**Primary actor(s)**: User

**Main success scenario (Create)**:
1. The user selects the option to create a group.
2. The user enters a group name.
3. The system creates the group in the database and assigns the user as a member.
4. The system generates a unique invite code that serves as the group's identifier.
5. The system displays the group page with the invite code for sharing.

**Main success scenario (Join)**:
1. The user selects the option to join a group.
2. The user enters the invite code shared by the group creator.
3. The system validates the invite code against the database.
4. The system adds the user to the group's member list.
5. If the user already has a group with the same name, the system displays both groups as "Group Name (distinct member name)" to differentiate them.
6. The system displays the group page with existing members and any active meetups.

**Failure scenario(s)**:
- Create 2a. The user enters an empty group name.
    - 2a1. The system displays an error message and prompts the user to enter a valid name.

- Create 2b. The user already has a group with the same name.
    - 2b1. The system creates the group and displays both groups as "Group Name (distinct member name)" to differentiate them.

- Create 3a. The user's device has no network connection.
    - 3a1. The system displays a "No network connection" error and prompts the user to try again when reconnected.

- Join 2a. The user enters an invalid invite code.
    - 2a1. The system displays an error message indicating the code is invalid.
    - 2a2. The system prompts the user to re-enter a valid code.

- Join 3a. The user is already a member of this group.
    - 3a1. The system displays a message informing the user they are already in the group.
    - 3a2. The system navigates the user to the existing group page.

- Join 3b. The user's device has no network connection.
    - 3b1. The system displays a "No network connection" error and prompts the user to try again when reconnected.

- Join 4a. The group has reached its maximum member limit.
    - 4a1. The system displays an error message that the group is full.
    - 4a2. The system returns the user to the join group screen.

<a name="uc3"></a>

#### Use Case 3: Get Recommendations

**Description**: ...

**Primary actor(s)**: ... 
    
**Main success scenario**:
1. ...
2. ...

**Failure scenario(s)**:
- 1a. ...
    - 1a1. ...
    - 1a2. ...

- 1b. ...
    - 1b1. ...
    - 1b2. ...
                
- 2a. ...
    - 2a1. ...
    - 2a2. ...

<a name="uc4"></a>

#### Use Case 4: Finalize Meetup

**Description**: The Organizer closes the voting process, allowing the system to determine the winning location, update each member's fairness debt based on their finalized commute, and export the event to the members' calendars.

**Primary actor(s)**: Organizer, Google Calendar API 
    
**Main success scenario**:
1. The Organizer selects the option to close voting and finalize the meetup.
2. The system tallies the weighted votes and determines the winning location.
3. The system saves the winning location as the finalized destination in the database.
4. The system sends the meetup details (location, time, attendees) to the Google Calendar API to create a calendar event.
5. The system marks the meetup as finalized and sends a notification to all group members with the final result.

**Failure scenario(s)**:
- 1a. The Organizer attempts to finalize with zero votes cast.
    - 1a1. The system blocks finalization and displays an error prompt, requiring the Organizer to either cast a vote themselves or manually select a winner to proceed.
- 1b. The Organizer attempts to finalize meetup before all members have voted.
    - 1b1. The system presents a warning prompt asking the Organizer to confirm they want to force-close the poll early. If confirmed, the system proceeds to step 2. If cancelled, the system returns to the active voting screen.
- 1c. The Organizer's device has no network connection.
    - 1c1. The system displays a "No network connection" error and leaves the poll open, prompting the Organizer to try again when reconnected.
- 2a. A tie occurs in the weighted voting tally.
    - 2a1. The system reviews the members who voted for the tied locations and selects the candidate chosen by the member with the highest individual fairness debt.
    - 2a2. If there is more than one member with the same fairness debt, the system prompts the Organizer to manually select the final winner to break the tie.
- 3a. The data fails to upload to the database.
    - 3a1. The system aborts the finalization, rolls back any partial database changes to prevent data corruption, and alerts the Organizer to retry.
- 4a. The external calendar sync fails for one or more members.
    - 4a1. The system successfully creates the calendar event for all other authorized members and sends a targeted notification to the affected member with a prompt to re-authenticate and sync manually.
    - 4a2. The finalized event remains in the user's database and is available for a manual sync later.
- 5a. Push notification service fails.
    - 5a1. The system updates the meetup status to "Finalized" within the app UI so active users see it, queues the notification payload, and silently retries sending the push notification in the background.

<a name="uc5"></a>

#### Use Case 5: Share Live Location

**Description**: ...

**Primary actor(s)**: ... 
    
**Main success scenario**:
1. ...
2. ...

**Failure scenario(s)**:
- 1a. ...
    - 1a1. ...
    - 1a2. ...

- 1b. ...
    - 1b1. ...
    - 1b2. ...
                
- 2a. ...
    - 2a1. ...
    - 2a2. ...
    
### **3.6. Screen Mock-ups**


### **3.7. Non-Functional Requirements**
<a name="nfr1"></a>

1. **[WRITE_NAME_HERE]**
    - **Description**: ...
    - **Justification**: ...
2. ...

---

## 4. Designs Specification
### **4.1. Main Components**
1. **[WRITE_NAME_HERE]**
    - **Purpose**: ...
    - **Interfaces**: 
        1. ...
            - **Purpose**: ...
        2. ...
2. ...


### **4.2. Databases**
1. **[WRITE_NAME_HERE]**
    - **Purpose**: ...
2. ...


### **4.3. External Modules**
1. **[WRITE_NAME_HERE]** 
- **Purpose**: ...
2. ...


### **4.4. Frameworks and Libraries**
1. **[WRITE_NAME_HERE]**
    - **Purpose**: ...
    - **Reason**: ...
2. ...


### **4.5. Dependencies Diagram**


### **4.6. Use Case Sequence Diagram (5 Most Major Use Cases)**
1. [**[WRITE_NAME_HERE]**](#uc1)\
[SEQUENCE_DIAGRAM_HERE]
2. [**Create or Join Group**](#uc2)

    **Create Group**

    ![Create Group sequence diagram](images/UC2_Create_Group_Sequence_Diagram.jpg)

    **Join Group**

    ![Join Group sequence diagram](images/UC2_Join_Group_Sequence_Diagram.png)


### **4.7. Design of Non-Functional Requirements**
1. [**[WRITE_NAME_HERE]**](#nfr1)
    - **Implementation**: ...
2. ...
