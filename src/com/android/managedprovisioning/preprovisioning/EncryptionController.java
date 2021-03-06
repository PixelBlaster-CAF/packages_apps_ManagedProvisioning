/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.preprovisioning;

import static com.android.internal.util.Preconditions.checkNotNull;

import static java.util.Objects.requireNonNull;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.common.Globals;
import com.android.managedprovisioning.common.NotificationHelper;
import com.android.managedprovisioning.common.ProvisionLogger;
import com.android.managedprovisioning.common.SettingsFacade;
import com.android.managedprovisioning.common.TransitionHelper;
import com.android.managedprovisioning.common.Utils;
import com.android.managedprovisioning.model.ProvisioningParams;

import java.io.File;
import java.util.function.Consumer;

/**
 * This controller manages all things related to the encryption reboot.
 *
 * <p>An encryption reminder can be scheduled using {@link #setEncryptionReminder}. This will store
 * the provisioning data to disk and enable a HOME intent receiver. After the reboot, the HOME
 * intent receiver calls {@link #resumeProvisioning} at which point a new provisioning intent is
 * sent. The reminder can be cancelled using {@link #cancelEncryptionReminder}.
 */
public class EncryptionController {
    private final Context mContext;
    private final Utils mUtils;
    private final SettingsFacade mSettingsFacade;
    private final ComponentName mHomeReceiver;
    private final NotificationHelper mNotificationHelper;
    private final int mUserId;

    private boolean mProvisioningResumed = false;

    private final PackageManager mPackageManager;

    private static EncryptionController sInstance;

    private static final String PROVISIONING_PARAMS_FILE_NAME
            = "encryption_controller_provisioning_params.xml";
    private static final Object LOCK = new Object();

    /**
     * Returns an instance of {@link EncryptionController}.
     *
     * <p>This method is thread-safe.
     */
    public static EncryptionController getInstance(
            Context context,
            ComponentName homeReceiver) {
        requireNonNull(context);
        requireNonNull(homeReceiver);
        synchronized (LOCK) {
            if (sInstance == null) {
                sInstance = new EncryptionController(context.getApplicationContext(), homeReceiver);
            }
            return sInstance;
        }
    }

    private EncryptionController(Context context, ComponentName homeReceiver) {
        this(context,
                new Utils(),
                new SettingsFacade(),
                homeReceiver,
                new NotificationHelper(context),
                UserHandle.myUserId());
    }

    @VisibleForTesting
    EncryptionController(Context context,
            Utils utils,
            SettingsFacade settingsFacade,
            ComponentName homeReceiver,
            NotificationHelper resumeNotificationHelper,
            int userId) {
        mContext = checkNotNull(context, "Context must not be null").getApplicationContext();
        mSettingsFacade = checkNotNull(settingsFacade);
        mUtils = checkNotNull(utils, "Utils must not be null");
        mHomeReceiver = checkNotNull(homeReceiver, "HomeReceiver must not be null");
        mNotificationHelper = checkNotNull(resumeNotificationHelper,
                "ResumeNotificationHelper must not be null");
        mUserId = userId;

        mPackageManager = context.getPackageManager();
    }

    /**
     * Store a resume intent into persistent storage. Provisioning will be resumed after reboot
     * using the stored intent.
     *
     * @param params the params to be stored.
     */
    public void setEncryptionReminder(ProvisioningParams params) {
        ProvisionLogger.logd("Setting provisioning reminder for action: "
                + params.provisioningAction);
        params.save(getProvisioningParamsFile(mContext));
        // Only enable the HOME intent receiver for flows inside SUW, as showing the notification
        // for non-SUW flows is less time cricital.
        if (!mSettingsFacade.isUserSetupCompleted(mContext)) {
            ProvisionLogger.logd("Enabling PostEncryptionActivity");
            mUtils.enableComponent(mHomeReceiver, mUserId);
            // To ensure that the enabled state has been persisted to disk, we flush the
            // restrictions.
            mPackageManager.flushPackageRestrictionsAsUser(mUserId);
        }
    }

    /**
     * Cancel the encryption reminder to avoid further resumption of encryption.
     */
    public void cancelEncryptionReminder() {
        ProvisionLogger.logd("Cancelling provisioning reminder.");
        getProvisioningParamsFile(mContext).delete();
        mUtils.disableComponent(mHomeReceiver, mUserId);
    }

    /**
     * Resume provisioning after encryption has happened.
     *
     * <p>If the device has already been set up, we show a notification to resume provisioning,
     * otherwise we continue provisioning direclty.
     *
     * <p>Note that this method has to be called on the main thread.
     */
    public void resumeProvisioning() {
        resumeProvisioningInternal(mContext::startActivity);
    }

    /**
     * Similar to {@link #resumeProvisioning()}, but starts provisioning with a cross-activity
     * transition.
     * @param activity the parent {@link Activity} to launch provisioning
     * @param transitionHelper helper to determine the appropriate transition to use
     */
    public void resumeProvisioning(Activity activity, TransitionHelper transitionHelper) {
        resumeProvisioningInternal(
                intent -> transitionHelper.startActivityWithTransition(activity, intent));
    }

    private void resumeProvisioningInternal(Consumer<Intent> launchActivityConsumer) {
        // verify that this method was called on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("resumeProvisioning must be called on the main thread");
        }

        if (mProvisioningResumed) {
            // If provisioning has already been resumed, don't resume it again.
            // This can happen if the HOME intent receiver was launched multiple times or the
            // BOOT_COMPLETED was received after the HOME intent receiver had already been launched.
            return;
        }

        ProvisioningParams params = ProvisioningParams.load(getProvisioningParamsFile(mContext));

        if (params != null) {
            Intent resumeIntent = new Intent(Globals.ACTION_RESUME_PROVISIONING);
            resumeIntent.putExtra(ProvisioningParams.EXTRA_PROVISIONING_PARAMS, params);
            mProvisioningResumed = true;
            String action = params.provisioningAction;
            ProvisionLogger.logd("Provisioning resumed after encryption with action: " + action);

            if (!mUtils.isPhysicalDeviceEncrypted()) {
                ProvisionLogger.loge("Device is not encrypted after provisioning with"
                        + " action " + action + " but it should");
                return;
            }
            resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (mUtils.isProfileOwnerAction(action)) {
                if (mSettingsFacade.isUserSetupCompleted(mContext)) {
                    mNotificationHelper.showResumeNotification(resumeIntent);
                } else {
                    launchActivityConsumer.accept(resumeIntent);
                }
            } else if (mUtils.isDeviceOwnerAction(action)) {
                launchActivityConsumer.accept(resumeIntent);
            } else {
                ProvisionLogger.loge("Unknown intent action loaded from the intent store: "
                        + action);
            }
        }
    }

    @VisibleForTesting
    File getProvisioningParamsFile(Context context) {
        return new File(context.getFilesDir(), PROVISIONING_PARAMS_FILE_NAME);
    }
}
