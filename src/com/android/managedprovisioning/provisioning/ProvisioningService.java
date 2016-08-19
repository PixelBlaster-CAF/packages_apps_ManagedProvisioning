/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.provisioning;

import static android.app.admin.DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE;
import static android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_CANCEL_PROVISIONING;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_GET_PROVISIONING_STATE;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_PROGRESS_UPDATE;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_PROVISIONING_CANCELLED;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_PROVISIONING_ERROR;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_PROVISIONING_SUCCESS;
import static com.android.managedprovisioning.provisioning.Constants.ACTION_START_PROVISIONING;
import static com.android.managedprovisioning.provisioning.Constants.EXTRA_FACTORY_RESET_REQUIRED;
import static com.android.managedprovisioning.provisioning.Constants.EXTRA_PROGRESS_MESSAGE_ID_KEY;
import static com.android.managedprovisioning.provisioning.Constants.EXTRA_USER_VISIBLE_ERROR_ID_KEY;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.UserHandle;
import android.support.v4.content.LocalBroadcastManager;

import com.android.managedprovisioning.DeviceOwnerProvisioningActivity;
import com.android.managedprovisioning.MdmReceivedSuccessReceiver;
import com.android.managedprovisioning.ProfileOwnerProvisioningActivity;
import com.android.managedprovisioning.ProvisionLogger;
import com.android.managedprovisioning.R;
import com.android.managedprovisioning.common.Utils;
import com.android.managedprovisioning.model.ProvisioningParams;

/**
 * Service that runs the provisioning process.
 *
 * <p>This service is started from and sends updates to one of the two provisioning activities:
 * {@link ProfileOwnerProvisioningActivity} or {@link DeviceOwnerProvisioningActivity} which
 * contain the provisioning UI.</p>
 *
 * <p>The actual execution of the various provisioning tasks is handled by the
 * {@link AbstractProvisioningController} and the main purpose of this service is to decouple the
 * task execution from the activity life-cycle.</p>
 */
public class ProvisioningService extends Service
        implements AbstractProvisioningController.ProvisioningServiceInterface {
    private ProvisioningParams mParams;

    private final Utils mUtils = new Utils();

    private AbstractProvisioningController mController;
    private HandlerThread mHandlerThread;

    @Override
    public void onCreate() {
        super.onCreate();

        mHandlerThread = new HandlerThread("ProvisioningHandler");
        mHandlerThread.start();
    }

    @Override
    public void onDestroy() {
        mHandlerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            ProvisionLogger.logw("Missing intent or action: " + intent);
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_CANCEL_PROVISIONING:
                ProvisionLogger.logd("Cancelling profile owner provisioning service");
                if (mController != null) {
                    mController.cancel();
                } else {
                    ProvisionLogger.logw("Cancelling provisioning, but controller is null");
                }
                break;
            case ACTION_START_PROVISIONING:
                if (mController == null) {
                    ProvisionLogger.logd("Starting provisioning service");
                    mParams = intent.getParcelableExtra(ProvisioningParams.EXTRA_PROVISIONING_PARAMS);
                    mController = buildController();
                    mController.initialize();
                    mController.start();
                } else {
                    ProvisionLogger.loge("Provisioning start requested,"
                            + " but controller not null");
                    error(R.string.device_owner_error_general, false);
                }
                break;
            case ACTION_GET_PROVISIONING_STATE:
                if (mController == null) {
                    ProvisionLogger.loge("Provisioning status requested,"
                            + " but provisioning not ongoing");
                    error(R.string.device_owner_error_general, false);
                } else {
                    mController.updateStatus();
                }
                break;
            default:
                ProvisionLogger.loge("Unknown intent action: " + intent.getAction());
        }
        return START_NOT_STICKY;
    }

    /**
     * This method constructs the controller used for the given type of provisioning.
     */
    private AbstractProvisioningController buildController() {
        if (mUtils.isDeviceOwnerAction(mParams.provisioningAction)) {
            return new DeviceOwnerProvisioningController(
                    this,
                    mParams,
                    UserHandle.myUserId(),
                    this,
                    mHandlerThread.getLooper());
        } else {
            return new ProfileOwnerProvisioningController(
                    this,
                    mParams,
                    UserHandle.myUserId(),
                    this,
                    mHandlerThread.getLooper());
        }
    }

    /**
     * Called when the new profile or managed user is ready for provisioning (the profile is created
     * and all the apps not needed have been deleted).
     */
    @Override
    public void provisioningComplete() {
        if (ACTION_PROVISION_MANAGED_PROFILE.equals(mParams.provisioningAction)
                && mUtils.isUserSetupCompleted(this)) {
            notifyMdmAndCleanup();
        }
        Intent successIntent = new Intent(ACTION_PROVISIONING_SUCCESS);
        LocalBroadcastManager.getInstance(this).sendBroadcast(successIntent);
    }

    /**
     * Notify the mdm that provisioning has completed. When the mdm has received the intent, stop
     * the service and notify the {@link ProfileOwnerProvisioningActivity} so that it can finish
     * itself.
     */
    // TODO: Consider moving this into FinalizationActivity
    private void notifyMdmAndCleanup() {

        // If profile owner provisioning was started after current user setup is completed, then we
        // can directly send the ACTION_PROFILE_PROVISIONING_COMPLETE broadcast to the MDM.
        // But if the provisioning was started as part of setup wizard flow, we signal setup-wizard
        // should shutdown via DPM.setUserProvisioningState(), which will result in a finalization
        // intent being sent to us once setup-wizard finishes. As part of the finalization intent
        // handling we then broadcast ACTION_PROFILE_PROVISIONING_COMPLETE.
        UserHandle managedUserHandle = mUtils.getManagedProfile(this);

        // Use an ordered broadcast, so that we only finish when the mdm has received it.
        // Avoids a lag in the transition between provisioning and the mdm.
        BroadcastReceiver mdmReceivedSuccessReceiver = new MdmReceivedSuccessReceiver(
                mParams.accountToMigrate, mParams.deviceAdminComponentName.getPackageName());

        Intent completeIntent = new Intent(ACTION_PROFILE_PROVISIONING_COMPLETE);
        completeIntent.setComponent(mParams.deviceAdminComponentName);
        completeIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES |
                Intent.FLAG_RECEIVER_FOREGROUND);
        if (mParams.adminExtrasBundle != null) {
            completeIntent.putExtra(EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                    mParams.adminExtrasBundle);
        }

        sendOrderedBroadcastAsUser(completeIntent, managedUserHandle, null,
                mdmReceivedSuccessReceiver, null, Activity.RESULT_OK, null, null);
        ProvisionLogger.logd("Provisioning complete broadcast has been sent to user "
                + managedUserHandle.getIdentifier());
    }

    @Override
    public void progressUpdate(int progressMessage) {
        Intent intent = new Intent(ACTION_PROGRESS_UPDATE);
        intent.putExtra(EXTRA_PROGRESS_MESSAGE_ID_KEY, progressMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void error(int dialogMessage, boolean factoryResetRequired) {
        Intent intent = new Intent(ACTION_PROVISIONING_ERROR);
        intent.putExtra(EXTRA_USER_VISIBLE_ERROR_ID_KEY, dialogMessage);
        intent.putExtra(EXTRA_FACTORY_RESET_REQUIRED, factoryResetRequired);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void cancelled() {
        Intent cancelIntent = new Intent(ACTION_PROVISIONING_CANCELLED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(cancelIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}