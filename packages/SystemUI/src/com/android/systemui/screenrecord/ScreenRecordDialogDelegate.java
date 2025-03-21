/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static android.app.Activity.RESULT_OK;

import static com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity.KEY_CAPTURE_TARGET;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.NONE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Prefs;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.Arrays;
import java.util.List;

/**
 * Dialog to select screen recording options
 */
public class ScreenRecordDialogDelegate implements SystemUIDialog.Delegate {
    private static final List<ScreenRecordingAudioSource> MODES = Arrays.asList(INTERNAL, MIC,
            MIC_AND_INTERNAL);
    private static final List<Integer> QUALITIES = Arrays.asList(0, 1, 2);
    private static final long DELAY_MS = 3000;
    private static final long NO_DELAY = 100;
    private static final long INTERVAL_MS = 1000;
    private static final String TAG = "ScreenRecordDialog";
    private static final String PREFS = "screenrecord_";
    private static final String PREF_TAPS = "show_taps";
    private static final String PREF_LOW = "use_low_quality_2";
    private static final String PREF_HEVC = "use_hevc";
    private static final String PREF_AUDIO = "use_audio";
    private static final String PREF_AUDIO_SOURCE = "audio_source";
    private static final String PREF_SKIP = "skip_timer";

    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final UserContextProvider mUserContextProvider;
    private final RecordingController mController;
    private final Context mUserContext;
    private final boolean mIsHEVCAllowed;
    private final Runnable mOnStartRecordingClicked;
    private SystemUIDialog mDialog;
    private Switch mTapsSwitch;
    private Switch mHEVCSwitch;
    private Switch mAudioSwitch;
    private Switch mSkipSwitch;
    private Spinner mLowQualitySpinner;
    private Spinner mOptions;

    @AssistedFactory
    public interface Factory {
        ScreenRecordDialogDelegate create(
                RecordingController recordingController,
                @Nullable Runnable onStartRecordingClicked
        );
    }

    @AssistedInject
    public ScreenRecordDialogDelegate(
            SystemUIDialog.Factory systemUIDialogFactory,
            UserContextProvider userContextProvider,
            @Assisted RecordingController controller,
            @Assisted @Nullable Runnable onStartRecordingClicked) {
        mSystemUIDialogFactory = systemUIDialogFactory;
        mUserContextProvider = userContextProvider;
        mController = controller;
        mUserContext = mUserContextProvider.getUserContext();
        mOnStartRecordingClicked = onStartRecordingClicked;
        mIsHEVCAllowed = controller.isHEVCAllowed();
    }

    @Override
    public SystemUIDialog createDialog() {
        return mSystemUIDialogFactory.create(this);
    }

    @Override
    public void onCreate(SystemUIDialog dialog, Bundle savedInstanceState) {
        mDialog = dialog;
        Window window = dialog.getWindow();

        window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);

        window.setGravity(Gravity.CENTER);
        dialog.setTitle(R.string.screenrecord_title);

        dialog.setContentView(R.layout.screen_record_dialog);

        TextView cancelBtn = dialog.findViewById(R.id.button_cancel);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        TextView startBtn = dialog.findViewById(R.id.button_start);
        startBtn.setOnClickListener(v -> {
            if (mOnStartRecordingClicked != null) {
                // Note that it is important to run this callback before dismissing, so that the
                // callback can disable the dialog exit animation if it wants to.
                mOnStartRecordingClicked.run();
            }

            // Start full-screen recording
            requestScreenCapture(/* captureTarget= */ null);
            dialog.dismiss();
        });

        mAudioSwitch = dialog.findViewById(R.id.screenrecord_audio_switch);
        mTapsSwitch = dialog.findViewById(R.id.screenrecord_taps_switch);
        mSkipSwitch = dialog.findViewById(R.id.screenrecord_skip_switch);
        mHEVCSwitch = dialog.findViewById(R.id.screenrecord_hevc_switch);
        mOptions = dialog.findViewById(R.id.screen_recording_options);
        ArrayAdapter a = new ScreenRecordingAdapter(dialog.getContext().getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MODES);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOptions.setAdapter(a);
        mOptions.setOnItemClickListenerInt((parent, view, position, id) -> {
            mAudioSwitch.setChecked(true);
        });
        // disable redundant Touch & Hold accessibility action for Switch Access
        mOptions.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfo info) {
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
        });
        mOptions.setLongClickable(false);

        mLowQualitySpinner = dialog.findViewById(R.id.screenrecord_lowquality_spinner);
        ArrayAdapter b = new ScreenRecordingQualityAdapter(dialog.getContext().getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                QUALITIES);
        b.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mLowQualitySpinner.setAdapter(b);
        // disable redundant Touch & Hold accessibility action for Switch Access
        mLowQualitySpinner.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfo info) {
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
        });

        if (!mIsHEVCAllowed) {
            View hevcView = dialog.findViewById(R.id.show_hevc);
            if (hevcView != null) {
                hevcView.setVisibility(View.GONE);
            }
        }

        dialog.setOnDismissListener(d -> { savePreferences(); });

        mTapsSwitch.setChecked(Prefs.getInt(mUserContext, PREFS + PREF_TAPS, 0) == 1);
        mLowQualitySpinner.setSelection(Prefs.getInt(mUserContext, PREFS + PREF_LOW, 0));
        mAudioSwitch.setChecked(Prefs.getInt(mUserContext, PREFS + PREF_AUDIO, 0) == 1);
        mOptions.setSelection(Prefs.getInt(mUserContext, PREFS + PREF_AUDIO_SOURCE, 0));
        mSkipSwitch.setChecked(Prefs.getInt(mUserContext, PREFS + PREF_SKIP, 0) == 1);
        mHEVCSwitch.setChecked(mIsHEVCAllowed && Prefs.getInt(mUserContext, PREFS + PREF_HEVC, 1) == 1);
    }

    /**
     * Starts screen capture after some countdown
     * @param captureTarget target to capture (could be e.g. a task) or
     *                      null to record the whole screen
     */
    private void requestScreenCapture(@Nullable MediaProjectionCaptureTarget captureTarget) {
        boolean showTaps = mTapsSwitch.isChecked();
        boolean skipTime = mSkipSwitch.isChecked();
        boolean audioSwitch = mAudioSwitch.isChecked();
        boolean hevc = mIsHEVCAllowed && mHEVCSwitch.isChecked();
        int lowQuality = mLowQualitySpinner.getSelectedItemPosition();
        ScreenRecordingAudioSource audioMode = audioSwitch
                ? (ScreenRecordingAudioSource) mOptions.getSelectedItem() : NONE;
        PendingIntent startIntent = PendingIntent.getForegroundService(mUserContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                        mUserContext, Activity.RESULT_OK,
                        audioMode.ordinal(), showTaps, captureTarget,
                        lowQuality, hevc),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(mUserContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(mUserContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mDialog.setOnDismissListener(null);
        savePreferences();
        mController.startCountdown(skipTime ? NO_DELAY : DELAY_MS, INTERVAL_MS, startIntent, stopIntent);
    }

    private void savePreferences() {
        Prefs.putInt(mUserContext, PREFS + PREF_TAPS, mTapsSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(mUserContext, PREFS + PREF_LOW, mLowQualitySpinner.getSelectedItemPosition());
        Prefs.putInt(mUserContext, PREFS + PREF_AUDIO, mAudioSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(mUserContext, PREFS + PREF_AUDIO_SOURCE, mOptions.getSelectedItemPosition());
        Prefs.putInt(mUserContext, PREFS + PREF_SKIP, mSkipSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(mUserContext, PREFS + PREF_HEVC, mIsHEVCAllowed && mHEVCSwitch.isChecked() ? 1 : 0);
    }

    private class CaptureTargetResultReceiver extends ResultReceiver {

        CaptureTargetResultReceiver() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == RESULT_OK) {
                MediaProjectionCaptureTarget captureTarget = resultData
                        .getParcelable(KEY_CAPTURE_TARGET, MediaProjectionCaptureTarget.class);

                // Start recording of the selected target
                requestScreenCapture(captureTarget);
            }
        }
    }
}
