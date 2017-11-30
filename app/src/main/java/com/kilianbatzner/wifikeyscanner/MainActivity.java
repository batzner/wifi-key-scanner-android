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

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.kilianbatzner.wifikeyscanner.ui.camera.CameraSource;
import com.kilianbatzner.wifikeyscanner.ui.camera.CameraSourcePreview;
import com.kilianbatzner.wifikeyscanner.ui.camera.GraphicOverlay;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Activity for the Ocr Detecting app.  This app detects text and displays the value with the
 * rear facing camera. During detection overlay graphics are drawn to indicate the position,
 * size, and contents of each TextBlock.
 */
public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // Intent request code to handle updating play services if needed.
    private static final int RC_HANDLE_GMS = 9001;

    // Permission request codes need to be < 256
    private static final int RC_HANDLE_LOCATION_PERM = 2;

    private CameraSource mCameraSource;
    private CameraSourcePreview mPreview;
    private GraphicOverlay<OcrGraphic> mGraphicOverlay;
    private TextView mStatusTextView;
    private ProgressBar mStatusProgressBar;
    private ScannerView mScannerView;
    private View mMatchView;
    private View mMatchShadowView;
    private TextView mMatchSSIDTextView;
    private TextView mMatchPasswordTextView;
    private ImageButton mRescanButton;
    private ImageButton mEditButton;
    private ImageButton mConnectButton;

    // Helper object for detecting pinches.
    private ScaleGestureDetector mScaleGestureDetector;

    private WifiManager mWifiManager;
    private OcrDetectorProcessor mProcessor;
    private Set<String> mSSIDs = new HashSet<>();

    /**
     * Initializes the UI and creates the detector pipeline.
     */
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);

        mPreview = findViewById(R.id.activity_main_preview);
        mGraphicOverlay = findViewById(R.id.activity_main_overlay);
        mStatusTextView = findViewById(R.id.activity_main_status_text);
        mStatusProgressBar = findViewById(R.id.activity_main_status_progress);
        mScannerView = findViewById(R.id.activity_main_scanner);
        mMatchView = findViewById(R.id.activity_main_match);
        mMatchShadowView = findViewById(R.id.activity_main_match_shadow);
        mMatchSSIDTextView = findViewById(R.id.activity_main_match_ssid);
        mMatchPasswordTextView = findViewById(R.id.activity_main_match_password);
        mRescanButton = findViewById(R.id.activity_main_rescan);
        mEditButton = findViewById(R.id.activity_main_edit);
        mConnectButton = findViewById(R.id.activity_main_connect);

        mRescanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSSIDScan();
            }
        });

        mProcessor = new OcrDetectorProcessor(mSSIDs, new ProcessorListener());
        mProcessor.setActive(false);

        mScaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        checkPermissions();
    }

    private void checkPermissions() {
        boolean cameraGranted = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean locationGranted = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (cameraGranted && locationGranted) {
            onPermissionsGranted();
        } else {
            showPermissionsDialog();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void showPermissionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle("Permission Needed")
                .setMessage("To scan the Wifi key, please give this app access to the camera and the coarse location. The location is needed to scan for available networks.")
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String[] permissions = new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                        };
                        requestPermissions(permissions, RC_HANDLE_LOCATION_PERM);
                    }
                })
                .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void onPermissionsGranted() {
        createWifiManager();

        // Set good defaults for capturing text.
        createCameraSource(true, false);
    }

    private void createWifiManager() {
        // From https://stackoverflow.com/a/7527380/2628369
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (mWifiManager == null) {
            // TODO: Exit
            return;
        }

        // Register handlers for WIFI changes
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                onSSIDScanComplete(mWifiManager.getScanResults());
            }
        }, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        // Register the connection change receiver
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                SupplicantState supplicantState = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                if (supplicantState == (SupplicantState.COMPLETED)) {
                    // Wifi is enabled
                    mWifiManager.startScan();
                }
            }
        }, new IntentFilter(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION));

        startSSIDScan();
    }

    private void startSSIDScan() {
        // TODO: Call that from a refresh action bar button

        // UI Updates
        mMatchView.setVisibility(View.GONE);
        mMatchShadowView.setVisibility(View.GONE);
        mStatusTextView.setText("Searching for Wifi networks");
        mStatusProgressBar.setVisibility(View.VISIBLE);
        setRecognitionActive(false);

        if (!mWifiManager.isWifiEnabled()) {
            mWifiManager.setWifiEnabled(true);
        }
        mWifiManager.startScan();
    }

    private boolean isConnectedViaWifi() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            NetworkInfo mWifi = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return mWifi.isConnected();
        } else {
            return false;
        }
    }

    private void onSSIDScanComplete(List<ScanResult> scanResults) {
        // UI ready for scanning
        mStatusTextView.setText("Scan the Wifi name and password");
        mStatusProgressBar.setVisibility(View.GONE);

        // Update the SSIDs
        mSSIDs.clear();
        for (ScanResult result : scanResults) {
            if (result.SSID.length() > 0) {
                mSSIDs.add(result.SSID);
            }
        }

        // Activate the recognition
        setRecognitionActive(true);
    }

    private void setRecognitionActive(boolean active) {
        mProcessor.setActive(active);
        mScannerView.setAnimated(active);

        if (active) {
            mMatchView.setVisibility(View.GONE);
            mMatchShadowView.setVisibility(View.GONE);
        }
    }

    private void onNetworkFound(final String SSID, final String password) {
        Log.e(TAG, "Found network: "+SSID+" // "+password);

        setRecognitionActive(false);
        mMatchView.setVisibility(View.VISIBLE);
        mMatchShadowView.setVisibility(View.VISIBLE);
        mMatchSSIDTextView.setText(SSID);
        mMatchPasswordTextView.setText(password);

        mConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectToNetwork(SSID, password);
            }
        });

        mEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editMatch(SSID, password);
            }
        });
    }

    private void editMatch(final String SSID, final String password) {
        LayoutInflater inflater = getLayoutInflater();
        View rootView = inflater.inflate(R.layout.dialog_edit_match, null);

        // TODO:
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(rootView);
        builder.show();
    }

    private void connectToNetwork(String SSID, String password) {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = String.format("\"%s\"", SSID);
        wifiConfig.preSharedKey = String.format("\"%s\"", password);

        int netId = mWifiManager.addNetwork(wifiConfig);
        mWifiManager.disconnect();
        mWifiManager.enableNetwork(netId, true);
        mWifiManager.reconnect();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean b = mScaleGestureDetector.onTouchEvent(e);

        return b || super.onTouchEvent(e);
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the ocr detector to detect small text samples
     * at long distances.
     * <p>
     * Suppressing InlinedApi since there is a check that the minimum version is met before using
     * the constant.
     */
    @SuppressLint("InlinedApi")
    private void createCameraSource(boolean autoFocus, boolean useFlash) {
        Context context = getApplicationContext();

        // Create the TextRecognizer
        TextRecognizer textRecognizer = new TextRecognizer.Builder(context).build();
        // Set the TextRecognizer's Processor.
        textRecognizer.setProcessor(mProcessor);

        // Check if the TextRecognizer is operational.
        if (!textRecognizer.isOperational()) {
            Log.w(TAG, "Detector dependencies are not yet available.");

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }

        // Create the mCameraSource using the TextRecognizer.
        mCameraSource =
                new CameraSource.Builder(getApplicationContext(), textRecognizer)
                        .setFacing(CameraSource.CAMERA_FACING_BACK)
                        .setRequestedPreviewSize(1280, 1024)
                        .setRequestedFps(15.0f)
                        .setFlashMode(useFlash ? Camera.Parameters.FLASH_MODE_TORCH : null)
                        .setFocusMode(autoFocus ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE : null)
                        .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPreview != null) {
            mPreview.stop();
        }
    }

    /**
     * Releases the resources associated with the camera source, the associated detectors, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        boolean locationGranted = false;
        boolean cameraGranted = false;

        if (requestCode == RC_HANDLE_LOCATION_PERM) {
            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationGranted = granted;
                } else if (permissions[i].equals(Manifest.permission.CAMERA)) {
                    cameraGranted = granted;
                }
            }

            Log.e(TAG, "Asked for permissions: "+ Arrays.toString(permissions));
            Log.e(TAG, "Location Permission: " + locationGranted + " Camera Permission: " + cameraGranted);

            // Check that both are granted
            if (locationGranted && cameraGranted) {
                onPermissionsGranted();
            } else {
                showPermissionsDialog();
            }
        } else {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() throws SecurityException {
        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    private class ProcessorListener implements OcrDetectorProcessor.Listener {

        public void onDetectionsProcessed(Set<String> passwordSet, Set<String> matchedSSIDSet) {

            // Sort the passwords by contains-digits first and length second
            List<String> passwords = new ArrayList<>(passwordSet);

            // Secondary order: length, descending
            Collections.sort(passwords, new Comparator<String>() {

                @Override
                public int compare(String str1, String str2) {
                    return -Integer.valueOf(str1.length()).compareTo(str2.length());
                }
            });

            // Primary order: has digits, descending
            Collections.sort(passwords, new Comparator<String>() {

                @Override
                public int compare(String str1, String str2) {
                    String digitRegex = ".*\\d+.*";
                    boolean hasDigits1 = str1.matches(digitRegex);
                    boolean hasDigits2 = str2.matches(digitRegex);
                    return -Boolean.valueOf(hasDigits1).compareTo(hasDigits2);
                }
            });

            // Sort the matched SSIDs by length, descending
            List<String> matchedSSIDs = new ArrayList<>(matchedSSIDSet);
            Collections.sort(matchedSSIDs, new Comparator<String>() {

                @Override
                public int compare(String str1, String str2) {
                    return -Integer.valueOf(str1.length()).compareTo(str2.length());
                }
            });

            // Try the best match
            if (!matchedSSIDs.isEmpty() && !passwords.isEmpty()) {
                final String SSID = matchedSSIDs.get(0);
                final String password = passwords.get(0);

                // Display the match
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        onNetworkFound(SSID, password);
                    }
                });
            }
        }
    }

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {

        /**
         * Responds to scaling events for a gesture in progress.
         * Reported by pointer motion.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should consider this event
         * as handled. If an event was not handled, the detector
         * will continue to accumulate movement until an event is
         * handled. This can be useful if an application, for example,
         * only wants to update scaling factors if the change is
         * greater than 0.01.
         */
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        /**
         * Responds to the beginning of a scaling gesture. Reported by
         * new pointers going down.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should continue recognizing
         * this gesture. For example, if a gesture is beginning
         * with a focal point outside of a region where it makes
         * sense, onScaleBegin() may return false to ignore the
         * rest of the gesture.
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        /**
         * Responds to the end of a scale gesture. Reported by existing
         * pointers going up.
         * <p/>
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} will return focal point
         * of the pointers remaining on the screen.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            if (mCameraSource != null) {
                mCameraSource.doZoom(detector.getScaleFactor());
            }
        }
    }
}
