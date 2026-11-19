package com.example.cpen321application.testutil

import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.AppContainer
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.model.Group
import kotlinx.coroutines.runBlocking

/** Deletes groups created by an E2E test; deleting a group also removes its meetups and trips. */
fun ComposeTestRule.cleanupCreatedGroups(names: Iterable<String>) {
    val namesToDelete = names.toSet()
    if (namesToDelete.isEmpty()) return
    val container = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        .let { it as MeetFairApplication }.container
    try {
        when (val groups = runBlocking { container.groupRepository.list() }) {
            is ApiResult.Success -> deleteMatchingGroups(container, namesToDelete, groups.data)
            is ApiResult.Failure -> Log.e("ComposeE2E", "Could not load groups for test cleanup: ${groups.error.message}")
        }
    } catch (error: Throwable) {
        Log.e("ComposeE2E", "Could not delete created test groups: ${error.message}", error)
    }
}

private fun deleteMatchingGroups(container: AppContainer, names: Set<String>, groups: List<Group>) {
    groups.filter { it.name in names }.forEach { deleteTestGroup(container, it) }
}

private fun deleteTestGroup(container: AppContainer, group: Group) {
    when (val result = runBlocking { container.groupRepository.delete(group.id) }) {
        is ApiResult.Success -> Log.i("ComposeE2E", "Deleted test group '${group.name}'")
        is ApiResult.Failure -> Log.e("ComposeE2E", "Could not delete test group '${group.name}': ${result.error.message}")
    }
}
