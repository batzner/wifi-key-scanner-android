package com.kilianbatzner.wifikeyscanner;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
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
import java.util.HashMap;
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
    private ProgressBar mTimeClockBar;
    private ScannerView mScannerView;
    private View mMatchView;
    private View mMatchShadowView;
    private TextView mMatchStatusTextView;
    private Spinner mMatchSSIDSpinner;
    private Spinner mMatchPasswordSpinner;
    private TextView mMatchSSIDEmptyView;
    private TextView mMatchPasswordEmptyView;
    private View mRescanButton;
    private View mEditButton;
    private View mCopyButton;
    private View mConnectButton;

    // Helper object for detecting pinches.
    private ScaleGestureDetector mScaleGestureDetector;

    // ValueAnimation for the recogntition timeout
    ValueAnimator mRecognitionTimeoutAnimation;

    private WifiManager mWifiManager;
    private boolean mActiveSSIDScan;
    private OcrDetectorProcessor mProcessor;
    private Set<String> mSSIDs = new HashSet<>();
    private Set<String> mAllPasswords = new HashSet<>();
    private HashMap<String, Integer> mSSIDMatchCounts = new HashMap<>();

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
        mTimeClockBar = findViewById(R.id.activity_main_time_clock);
        mScannerView = findViewById(R.id.activity_main_scanner);
        mMatchView = findViewById(R.id.activity_main_match);
        mMatchShadowView = findViewById(R.id.activity_main_match_shadow);
        mMatchStatusTextView = findViewById(R.id.activity_main_match_status);
        mMatchSSIDSpinner = findViewById(R.id.activity_main_match_ssid_spinner);
        mMatchPasswordSpinner = findViewById(R.id.activity_main_match_password_spinner);
        mMatchSSIDEmptyView = findViewById(R.id.activity_main_match_ssid_empty);
        mMatchPasswordEmptyView = findViewById(R.id.activity_main_match_password_empty);
        mRescanButton = findViewById(R.id.activity_main_rescan);
        mEditButton = findViewById(R.id.activity_main_edit);
        mCopyButton = findViewById(R.id.activity_main_copy);
        mConnectButton = findViewById(R.id.activity_main_connect);

        mRescanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rescan();
            }
        });

        // Initial UI:
        mMatchView.setVisibility(View.INVISIBLE);
        mMatchShadowView.setAlpha(0);
        mTimeClockBar.setVisibility(View.GONE);
        mRescanButton.setVisibility(View.GONE);

        mProcessor = new OcrDetectorProcessor(mSSIDs, new ProcessorListener());
        mProcessor.setActive(false);

        mScaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        checkPermissions();
    }

    private void rescan() {
        // If we already have some SSIDs, do the scan in the background
        if (mSSIDs.size() >= 3) {
            scanSSIDsInBackground();
            startRecognition();
        } else {
            scanSSIDs();
        }
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

        builder.setTitle(R.string.dialog_permission_needed_title)
                .setMessage(R.string.dialog_permission_needed_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String[] permissions = new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                        };
                        requestPermissions(permissions, RC_HANDLE_LOCATION_PERM);
                    }
                })
                .setNegativeButton(R.string.close_app, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void onPermissionsGranted() {
        // From https://stackoverflow.com/a/7527380/2628369
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (mWifiManager == null) {
            new MaterialDialog.Builder(this)
                    .title(R.string.dialog_wifi_error_title)
                    .content(R.string.dialog_wifi_error_message)
                    .neutralText(R.string.close_app)
                    .onAny(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            finish();
                        }
                    })
                    .canceledOnTouchOutside(false)
                    .show();
            return;
        }

        scanSSIDs();

        // Set good defaults for capturing text.
        createCameraSource(true, false);
    }

    private void scanSSIDsInBackground() {
        if (mActiveSSIDScan) return;

        // Make sure Wifi is enabled
        if (!mWifiManager.isWifiEnabled()) {
            // Register the connection change receiver and activate wifi
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    // Call this only once
                    unregisterReceiver(this);

                    SupplicantState supplicantState = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                    if (supplicantState == (SupplicantState.COMPLETED)) {
                        // Wifi is enabled
                        scanSSIDsInBackground();
                    }
                }
            }, new IntentFilter(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION));

            mWifiManager.setWifiEnabled(true);
        } else {
            // Register handler for the scan completion
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Call this only once
                    unregisterReceiver(this);
                    mActiveSSIDScan = false;
                    onSSIDScanComplete();
                }
            }, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

            // Start the scan
            mActiveSSIDScan = true;
            mWifiManager.startScan();
        }
    }

    private void scanSSIDs() {
        // This runs in the foreground, so cancel the recognition
        if (mRecognitionTimeoutAnimation != null) mRecognitionTimeoutAnimation.cancel();

        // UI Updates
        hideMatchView();
        mRescanButton.setVisibility(View.GONE);
        mTimeClockBar.setVisibility(View.GONE);
        mScannerView.setAnimated(false);

        mStatusTextView.setVisibility(View.VISIBLE);
        mStatusTextView.setText(R.string.status_wifi_search);
        mStatusProgressBar.setVisibility(View.VISIBLE);

        scanSSIDsInBackground();
    }

    private void onSSIDScanComplete() {
        List<ScanResult> scanResults = mWifiManager.getScanResults();

        // Update the SSIDs
        mSSIDs.clear();
        for (ScanResult result : scanResults) {
            int security = WifiUtils.getSecurity(result);
            if (result.SSID.length() > 0 && security != WifiUtils.SECURITY_NONE) {
                mSSIDs.add(result.SSID);
            }
        }

        // Handle "no available SSIDs"
        if (mSSIDs.isEmpty()) {
            mStatusTextView.setText(R.string.status_no_networks_found);
            mStatusProgressBar.setVisibility(View.GONE);
            new MaterialDialog.Builder(this)
                    .title(R.string.dialog_no_networks_found_title)
                    .content(R.string.dialog_no_networks_found_message)
                    .negativeText(R.string.dialog_no_networks_found_action_search)
                    .positiveText(R.string.dialog_no_networks_found_action_scan)
                    .onNegative(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            scanSSIDs();
                        }
                    })
                    .onPositive(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            startRecognition();
                        }
                    })
                    .show();
        } else {
            if (!mProcessor.isActive()) startRecognition();
        }
    }

    private void startRecognition() {

        // UI ready for scanning
        mStatusTextView.setVisibility(View.VISIBLE);
        mStatusTextView.setText(R.string.status_scan_credentials);
        mStatusProgressBar.setVisibility(View.GONE);
        mRescanButton.setVisibility(View.GONE);

        mScannerView.setAnimated(true);
        hideMatchView();

        mTimeClockBar.setVisibility(View.VISIBLE);
        mTimeClockBar.setProgress(0);

        // Start a timeout for the recognition
        startRecognitionTimeout();

        mAllPasswords.clear();
        mSSIDMatchCounts.clear();
        mProcessor.setActive(true);
    }

    private void startRecognitionTimeout() {
        // Cancel previous recognition timeouts
        if (mRecognitionTimeoutAnimation != null) mRecognitionTimeoutAnimation.cancel();

        mRecognitionTimeoutAnimation = ValueAnimator.ofFloat(0, 1);
        mRecognitionTimeoutAnimation.setInterpolator(new LinearInterpolator());
        mRecognitionTimeoutAnimation.setDuration(10000);
        mRecognitionTimeoutAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float progress = (float) valueAnimator.getAnimatedValue();
                int barProgress = (int) (100 * progress);
                mTimeClockBar.setProgress(barProgress);
            }
        });

        // Add an onTimeout listener
        mRecognitionTimeoutAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                displayResults(null, null);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // Make the animation cancelable without calling the endListener
                animation.removeAllListeners();
            }
        });

        mRecognitionTimeoutAnimation.start();
    }

    private void stopRecognition() {
        mProcessor.setActive(false);
        mScannerView.setAnimated(false);

        // Show the rescan button
        mRescanButton.setVisibility(View.VISIBLE);
        mStatusTextView.setVisibility(View.GONE);
        mStatusProgressBar.setVisibility(View.GONE);
        mTimeClockBar.setVisibility(View.GONE);
    }

    private void showMatchView() {
        // Animate the appearance of the match view
        mMatchShadowView.animate().alpha(1);

        // Only show if it is not already visible
        if (mMatchView.getVisibility() != View.VISIBLE) {
            mMatchView.setVisibility(View.INVISIBLE);
            mMatchView.setTranslationY(-mMatchView.getHeight());
            mMatchView.setVisibility(View.VISIBLE);
            // Clear the listener from the hiding animation
            mMatchView.animate().translationY(0).setListener(null);
        }
    }

    private void hideMatchView() {
        // Animate the disappearance of the match view
        mMatchShadowView.animate().alpha(0);

        mMatchView.animate()
                .translationY(-mMatchView.getHeight())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        mMatchView.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void displayResults(@Nullable final String SSID, @Nullable final String password) {
        // Stop the recognition timeout
        mRecognitionTimeoutAnimation.cancel();

        stopRecognition();
        if (mAllPasswords.isEmpty()) {
            // There is nothing to display or select. Show a dialog
            new MaterialDialog.Builder(this)
                    .title(R.string.dialog_no_passwords_found_title)
                    .content(R.string.dialog_no_passwords_found_message)
                    .neutralText(R.string.try_again)
                    .onNeutral(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                            rescan();
                        }
                    })
                    .show();
            return;
        }

        if (SSID != null && password != null) {
            mMatchStatusTextView.setText(R.string.match_status_network_detected);
        } else {
            mMatchStatusTextView.setText(R.string.match_status_nothing_found);
        }

        populateSSIDSpinner(SSID);
        populatePasswordSpinner(password);

        if (!mSSIDs.isEmpty() && !mAllPasswords.isEmpty()) {
            // Make sure, to only connect when there both pickers have values
            mConnectButton.setVisibility(View.VISIBLE);
            mConnectButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    connectToNetwork();
                }
            });
        } else {
            mConnectButton.setVisibility(View.GONE);
        }

        mEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPassword();
            }
        });

        mCopyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard();
            }
        });

        showMatchView();
    }

    private void populateSSIDSpinner(@Nullable String matchedSSID) {
        if (!mSSIDs.isEmpty()) {
            List<String> SSIDChoices = new ArrayList<>(mSSIDs);
            Collections.sort(SSIDChoices, new SSIDComparator());
            populateSpinner(mMatchSSIDSpinner, SSIDChoices, matchedSSID);
            mMatchSSIDSpinner.setVisibility(View.VISIBLE);
            mMatchSSIDEmptyView.setVisibility(View.GONE);
        } else {
            mMatchSSIDSpinner.setVisibility(View.GONE);
            mMatchSSIDEmptyView.setVisibility(View.VISIBLE);
        }
    }

    private void populatePasswordSpinner(@Nullable String matchedPassword) {
        if (!mAllPasswords.isEmpty()) {
            List<String> passwordChoices = new ArrayList<>(mAllPasswords);
            Collections.sort(passwordChoices, new PasswordComparator());
            if (matchedPassword != null) {
                // Put the found password to the top
                passwordChoices.remove(matchedPassword);
                passwordChoices.add(0, matchedPassword);
            }
            // Only display the top k passwords
            int maxPasswords = Math.min(5, passwordChoices.size());
            passwordChoices = passwordChoices.subList(0, maxPasswords);
            populateSpinner(mMatchPasswordSpinner, passwordChoices, matchedPassword);

            mMatchPasswordSpinner.setVisibility(View.VISIBLE);
            mMatchPasswordEmptyView.setVisibility(View.GONE);
        } else {
            mMatchPasswordSpinner.setVisibility(View.GONE);
            mMatchPasswordEmptyView.setVisibility(View.VISIBLE);
        }

    }

    private void populateSpinner(Spinner spinner, List<String> items, @Nullable String choice) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.match_spinner_item,
                items);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(R.layout.match_spinner_dropdown_item);
        // Apply the adapter to the spinner
        spinner.setAdapter(adapter);

        if (choice != null) {
            // Select the choice
            int choicePosition = adapter.getPosition(choice);
            if (choicePosition >= 0) {
                spinner.setSelection(choicePosition);
            }
        }
    }

    private void editPassword() {
        ArrayAdapter<String> adapter = null;
        try {
            adapter = (ArrayAdapter<String>) mMatchPasswordSpinner.getAdapter();
        } catch (ClassCastException e) {
            Log.e(TAG, "Could not cast password spinner adapter to String array adapter", e);
        }

        if (adapter == null) {
            return;
        }

        final ArrayAdapter<String> finalAdapter = adapter;
        final String prefill = (String) mMatchPasswordSpinner.getSelectedItem();
        final int editPosition = mMatchPasswordSpinner.getSelectedItemPosition();

        new MaterialDialog.Builder(this)
                .title(R.string.dialog_edit_password_title)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .input(getResources().getString(R.string.password), prefill,
                        new MaterialDialog.InputCallback() {
                            @Override
                            public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                                // Update the spinner
                                finalAdapter.remove(prefill);
                                finalAdapter.insert(input.toString(), editPosition);
                            }
                        }).show();
    }

    private void connectToNetwork() {
        String SSID = (String) mMatchSSIDSpinner.getSelectedItem();
        String password = (String) mMatchPasswordSpinner.getSelectedItem();
        if (SSID == null || password == null) {
            Log.e(TAG, "Tried to connect to network with SSID: " + SSID + " Password: " + password);
            return;
        }

        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = String.format("\"%s\"", SSID);
        wifiConfig.preSharedKey = String.format("\"%s\"", password);

        // Connect to the network
        int netId = mWifiManager.addNetwork(wifiConfig);
        mWifiManager.enableNetwork(netId, true);
        mWifiManager.reconnect();

        // TODO: Display a progress bar and check after three seconds if it worked
    }

    private boolean isConnectedViaWifi() {
        // Checks if the phone is connected to a Wifi network. Agnostic of actual internet
        // connection.
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return netInfo != null && netInfo.isConnected();
        } else {
            return false;
        }
    }

    private void copyToClipboard() {
        String label = getString(R.string.password_for, mMatchSSIDSpinner.getSelectedItem());
        String text = (String) mMatchPasswordSpinner.getSelectedItem();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.copied_password_to_clipboard, Toast.LENGTH_SHORT).show();
        }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menu_main_rescan:
                rescan();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
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

            Log.e(TAG, "Asked for permissions: " + Arrays.toString(permissions));
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
            // Sort the passwords and ssids by relevance
            List<String> passwords = new ArrayList<>(passwordSet);
            Collections.sort(passwords, new PasswordComparator());

            List<String> matchedSSIDs = new ArrayList<>(matchedSSIDSet);
            Collections.sort(matchedSSIDs, new SSIDComparator());

            // Add the passwords and ssids to all passwords and all ssids
            mAllPasswords.addAll(passwords);
            for (String matchedSSID : matchedSSIDs) {
                if (!mSSIDMatchCounts.containsKey(matchedSSID)) {
                    mSSIDMatchCounts.put(matchedSSID, 0);
                }
                mSSIDMatchCounts.put(matchedSSID, mSSIDMatchCounts.get(matchedSSID) + 1);
            }

            // Try the best match
            if (!matchedSSIDs.isEmpty() && !passwords.isEmpty()) {
                final String SSID = matchedSSIDs.get(0);
                final String password = passwords.get(0);

                // Display the match
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        displayResults(SSID, password);
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

    private class PasswordComparator implements Comparator<String> {
        @Override
        public int compare(String str1, String str2) {
            // Primary order: has digits, descending
            String digitRegex = ".*\\d+.*";
            boolean hasDigits1 = str1.matches(digitRegex);
            boolean hasDigits2 = str2.matches(digitRegex);
            int result = -Boolean.valueOf(hasDigits1).compareTo(hasDigits2);

            if (result == 0) {
                // Secondary order: length, descending
                result = -Integer.valueOf(str1.length()).compareTo(str2.length());
            }

            return result;
        }
    }

    private class SSIDComparator implements Comparator<String> {
        @Override
        public int compare(String str1, String str2) {
            // Primary order: match counts, descending
            int count1 = mSSIDMatchCounts.containsKey(str1) ? mSSIDMatchCounts.get(str1) : 0;
            int count2 = mSSIDMatchCounts.containsKey(str2) ? mSSIDMatchCounts.get(str2) : 0;


            int result = -Integer.valueOf(count1).compareTo(count2);
            if (result == 0) {
                // Secondary order: lexicographical, ascending
                result = str1.compareTo(str2);
            }
            return result;
        }
    }
}
