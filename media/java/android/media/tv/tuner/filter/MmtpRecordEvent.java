/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.BytesLong;
import android.annotation.SystemApi;
import android.media.tv.tuner.filter.RecordSettings.ScHevcIndex;

/**
 * Filter event sent from {@link Filter} objects with MMTP type.
 *
 * @hide
 */
@SystemApi
public class MmtpRecordEvent extends FilterEvent {
    private final int mScHevcIndexMask;
    private final long mDataLength;
    private final int mMpuSequenceNumber;
    private final long mPts;

    // This constructor is used by JNI code only
    private MmtpRecordEvent(int scHevcIndexMask, long dataLength, int mpuSequenceNumber, long pts) {
        mScHevcIndexMask = scHevcIndexMask;
        mDataLength = dataLength;
        mMpuSequenceNumber = mpuSequenceNumber;
        mPts = pts;
    }

    /**
     * Gets indexes which can be tagged by NAL unit group in HEVC according to ISO/IEC 23008-2.
     */
    @ScHevcIndex
    public int getScHevcIndexMask() {
        return mScHevcIndexMask;
    }

    /**
     * Gets data size in bytes of filtered data.
     */
    @BytesLong
    public long getDataLength() {
        return mDataLength;
    }

    /**
     * Get the MPU sequence number of the filtered data.
     */
    public int getMpuSequenceNumber() {
        return mMpuSequenceNumber;
    }

    /**
     * Get the Presentation Time Stamp(PTS) for the audio or video frame. It is based on 90KHz
     * and has the same format as the PTS in ISO/IEC 13818-1. It is used only for the SC and
     * the SC_HEVC.
     */
    public long getPts() {
        return mPts;
    }
}
