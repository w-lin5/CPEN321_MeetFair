package com.example.cpen321application.ui.common

/**
 * Test tags applied via `Modifier.testTag`. Production code and androidTest sources both reference
 * these constants, so a tag change cannot silently break a test.
 */
object TestTags {
    const val SIGN_IN_BUTTON = "sign_in_button"
    const val SIGN_OUT_BUTTON = "sign_out_button"
    const val ERROR_TEXT = "error_text"
    const val RETRY_BUTTON = "retry_button"
    const val LOADING = "loading"
    const val EMPTY = "empty"
    const val BACK_BUTTON = "back_button"
    const val CREATE_GROUP_BUTTON = "create_group_button"
    const val JOIN_GROUP_BUTTON = "join_group_button"
    const val GROUP_ITEM = "group_item"
    const val GROUP_NAME_INPUT = "group_name_input"
    const val CREATE_GROUP_SUBMIT = "create_group_submit"
    const val INVITE_CODE_INPUT = "invite_code_input"
    const val JOIN_GROUP_SUBMIT = "join_group_submit"
    const val INVITE_CODE_TEXT = "invite_code_text"
    const val MEMBER_ITEM = "member_item"
    const val NEW_MEETUP_BUTTON = "new_meetup_button"
    const val MEETUP_ITEM = "meetup_item"
    const val MEETUPS_EMPTY = "meetups_empty"
    const val MEETUP_TITLE_INPUT = "meetup_title_input"
    const val MEETUP_TIME_INPUT = "meetup_time_input"
    const val CREATE_MEETUP_SUBMIT = "create_meetup_submit"
    const val MEETUP_TITLE_TEXT = "meetup_title_text"
    const val MEETUP_STATUS_TEXT = "meetup_status_text"
    const val PARTICIPATION_STATUS_TEXT = "participation_status_text"
    const val PARTICIPATION_BUTTON = "participation_button"
    const val LAT_INPUT = "lat_input"
    const val LNG_INPUT = "lng_input"
    const val LABEL_INPUT = "label_input"
    const val PARTICIPATION_SUBMIT = "participation_submit"
    const val PROFILE_BUTTON = "profile_button"
    const val PROFILE_NAME_TEXT = "profile_name_text"
    const val DISPLAY_NAME_INPUT = "display_name_input"
    const val SAVE_DISPLAY_NAME_BUTTON = "save_display_name_button"
    const val RESET_DISPLAY_NAME_BUTTON = "reset_display_name_button"
    const val PROFILE_GROUPS_EMPTY = "profile_groups_empty"
    const val FAIRNESS_DEBT_TEXT = "fairness_debt_text"
    const val FAIRNESS_HISTORY_EMPTY = "fairness_history_empty"
    const val FAIRNESS_ENTRY_ITEM = "fairness_entry_item"

    // Chips are tagged per option name, e.g. modeChip("WALK")
    fun modeChip(mode: String) = "mode_chip_$mode"
    fun preferenceChip(preference: String) = "preference_chip_$preference"
    fun profileGroupItem(groupId: String) = "profile_group_item_$groupId"
}
