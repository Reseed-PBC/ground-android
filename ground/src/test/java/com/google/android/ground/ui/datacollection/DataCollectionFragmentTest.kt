/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.ground.ui.datacollection

import android.os.Bundle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.google.android.ground.BaseHiltTest
import com.google.android.ground.R
import com.google.android.ground.capture
import com.google.android.ground.domain.usecases.survey.ActivateSurveyUseCase
import com.google.android.ground.launchFragmentWithNavController
import com.google.android.ground.model.submission.TextResponse
import com.google.android.ground.model.submission.ValueDelta
import com.google.android.ground.model.task.Task
import com.google.android.ground.persistence.local.room.converter.SubmissionDeltasConverter
import com.google.android.ground.repository.SubmissionRepository
import com.google.common.truth.Truth.assertThat
import com.sharedtest.FakeData
import com.sharedtest.FakeData.LOCATION_OF_INTEREST
import com.sharedtest.FakeData.LOCATION_OF_INTEREST_NAME
import com.sharedtest.persistence.remote.FakeRemoteDataStore
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class DataCollectionFragmentTest : BaseHiltTest() {

  @Inject lateinit var activateSurvey: ActivateSurveyUseCase
  @Inject lateinit var fakeRemoteDataStore: FakeRemoteDataStore
  @BindValue @Mock lateinit var submissionRepository: SubmissionRepository
  @Captor lateinit var deltaCaptor: ArgumentCaptor<List<ValueDelta>>
  lateinit var fragment: DataCollectionFragment

  override fun setUp() {
    super.setUp()

    setupSubmission()
    setupFragment()
  }

  @Test
  fun `Job and LOI names are displayed correctly`() {
    runner()
      .validateTextIsDisplayed("Unnamed point")
      .validateTextIsDisplayed(requireNotNull(JOB.name))
  }

  @Test
  fun `First task is loaded and is visible`() {
    runner().validateTextIsDisplayed(TASK_1_NAME)
  }

  @Test
  fun `Next button is disabled when task doesn't have any value`() {
    runner()
      .clickNextButton()
      .validateTextIsDisplayed(TASK_1_NAME)
      .validateTextIsNotDisplayed(TASK_2_NAME)
  }

  @Test
  fun `Next button proceeds to the second task when task has value`() {
    runner()
      .inputText(TASK_1_RESPONSE)
      .clickNextButton()
      .validateTextIsNotDisplayed(TASK_1_NAME)
      .validateTextIsDisplayed(TASK_2_NAME)

    // Ensure that no validation error toasts were displayed
    assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
  }

  @Test
  fun `Previous button navigates back to first task`() {
    runner()
      .inputText(TASK_1_RESPONSE)
      .clickNextButton()
      .pressBackButton(true)
      .validateTextIsDisplayed(TASK_1_NAME)
      .validateTextIsNotDisplayed(TASK_2_NAME)

    // Ensure that no validation error toasts were displayed
    assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
  }

  @Test
  fun `Next click saves draft`() = runWithTestDispatcher {
    runner().inputText(TASK_1_RESPONSE).clickNextButton()

    // Validate that previous drafts were cleared
    verify(submissionRepository, times(1)).deleteDraftSubmission()

    // Validate that new draft was created
    verify(submissionRepository, times(1))
      .saveDraftSubmission(
        eq(JOB.id),
        eq(LOCATION_OF_INTEREST.id),
        eq(SURVEY.id),
        capture(deltaCaptor),
        eq(LOCATION_OF_INTEREST_NAME),
      )

    listOf(TASK_1_VALUE_DELTA).forEach { value -> assertThat(deltaCaptor.value).contains(value) }
  }

  @Test
  fun `Clicking previous button saves draft`() = runWithTestDispatcher {
    runner()
      .inputText(TASK_1_RESPONSE)
      .clickNextButton()
      .inputText(TASK_2_RESPONSE)
      .clickPreviousButton()

    // Both deletion and creating happens twice as we do it on every previous/next step
    verify(submissionRepository, times(2)).deleteDraftSubmission()
    verify(submissionRepository, times(2))
      .saveDraftSubmission(
        eq(JOB.id),
        eq(LOCATION_OF_INTEREST.id),
        eq(SURVEY.id),
        capture(deltaCaptor),
        eq(LOCATION_OF_INTEREST_NAME),
      )

    listOf(TASK_1_VALUE_DELTA, TASK_2_VALUE_DELTA).forEach { value ->
      assertThat(deltaCaptor.value).contains(value)
    }
  }

  @Test
  fun `Click previous button does not show initial task if validation failed`() {
    runner()
      .inputText(TASK_1_RESPONSE)
      .clickNextButton()
      .clickPreviousButton()
      .validateTextIsDisplayed(TASK_2_NAME)
      .validateTextIsNotDisplayed(TASK_1_NAME)

    // Validation error is shown as a toast message
    assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
  }

  @Test
  fun `Load tasks from draft`() = runWithTestDispatcher {
    // TODO(#708): add coverage for loading from draft for all types of tasks
    val expectedDeltas = listOf(TASK_1_VALUE_DELTA, TASK_2_VALUE_DELTA)

    // Start the fragment with draft values
    setupFragment(
      DataCollectionFragmentArgs.Builder(
          LOCATION_OF_INTEREST.id,
          LOCATION_OF_INTEREST_NAME,
          JOB.id,
          true,
          SubmissionDeltasConverter.toString(expectedDeltas),
        )
        .build()
        .toBundle()
    )

    runner()
      .validateTextIsDisplayed(TASK_1_RESPONSE)
      .clickNextButton()
      .validateTextIsDisplayed(TASK_2_RESPONSE)
  }

  @Test
  fun `Clicking done on final task saves the submission`() = runWithTestDispatcher {
    runner()
      .inputText(TASK_1_RESPONSE)
      .clickNextButton()
      .validateTextIsNotDisplayed(TASK_1_NAME)
      .validateTextIsDisplayed(TASK_2_NAME)
      .inputText(TASK_2_RESPONSE)
      .clickDoneButton() // Click "done" on final task

    verify(submissionRepository)
      .saveSubmission(eq(SURVEY.id), eq(LOCATION_OF_INTEREST.id), capture(deltaCaptor))

    listOf(TASK_1_VALUE_DELTA, TASK_2_VALUE_DELTA).forEach { value ->
      assertThat(deltaCaptor.value).contains(value)
    }
  }

  @Test
  fun `Clicking back button on first task clears the draft and returns false`() =
    runWithTestDispatcher {
      runner().pressBackButton(false)

      verify(submissionRepository, times(1)).deleteDraftSubmission()
    }

  private fun setupSubmission() = runWithTestDispatcher {
    whenever(submissionRepository.createSubmission(SURVEY.id, LOCATION_OF_INTEREST.id))
      .thenReturn(SUBMISSION)

    fakeRemoteDataStore.surveys = listOf(SURVEY)
    fakeRemoteDataStore.lois = listOf(LOCATION_OF_INTEREST)
    activateSurvey(SURVEY.id)
    advanceUntilIdle()
  }

  private fun setupFragment(fragmentArgs: Bundle? = null) {
    val argsBundle =
      fragmentArgs
        ?: DataCollectionFragmentArgs.Builder(LOCATION_OF_INTEREST.id, JOB.id, false, null)
          .build()
          .toBundle()

    launchFragmentWithNavController<DataCollectionFragment>(
      argsBundle,
      destId = R.id.data_collection_fragment,
    ) {
      fragment = this as DataCollectionFragment
    }
  }

  private fun runner() = Runner(this, fragment)

  /** Helper class for interacting with the data collection tasks and verifying the ui state. */
  class Runner(
    private val baseHiltTest: BaseHiltTest,
    private val fragment: DataCollectionFragment,
  ) {

    internal fun clickNextButton(): Runner {
      clickButton("Next")
      return this
    }

    internal fun clickPreviousButton(): Runner {
      clickButton("Previous")
      return this
    }

    internal fun clickDoneButton(): Runner {
      clickButton("Done")
      return this
    }

    internal fun inputText(text: String): Runner {
      onView(allOf(withId(R.id.user_response_text), isDisplayed())).perform(typeText(text))
      return this
    }

    internal fun validateTextIsDisplayed(text: String): Runner {
      onView(withText(text)).check(matches(isDisplayed()))
      return this
    }

    internal fun validateTextIsNotDisplayed(text: String): Runner {
      onView(withText(text)).check(matches(not(isDisplayed())))
      return this
    }

    internal fun pressBackButton(result: Boolean): Runner {
      waitUntilDone { assertThat(fragment.onBack()).isEqualTo(result) }
      return this
    }

    private fun clickButton(text: String) = waitUntilDone {
      onView(allOf(withText(text), isDisplayed())).perform(click())
    }

    private fun waitUntilDone(testBody: suspend () -> Unit) {
      baseHiltTest.runWithTestDispatcher {
        testBody()
        advanceUntilIdle()
      }
    }
  }

  companion object {
    private const val TASK_ID_1 = "1"
    const val TASK_1_NAME = "task 1"
    private const val TASK_1_RESPONSE = "response 1"
    private val TASK_1_VALUE = TextResponse.fromString(TASK_1_RESPONSE)
    private val TASK_1_VALUE_DELTA = ValueDelta(TASK_ID_1, Task.Type.TEXT, TASK_1_VALUE)

    private const val TASK_ID_2 = "2"
    const val TASK_2_NAME = "task 2"
    private const val TASK_2_RESPONSE = "response 2"
    private val TASK_2_VALUE = TextResponse.fromString(TASK_2_RESPONSE)
    private val TASK_2_VALUE_DELTA = ValueDelta(TASK_ID_2, Task.Type.TEXT, TASK_2_VALUE)

    private val TASKS =
      listOf(
        Task(TASK_ID_1, 0, Task.Type.TEXT, TASK_1_NAME, true),
        Task(TASK_ID_2, 1, Task.Type.TEXT, TASK_2_NAME, true),
      )

    private val JOB = FakeData.JOB.copy(tasks = TASKS.associateBy { it.id })
    private val SUBMISSION = FakeData.SUBMISSION.copy(job = JOB)
    private val SURVEY = FakeData.SURVEY.copy(jobMap = mapOf(Pair(JOB.id, JOB)))
  }
}
