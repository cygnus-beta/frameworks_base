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

package com.android.wm.shell.draganddrop;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;

import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_FULLSCREEN;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_BOTTOM;
import static com.android.wm.shell.draganddrop.DragAndDropPolicy.Target.TYPE_SPLIT_RIGHT;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.view.DisplayInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.draganddrop.DragAndDropPolicy.Target;
import com.android.wm.shell.splitscreen.DividerView;
import com.android.wm.shell.splitscreen.SplitScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Tests for the drag and drop policy.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DragAndDropPolicyTest {

    @Mock
    private Context mContext;

    @Mock
    private IActivityTaskManager mIActivityTaskManager;

    @Mock
    private SplitScreen mSplitScreen;

    @Mock
    private DragAndDropPolicy.Starter mStarter;

    private DisplayLayout mDisplayLayout;
    private Insets mInsets;
    private DragAndDropPolicy mPolicy;

    private ClipData mActivityClipData;
    private ClipData mNonResizeableActivityClipData;
    private ClipData mTaskClipData;
    private ClipData mShortcutClipData;

    private ActivityManager.RunningTaskInfo mHomeTask;
    private ActivityManager.RunningTaskInfo mFullscreenAppTask;
    private ActivityManager.RunningTaskInfo mNonResizeableFullscreenAppTask;
    private ActivityManager.RunningTaskInfo mSplitPrimaryAppTask;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        Resources res = mock(Resources.class);
        Configuration config = new Configuration();
        doReturn(config).when(res).getConfiguration();
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = 100;
        info.logicalHeight = 100;
        mDisplayLayout = new DisplayLayout(info, res, false, false);
        mInsets = Insets.of(0, 0, 0, 0);

        DividerView divider = mock(DividerView.class);
        doReturn(divider).when(mSplitScreen).getDividerView();
        doReturn(new Rect(50, 0, 100, 100)).when(divider)
                .getNonMinimizedSplitScreenSecondaryBounds();

        mPolicy = new DragAndDropPolicy(mContext, mIActivityTaskManager, mSplitScreen, mStarter);
        mActivityClipData = createClipData(MIMETYPE_APPLICATION_ACTIVITY);
        mNonResizeableActivityClipData = createClipData(MIMETYPE_APPLICATION_ACTIVITY);
        setClipDataResizeable(mNonResizeableActivityClipData, false);
        mTaskClipData = createClipData(MIMETYPE_APPLICATION_TASK);
        mShortcutClipData = createClipData(MIMETYPE_APPLICATION_SHORTCUT);

        mHomeTask = createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        mFullscreenAppTask = createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        mNonResizeableFullscreenAppTask =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        mNonResizeableFullscreenAppTask.isResizeable = false;
        mSplitPrimaryAppTask = createTaskInfo(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_STANDARD);

        setIsPhone(false);
        setInSplitScreen(false);
        setRunningTask(mFullscreenAppTask);
    }

    /**
     * Creates a clip data that is by default resizeable.
     */
    private ClipData createClipData(String mimeType) {
        ClipDescription clipDescription = new ClipDescription(mimeType, new String[] { mimeType });
        Intent i = new Intent();
        switch (mimeType) {
            case MIMETYPE_APPLICATION_SHORTCUT:
                i.putExtra(Intent.EXTRA_PACKAGE_NAME, "package");
                i.putExtra(Intent.EXTRA_SHORTCUT_ID, "shortcut_id");
                break;
            case MIMETYPE_APPLICATION_TASK:
                i.putExtra(Intent.EXTRA_TASK_ID, 12345);
                break;
            case MIMETYPE_APPLICATION_ACTIVITY:
                i.putExtra(ClipDescription.EXTRA_PENDING_INTENT, mock(PendingIntent.class));
                break;
        }
        i.putExtra(Intent.EXTRA_USER, android.os.Process.myUserHandle());
        ClipData.Item item = new ClipData.Item(i);
        item.setActivityInfo(new ActivityInfo());
        ClipData data = new ClipData(clipDescription, item);
        setClipDataResizeable(data, true);
        return data;
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(int winMode, int actType) {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.configuration.windowConfiguration.setActivityType(actType);
        info.configuration.windowConfiguration.setWindowingMode(winMode);
        info.isResizeable = true;
        return info;
    }

    private void setRunningTask(ActivityManager.RunningTaskInfo task) throws RemoteException {
        doReturn(Collections.singletonList(task)).when(mIActivityTaskManager)
                .getFilteredTasks(anyInt(), anyBoolean());
    }

    private void setClipDataResizeable(ClipData data, boolean resizeable) {
        data.getItemAt(0).getActivityInfo().resizeMode = resizeable
                ? ActivityInfo.RESIZE_MODE_RESIZEABLE
                : ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
    }

    private void setIsPhone(boolean isPhone) {
        Resources res = mock(Resources.class);
        Configuration config = mock(Configuration.class);
        config.smallestScreenWidthDp = isPhone ? 400 : 800;
        doReturn(config).when(res).getConfiguration();
        doReturn(res).when(mContext).getResources();
    }

    private void setInSplitScreen(boolean inSplitscreen) {
        doReturn(inSplitscreen).when(mSplitScreen).isDividerVisible();
    }

    @Test
    public void testDragAppOverFullscreenHome_expectOnlyFullscreenTarget() throws RemoteException {
        setRunningTask(mHomeTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragAppOverFullscreenApp_expectSplitScreenAndFullscreenTargets()
            throws RemoteException {
        setRunningTask(mFullscreenAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        // TODO(b/169894807): For now, only allow splitting to the right/bottom until we have split
        //                    pairs
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN, TYPE_SPLIT_RIGHT);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
        reset(mStarter);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_SPLIT_RIGHT), mActivityClipData);
        verify(mStarter).enterSplitScreen(anyInt(), eq(false));
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragAppOverFullscreenAppPhone_expectVerticalSplitScreenAndFullscreenTargets()
            throws RemoteException {
        setIsPhone(true);
        setRunningTask(mFullscreenAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        // TODO(b/169894807): For now, only allow splitting to the right/bottom until we have split
        //                    pairs
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN, TYPE_SPLIT_BOTTOM);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
        reset(mStarter);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_SPLIT_BOTTOM), mActivityClipData);
        verify(mStarter).enterSplitScreen(anyInt(), eq(false));
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragAppOverFullscreenNonResizeableApp_expectOnlyFullscreenTargets()
            throws RemoteException {
        setRunningTask(mNonResizeableFullscreenAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragNonResizeableAppOverFullscreenApp_expectOnlyFullscreenTargets()
            throws RemoteException {
        setRunningTask(mFullscreenAppTask);
        mPolicy.start(mDisplayLayout, mNonResizeableActivityClipData);
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragAppOverSplitApp_expectFullscreenAndSplitTargets() throws RemoteException {
        setInSplitScreen(true);
        setRunningTask(mSplitPrimaryAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        // TODO(b/169894807): For now, only allow splitting to the right/bottom until we have split
        //                    pairs
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN, TYPE_SPLIT_RIGHT);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
        reset(mStarter);

        // TODO(b/169894807): Just verify starting for the non-docked task until we have app pairs
        mPolicy.handleDrop(filterTargetByType(targets, TYPE_SPLIT_RIGHT), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testDragAppOverSplitAppPhone_expectFullscreenAndVerticalSplitTargets()
            throws RemoteException {
        setIsPhone(true);
        setInSplitScreen(true);
        setRunningTask(mSplitPrimaryAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        // TODO(b/169894807): For now, only allow splitting to the right/bottom until we have split
        //                    pairs
        ArrayList<Target> targets = assertExactTargetTypes(
                mPolicy.getTargets(mInsets), TYPE_FULLSCREEN, TYPE_SPLIT_BOTTOM);

        mPolicy.handleDrop(filterTargetByType(targets, TYPE_FULLSCREEN), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
        reset(mStarter);

        // TODO(b/169894807): Just verify starting for the non-docked task until we have app pairs
        mPolicy.handleDrop(filterTargetByType(targets, TYPE_SPLIT_BOTTOM), mActivityClipData);
        verify(mStarter).startIntent(any(), any());
    }

    @Test
    public void testTargetHitRects() throws RemoteException {
        setRunningTask(mFullscreenAppTask);
        mPolicy.start(mDisplayLayout, mActivityClipData);
        ArrayList<Target> targets = mPolicy.getTargets(mInsets);
        for (Target t : targets) {
            assertTrue(mPolicy.getTargetAtLocation(t.hitRegion.left, t.hitRegion.top) == t);
            assertTrue(mPolicy.getTargetAtLocation(t.hitRegion.right - 1, t.hitRegion.top) == t);
            assertTrue(mPolicy.getTargetAtLocation(t.hitRegion.right - 1, t.hitRegion.bottom - 1)
                    == t);
            assertTrue(mPolicy.getTargetAtLocation(t.hitRegion.left, t.hitRegion.bottom - 1)
                    == t);
        }
    }

    private Target filterTargetByType(ArrayList<Target> targets, int type) {
        for (Target t : targets) {
            if (type == t.type) {
                return t;
            }
        }
        fail("Target with type: " + type + " not found");
        return null;
    }

    private ArrayList<Target> assertExactTargetTypes(ArrayList<Target> targets,
            int... expectedTargetTypes) {
        HashSet<Integer> expected = new HashSet<>();
        for (int t : expectedTargetTypes) {
            expected.add(t);
        }
        for (Target t : targets) {
            if (!expected.contains(t.type)) {
                fail("Found unexpected target type: " + t.type);
            }
            expected.remove(t.type);
        }
        assertTrue(expected.isEmpty());
        return targets;
    }
}
