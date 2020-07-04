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

package com.google.android.gnd.repository;

import com.google.android.gnd.model.AuditInfo;
import com.google.android.gnd.model.User;
import com.google.android.gnd.model.feature.Feature;
import com.google.android.gnd.model.observation.Observation;
import com.google.android.gnd.model.observation.ObservationMutation;
import com.google.android.gnd.model.observation.ResponseDelta;
import com.google.android.gnd.persistence.local.LocalDataStore;
import com.google.android.gnd.persistence.remote.NotFoundException;
import com.google.android.gnd.persistence.remote.RemoteDataStore;
import com.google.android.gnd.persistence.sync.DataSyncWorkManager;
import com.google.android.gnd.persistence.uuid.OfflineUuidGenerator;
import com.google.android.gnd.rx.ValueOrError;
import com.google.android.gnd.system.AuthenticationManager;
import com.google.common.collect.ImmutableList;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import timber.log.Timber;
import timber.log.Timber;

/**
 * Coordinates persistence and retrieval of {@link Observation} instances from remote, local, and in
 * memory data stores. For more details on this pattern and overall architecture, see
 * https://developer.android.com/jetpack/docs/guide.
 */
public class ObservationRepository {

  private static final long LOAD_REMOTE_OBSERVATIONS_TIMEOUT_SECS = 5;

  private final LocalDataStore localDataStore;
  private final RemoteDataStore remoteDataStore;
  private final FeatureRepository featureRepository;
  private final DataSyncWorkManager dataSyncWorkManager;
  private final OfflineUuidGenerator uuidGenerator;
  private final AuthenticationManager authManager;

  @Inject
  public ObservationRepository(
      LocalDataStore localDataStore,
      RemoteDataStore remoteDataStore,
      FeatureRepository featureRepository,
      DataSyncWorkManager dataSyncWorkManager,
      OfflineUuidGenerator uuidGenerator,
      AuthenticationManager authManager) {

    this.localDataStore = localDataStore;
    this.remoteDataStore = remoteDataStore;
    this.featureRepository = featureRepository;
    this.dataSyncWorkManager = dataSyncWorkManager;
    this.uuidGenerator = uuidGenerator;
    this.authManager = authManager;
  }

  /**
   * Retrieves the observations or the specified project, feature, and form.
   *
   * <ol>
   *   <li>Attempt to sync remote observation changes to the local data store. If network is not
   *       available or operation times out, this step is skipped.
   *   <li>Relevant observations are returned directly from the local data store.
   * </ol>
   */
  public Single<ImmutableList<Observation>> getObservations(
      String projectId, String featureId, String formId) {
    // TODO: Only fetch first n fields.
    // TODO(#127): Decouple feature from observation so that we don't need to fetch observation
    // here.
    return featureRepository
        .getFeature(projectId, featureId)
        .switchIfEmpty(Single.error(() -> new NotFoundException("Feature " + featureId)))
        .flatMap(feature -> getObservations(feature, formId));
  }

  private Single<ImmutableList<Observation>> getObservations(Feature feature, String formId) {
    Completable remoteSync =
        remoteDataStore
            .loadObservations(feature)
            .timeout(LOAD_REMOTE_OBSERVATIONS_TIMEOUT_SECS, TimeUnit.SECONDS)
            .doOnError(t -> Timber.e(t, "Observation sync timed out"))
            .flatMapCompletable(this::mergeRemoteObservations)
            .onErrorComplete();
    return remoteSync.andThen(localDataStore.getObservations(feature, formId));
  }

  private Completable mergeRemoteObservations(
      ImmutableList<ValueOrError<Observation>> observations) {
    return Observable.fromIterable(observations)
        .doOnNext(voe -> voe.error().ifPresent(t -> Timber.e(t, "Skipping bad observation")))
        .compose(ValueOrError::ignoreErrors)
        .flatMapCompletable(localDataStore::mergeObservation);
  }

  public Single<Observation> getObservation(
      String projectId, String featureId, String observationId) {
    // TODO: Store and retrieve latest edits from cache and/or db.
    // TODO(#127): Decouple feature from observation so that we don't need to fetch feature here.
    return featureRepository
        .getFeature(projectId, featureId)
        .switchIfEmpty(Single.error(() -> new NotFoundException("Feature " + featureId)))
        .flatMap(
            feature ->
                localDataStore
                    .getObservation(feature, observationId)
                    .switchIfEmpty(
                        Single.error(() -> new NotFoundException("Observation " + observationId))));
  }

  public Single<Observation> createObservation(String projectId, String featureId, String formId) {
    // TODO: Handle invalid formId.
    // TODO(#127): Decouple feature from observation so that we don't need to fetch feature here.
    AuditInfo auditInfo = AuditInfo.now(authManager.getCurrentUser());
    return featureRepository
        .getFeature(projectId, featureId)
        .switchIfEmpty(Single.error(() -> new NotFoundException("Feature " + featureId)))
        .map(
            feature ->
                Observation.newBuilder()
                    .setId(uuidGenerator.generateUuid())
                    .setProject(feature.getProject())
                    .setFeature(feature)
                    .setForm(feature.getLayer().getForm(formId).get())
                    .setCreated(auditInfo)
                    .setLastModified(auditInfo)
                    .build());
  }

  public Completable deleteObservation(Observation originalObservation) {
    ObservationMutation observationMutation =
        ObservationMutation.builder()
            .setType(Type.DELETE)
            .setProjectId(originalObservation.getProject().getId())
            .setFeatureId(originalObservation.getFeature().getId())
            .setLayerId(originalObservation.getFeature().getLayer().getId())
            .setObservationId(originalObservation.getId())
            .setFormId(originalObservation.getForm().getId())
            .setResponseDeltas(ImmutableList.of())
            .setClientTimestamp(new Date())
            .setUserId(authManager.getCurrentUser().getId())
            .build();

    /*
     * When deleting observation, we can't apply the changes first to the local database. This would
     * fail a foreign key constraint in the ObservationMutationEntity as the observation_id is a
     * foreign key of the observation table.
     *
     * So, first we enqueue the mutation and remove the remote entry. After that, update the local
     * entry.
     */
    return enqueue(observationMutation);
  }

  public Completable enqueue(ObservationMutation mutation) {
    return localDataStore
        .enqueue(mutation)
        .andThen(dataSyncWorkManager.enqueueSyncWorker(mutation.getFeatureId()));
  }

  public Completable addObservationMutation(
      Observation observation, ImmutableList<ResponseDelta> responseDeltas, boolean isNew) {
    ObservationMutation observationMutation =
        ObservationMutation.builder()
            .setType(isNew ? ObservationMutation.Type.CREATE : ObservationMutation.Type.UPDATE)
            .setProjectId(observation.getProject().getId())
            .setFeatureId(observation.getFeature().getId())
            .setLayerId(observation.getFeature().getLayer().getId())
            .setObservationId(observation.getId())
            .setFormId(observation.getForm().getId())
            .setResponseDeltas(responseDeltas)
            .setClientTimestamp(new Date())
            .setUserId(authManager.getCurrentUser().getId())
            .build();
    return applyAndEnqueue(observationMutation);
  }

  private Completable applyAndEnqueue(ObservationMutation mutation) {
    return localDataStore
        .applyAndEnqueue(mutation)
        .andThen(dataSyncWorkManager.enqueueSyncWorker(mutation.getFeatureId()));
  }
}
