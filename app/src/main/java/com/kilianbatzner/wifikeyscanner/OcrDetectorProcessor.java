/*
 * Copyright (C) The Android Open Source Project
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
package com.kilianbatzner.wifikeyscanner;

import android.util.Log;
import android.util.SparseArray;

import com.kilianbatzner.wifikeyscanner.ui.camera.GraphicOverlay;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.text.TextBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A very simple Processor which gets detected TextBlocks and adds them to the overlay
 * as OcrGraphics.
 */
public class OcrDetectorProcessor implements Detector.Processor<TextBlock> {

    public interface Listener {
        void onDetectionsProcessed(Set<String> passwords, Set<String> matchedSSIDs);
    }

    private static final String TAG = "OcrDetectorProcessor";

    private Listener mListener;
    private Set<String> mSSIDs;
    private boolean mIsActive;

    public OcrDetectorProcessor (Set<String> SSIDs, Listener listener) {
        mSSIDs = SSIDs;
        mListener = listener;
    }

    public void setActive(boolean isActive) {
        mIsActive = isActive;
    }

    @Override
    public void receiveDetections(Detector.Detections<TextBlock> detections) {
        if (!mIsActive) return;

        SparseArray<TextBlock> items = detections.getDetectedItems();
        Set<String> passwords = new HashSet<>();
        Set<String> matchedSSIDs = new HashSet<>();

        for (int i = 0; i < items.size(); ++i) {
            TextBlock item = items.valueAt(i);
            if (item != null && item.getValue() != null) {
                String text = item.getValue();

                // Check if it contains an SSID
                for (String SSID : mSSIDs) {
                    if (text.replace("_", " ").contains(SSID.replace("_", " "))) {
                        matchedSSIDs.add(SSID);
                        // Remove the SSID from the text for password matching
                        text = text.replaceFirst(SSID, "");
                    }
                }

                // Split on whitespaces to get the passwords
                String[] words = text.split("\\s+");
                for (String word : words) {
                    if (word.length() >= 8) {
                        passwords.add(word);
                    }
                }
            }
        }

        if (mListener != null) mListener.onDetectionsProcessed(passwords, matchedSSIDs);
    }

    @Override
    public void release() {
    }
}
