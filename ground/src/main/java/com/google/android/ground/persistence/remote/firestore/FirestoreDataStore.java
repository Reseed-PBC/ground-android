/*
 * Copyright 2018 Google LLC
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

package com.google.android.ground.persistence.remote.firestore;

import com.google.android.gms.tasks.Task;
import com.google.android.ground.model.Survey;
import com.google.android.ground.model.TermsOfService;
import com.google.android.ground.model.User;
import com.google.android.ground.model.locationofinterest.LocationOfInterest;
import com.google.android.ground.model.mutation.LocationOfInterestMutation;
import com.google.android.ground.model.mutation.Mutation;
import com.google.android.ground.model.mutation.SubmissionMutation;
import com.google.android.ground.model.submission.Submission;
import com.google.android.ground.persistence.remote.DataStoreException;
import com.google.android.ground.persistence.remote.NotFoundException;
import com.google.android.ground.persistence.remote.RemoteDataEvent;
import com.google.android.ground.persistence.remote.RemoteDataStore;
import com.google.android.ground.persistence.remote.firestore.schema.GroundFirestore;
import com.google.android.ground.rx.RxTask;
import com.google.android.ground.rx.Schedulers;
import com.google.android.ground.rx.annotations.Cold;
import com.google.android.ground.system.ApplicationErrorManager;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.WriteBatch;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Result;
import timber.log.Timber;

@Singleton
public class FirestoreDataStore implements RemoteDataStore {

  static final String ID_COLLECTION = "/ids";

  @Inject ApplicationErrorManager errorManager;
  @Inject GroundFirestore db;
  @Inject Schedulers schedulers;

  @Inject
  FirestoreDataStore() {}

  /**
   * Prevents known {@link FirebaseFirestoreException} from propagating downstream. Also, notifies
   * the event to a processor that should be handled commonly.
   */
  private boolean shouldInterceptException(Throwable throwable) {
    return errorManager.handleException(throwable);
  }

  private void recordException(Throwable t, String message) {
    FirebaseCrashlytics.getInstance().log(message);
    FirebaseCrashlytics.getInstance().recordException(t);
  }

  @Cold
  @Override
  public Single<Survey> loadSurvey(String surveyId) {
    return db.surveys()
        .survey(surveyId)
        .get()
        .onErrorResumeNext(e -> shouldInterceptException(e) ? Maybe.never() : Maybe.error(e))
        .switchIfEmpty(Single.error(() -> new NotFoundException("Survey " + surveyId)))
        .subscribeOn(schedulers.io());
  }

  @Cold
  @Override
  public Single<ImmutableList<Result<Submission>>> loadSubmissions(
      LocationOfInterest locationOfInterest) {
    return db.surveys()
        .survey(locationOfInterest.getSurveyId())
        .submissions()
        .submissionsByLocationOfInterestId(locationOfInterest)
        .onErrorResumeNext(e -> shouldInterceptException(e) ? Single.never() : Single.error(e))
        .subscribeOn(schedulers.io());
  }

  @Cold
  @Override
  public Maybe<TermsOfService> loadTermsOfService() {
    return db.termsOfService()
        .terms()
        .get()
        .onErrorResumeNext(e -> shouldInterceptException(e) ? Maybe.never() : Maybe.error(e))
        .subscribeOn(schedulers.io());
  }

  @Cold
  @Override
  public Single<List<Survey>> loadSurveySummaries(User user) {
    return db.surveys()
        .getReadable(user)
        .onErrorResumeNext(e -> shouldInterceptException(e) ? Single.never() : Single.error(e))
        .subscribeOn(schedulers.io());
  }

  @Cold(stateful = true, terminates = false)
  @Override
  public Flowable<RemoteDataEvent<LocationOfInterest>> loadLocationsOfInterestOnceAndStreamChanges(
      Survey survey) {
    return db.surveys()
        .survey(survey.getId())
        .lois()
        .loadOnceAndStreamChanges(survey)
        .onErrorResumeNext(e -> shouldInterceptException(e) ? Flowable.never() : Flowable.error(e))
        .subscribeOn(schedulers.io());
  }

  @Cold
  @Override
  public Completable applyMutations(ImmutableCollection<Mutation> mutations, User user) {
    return RxTask.toCompletable(() -> applyMutationsInternal(mutations, user))
        .doOnError(e -> recordException(e, "Error applying mutation"))
        .onErrorResumeNext(
            e -> shouldInterceptException(e) ? Completable.never() : Completable.error(e))
        .subscribeOn(schedulers.io());
  }

  private Task<?> applyMutationsInternal(ImmutableCollection<Mutation> mutations, User user) {
    WriteBatch batch = db.batch();
    for (Mutation mutation : mutations) {
      try {
        addMutationToBatch(mutation, user, batch);
      } catch (DataStoreException e) {
        recordException(
            e,
            "Error adding "
                + mutation.getType()
                + " "
                + mutation.getClass().getSimpleName()
                + " for "
                + (mutation instanceof SubmissionMutation
                    ? ((SubmissionMutation) mutation).getSubmissionId()
                    : mutation.getLocationOfInterestId())
                + " to batch");
        Timber.e(e, "Skipping invalid mutation");
      }
    }
    return batch.commit();
  }

  private void addMutationToBatch(Mutation mutation, User user, WriteBatch batch)
      throws DataStoreException {
    if (mutation instanceof LocationOfInterestMutation) {
      addLocationOfInterestMutationToBatch((LocationOfInterestMutation) mutation, user, batch);
    } else if (mutation instanceof SubmissionMutation) {
      addSubmissionMutationToBatch((SubmissionMutation) mutation, user, batch);
    } else {
      throw new DataStoreException("Unsupported mutation " + mutation.getClass());
    }
  }

  private void addLocationOfInterestMutationToBatch(
      LocationOfInterestMutation mutation, User user, WriteBatch batch) throws DataStoreException {
    db.surveys()
        .survey(mutation.getSurveyId())
        .lois()
        .loi(mutation.getLocationOfInterestId())
        .addMutationToBatch(mutation, user, batch);
  }

  private void addSubmissionMutationToBatch(
      SubmissionMutation mutation, User user, WriteBatch batch) throws DataStoreException {
    db.surveys()
        .survey(mutation.getSurveyId())
        .submissions()
        .submission(mutation.getSubmissionId())
        .addMutationToBatch(mutation, user, batch);
  }
}