# Testing

## 1. Change History

| **Change Date**   | **Modified Sections** | **Rationale** |
| ----------------- | --------------------- | ------------- |
| _Nothing to show_ |

---

## 2. Back-end Test Specification: APIs

### 2.1. Locations of Back-end Tests and Instructions to Run Them

#### 2.1.1. Tests

| **Interface**                 | **Describe Group Location, No Mocks**                | **Describe Group Location, With Mocks**            | **Mocked Components**              |
| ----------------------------- | ---------------------------------------------------- | -------------------------------------------------- | ---------------------------------- |
| **POST /user/login**          | [`tests/no-mock/authenticationLogin.test.js#L1`](#) | [`tests/mock/authenticationLogin.test.js#L1`](#) | Google Authentication API, User DB |
| **POST /study-groups/create** | ...                                                  | ...                                                | Study Group DB                     |
| ...                           | ...                                                  | ...                                                | ...                                |
| ...                           | ...                                                  | ...                                                | ...                                |

#### 2.1.2. Commit Hash Where Tests Run

`[Insert Commit SHA here]`

#### 2.1.3. Explanation on How to Run the Tests

1. **Clone the Repository**:

   - Open your terminal and run:
     ```
     git clone https://github.com/example/your-project.git
     ```

2. **...**

### 2.2. GitHub Actions Configuration Location

`~/.github/workflows/backend-tests.yml`

### 2.3. Jest Coverage Report Screenshots With Mocks

_(Placeholder for Jest coverage screenshot with mocks enabled)_

### 2.4. Jest Coverage Report Screenshots Without Mocks

_(Placeholder for Jest coverage screenshot without mocks)_

---

## 3. Tests of Non-Functional Requirements

### 3.1. Test Locations in Git

| **Non-Functional Requirement**  | **Location in Git**                              |
| ------------------------------- | ------------------------------------------------ |
| **Performance (Response Time)** | [`backend/tests/nfr/response_time.test.js`](#) |
| **Chat Data Security**          | [`backend/tests/nfr/chat_security.test.js`](#) |

### 3.2. Test Verification and Logs

- **Performance (Response Time)**

  - **Verification:** This test suite simulates multiple concurrent API calls using Jest along with a load-testing utility to mimic real-world user behavior. The focus is on key endpoints such as user login and study group search to ensure that each call completes within the target response time of 2 seconds under normal load. The test logs capture metrics such as average response time, maximum response time, and error rates. These logs are then analyzed to identify any performance bottlenecks, ensuring the system can handle expected traffic without degradation in user experience.
  - **Log Output**
    ```
    [Placeholder for response time test logs]
    ```

- **Chat Data Security**
  - **Verification:** ...
  - **Log Output**
    ```
    [Placeholder for chat security test logs]
    ```

---

### 4. Front-end Test Specification

### 4.1. Location in Git of Front-end Test Suite:

`frontend/app/src/androidTest/java/com/example/cpen321application/e2e/`

The suite is run with `./scripts/run-frontend-e2e-tests.sh` (macOS/Linux) or `./scripts/run-frontend-e2e-tests.ps1` (Windows), with an emulator running, a Google test account added to the emulator, and the back end reachable at `API_BASE_URL`. UI elements are located by the test tags defined in `ui/common/TestTags.kt`; the Google account picker is a system window and is driven with UI Automator.

### 4.2. Tests

- **Use Case: Sign In**

  - **Expected Behaviors:**
    | **Scenario Steps** | **Test Case Steps** |
    | ------------------ | ------------------- |
    | 1. The user opens the app while signed out. | Clear the saved session.<br>Launch the app. |
    | 2. The app shows the sign-in screen with the app name, the tagline and a "Sign in with Google" button. | Check that a text "MeetFair" is present on screen.<br>Check that a text "Equal effort, this meetup and the next" is present on screen.<br>Check that the button with test tag `sign_in_button` and text "Sign in with Google" is present and enabled.<br>Check that no node with test tag `error_text` is present. |
    | 3. The user taps "Sign in with Google". | Click the button with test tag `sign_in_button`. |
    | 4. The app shows the Google account picker. | Check that the Google account picker is displayed. |
    | 4a. The user dismisses the account picker without choosing an account. | Press the back button to dismiss the account picker. |
    | 4a1. The app stays on the sign-in screen and shows an error message; the user can try again. | Check that a node with test tag `error_text` is displayed.<br>Check that the button with test tag `sign_in_button` is present and enabled. |
    | 5. The user selects their Google account. | Select the Google test account in the account picker. |
    | 6. The app sends the Google ID token to the back end; the sign-in button is disabled while the request is in progress. | Check that the button with test tag `sign_in_button` is disabled. |
    | 6a. The back end rejects the Google ID token. | Start the back end with a `GOOGLE_CLIENT_ID` that does not match the app's client ID.<br>Repeat steps 3 and 5. |
    | 6a1. The app stays on the sign-in screen and shows "Invalid Google ID token". | Check that a node with test tag `error_text` has the text "Invalid Google ID token".<br>Check that the button with test tag `sign_in_button` is enabled. |
    | 6b. The device cannot reach the back end. | Stop the back end.<br>Repeat steps 3 and 5. |
    | 6b1. The app stays on the sign-in screen and shows a network error message. | Check that a node with test tag `error_text` has text starting with "Network error".<br>Check that the button with test tag `sign_in_button` is enabled. |
    | 7. The app saves the session and shows the group list with the user's name. | Check that a text "Signed in as <test account name>" is present on screen.<br>Check that the button with test tag `sign_out_button` is present.<br>Check that no node with test tag `sign_in_button` is present. |
    | 8. The user closes and reopens the app and is still signed in. | Close and relaunch the app.<br>Check that a text "Signed in as <test account name>" is present on screen.<br>Check that no node with test tag `sign_in_button` is present. |

  - **Test Logs:**
    ```
    [Placeholder for Espresso test execution logs]
    ```


- **Use Case: ...**

  - **Expected Behaviors:**

    | **Scenario Steps** | **Test Case Steps** |
    | ------------------ | ------------------- |
    | ...                | ...                 |

  - **Test Logs:**
    ```
    [Placeholder for Espresso test execution logs]
    ```

- **...**
