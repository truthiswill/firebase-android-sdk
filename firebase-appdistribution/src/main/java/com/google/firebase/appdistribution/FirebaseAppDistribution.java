// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appdistribution;

import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.AUTHENTICATION_CANCELED;
import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.AUTHENTICATION_FAILURE;
import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.HOST_ACTIVITY_INTERRUPTED;
import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.UPDATE_NOT_AVAILABLE;
import static com.google.firebase.appdistribution.TaskUtils.safeSetTaskException;
import static com.google.firebase.appdistribution.TaskUtils.safeSetTaskResult;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appdistribution.Constants.ErrorMessages;
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status;
import com.google.firebase.appdistribution.internal.LogWrapper;
import com.google.firebase.appdistribution.internal.SignInStorage;
import com.google.firebase.inject.Provider;
import com.google.firebase.installations.FirebaseInstallationsApi;

/**
 * The Firebase App Distribution API provides methods to update the app to the most recent
 * pre-release build.
 *
 * <p>By default, Firebase App Distribution is automatically initialized.
 *
 * <p>Call {@link FirebaseAppDistribution#getInstance()} to get the singleton instance of
 * FirebaseAppDistribution.
 */
public class FirebaseAppDistribution {

  private static final int UNKNOWN_RELEASE_FILE_SIZE = -1;

  private final FirebaseApp firebaseApp;
  private final TesterSignInManager testerSignInManager;
  private final NewReleaseFetcher newReleaseFetcher;
  private final FirebaseAppDistributionLifecycleNotifier lifecycleNotifier;
  private final ApkUpdater apkUpdater;
  private final AabUpdater aabUpdater;
  private final SignInStorage signInStorage;

  private final Object updateIfNewReleaseTaskLock = new Object();

  @GuardedBy("updateIfNewReleaseTaskLock")
  private UpdateTaskImpl cachedUpdateIfNewReleaseTask;

  private final Object cachedNewReleaseLock = new Object();

  @GuardedBy("cachedNewReleaseLock")
  private AppDistributionReleaseInternal cachedNewRelease;

  private Task<AppDistributionRelease> cachedCheckForNewReleaseTask;
  private AlertDialog updateConfirmationDialog;
  private AlertDialog signInConfirmationDialog;
  @Nullable private Activity dialogHostActivity = null;

  private boolean remakeSignInConfirmationDialog = false;
  private boolean remakeUpdateConfirmationDialog = false;

  private TaskCompletionSource<Void> showSignInDialogTask = null;
  private TaskCompletionSource<Void> showUpdateDialogTask = null;

  /** Constructor for FirebaseAppDistribution */
  @VisibleForTesting
  FirebaseAppDistribution(
      @NonNull FirebaseApp firebaseApp,
      @NonNull TesterSignInManager testerSignInManager,
      @NonNull NewReleaseFetcher newReleaseFetcher,
      @NonNull ApkUpdater apkUpdater,
      @NonNull AabUpdater aabUpdater,
      @NonNull SignInStorage signInStorage,
      @NonNull FirebaseAppDistributionLifecycleNotifier lifecycleNotifier) {
    this.firebaseApp = firebaseApp;
    this.testerSignInManager = testerSignInManager;
    this.newReleaseFetcher = newReleaseFetcher;
    this.apkUpdater = apkUpdater;
    this.aabUpdater = aabUpdater;
    this.signInStorage = signInStorage;
    this.lifecycleNotifier = lifecycleNotifier;
    lifecycleNotifier.addOnActivityDestroyedListener(this::onActivityDestroyed);
    lifecycleNotifier.addOnActivityPausedListener(this::onActivityPaused);
    lifecycleNotifier.addOnActivityResumedListener(this::onActivityResumed);
  }

  /** Constructor for FirebaseAppDistribution */
  FirebaseAppDistribution(
      @NonNull FirebaseApp firebaseApp,
      @NonNull Provider<FirebaseInstallationsApi> firebaseInstallationsApiProvider,
      @NonNull SignInStorage signInStorage,
      @NonNull FirebaseAppDistributionLifecycleNotifier lifecycleNotifier) {
    this(
        firebaseApp,
        new TesterSignInManager(firebaseApp, firebaseInstallationsApiProvider, signInStorage),
        new NewReleaseFetcher(
            firebaseApp,
            new FirebaseAppDistributionTesterApiClient(),
            firebaseInstallationsApiProvider),
        new ApkUpdater(firebaseApp, new ApkInstaller()),
        new AabUpdater(),
        signInStorage,
        lifecycleNotifier);
  }

  /** Constructor for FirebaseAppDistribution */
  FirebaseAppDistribution(
      @NonNull FirebaseApp firebaseApp,
      @NonNull Provider<FirebaseInstallationsApi> firebaseInstallationsApiProvider) {
    this(
        firebaseApp,
        firebaseInstallationsApiProvider,
        new SignInStorage(firebaseApp.getApplicationContext()),
        FirebaseAppDistributionLifecycleNotifier.getInstance());
  }

  /** Gets the singleton {@link FirebaseAppDistribution} instance. */
  @NonNull
  public static FirebaseAppDistribution getInstance() {
    return FirebaseApp.getInstance().get(FirebaseAppDistribution.class);
  }

  /**
   * Updates the app to the newest release, if one is available.
   *
   * <p>Returns the release information or null if no update is found. Performs the following
   * actions:
   *
   * <ol>
   *   <li>If tester is not signed in, presents the tester with a Google sign in UI.
   *   <li>Checks if a newer release is available. If so, presents the tester with a confirmation
   *       dialog to begin the download.
   *   <li>For APKs, downloads the binary and starts an installation intent. For AABs, directs the
   *       tester to the Play app to complete the download and installation.
   * </ol>
   */
  @NonNull
  public UpdateTask updateIfNewReleaseAvailable() {
    synchronized (updateIfNewReleaseTaskLock) {
      if (updateIfNewReleaseAvailableIsTaskInProgress()) {
        return cachedUpdateIfNewReleaseTask;
      }
      cachedUpdateIfNewReleaseTask = new UpdateTaskImpl();
      remakeSignInConfirmationDialog = false;
      remakeUpdateConfirmationDialog = false;
      dialogHostActivity = null;
    }

    lifecycleNotifier
        .applyToForegroundActivityTask(this::showSignInConfirmationDialog)
        // TODO(rachelprince): Revisit this comment once changes to checkForNewRelease are reviewed
        // Even though checkForNewRelease() calls signInTester(), we explicitly call signInTester
        // here for code clarity, and because we plan to remove the signInTester() call
        // from checkForNewRelease() in the near future
        .onSuccessTask(unused -> signInTester())
        .onSuccessTask(unused -> checkForNewRelease())
        .continueWithTask(
            task -> {
              if (!task.isSuccessful()) {
                postProgressToCachedUpdateIfNewReleaseTask(
                    UpdateProgress.builder()
                        .setApkBytesDownloaded(UNKNOWN_RELEASE_FILE_SIZE)
                        .setApkFileTotalBytes(UNKNOWN_RELEASE_FILE_SIZE)
                        .setUpdateStatus(UpdateStatus.NEW_RELEASE_CHECK_FAILED)
                        .build());
              }
              // if the task failed, this get() will cause the error to propagate to the handler
              // below
              AppDistributionRelease release = task.getResult();
              if (release == null) {
                postProgressToCachedUpdateIfNewReleaseTask(
                    UpdateProgress.builder()
                        .setApkFileTotalBytes(UNKNOWN_RELEASE_FILE_SIZE)
                        .setApkBytesDownloaded(UNKNOWN_RELEASE_FILE_SIZE)
                        .setUpdateStatus(UpdateStatus.NEW_RELEASE_NOT_AVAILABLE)
                        .build());
                setCachedUpdateIfNewReleaseResult();
                return Tasks.forResult(null);
              }
              return lifecycleNotifier.applyToForegroundActivityTask(
                  activity -> showUpdateConfirmationDialog(activity, release));
            })
        .onSuccessTask(
            unused ->
                updateApp(true)
                    .addOnProgressListener(this::postProgressToCachedUpdateIfNewReleaseTask))
        .addOnFailureListener(this::setCachedUpdateIfNewReleaseCompletionError);

    synchronized (updateIfNewReleaseTaskLock) {
      return cachedUpdateIfNewReleaseTask;
    }
  }

  private Task<Void> showSignInConfirmationDialog(Activity hostActivity) {
    if (isTesterSignedIn()) {
      return Tasks.forResult(null);
    }

    if (showSignInDialogTask == null || showSignInDialogTask.getTask().isComplete()) {
      showSignInDialogTask = new TaskCompletionSource<>();
    }

    signInConfirmationDialog = new AlertDialog.Builder(hostActivity).create();
    dialogHostActivity = hostActivity;

    Context context = firebaseApp.getApplicationContext();
    signInConfirmationDialog.setTitle(context.getString(R.string.signin_dialog_title));
    signInConfirmationDialog.setMessage(context.getString(R.string.singin_dialog_message));

    signInConfirmationDialog.setButton(
        AlertDialog.BUTTON_POSITIVE,
        context.getString(R.string.singin_yes_button),
        (dialogInterface, i) -> showSignInDialogTask.setResult(null));

    signInConfirmationDialog.setButton(
        AlertDialog.BUTTON_NEGATIVE,
        context.getString(R.string.singin_no_button),
        (dialogInterface, i) ->
            showSignInDialogTask.setException(
                new FirebaseAppDistributionException(
                    ErrorMessages.AUTHENTICATION_CANCELED, AUTHENTICATION_CANCELED)));

    signInConfirmationDialog.setOnCancelListener(
        dialogInterface ->
            showSignInDialogTask.setException(
                new FirebaseAppDistributionException(
                    ErrorMessages.AUTHENTICATION_CANCELED, AUTHENTICATION_CANCELED)));

    signInConfirmationDialog.show();

    return showSignInDialogTask.getTask();
  }

  /** Signs in the App Distribution tester. Presents the tester with a Google sign in UI */
  @NonNull
  public Task<Void> signInTester() {
    return this.testerSignInManager.signInTester();
  }

  /**
   * Returns an {@link AppDistributionRelease} if an update is available for the current signed in
   * tester, or {@code null} otherwise.
   */
  @NonNull
  public synchronized Task<AppDistributionRelease> checkForNewRelease() {
    if (cachedCheckForNewReleaseTask != null && !cachedCheckForNewReleaseTask.isComplete()) {
      LogWrapper.getInstance().v("Response in progress");
      return cachedCheckForNewReleaseTask;
    }
    cachedCheckForNewReleaseTask =
        signInTester()
            .onSuccessTask(unused -> this.newReleaseFetcher.checkForNewRelease())
            .onSuccessTask(
                appDistributionReleaseInternal -> {
                  setCachedNewRelease(appDistributionReleaseInternal);
                  return Tasks.forResult(
                      ReleaseUtils.convertToAppDistributionRelease(appDistributionReleaseInternal));
                })
            .addOnFailureListener(
                e -> {
                  if (e instanceof FirebaseAppDistributionException
                      && ((FirebaseAppDistributionException) e).getErrorCode()
                          == AUTHENTICATION_FAILURE) {
                    // If CheckForNewRelease returns authentication error, the FID is no longer
                    // valid or does not have access to the latest release. So sign out the tester
                    // to force FID re-registration
                    signOutTester();
                  }
                });

    return cachedCheckForNewReleaseTask;
  }

  /**
   * Updates app to the {@link AppDistributionRelease} returned by {@link #checkForNewRelease}.
   *
   * <p>If the newest release is an APK, downloads the binary and starts an installation. If the
   * newest release is an AAB, directs the tester to the Play app to complete the download and
   * installation.
   *
   * <p>Cancels task with {@link Status#UPDATE_NOT_AVAILABLE} if no new release is cached from
   * {@link #checkForNewRelease}.
   */
  @NonNull
  public UpdateTask updateApp() {
    return updateApp(false);
  }

  /**
   * Overloaded updateApp with boolean input showDownloadInNotificationsManager. Set to true for
   * basic configuration and false for advanced configuration.
   */
  private UpdateTask updateApp(boolean showDownloadInNotificationManager) {
    synchronized (cachedNewReleaseLock) {
      if (!isTesterSignedIn()) {
        UpdateTaskImpl updateTask = new UpdateTaskImpl();
        updateTask.setException(
            new FirebaseAppDistributionException(
                Constants.ErrorMessages.AUTHENTICATION_ERROR, AUTHENTICATION_FAILURE));
        return updateTask;
      }
      if (cachedNewRelease == null) {
        LogWrapper.getInstance().v("New release not found.");
        return getErrorUpdateTask(
            new FirebaseAppDistributionException(
                Constants.ErrorMessages.NOT_FOUND_ERROR, UPDATE_NOT_AVAILABLE));
      }
      if (cachedNewRelease.getDownloadUrl() == null) {
        LogWrapper.getInstance().v("Download failed to execute");
        return getErrorUpdateTask(
            new FirebaseAppDistributionException(
                Constants.ErrorMessages.DOWNLOAD_URL_NOT_FOUND,
                FirebaseAppDistributionException.Status.DOWNLOAD_FAILURE));
      }

      if (cachedNewRelease.getBinaryType() == BinaryType.AAB) {
        return this.aabUpdater.updateAab(cachedNewRelease);
      } else {
        return this.apkUpdater.updateApk(cachedNewRelease, showDownloadInNotificationManager);
      }
    }
  }

  /** Returns {@code true} if the App Distribution tester is signed in. */
  public boolean isTesterSignedIn() {
    return this.signInStorage.getSignInStatus();
  }

  /** Signs out the App Distribution tester */
  public void signOutTester() {
    setCachedNewRelease(null);
    this.signInStorage.setSignInStatus(false);
  }

  @VisibleForTesting
  void onActivityResumed(Activity activity) {
    if (awaitingSignInDialogConfirmation()) {
      if (dialogHostActivity != null && dialogHostActivity != activity) {
        showSignInDialogTask.setException(
            new FirebaseAppDistributionException(
                ErrorMessages.HOST_ACTIVITY_INTERRUPTED, HOST_ACTIVITY_INTERRUPTED));
      } else {
        showSignInConfirmationDialog(activity);
      }
    }

    if (awaitingUpdateDialogConfirmation()) {
      if (dialogHostActivity != null && dialogHostActivity != activity) {
        showUpdateDialogTask.setException(
            new FirebaseAppDistributionException(
                ErrorMessages.HOST_ACTIVITY_INTERRUPTED, HOST_ACTIVITY_INTERRUPTED));
      } else {
        synchronized (cachedNewReleaseLock) {
          showUpdateConfirmationDialog(
              activity, ReleaseUtils.convertToAppDistributionRelease(cachedNewRelease));
        }
      }
    }
  }

  @VisibleForTesting
  void onActivityPaused(Activity activity) {
    if (activity == dialogHostActivity) {
      remakeSignInConfirmationDialog =
          signInConfirmationDialog != null && signInConfirmationDialog.isShowing();
      remakeUpdateConfirmationDialog =
          updateConfirmationDialog != null && updateConfirmationDialog.isShowing();
      dismissDialogs();
    }
  }

  @VisibleForTesting
  void onActivityDestroyed(@NonNull Activity activity) {
    // If the dialogHostActivity is being destroyed it is set to null. This is to ensure onResume
    // shows the dialog on a configuration change and does not check the activity reference.
    if (activity == dialogHostActivity) {
      dialogHostActivity = null;
    }
  }

  @VisibleForTesting
  void setCachedNewRelease(@Nullable AppDistributionReleaseInternal newRelease) {
    synchronized (cachedNewReleaseLock) {
      this.cachedNewRelease = newRelease;
    }
  }

  @VisibleForTesting
  AppDistributionReleaseInternal getCachedNewRelease() {
    synchronized (cachedNewReleaseLock) {
      return this.cachedNewRelease;
    }
  }

  private Task<Void> showUpdateConfirmationDialog(
      Activity hostActivity, AppDistributionRelease newRelease) {

    if (showUpdateDialogTask == null || showUpdateDialogTask.getTask().isComplete()) {
      showUpdateDialogTask = new TaskCompletionSource<>();
    }

    Context context = firebaseApp.getApplicationContext();

    updateConfirmationDialog = new AlertDialog.Builder(hostActivity).create();
    dialogHostActivity = hostActivity;
    updateConfirmationDialog.setTitle(context.getString(R.string.update_dialog_title));

    StringBuilder message =
        new StringBuilder(
            String.format(
                "Version %s (%s) is available.",
                newRelease.getDisplayVersion(), newRelease.getVersionCode()));

    if (newRelease.getReleaseNotes() != null && !newRelease.getReleaseNotes().isEmpty()) {
      message.append(String.format("\n\nRelease notes: %s", newRelease.getReleaseNotes()));
    }
    updateConfirmationDialog.setMessage(message);

    updateConfirmationDialog.setButton(
        AlertDialog.BUTTON_POSITIVE,
        context.getString(R.string.update_yes_button),
        (dialogInterface, i) -> showUpdateDialogTask.setResult(null));

    updateConfirmationDialog.setButton(
        AlertDialog.BUTTON_NEGATIVE,
        context.getString(R.string.update_no_button),
        (dialogInterface, i) ->
            showUpdateDialogTask.setException(
                new FirebaseAppDistributionException(
                    ErrorMessages.UPDATE_CANCELED, Status.INSTALLATION_CANCELED)));

    updateConfirmationDialog.setOnCancelListener(
        dialogInterface ->
            showUpdateDialogTask.setException(
                new FirebaseAppDistributionException(
                    ErrorMessages.UPDATE_CANCELED, Status.INSTALLATION_CANCELED)));

    updateConfirmationDialog.show();

    return showUpdateDialogTask.getTask();
  }

  private void setCachedUpdateIfNewReleaseCompletionError(Exception e) {
    synchronized (updateIfNewReleaseTaskLock) {
      safeSetTaskException(cachedUpdateIfNewReleaseTask, e);
    }
    dismissDialogs();
  }

  private void postProgressToCachedUpdateIfNewReleaseTask(UpdateProgress progress) {
    synchronized (updateIfNewReleaseTaskLock) {
      if (cachedUpdateIfNewReleaseTask != null && !cachedUpdateIfNewReleaseTask.isComplete()) {
        cachedUpdateIfNewReleaseTask.updateProgress(progress);
      }
    }
  }

  private void setCachedUpdateIfNewReleaseResult() {
    synchronized (updateIfNewReleaseTaskLock) {
      safeSetTaskResult(cachedUpdateIfNewReleaseTask);
    }
    dismissDialogs();
  }

  private void dismissDialogs() {
    if (signInConfirmationDialog != null && signInConfirmationDialog.isShowing()) {
      signInConfirmationDialog.dismiss();
    }
    if (updateConfirmationDialog != null && updateConfirmationDialog.isShowing()) {
      updateConfirmationDialog.dismiss();
    }
  }

  private UpdateTaskImpl getErrorUpdateTask(Exception e) {
    UpdateTaskImpl updateTask = new UpdateTaskImpl();
    updateTask.setException(e);
    return updateTask;
  }

  private boolean updateIfNewReleaseAvailableIsTaskInProgress() {
    synchronized (updateIfNewReleaseTaskLock) {
      return cachedUpdateIfNewReleaseTask != null && !cachedUpdateIfNewReleaseTask.isComplete();
    }
  }

  private boolean awaitingSignInDialogConfirmation() {
    return (showSignInDialogTask != null
        && !showSignInDialogTask.getTask().isComplete()
        && remakeSignInConfirmationDialog);
  }

  private boolean awaitingUpdateDialogConfirmation() {
    return (showUpdateDialogTask != null
        && !showUpdateDialogTask.getTask().isComplete()
        && remakeUpdateConfirmationDialog);
  }
}
