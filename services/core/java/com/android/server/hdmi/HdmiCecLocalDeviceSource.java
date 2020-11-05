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

package com.android.server.hdmi;

import android.annotation.CallSuper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.Constants.LocalActivePort;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.util.List;

/**
 * Represent a logical source device residing in Android system.
 */
abstract class HdmiCecLocalDeviceSource extends HdmiCecLocalDevice {

    private static final String TAG = "HdmiCecLocalDeviceSource";

    // Device has cec switch functionality or not.
    // Default is false.
    protected boolean mIsSwitchDevice = HdmiProperties.is_switch().orElse(false);

    // Routing port number used for Routing Control.
    // This records the default routing port or the previous valid routing port.
    // Default is HOME input.
    // Note that we don't save active path here because for source device,
    // new Active Source physical address might not match the active path
    @GuardedBy("mLock")
    @LocalActivePort
    private int mRoutingPort = Constants.CEC_SWITCH_HOME;

    // This records the current input of the device.
    // When device is switched to ARC input, mRoutingPort does not record it
    // since it's not an HDMI port used for Routing Control.
    // mLocalActivePort will record whichever input we switch to to keep tracking on
    // the current input status of the device.
    // This can help prevent duplicate switching and provide status information.
    @GuardedBy("mLock")
    @LocalActivePort
    protected int mLocalActivePort = Constants.CEC_SWITCH_HOME;

    // Whether the Routing Coutrol feature is enabled or not. False by default.
    @GuardedBy("mLock")
    protected boolean mRoutingControlFeatureEnabled;

    protected HdmiCecLocalDeviceSource(HdmiControlService service, int deviceType) {
        super(service, deviceType);
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        if (mService.getPortInfo(portId).getType() == HdmiPortInfo.PORT_OUTPUT) {
            mCecMessageCache.flushAll();
        }
        // We'll not invalidate the active source on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (connected) {
            mService.wakeUp();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void sendStandby(int deviceId) {
        assertRunOnServiceThread();

        // Send standby to TV only for now
        int targetAddress = Constants.ADDR_TV;
        mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(mAddress, targetAddress));
    }

    @ServiceThreadOnly
    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<OneTouchPlayAction> actions = getActions(OneTouchPlayAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "oneTouchPlay already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        OneTouchPlayAction action = OneTouchPlayAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    protected void onActiveSourceLost() {
        // Nothing to do.
    }

    @Override
    @CallSuper
    @ServiceThreadOnly
    void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        boolean wasActiveSource = isActiveSource();
        super.setActiveSource(logicalAddress, physicalAddress, caller);
        if (wasActiveSource && !isActiveSource()) {
            onActiveSourceLost();
        }
    }

    @ServiceThreadOnly
    protected void setActiveSource(int physicalAddress, String caller) {
        assertRunOnServiceThread();
        // Invalidate the internal active source record.
        ActiveSource activeSource = ActiveSource.of(Constants.ADDR_INVALID, physicalAddress);
        setActiveSource(activeSource, caller);
    }

    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
        if (!getActiveSource().equals(activeSource)) {
            setActiveSource(activeSource, "HdmiCecLocalDeviceSource#handleActiveSource()");
        }
        updateDevicePowerStatus(logicalAddress, HdmiControlManager.POWER_STATUS_ON);
        if (isRoutingControlFeatureEnabled()) {
            switchInputOnReceivingNewActivePath(physicalAddress);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        maySendActiveSource(message.getSource());
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        // If current device is the target path, set to Active Source.
        // If the path is under the current device, should switch
        if (physicalAddress == mService.getPhysicalAddress() && mService.isPlaybackDevice()) {
            setAndBroadcastActiveSource(message, physicalAddress,
                    "HdmiCecLocalDeviceSource#handleSetStreamPath()");
        } else if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if stream path is set to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleSetStreamPath()");
        }
        switchInputOnReceivingNewActivePath(physicalAddress);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if routing is changed to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleRoutingChange()");
        }
        if (!isRoutingControlFeatureEnabled()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }
        handleRoutingChangeAndInformation(physicalAddress, message);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if routing is changed to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleRoutingInformation()");
        }
        if (!isRoutingControlFeatureEnabled()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }
        handleRoutingChangeAndInformation(physicalAddress, message);
        return true;
    }

    // Method to switch Input with the new Active Path.
    // All the devices with Switch functionality should implement this.
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        // do nothing
    }

    // Only source devices that react to routing control messages should implement
    // this method (e.g. a TV with built in switch).
    // TODO(): decide which type will handle the routing when multi device type is supported
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        // do nothing
    }

    // Update the power status of the devices connected to the current device.
    // This only works if the current device is a switch and keeps tracking the device info
    // of the device connected to it.
    protected void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        // do nothing
    }

    // Active source claiming needs to be handled in Service
    // since service can decide who will be the active source when the device supports
    // multiple device types in this method.
    // This method should only be called when the device can be the active source.
    protected void setAndBroadcastActiveSource(HdmiCecMessage message, int physicalAddress,
            String caller) {
        mService.setAndBroadcastActiveSource(
                physicalAddress, getDeviceInfo().getDeviceType(), message.getSource(), caller);
    }

    // Indicates if current device is the active source or not
    @ServiceThreadOnly
    protected boolean isActiveSource() {
        return getActiveSource().equals(getDeviceInfo().getLogicalAddress(),
                getDeviceInfo().getPhysicalAddress());
    }

    protected void wakeUpIfActiveSource() {
        if (!isActiveSource()) {
            return;
        }
        // Wake up the device
        mService.wakeUp();
        return;
    }

    protected void maySendActiveSource(int dest) {
        if (!isActiveSource()) {
            return;
        }
        addAndStartAction(new ActiveSourceAction(this, dest));
    }

    /**
     * Set {@link #mRoutingPort} to a specific {@link LocalActivePort} to record the current active
     * CEC Routing Control related port.
     *
     * @param portId The portId of the new routing port.
     */
    @VisibleForTesting
    protected void setRoutingPort(@LocalActivePort int portId) {
        synchronized (mLock) {
            mRoutingPort = portId;
        }
    }

    /**
     * Get {@link #mRoutingPort}. This is useful when the device needs to route to the last valid
     * routing port.
     */
    @LocalActivePort
    protected int getRoutingPort() {
        synchronized (mLock) {
            return mRoutingPort;
        }
    }

    /**
     * Get {@link #mLocalActivePort}. This is useful when device needs to know the current active
     * port.
     */
    @LocalActivePort
    protected int getLocalActivePort() {
        synchronized (mLock) {
            return mLocalActivePort;
        }
    }

    /**
     * Set {@link #mLocalActivePort} to a specific {@link LocalActivePort} to record the current
     * active port.
     *
     * <p>It does not have to be a Routing Control related port. For example it can be
     * set to {@link Constants#CEC_SWITCH_ARC} but this port is System Audio related.
     *
     * @param activePort The portId of the new active port.
     */
    protected void setLocalActivePort(@LocalActivePort int activePort) {
        synchronized (mLock) {
            mLocalActivePort = activePort;
        }
    }

    boolean isRoutingControlFeatureEnabled() {
        synchronized (mLock) {
            return mRoutingControlFeatureEnabled;
        }
    }

    // Check if the device is trying to switch to the same input that is active right now.
    // This can help avoid redundant port switching.
    protected boolean isSwitchingToTheSameInput(@LocalActivePort int activePort) {
        return activePort == getLocalActivePort();
    }
}
