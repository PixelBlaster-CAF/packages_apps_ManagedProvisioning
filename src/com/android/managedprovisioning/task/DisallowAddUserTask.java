/*
 * Copyright 2015, The Android Open Source Project
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

package com.android.managedprovisioning.task;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.analytics.MetricsWriterFactory;
import com.android.managedprovisioning.analytics.ProvisioningAnalyticsTracker;
import com.android.managedprovisioning.common.ManagedProvisioningSharedPreferences;
import com.android.managedprovisioning.common.ProvisionLogger;
import com.android.managedprovisioning.common.SettingsFacade;
import com.android.managedprovisioning.model.ProvisioningParams;

/**
 * Disables user addition for all users on the device.
 */
public class DisallowAddUserTask extends AbstractProvisioningTask {
    private final boolean mIsHeadlessSystemUserMode;
    private final UserManager mUserManager;

    public DisallowAddUserTask(
            Context context,
            ProvisioningParams params,
            Callback callback) {
        this(UserManager.isHeadlessSystemUserMode(), context, params, callback,
                new ProvisioningAnalyticsTracker(
                        MetricsWriterFactory.getMetricsWriter(context, new SettingsFacade()),
                        new ManagedProvisioningSharedPreferences(context)));
    }

    @VisibleForTesting
    public DisallowAddUserTask(boolean headlessSystemUser,
            Context context,
            ProvisioningParams params,
            Callback callback,
            ProvisioningAnalyticsTracker provisioningAnalyticsTracker) {
        super(context, params, callback, provisioningAnalyticsTracker);
        mIsHeadlessSystemUserMode = headlessSystemUser;
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public void run(int userId) {
        ProvisionLogger.logi("Running as user " + userId
                + (mIsHeadlessSystemUserMode ? " on headless system user mode" : ""));
        if (mIsHeadlessSystemUserMode) {
            if (userId != UserHandle.USER_SYSTEM) {
                // It shouldn't happen, but it doesn't hurt to log...
                ProvisionLogger.loge("App NOT running as system user on headless system user mode");
            }
            ProvisionLogger.logi("Not setting DISALLOW_ADD_USER on headless system user mode.");
            success();
            return;
        }

        for (UserInfo userInfo : mUserManager.getUsers()) {
            UserHandle userHandle = userInfo.getUserHandle();
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER, userHandle)) {
                mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, true, userHandle);
                ProvisionLogger.logi("DISALLOW_ADD_USER restriction set on user: " + userInfo.id);
            }
        }
        success();
    }

}
