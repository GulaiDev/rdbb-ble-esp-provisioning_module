package com.rdbb.esp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.espressif.provisioning.DeviceConnectionEvent;
import com.espressif.provisioning.ESPConstants;
import com.espressif.provisioning.ESPDevice;
import com.espressif.provisioning.ESPProvisionManager;
import com.espressif.provisioning.WiFiAccessPoint;
import com.espressif.provisioning.listeners.ProvisionListener;
import com.espressif.provisioning.listeners.ResponseListener;
import com.espressif.provisioning.listeners.WiFiScanListener;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.common.UniModule;

public class RdbbBleEspProvisioningModule extends UniModule {

    private static final String EVENT_NAME = "rdbbEspEvent";

    private static final int REQUEST_CODE_PERMISSIONS = 41001;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 41002;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SCAN = 1;
    private static final int ACTION_CONNECT = 2;
    private static final int ACTION_INIT_SESSION = 3;

    private static final int DEFAULT_SCAN_TIMEOUT_MS = 10000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;
    private static final int DEFAULT_RETRY_DELAY_MS = 1500;
    private static final int DEFAULT_MAX_CONNECT_RETRIES = 1;
    private static final int DEFAULT_MAX_SESSION_RETRIES = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable scanTimeoutRunnable = this::handleScanTimeout;
    private final Runnable connectTimeoutRunnable = this::handleConnectTimeout;
    private final Runnable sessionTimeoutRunnable = this::handleSessionTimeout;

    private ESPProvisionManager provisionManager;
    private ESPDevice espDevice;

    private Context appContext;
    private WeakReference<Activity> activityRef = new WeakReference<>(null);

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    private final Map<String, ScanResult> devices = new HashMap<>();

    private String devicePrefix = "PROV_";
    private String deviceName = "";
    private String primaryServiceUuid = "";
    private String pop = "";

    private int securityType = 1;

    private int defaultConnectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int defaultSessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private int defaultConnectRetries = DEFAULT_MAX_CONNECT_RETRIES;
    private int defaultSessionRetries = DEFAULT_MAX_SESSION_RETRIES;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;

    private int pendingAction = ACTION_NONE;
    private JSONObject pendingActionData;

    private boolean scanning = false;
    private boolean connectionInProgress = false;
    private boolean sessionInProgress = false;
    private boolean deviceConnected = false;
    private boolean sessionInitialized = false;
    private boolean userInitiatedDisconnect = false;
    private boolean autoDisconnectAfterProvision = false;

    private String connectAddress = "";
    private String connectPop = "";
    private String connectServiceUuid = "";

    private int connectSecurityType = 1;
    private int connectAttempt = 0;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int connectRetriesRemaining = DEFAULT_MAX_CONNECT_RETRIES;

    private int sessionAttempt = 0;
    private int sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private int sessionRetriesRemaining = DEFAULT_MAX_SESSION_RETRIES;

    private Runnable pendingConnectRetryRunnable;
    private Runnable pendingSessionRetryRunnable;

    @UniJSMethod(uiThread = true)
    public void init(JSONObject options) {
        refreshActivityReference();

        Context context = getUniContext();
        appContext = context == null ? null : context.getApplicationContext();

        if (appContext == null && activityRef.get() != null) {
            appContext = activityRef.get().getApplicationContext();
        }

        if (appContext == null) {
            emitError("NO_CONTEXT", "DCloud context is null");
            return;
        }

        securityType = getInt(options, "securityType", 1);
        pop = getString(options, "pop", "");
        devicePrefix = getString(options, "prefix", "PROV_");

        primaryServiceUuid = getString(options, "serviceUuid", "").trim();

        if (primaryServiceUuid.length() > 0 && !isValidUuid(primaryServiceUuid)) {
            emitError("INVALID_SERVICE_UUID", "serviceUuid is invalid");
            return;
        }

        defaultConnectTimeoutMs = sanitizeTimeout(
                getInt(options, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS),
                DEFAULT_CONNECT_TIMEOUT_MS
        );

        defaultSessionTimeoutMs = sanitizeTimeout(
                getInt(options, "sessionTimeoutMs", DEFAULT_SESSION_TIMEOUT_MS),
                DEFAULT_SESSION_TIMEOUT_MS
        );

        defaultConnectRetries = sanitizeRetryCount(
                getInt(options, "maxConnectRetries", DEFAULT_MAX_CONNECT_RETRIES)
        );

        defaultSessionRetries = sanitizeRetryCount(
                getInt(options, "maxSessionRetries", DEFAULT_MAX_SESSION_RETRIES)
        );

        retryDelayMs = sanitizeTimeout(
                getInt(options, "retryDelayMs", DEFAULT_RETRY_DELAY_MS),
                DEFAULT_RETRY_DELAY_MS
        );

        autoDisconnectAfterProvision = getBoolean(options, "autoDisconnectAfterProvision", false);

        provisionManager = ESPProvisionManager.getInstance(appContext);

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }

        emit("initialized", put(json(), "serviceUuid", primaryServiceUuid));
    }

    @UniJSMethod(uiThread = true)
    public void searchESPDevices(JSONObject options) {
        if (!ensureReady()) return;

        refreshActivityReference();

        devicePrefix = getString(options, "prefix", devicePrefix);
        primaryServiceUuid = getString(options, "serviceUuid", primaryServiceUuid).trim();

        int timeoutMs = sanitizeTimeout(
                getInt(options, "timeoutMs", DEFAULT_SCAN_TIMEOUT_MS),
                DEFAULT_SCAN_TIMEOUT_MS
        );

        if (primaryServiceUuid.length() > 0 && !isValidUuid(primaryServiceUuid)) {
            emitError("INVALID_SERVICE_UUID", "serviceUuid is invalid");
            return;
        }

        if (!ensurePermissions(ACTION_SCAN, options)) return;
        if (!ensureBluetoothEnabled(ACTION_SCAN, options)) return;

        stopScanInternal(false);
        devices.clear();

        BluetoothAdapter adapter = getBluetoothAdapter();

        if (adapter == null) {
            emitError("BLE_UNAVAILABLE", "Bluetooth adapter is unavailable");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();

        if (scanner == null) {
            emitError("BLE_SCANNER_NULL", "BluetoothLeScanner is null");
            return;
        }

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult result : results) {
                    handleScanResult(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                stopScanInternal(false);
                emitError("BLE_SCAN_FAILED", String.valueOf(errorCode));
            }
        };

        try {
            scanner.startScan(scanCallback);
            scanning = true;

            emit("scanStart", put(
                    put(json(), "prefix", devicePrefix),
                    "timeoutMs", timeoutMs
            ));

            mainHandler.removeCallbacks(scanTimeoutRunnable);
            mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs);

        } catch (SecurityException se) {
            emitError("NO_PERMISSION", se.getMessage());
        } catch (Exception e) {
            emitError("SCAN_EXCEPTION", e.getMessage());
        }
    }

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;

        String name = resolveDeviceName(result);

        if (name == null || !name.startsWith(devicePrefix)) return;

        String address = result.getDevice().getAddress();

        if (address == null || address.length() == 0) return;
        if (devices.containsKey(address)) return;

        devices.put(address, result);

        String advServiceUuid = resolvePrimaryServiceUuid(result);

        emit("deviceFound",
                put(
                        put(
                                put(
                                        put(
                                                json(),
                                                "name", name
                                        ),
                                        "address", address
                                ),
                                "rssi", result.getRssi()
                        ),
                        "serviceUuid", advServiceUuid == null ? "" : advServiceUuid
                )
        );
    }

    @UniJSMethod(uiThread = true)
    public JSONObject stopScan(JSONObject ignored) {
        stopScanInternal(true);
        return ok("scan stopped");
    }

    @UniJSMethod(uiThread = true)
    public void connect(JSONObject data) {
        if (!ensureReady()) return;

        refreshActivityReference();

        if (!ensurePermissions(ACTION_CONNECT, data)) return;
        if (!ensureBluetoothEnabled(ACTION_CONNECT, data)) return;

        String address = getString(data, "address", "");
        String name = getString(data, "name", "");

        if (address.length() == 0) {
            emitError("ADDRESS_EMPTY", "address is required");
            return;
        }

        ScanResult sr = devices.get(address);

        if (sr == null) {
            emitError("DEVICE_NOT_FOUND", "Please call searchESPDevices first and use address from deviceFound");
            return;
        }

        int sec = getInt(data, "securityType", securityType);
        String usePop = getString(data, "pop", pop);

        String requestedServiceUuid = getString(data, "serviceUuid", primaryServiceUuid).trim();

        if (requestedServiceUuid.length() > 0 && !isValidUuid(requestedServiceUuid)) {
            emitError("INVALID_SERVICE_UUID", "serviceUuid is invalid");
            return;
        }

        String scannedServiceUuid = resolvePrimaryServiceUuid(sr);
        String useServiceUuid = chooseServiceUuid(scannedServiceUuid, requestedServiceUuid);

        if (!isValidUuid(useServiceUuid)) {
            emitError(
                    "SERVICE_UUID_MISSING",
                    "No valid service UUID found. Please make sure the ESP device advertises service UUID or pass serviceUuid manually."
            );
            return;
        }

        stopScanInternal(false);

        connectAddress = address;
        connectSecurityType = sec;
        connectPop = usePop;
        connectServiceUuid = useServiceUuid;

        deviceName = name.length() > 0 ? name : resolveDeviceName(sr);

        if (deviceName == null || deviceName.length() == 0) {
            deviceName = "ESP_DEVICE";
        }

        connectTimeoutMs = sanitizeTimeout(
                getInt(data, "connectTimeoutMs", defaultConnectTimeoutMs),
                defaultConnectTimeoutMs
        );

        connectRetriesRemaining = sanitizeRetryCount(
                getInt(data, "maxConnectRetries", defaultConnectRetries)
        );

        retryDelayMs = sanitizeTimeout(
                getInt(data, "retryDelayMs", retryDelayMs),
                retryDelayMs
        );

        connectAttempt = 0;
        userInitiatedDisconnect = false;
        deviceConnected = false;
        sessionInitialized = false;

        startConnectAttempt(sr);
    }

    private void startConnectAttempt(ScanResult sr) {
        if (sr == null || sr.getDevice() == null) {
            connectionInProgress = false;
            emitError("DEVICE_NOT_FOUND", "Scan result expired, please scan again");
            return;
        }

        try {
            ESPConstants.SecurityType st =
                    connectSecurityType == 0
                            ? ESPConstants.SecurityType.SECURITY_0
                            : ESPConstants.SecurityType.SECURITY_1;

            clearConnectTimeout();
            clearConnectRetry();
            clearSessionTimeout();
            clearSessionRetry();

            disconnectQuietly();

            deviceConnected = false;
            sessionInitialized = false;

            provisionManager.createESPDevice(
                    ESPConstants.TransportType.TRANSPORT_BLE,
                    st
            );

            espDevice = provisionManager.getEspDevice();

            if (espDevice == null) {
                connectionInProgress = false;
                emitError("DEVICE_CREATE_FAILED", "ESPDevice is null");
                return;
            }

            espDevice.setDeviceName(deviceName);
            espDevice.setPrimaryServiceUuid(connectServiceUuid);

            if (connectPop != null && connectPop.length() > 0) {
                espDevice.setProofOfPossession(connectPop);
            }

            connectionInProgress = true;
            connectAttempt++;

            emit("connectStart",
                    put(
                            put(
                                    put(
                                            put(
                                                    put(
                                                            put(
                                                                    json(),
                                                                    "name", deviceName
                                                            ),
                                                            "address", connectAddress
                                                    ),
                                                    "securityType", connectSecurityType
                                            ),
                                            "serviceUuid", connectServiceUuid
                                    ),
                                    "attempt", connectAttempt
                            ),
                            "remainingRetries", connectRetriesRemaining
                    )
            );

            espDevice.connectBLEDevice(sr.getDevice(), connectServiceUuid);

            mainHandler.postDelayed(connectTimeoutRunnable, connectTimeoutMs);

        } catch (SecurityException se) {
            connectionInProgress = false;
            emitError("NO_PERMISSION", se.getMessage());
        } catch (Exception e) {
            handleConnectFailure("CONNECT_EXCEPTION", e.getMessage(), true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnectionEvent(DeviceConnectionEvent event) {
        if (event == null) return;

        int type = event.getEventType();

        if (type == ESPConstants.EVENT_DEVICE_CONNECTED) {
            clearConnectTimeout();
            clearConnectRetry();

            connectionInProgress = false;
            deviceConnected = true;

            emit("connected",
                    put(
                            put(
                                    put(json(), "name", deviceName),
                                    "address", connectAddress
                            ),
                            "attempt", connectAttempt
                    )
            );

        } else if (type == ESPConstants.EVENT_DEVICE_CONNECTION_FAILED) {
            deviceConnected = false;
            sessionInitialized = false;
            handleConnectFailure("CONNECT_FAILED", "Device connection failed", true);

        } else if (type == ESPConstants.EVENT_DEVICE_DISCONNECTED) {
            clearConnectTimeout();
            clearConnectRetry();
            clearSessionTimeout();
            clearSessionRetry();

            boolean wasUserInitiated = userInitiatedDisconnect;

            connectionInProgress = false;
            sessionInProgress = false;
            deviceConnected = false;
            sessionInitialized = false;
            userInitiatedDisconnect = false;

            if (wasUserInitiated) {
                emit("disconnected",
                        put(
                                put(json(), "name", deviceName),
                                "reason", "user"
                        )
                );
            } else {
                emit("disconnected",
                        put(
                                put(json(), "name", deviceName),
                                "reason", "remote"
                        )
                );
            }

        } else {
            emit("connectionEvent", put(json(), "type", String.valueOf(type)));
        }
    }

    @UniJSMethod(uiThread = true)
    public void initializeSession(JSONObject data) {
        if (!ensureConnectedDevice()) return;

        refreshActivityReference();

        if (!ensurePermissions(ACTION_INIT_SESSION, data)) return;

        sessionTimeoutMs = sanitizeTimeout(
                getInt(data, "sessionTimeoutMs", defaultSessionTimeoutMs),
                defaultSessionTimeoutMs
        );

        sessionRetriesRemaining = sanitizeRetryCount(
                getInt(data, "maxSessionRetries", defaultSessionRetries)
        );

        retryDelayMs = sanitizeTimeout(
                getInt(data, "retryDelayMs", retryDelayMs),
                retryDelayMs
        );

        sessionAttempt = 0;
        sessionInitialized = false;

        startSessionAttempt();
    }

    private void startSessionAttempt() {
        if (!ensureConnectedDevice()) return;

        clearSessionTimeout();
        clearSessionRetry();

        sessionInProgress = true;
        sessionAttempt++;

        emit("sessionStart",
                put(
                        put(json(), "attempt", sessionAttempt),
                        "remainingRetries", sessionRetriesRemaining
                )
        );

        try {
            espDevice.initSession(new ResponseListener() {
                @Override
                public void onSuccess(byte[] returnData) {
                    clearSessionTimeout();
                    clearSessionRetry();

                    sessionInProgress = false;
                    sessionInitialized = true;

                    emit("sessionSuccess",
                            put(
                                    put(json(), "message", "session initialized"),
                                    "attempt", sessionAttempt
                            )
                    );
                }

                @Override
                public void onFailure(Exception e) {
                    handleSessionFailure(
                            "SESSION_FAILED",
                            e == null ? "unknown" : e.getMessage(),
                            true
                    );
                }
            });

            mainHandler.postDelayed(sessionTimeoutRunnable, sessionTimeoutMs);

        } catch (SecurityException se) {
            clearSessionTimeout();
            sessionInProgress = false;
            sessionInitialized = false;
            emitError("NO_PERMISSION", se.getMessage());
        } catch (Exception e) {
            handleSessionFailure("SESSION_EXCEPTION", e.getMessage(), true);
        }
    }

    @UniJSMethod(uiThread = true)
    public void scanWifiList(JSONObject ignored) {
        if (!ensureSessionReady()) return;

        try {
            espDevice.scanNetworks(new WiFiScanListener() {
                @Override
                public void onWifiListReceived(ArrayList<WiFiAccessPoint> list) {
                    JSONArray arr = new JSONArray();

                    if (list != null) {
                        for (WiFiAccessPoint ap : list) {
                            JSONObject item = json();

                            put(item, "ssid", ap.getWifiName());
                            put(item, "rssi", ap.getRssi());
                            put(item, "security", ap.getSecurity());

                            arr.add(item);
                        }
                    }

                    emit("wifiList", put(json(), "list", arr));
                }

                @Override
                public void onWiFiScanFailed(Exception e) {
                    emitError(
                            "WIFI_SCAN_FAILED",
                            e == null ? "unknown" : e.getMessage()
                    );
                }
            });
        } catch (Exception e) {
            emitError("WIFI_SCAN_EXCEPTION", e.getMessage());
        }
    }

    @UniJSMethod(uiThread = true)
    public void provision(JSONObject data) {
        if (!ensureSessionReady()) return;

        String ssid = getString(data, "ssid", "");
        String password = getString(data, "password", "");

        boolean autoDisconnect = getBoolean(
                data,
                "autoDisconnectAfterProvision",
                autoDisconnectAfterProvision
        );

        if (ssid.length() == 0) {
            emitError("SSID_EMPTY", "ssid is required");
            return;
        }

        try {
            espDevice.provision(ssid, password, new ProvisionListener() {
                @Override
                public void createSessionFailed(Exception e) {
                    emitError(
                            "CREATE_SESSION_FAILED",
                            e == null ? "unknown" : e.getMessage()
                    );
                }

                @Override
                public void wifiConfigSent() {
                    emit("wifiConfigSent", json());
                }

                @Override
                public void wifiConfigFailed(Exception e) {
                    emitError(
                            "WIFI_CONFIG_FAILED",
                            e == null ? "unknown" : e.getMessage()
                    );
                }

                @Override
                public void wifiConfigApplied() {
                    emit("wifiConfigApplied", json());
                }

                @Override
                public void wifiConfigApplyFailed(Exception e) {
                    emitError(
                            "WIFI_APPLY_FAILED",
                            e == null ? "unknown" : e.getMessage()
                    );
                }

                @Override
                public void provisioningFailedFromDevice(
                        ESPConstants.ProvisionFailureReason reason
                ) {
                    emitError(
                            "PROVISION_FAILED_FROM_DEVICE",
                            String.valueOf(reason)
                    );
                }

                @Override
                public void deviceProvisioningSuccess() {
                    emit("provisionSuccess", put(json(), "ssid", ssid));

                    if (autoDisconnect) {
                        mainHandler.postDelayed(() -> {
                            userInitiatedDisconnect = true;
                            disconnectQuietly();
                        }, 800);
                    }
                }

                @Override
                public void onProvisioningFailed(Exception e) {
                    emitError(
                            "PROVISION_FAILED",
                            e == null ? "unknown" : e.getMessage()
                    );
                }
            });
        } catch (Exception e) {
            emitError("PROVISION_EXCEPTION", e.getMessage());
        }
    }

    @UniJSMethod(uiThread = true)
    public JSONObject disconnect(JSONObject ignored) {
        userInitiatedDisconnect = true;

        clearConnectState();
        clearSessionState();

        sessionInitialized = false;
        deviceConnected = false;

        try {
            if (espDevice != null) {
                espDevice.disconnectDevice();
            }
        } catch (Exception ignoredEx) {
        }

        return ok("disconnect requested");
    }

    @UniJSMethod(uiThread = true)
    public JSONObject destroy(JSONObject ignored) {
        releaseResources(true);
        return ok("destroyed");
    }

    @UniJSMethod(uiThread = true)
    public JSONObject getState(JSONObject ignored) {
        JSONObject state = json();

        put(state, "scanning", scanning);
        put(state, "connectionInProgress", connectionInProgress);
        put(state, "sessionInProgress", sessionInProgress);
        put(state, "deviceConnected", deviceConnected);
        put(state, "sessionInitialized", sessionInitialized);
        put(state, "deviceName", deviceName);
        put(state, "address", connectAddress);
        put(state, "serviceUuid", connectServiceUuid);
        put(state, "securityType", connectSecurityType);

        return put(put(json(), "success", true), "state", state);
    }

    @Override
    public void onActivityPause() {
        stopScanInternal(false);
        super.onActivityPause();
    }

    @Override
    public void onActivityDestroy() {
        releaseResources(true);
        super.onActivityDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                emit("bluetoothEnabled", json());
                resumePendingAction();
            } else {
                clearPendingAction();
                emit("bluetoothEnableCanceled", json());
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            String actionName = actionName(pendingAction);

            if (allPermissionsGranted(grantResults)) {
                emit("permissionGranted", put(json(), "action", actionName));
                resumePendingAction();
            } else {
                JSONArray denied = new JSONArray();

                if (permissions != null && grantResults != null) {
                    for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                            denied.add(permissions[i]);
                        }
                    }
                }

                clearPendingAction();

                emit("permissionDenied",
                        put(
                                put(json(), "action", actionName),
                                "permissions", denied
                        )
                );
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void handleScanTimeout() {
        if (!scanning) return;

        stopScanInternal(true);

        emit("scanTimeout", put(json(), "count", devices.size()));
    }

    private void handleConnectTimeout() {
        if (!connectionInProgress) return;

        handleConnectFailure(
                "CONNECT_TIMEOUT",
                "BLE connection timed out",
                true
        );
    }

    private void handleSessionTimeout() {
        if (!sessionInProgress) return;

        handleSessionFailure(
                "SESSION_TIMEOUT",
                "Session initialization timed out",
                true
        );
    }

    private void handleConnectFailure(
            String code,
            String message,
            boolean retryable
    ) {
        clearConnectTimeout();
        clearConnectRetry();

        disconnectQuietly();

        deviceConnected = false;
        sessionInitialized = false;

        if (retryable && connectRetriesRemaining > 0) {
            ScanResult sr = devices.get(connectAddress);

            int nextAttempt = connectAttempt + 1;
            int retriesLeftAfterThis = connectRetriesRemaining - 1;

            connectRetriesRemaining--;

            emit("connectRetry",
                    put(
                            put(
                                    put(
                                            put(json(), "attempt", nextAttempt),
                                            "remainingRetries", retriesLeftAfterThis
                                    ),
                                    "code", code
                            ),
                            "message", safeMessage(message)
                    )
            );

            pendingConnectRetryRunnable = () -> startConnectAttempt(sr);
            mainHandler.postDelayed(pendingConnectRetryRunnable, retryDelayMs);
            return;
        }

        connectionInProgress = false;

        emitError(code, safeMessage(message));
    }

    private void handleSessionFailure(
            String code,
            String message,
            boolean retryable
    ) {
        clearSessionTimeout();
        clearSessionRetry();

        sessionInitialized = false;

        if (retryable && sessionRetriesRemaining > 0) {
            int nextAttempt = sessionAttempt + 1;
            int retriesLeftAfterThis = sessionRetriesRemaining - 1;

            sessionRetriesRemaining--;

            emit("sessionRetry",
                    put(
                            put(
                                    put(
                                            put(json(), "attempt", nextAttempt),
                                            "remainingRetries", retriesLeftAfterThis
                                    ),
                                    "code", code
                            ),
                            "message", safeMessage(message)
                    )
            );

            pendingSessionRetryRunnable = this::startSessionAttempt;
            mainHandler.postDelayed(pendingSessionRetryRunnable, retryDelayMs);
            return;
        }

        sessionInProgress = false;

        emitError(code, safeMessage(message));
    }

    private boolean ensureReady() {
        if (appContext == null || provisionManager == null) {
            emitError("NOT_INITIALIZED", "Call init first");
            return false;
        }

        return true;
    }

    private boolean ensureDevice() {
        if (!ensureReady()) return false;

        if (espDevice == null) {
            emitError("DEVICE_NULL", "Call connect first");
            return false;
        }

        return true;
    }

    private boolean ensureConnectedDevice() {
        if (!ensureDevice()) return false;

        if (!deviceConnected) {
            emitError("DEVICE_NOT_CONNECTED", "Device is not connected");
            return false;
        }

        return true;
    }

    private boolean ensureSessionReady() {
        if (!ensureConnectedDevice()) return false;

        if (!sessionInitialized) {
            emitError("SESSION_NOT_INITIALIZED", "Call initializeSession first");
            return false;
        }

        return true;
    }

    private boolean ensurePermissions(int action, JSONObject data) {
        String[] missingPermissions = getMissingPermissions(action);

        if (missingPermissions.length == 0) return true;

        Activity activity = activityRef.get();

        if (activity == null) {
            emitError("NO_ACTIVITY", "Foreground activity is unavailable for permission request");
            return false;
        }

        rememberPendingAction(action, data);

        activity.requestPermissions(missingPermissions, REQUEST_CODE_PERMISSIONS);

        emit("permissionRequest",
                put(
                        put(json(), "action", actionName(action)),
                        "permissions", toJsonArray(missingPermissions)
                )
        );

        return false;
    }

    private String[] getMissingPermissions(int action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || appContext == null) {
            return new String[0];
        }

        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (action == ACTION_SCAN) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    permissions.add(Manifest.permission.BLUETOOTH_SCAN);
                }

                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
                }
            }

            if ((action == ACTION_CONNECT || action == ACTION_INIT_SESSION)
                    && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (action == ACTION_SCAN
                    && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        return permissions.toArray(new String[0]);
    }

    private boolean ensureBluetoothEnabled(int action, JSONObject data) {
        BluetoothAdapter adapter = getBluetoothAdapter();

        if (adapter == null) {
            emitError("BLE_UNAVAILABLE", "Bluetooth adapter is unavailable");
            return false;
        }

        if (adapter.isEnabled()) return true;

        rememberPendingAction(action, data);

        emit("bluetoothDisabled", put(json(), "action", actionName(action)));

        openBluetoothEnableScreen();

        return false;
    }

    private void openBluetoothEnableScreen() {
        Activity activity = activityRef.get();

        if (activity == null) {
            emitError("NO_ACTIVITY", "Foreground activity is unavailable for Bluetooth enable flow");
            clearPendingAction();
            return;
        }

        try {
            activity.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_CODE_ENABLE_BLUETOOTH
            );

            emit("bluetoothEnableRequested", json());
            return;
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }

        try {
            activity.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            emit("bluetoothSettingsOpened", json());
        } catch (Exception e) {
            emitError("OPEN_BLUETOOTH_SETTINGS_FAILED", e.getMessage());
        }

        clearPendingAction();
    }

    private void stopScanInternal(boolean emitEvent) {
        mainHandler.removeCallbacks(scanTimeoutRunnable);

        boolean wasScanning = scanning;

        try {
            if (scanner != null && scanCallback != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception ignoredEx) {
        }

        scanning = false;
        scanner = null;
        scanCallback = null;

        if (emitEvent && wasScanning) {
            emit("scanStop", put(json(), "count", devices.size()));
        }
    }

    private void disconnectQuietly() {
        try {
            if (espDevice != null) {
                espDevice.disconnectDevice();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseResources(boolean unregisterEventBus) {
        clearPendingAction();
        clearConnectState();
        clearSessionState();

        stopScanInternal(false);

        mainHandler.removeCallbacksAndMessages(null);

        userInitiatedDisconnect = true;

        disconnectQuietly();

        espDevice = null;
        scanner = null;
        scanCallback = null;

        connectionInProgress = false;
        sessionInProgress = false;
        deviceConnected = false;
        sessionInitialized = false;

        devices.clear();

        if (unregisterEventBus && EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }

        activityRef.clear();
    }

    private void clearConnectState() {
        clearConnectTimeout();
        clearConnectRetry();
        connectionInProgress = false;
    }

    private void clearSessionState() {
        clearSessionTimeout();
        clearSessionRetry();
        sessionInProgress = false;
    }

    private void clearConnectTimeout() {
        mainHandler.removeCallbacks(connectTimeoutRunnable);
    }

    private void clearSessionTimeout() {
        mainHandler.removeCallbacks(sessionTimeoutRunnable);
    }

    private void clearConnectRetry() {
        if (pendingConnectRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingConnectRetryRunnable);
            pendingConnectRetryRunnable = null;
        }
    }

    private void clearSessionRetry() {
        if (pendingSessionRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingSessionRetryRunnable);
            pendingSessionRetryRunnable = null;
        }
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (appContext == null) return null;

        BluetoothManager bluetoothManager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);

        return bluetoothManager == null ? null : bluetoothManager.getAdapter();
    }

    private String resolveDeviceName(ScanResult result) {
        if (result == null) return null;

        ScanRecord record = result.getScanRecord();

        if (record != null) {
            String advertisedName = record.getDeviceName();

            if (advertisedName != null && advertisedName.length() > 0) {
                return advertisedName;
            }
        }

        try {
            return result.getDevice() == null ? null : result.getDevice().getName();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private String resolvePrimaryServiceUuid(ScanResult result) {
        if (result == null) return null;

        ScanRecord record = result.getScanRecord();

        if (record == null
                || record.getServiceUuids() == null
                || record.getServiceUuids().isEmpty()) {
            return null;
        }

        if (isValidUuid(primaryServiceUuid)) {
            for (ParcelUuid parcelUuid : record.getServiceUuids()) {
                if (parcelUuid == null || parcelUuid.getUuid() == null) continue;

                String uuid = parcelUuid.getUuid().toString();

                if (uuid.equalsIgnoreCase(primaryServiceUuid)) {
                    return uuid;
                }
            }
        }

        ParcelUuid firstUuid = record.getServiceUuids().get(0);

        if (firstUuid == null || firstUuid.getUuid() == null) {
            return null;
        }

        return firstUuid.getUuid().toString();
    }

    private String chooseServiceUuid(String scannedServiceUuid, String requestedServiceUuid) {
        if (isValidUuid(scannedServiceUuid)) {
            return scannedServiceUuid;
        }

        if (isValidUuid(requestedServiceUuid)) {
            return requestedServiceUuid;
        }

        return "";
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;

        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private void rememberPendingAction(int action, JSONObject data) {
        pendingAction = action;
        pendingActionData = copy(data);
    }

    private void resumePendingAction() {
        int action = pendingAction;
        JSONObject data = pendingActionData == null ? json() : copy(pendingActionData);

        clearPendingAction();

        if (action == ACTION_SCAN) {
            searchESPDevices(data);
        } else if (action == ACTION_CONNECT) {
            connect(data);
        } else if (action == ACTION_INIT_SESSION) {
            initializeSession(data);
        }
    }

    private void clearPendingAction() {
        pendingAction = ACTION_NONE;
        pendingActionData = null;
    }

    private String actionName(int action) {
        if (action == ACTION_SCAN) return "scan";
        if (action == ACTION_CONNECT) return "connect";
        if (action == ACTION_INIT_SESSION) return "initializeSession";
        return "none";
    }

    private Context getUniContext() {
        try {
            return mUniSDKInstance == null ? null : mUniSDKInstance.getContext();
        } catch (Exception e) {
            return null;
        }
    }

    private Activity getUniActivity() {
        Context context = getUniContext();
        return context instanceof Activity ? (Activity) context : null;
    }

    private void refreshActivityReference() {
        Activity activity = getUniActivity();

        if (activity != null) {
            activityRef = new WeakReference<>(activity);
        }
    }

    private boolean isValidUuid(String value) {
        if (value == null || value.length() == 0) return false;

        try {
            UUID.fromString(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int sanitizeTimeout(int timeoutMs, int defaultValue) {
        return timeoutMs > 0 ? timeoutMs : defaultValue;
    }

    private int sanitizeRetryCount(int retryCount) {
        return Math.max(0, retryCount);
    }

    private String safeMessage(String message) {
        return message == null ? "unknown" : message;
    }

    private JSONObject json() {
        return new JSONObject();
    }

    private JSONObject put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }

        return obj;
    }

    private JSONObject copy(JSONObject source) {
        JSONObject target = new JSONObject();

        if (source != null) {
            target.putAll(source);
        }

        return target;
    }

    private String getString(JSONObject obj, String key, String defaultValue) {
        String value = obj == null ? null : obj.getString(key);
        return value == null ? defaultValue : value;
    }

    private int getInt(JSONObject obj, String key, int defaultValue) {
        Integer value = obj == null ? null : obj.getInteger(key);
        return value == null ? defaultValue : value;
    }

    private boolean getBoolean(JSONObject obj, String key, boolean defaultValue) {
        Boolean value = obj == null ? null : obj.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private JSONArray toJsonArray(String[] values) {
        JSONArray array = new JSONArray();

        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }

        return array;
    }

    private JSONObject ok(String msg) {
        return put(
                put(json(), "success", true),
                "message", msg
        );
    }

    private JSONObject fail(String code, String msg) {
        return put(
                put(
                        put(json(), "success", false),
                        "code", code
                ),
                "message", msg
        );
    }

    private void emitError(String code, String message) {
        emit(
                "error",
                put(
                        put(json(), "code", code),
                        "message", message == null ? "" : message
                )
        );
    }

    private void emit(String type, JSONObject data) {
        try {
            JSONObject obj = json();

            put(obj, "type", type);
            put(obj, "data", data == null ? json() : data);

            if (mUniSDKInstance != null) {
                mUniSDKInstance.fireGlobalEventCallback(EVENT_NAME, obj);
            }
        } catch (Exception ignored) {
        }
    }
}