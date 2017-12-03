package com.kilianbatzner.wifikeyscanner;

import android.util.SparseArray;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.text.TextBlock;

import java.util.HashSet;
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

    public boolean isActive() {
        return mIsActive;
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
                    if (getCrunchedSSID(text).contains(getCrunchedSSID(SSID))) {
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

    private String getCrunchedSSID(String SSID) {
        return SSID.toLowerCase().replace("_", " ").replace(" ", "");
    }
}
