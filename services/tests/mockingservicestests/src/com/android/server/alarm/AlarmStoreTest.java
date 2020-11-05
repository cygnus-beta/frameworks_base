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

package com.android.server.alarm;

import static com.android.server.alarm.Constants.TEST_CALLING_PACKAGE;
import static com.android.server.alarm.Constants.TEST_CALLING_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AlarmStoreTest {
    private AlarmStore mAlarmStore;

    @Before
    public void setUp() {
        mAlarmStore = new BatchingAlarmStore(null);
    }

    private static Alarm createAlarm(long whenElapsed, long windowLength,
            AlarmManager.AlarmClockInfo alarmClock) {
        return createAlarm(AlarmManager.ELAPSED_REALTIME, whenElapsed, windowLength,
                alarmClock);
    }

    private static Alarm createWakeupAlarm(long whenElapsed, long windowLength,
            AlarmManager.AlarmClockInfo alarmClock) {
        return createAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, windowLength,
                alarmClock);
    }

    private static Alarm createAlarm(int type, long whenElapsed, long windowLength,
            AlarmManager.AlarmClockInfo alarmClock) {
        return new Alarm(type, whenElapsed, whenElapsed, windowLength, 0, mock(PendingIntent.class),
                null, null, null, 0, alarmClock, TEST_CALLING_UID, TEST_CALLING_PACKAGE);
    }

    private void addAlarmsToStore(Alarm... alarms) {
        for (Alarm a : alarms) {
            mAlarmStore.add(a);
        }
    }

    @Test
    public void add() {
        final Alarm a1 = createAlarm(1, 0, null);
        mAlarmStore.add(a1);
        assertEquals(1, mAlarmStore.size());

        final Alarm a2 = createAlarm(2, 0, null);
        mAlarmStore.add(a2);
        assertEquals(2, mAlarmStore.size());

        ArrayList<Alarm> alarmsAdded = mAlarmStore.asList();
        assertEquals(2, alarmsAdded.size());
        assertTrue(alarmsAdded.contains(a1) && alarmsAdded.contains(a2));
    }

    @Test
    public void remove() {
        final Alarm a1 = createAlarm(1, 0, null);
        final Alarm a2 = createAlarm(2, 0, null);
        final Alarm a5 = createAlarm(5, 0, null);
        addAlarmsToStore(a1, a2, a5);

        ArrayList<Alarm> removed = mAlarmStore.remove(a -> (a.getWhenElapsed() < 4));
        assertEquals(2, removed.size());
        assertEquals(1, mAlarmStore.size());
        assertTrue(removed.contains(a1) && removed.contains(a2));

        final Alarm a8 = createAlarm(8, 0, null);
        addAlarmsToStore(a8, a2, a1);

        removed = mAlarmStore.remove(unused -> false);
        assertEquals(0, removed.size());
        assertEquals(4, mAlarmStore.size());

        removed = mAlarmStore.remove(unused -> true);
        assertEquals(4, removed.size());
        assertEquals(0, mAlarmStore.size());
    }

    @Test
    public void removePendingAlarms() {
        final Alarm a1to11 = createAlarm(1, 10, null);
        final Alarm a2to5 = createAlarm(2, 3, null);
        final Alarm a6to9 = createAlarm(6, 3, null);
        addAlarmsToStore(a2to5, a6to9, a1to11);

        final ArrayList<Alarm> pendingAt0 = mAlarmStore.removePendingAlarms(0);
        assertEquals(0, pendingAt0.size());
        assertEquals(3, mAlarmStore.size());

        final ArrayList<Alarm> pendingAt3 = mAlarmStore.removePendingAlarms(3);
        assertEquals(2, pendingAt3.size());
        assertTrue(pendingAt3.contains(a1to11) && pendingAt3.contains(a2to5));
        assertEquals(1, mAlarmStore.size());

        addAlarmsToStore(a2to5, a1to11);
        final ArrayList<Alarm> pendingAt7 = mAlarmStore.removePendingAlarms(7);
        assertEquals(3, pendingAt7.size());
        assertTrue(pendingAt7.contains(a1to11) && pendingAt7.contains(a2to5) && pendingAt7.contains(
                a6to9));
        assertEquals(0, mAlarmStore.size());
    }

    @Test
    public void getNextWakeupDeliveryTime() {
        final Alarm a1to10 = createAlarm(1, 9, null);
        final Alarm a3to8wakeup = createWakeupAlarm(3, 5, null);
        final Alarm a6wakeup = createWakeupAlarm(6, 0, null);
        final Alarm a5 = createAlarm(5, 0, null);
        addAlarmsToStore(a5, a6wakeup, a3to8wakeup, a1to10);

        // The wakeup alarms are [6] and [3, 8], hence 6 is the latest time till when we can
        // defer delivering any wakeup alarm.
        assertTrue(mAlarmStore.getNextWakeupDeliveryTime() <= 6);

        mAlarmStore.remove(a -> a.wakeup);
        assertEquals(2, mAlarmStore.size());
        // No wakeup alarms left.
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());

        mAlarmStore.remove(unused -> true);
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());
    }

    @Test
    public void getNextDeliveryTime() {
        final Alarm a1to10 = createAlarm(1, 9, null);
        final Alarm a3to8wakeup = createWakeupAlarm(3, 5, null);
        final Alarm a6wakeup = createWakeupAlarm(6, 0, null);
        final Alarm a5 = createAlarm(5, 0, null);
        addAlarmsToStore(a5, a6wakeup, a3to8wakeup, a1to10);

        assertTrue(mAlarmStore.getNextDeliveryTime() <= 5);

        mAlarmStore.remove(unused -> true);
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());
    }

    @Test
    public void updateAlarmDeliveries() {
        final Alarm a5 = createAlarm(5, 0, null);
        final Alarm a8 = createAlarm(8, 0, null);
        final Alarm a10 = createAlarm(10, 0, null);
        addAlarmsToStore(a8, a10, a5);

        assertEquals(5, mAlarmStore.getNextDeliveryTime());

        mAlarmStore.updateAlarmDeliveries(a -> {
            a.setPolicyElapsed(Alarm.REQUESTER_POLICY_INDEX, a.getWhenElapsed() + 3);
            return true;
        });
        assertEquals(8, mAlarmStore.getNextDeliveryTime());

        mAlarmStore.updateAlarmDeliveries(a -> {
            a.setPolicyElapsed(Alarm.REQUESTER_POLICY_INDEX, 20 - a.getWhenElapsed());
            return true;
        });
        assertEquals(7, mAlarmStore.getNextDeliveryTime());
    }
}
