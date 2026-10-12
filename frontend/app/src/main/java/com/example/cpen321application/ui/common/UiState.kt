package com.example.cpen321application.ui.common

import com.example.cpen321application.data.ApiResult

/** Render state for a screen that loads data from the backend. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    
    /** The request succeeded but returned no items; only produced by [toListUiState]. */
    data object Empty : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}

/** One-shot form submission state; [result] is non-null once the call succeeded. */
data class FormState<out T>(
    val submitting: Boolean = false,
    val error: String? = null,
    val result: T? = null,
)

fun <T> ApiResult<T>.toUiState(): UiState<T> = when (this) {
    is ApiResult.Success -> UiState.Success(data)
    is ApiResult.Failure -> UiState.Error(error.message)
}

/** Like [toUiState], but maps an empty list to [UiState.Empty] so screens can show an empty message. */
fun <T> ApiResult<List<T>>.toListUiState(): UiState<List<T>> = when (this) {
    is ApiResult.Success -> if (data.isEmpty()) UiState.Empty else UiState.Success(data)
    is ApiResult.Failure -> UiState.Error(error.message)
}

fun <T> ApiResult<T>.toFormState(): FormState<T> = when (this) {
    is ApiResult.Success -> FormState(result = data)
    is ApiResult.Failure -> FormState(error = error.message)
}
