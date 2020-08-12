/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.systemui.R;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 */
class UdfpsController {
    private static final String TAG = "UdfpsController";

    private final FingerprintManager mFingerprintManager;
    private final WindowManager mWindowManager;
    private final Handler mHandler;
    private final UdfpsView mView;
    private final WindowManager.LayoutParams mLayoutParams;
    private final String mHbmPath;
    private final String mHbmEnableCommand;
    private final String mHbmDisableCommand;

    private boolean mIsOverlayShowing;

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay() {
            UdfpsController.this.showUdfpsOverlay();
        }

        @Override
        public void hideUdfpsOverlay() {
            UdfpsController.this.hideUdfpsOverlay();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnTouchListener mOnTouchListener = (v, event) -> {
        UdfpsView view = (UdfpsView) v;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                boolean isValidTouch = view.isValidTouch(event.getX(), event.getY(),
                        event.getPressure());
                if (!view.isFingerDown() && isValidTouch) {
                    onFingerDown((int) event.getX(), (int) event.getY(), event.getTouchMinor(),
                            event.getTouchMajor());
                } else if (view.isFingerDown() && !isValidTouch) {
                    onFingerUp();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (view.isFingerDown()) {
                    onFingerUp();
                }
                return true;

            default:
                return false;
        }
    };

    UdfpsController(Context context) {
        mFingerprintManager = context.getSystemService(FingerprintManager.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        mHandler = new Handler(Looper.getMainLooper());

        mLayoutParams = createLayoutParams(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setLayoutParams(mLayoutParams);
        mView = (UdfpsView) LayoutInflater.from(context).inflate(R.layout.udfps_view, layout,
                false);
        mView.setOnTouchListener(mOnTouchListener);

        mHbmPath = context.getResources().getString(R.string.udfps_hbm_sysfs_path);
        mHbmEnableCommand = context.getResources().getString(R.string.udfps_hbm_enable_command);
        mHbmDisableCommand = context.getResources().getString(R.string.udfps_hbm_disable_command);

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());
        mIsOverlayShowing = false;
    }

    private void showUdfpsOverlay() {
        mHandler.post(() -> {
            Log.v(TAG, "showUdfpsOverlay | adding window");
            if (!mIsOverlayShowing) {
                try {
                    mWindowManager.addView(mView, mLayoutParams);
                    mIsOverlayShowing = true;
                } catch (RuntimeException e) {
                    Log.e(TAG, "showUdfpsOverlay | failed to add window", e);
                }
            }
        });
    }

    private void hideUdfpsOverlay() {
        onFingerUp();
        mHandler.post(() -> {
            Log.v(TAG, "hideUdfpsOverlay | removing window");
            if (mIsOverlayShowing) {
                mWindowManager.removeView(mView);
                mIsOverlayShowing = false;
            }
        });
    }

    private void onFingerDown(int x, int y, float minor, float major) {
        try {
            FileWriter fw = new FileWriter(mHbmPath);
            fw.write(mHbmEnableCommand);
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "onFingerDown | failed to enable HBM: " + e.getMessage());
        }
        mView.onFingerDown();
        mFingerprintManager.onFingerDown(x, y, minor, major);
    }

    private void onFingerUp() {
        mFingerprintManager.onFingerUp();
        mView.onFingerUp();
        try {
            FileWriter fw = new FileWriter(mHbmPath);
            fw.write(mHbmDisableCommand);
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "onFingerUp | failed to disable HBM: " + e.getMessage());
        }
    }

    private static WindowManager.LayoutParams createLayoutParams(Context context) {
        Point displaySize = new Point();
        context.getDisplay().getRealSize(displaySize);
        // TODO(b/160025856): move to the "dump" method.
        Log.v(TAG, "createLayoutParams | display size: " + displaySize.x + "x"
                + displaySize.y);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                displaySize.x,
                displaySize.y,
                // TODO(b/152419866): Use the UDFPS window type when it becomes available.
                WindowManager.LayoutParams.TYPE_BOOT_PROGRESS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.setTitle(TAG);
        lp.windowAnimations = 0;
        return lp;
    }
}
