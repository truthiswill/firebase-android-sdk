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

import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.DOWNLOAD_FAILURE;
import static com.google.firebase.appdistribution.FirebaseAppDistributionException.Status.NETWORK_FAILURE;
import static com.google.firebase.appdistribution.TaskUtils.safeSetTaskException;
import static com.google.firebase.appdistribution.TaskUtils.safeSetTaskResult;

import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appdistribution.Constants.ErrorMessages;
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status;
import com.google.firebase.appdistribution.internal.LogWrapper;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import javax.net.ssl.HttpsURLConnection;

/** Class that handles updateApp functionality for APKs in {@link FirebaseAppDistribution}. */
class ApkUpdater {

  private static final int UPDATE_INTERVAL_MS = 250;
  private static final String TAG = "ApkUpdater:";
  private static final String REQUEST_METHOD_GET = "GET";
  private static final String DEFAULT_APK_FILE_NAME = "downloaded_release.apk";

  private TaskCompletionSource<File> downloadTaskCompletionSource;
  private final Executor taskExecutor; // Executor to run task listeners on a background thread
  private final Context context;
  private final ApkInstaller apkInstaller;
  private final FirebaseAppDistributionNotificationsManager appDistributionNotificationsManager;
  private final HttpsUrlConnectionFactory httpsUrlConnectionFactory;
  private final FirebaseAppDistributionLifecycleNotifier lifeCycleNotifier;

  @GuardedBy("updateTaskLock")
  private UpdateTaskImpl cachedUpdateTask;

  private final Object updateTaskLock = new Object();

  public ApkUpdater(@NonNull FirebaseApp firebaseApp, @NonNull ApkInstaller apkInstaller) {
    this(
        Executors.newSingleThreadExecutor(),
        firebaseApp.getApplicationContext(),
        apkInstaller,
        new FirebaseAppDistributionNotificationsManager(firebaseApp.getApplicationContext()),
        new HttpsUrlConnectionFactory(),
        FirebaseAppDistributionLifecycleNotifier.getInstance());
  }

  @VisibleForTesting
  public ApkUpdater(
      @NonNull Executor taskExecutor,
      @NonNull Context context,
      @NonNull ApkInstaller apkInstaller,
      @NonNull FirebaseAppDistributionNotificationsManager appDistributionNotificationsManager,
      @NonNull HttpsUrlConnectionFactory httpsUrlConnectionFactory,
      @NonNull FirebaseAppDistributionLifecycleNotifier lifeCycleNotifier) {
    this.taskExecutor = taskExecutor;
    this.context = context;
    this.apkInstaller = apkInstaller;
    this.appDistributionNotificationsManager = appDistributionNotificationsManager;
    this.httpsUrlConnectionFactory = httpsUrlConnectionFactory;
    this.lifeCycleNotifier = lifeCycleNotifier;
  }

  UpdateTaskImpl updateApk(
      @NonNull AppDistributionReleaseInternal newRelease, boolean showNotification) {
    synchronized (updateTaskLock) {
      if (cachedUpdateTask != null && !cachedUpdateTask.isComplete()) {
        return cachedUpdateTask;
      }

      cachedUpdateTask = new UpdateTaskImpl();
    }

    downloadApk(newRelease, showNotification)
        .addOnSuccessListener(taskExecutor, file -> installApk(file, showNotification))
        .addOnFailureListener(
            taskExecutor,
            e -> {
              setUpdateTaskCompletionErrorWithDefault(
                  e, "Failed to download APK", Status.DOWNLOAD_FAILURE);
            });

    synchronized (updateTaskLock) {
      return cachedUpdateTask;
    }
  }

  private void installApk(File file, boolean showDownloadNotificationManager) {
    lifeCycleNotifier
        .applyToForegroundActivityTask(
            activity -> apkInstaller.installApk(file.getPath(), activity))
        .addOnSuccessListener(
            taskExecutor,
            unused -> {
              synchronized (updateTaskLock) {
                safeSetTaskResult(cachedUpdateTask);
              }
            })
        .addOnFailureListener(
            taskExecutor,
            e -> {
              postUpdateProgress(
                  file.length(),
                  file.length(),
                  UpdateStatus.INSTALL_FAILED,
                  showDownloadNotificationManager);
              setUpdateTaskCompletionErrorWithDefault(
                  e, ErrorMessages.APK_INSTALLATION_FAILED, Status.INSTALLATION_FAILURE);
            });
  }

  @VisibleForTesting
  @NonNull
  Task<File> downloadApk(
      @NonNull AppDistributionReleaseInternal newRelease, boolean showNotification) {
    if (downloadTaskCompletionSource != null
        && !downloadTaskCompletionSource.getTask().isComplete()) {
      return downloadTaskCompletionSource.getTask();
    }

    downloadTaskCompletionSource = new TaskCompletionSource<>();

    taskExecutor.execute(
        () -> {
          try {
            makeApkDownloadRequest(newRelease, showNotification);
          } catch (FirebaseAppDistributionException e) {
            safeSetTaskException(downloadTaskCompletionSource, e);
          }
        });
    return downloadTaskCompletionSource.getTask();
  }

  private void makeApkDownloadRequest(
      @NonNull AppDistributionReleaseInternal newRelease, boolean showNotification)
      throws FirebaseAppDistributionException {
    String downloadUrl = newRelease.getDownloadUrl();
    HttpsURLConnection connection;
    int responseCode;
    try {
      connection = httpsUrlConnectionFactory.openConnection(downloadUrl);
      connection.setRequestMethod(REQUEST_METHOD_GET);
      responseCode = connection.getResponseCode();
    } catch (IOException e) {
      throw new FirebaseAppDistributionException(
          "Failed to open connection to: " + downloadUrl, NETWORK_FAILURE, e);
    }

    if (!isResponseSuccess(responseCode)) {
      throw new FirebaseAppDistributionException(
          "Failed to download APK. Response code: " + responseCode, DOWNLOAD_FAILURE);
    }

    long responseLength = connection.getContentLength();
    postUpdateProgress(responseLength, 0, UpdateStatus.PENDING, showNotification);
    String fileName = getApkFileName();
    LogWrapper.getInstance().v(TAG + "Attempting to download APK to disk");

    long bytesDownloaded = downloadToDisk(connection, responseLength, fileName, showNotification);

    File apkFile = context.getFileStreamPath(fileName);
    validateJarFile(apkFile, responseLength, showNotification, bytesDownloaded);

    postUpdateProgress(responseLength, bytesDownloaded, UpdateStatus.DOWNLOADED, showNotification);
    safeSetTaskResult(downloadTaskCompletionSource, apkFile);
  }

  private static boolean isResponseSuccess(int responseCode) {
    return responseCode >= 200 && responseCode < 300;
  }

  private long downloadToDisk(
      HttpsURLConnection connection, long totalSize, String fileName, boolean showNotification)
      throws FirebaseAppDistributionException {
    context.deleteFile(fileName);
    int fileCreationMode =
        VERSION.SDK_INT >= VERSION_CODES.N ? Context.MODE_PRIVATE : Context.MODE_WORLD_READABLE;
    long bytesDownloaded = 0;
    try (BufferedOutputStream outputStream =
            new BufferedOutputStream(context.openFileOutput(fileName, fileCreationMode));
        InputStream inputStream = connection.getInputStream()) {
      byte[] data = new byte[8 * 1024];
      int readSize = inputStream.read(data);
      long lastMsUpdated = 0;

      while (readSize != -1) {
        outputStream.write(data, 0, readSize);
        bytesDownloaded += readSize;
        readSize = inputStream.read(data);

        // update progress logic for onProgressListener
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - lastMsUpdated > UPDATE_INTERVAL_MS) {
          lastMsUpdated = currentTimeMs;
          postUpdateProgress(
              totalSize, bytesDownloaded, UpdateStatus.DOWNLOADING, showNotification);
        }
      }
    } catch (IOException e) {
      postUpdateProgress(
          totalSize, bytesDownloaded, UpdateStatus.DOWNLOAD_FAILED, showNotification);
      throw new FirebaseAppDistributionException("Failed to download APK", DOWNLOAD_FAILURE, e);
    }
    return bytesDownloaded;
  }

  private void validateJarFile(
      File apkFile, long totalSize, boolean showNotification, long bytesDownloaded)
      throws FirebaseAppDistributionException {
    try {
      new JarFile(apkFile).close();
    } catch (IOException e) {
      postUpdateProgress(
          totalSize, bytesDownloaded, UpdateStatus.DOWNLOAD_FAILED, showNotification);
      throw new FirebaseAppDistributionException(
          "Downloaded APK was not a valid JAR file", DOWNLOAD_FAILURE, e);
    }
  }

  private String getApkFileName() {
    try {
      String applicationName =
          context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
      return applicationName + ".apk";
    } catch (Exception e) {
      LogWrapper.getInstance()
          .w(
              TAG
                  + "Unable to retrieve app name. Using generic file name for APK: "
                  + DEFAULT_APK_FILE_NAME);
      return DEFAULT_APK_FILE_NAME;
    }
  }

  private void setUpdateTaskCompletionError(FirebaseAppDistributionException e) {
    synchronized (updateTaskLock) {
      safeSetTaskException(cachedUpdateTask, e);
    }
  }

  private void setUpdateTaskCompletionErrorWithDefault(Exception e, String message, Status status) {
    if (e instanceof FirebaseAppDistributionException) {
      setUpdateTaskCompletionError((FirebaseAppDistributionException) e);
    } else {
      setUpdateTaskCompletionError(new FirebaseAppDistributionException(message, status, e));
    }
  }

  private void postUpdateProgress(
      long totalBytes, long downloadedBytes, UpdateStatus status, boolean showNotification) {
    synchronized (updateTaskLock) {
      cachedUpdateTask.updateProgress(
          UpdateProgress.builder()
              .setApkFileTotalBytes(totalBytes)
              .setApkBytesDownloaded(downloadedBytes)
              .setUpdateStatus(status)
              .build());
    }
    if (showNotification) {
      appDistributionNotificationsManager.updateNotification(totalBytes, downloadedBytes, status);
    }
  }
}
