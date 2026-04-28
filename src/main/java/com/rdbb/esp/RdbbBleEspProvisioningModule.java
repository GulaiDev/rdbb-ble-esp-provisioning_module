package com.rdbb.esp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
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

    private static final String EVENT_NAME = "espEvent";

    private static final int REQUEST_CODE_PERMISSIONS = 41001;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 41002;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SCAN = 1;
    private static final int ACTION_CONNECT = 2;
    private static final int ACTION_SCAN_WIFI = 3;
    private static final int ACTION_PROVISION = 4;

    public static final int CODE_SUCCESS = 0;
    public static final int CODE_ENV_EXISTS = 1;
    public static final int CODE_NOT_INITIALIZED = 2;
    public static final int CODE_SCAN_STOPPED = 3;
    public static final int CODE_SCAN_RUNNING = 4;
    public static final int CODE_WIFI_SCAN_NOT_SUPPORTED = 5;
    public static final int CODE_DEVICE_NOT_CONNECTED = 6;
    public static final int CODE_PARAM_ERROR = 7;
    public static final int CODE_INIT_SESSION_FAILED = 8;
    public static final int CODE_RESERVED = 9;
    public static final int CODE_ENV_CHECK_OK = 10;
    public static final int CODE_LOCATION_DISABLED = 11;
    public static final int CODE_GPS_DISABLED = 12;
    public static final int CODE_BLE_NOT_SUPPORTED = 13;
    public static final int CODE_BLE_NEED_ENABLE = 14;
    public static final int CODE_LOCATION_PERMISSION_REQUIRED = 15;
    public static final int CODE_LOCATION_PERMISSION_DENIED = 16;
    public static final int CODE_LOCATION_PERMISSION_REQUIRED_2 = 17;
    public static final int CODE_CONNECTING = 18;
    public static final int CODE_ALREADY_CONNECTED = 19;
    public static final int CODE_PROVISIONING = 20;
    public static final int CODE_BLE_PERMISSION_REQUIRED = 21;
    public static final int CODE_BLE_PERMISSION_REQUESTED = 22;

    private static final int DEFAULT_SCAN_TIMEOUT_MS = 10000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;
    private static final int DEFAULT_PROVISION_TIMEOUT_MS = 60000;
    private static final int DEFAULT_RETRY_DELAY_MS = 1200;
    private static final int DEFAULT_MAX_CONNECT_RETRIES = 1;
    private static final int DEFAULT_MAX_SESSION_RETRIES = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable scanTimeoutRunnable = this::onScanTimeout;
    private final Runnable connectTimeoutRunnable = this::onConnectTimeout;
    private final Runnable sessionTimeoutRunnable = this::onSessionTimeout;
    private final Runnable provisionTimeoutRunnable = this::onProvisionTimeout;

    private Context appContext;
    private WeakReference<Activity> activityRef = new WeakReference<>(null);

    private ESPProvisionManager provisionManager;
    private ESPDevice espDevice;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    private final Map<String, ScanResult> scannedResults = new HashMap<>();

    private int pendingAction = ACTION_NONE;
    private Object pendingActionData;

    private String devicePrefix = "";
    private String configuredServiceUuid = "";
    private String connectedAddress = "";
    private String connectedName = "";
    private String connectedServiceUuid = "";
    private String pop = "";
    private String disconnectReason = "remote";

    private int securityType = 1;
    private int scanTimeoutMs = DEFAULT_SCAN_TIMEOUT_MS;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private int provisionTimeoutMs = DEFAULT_PROVISION_TIMEOUT_MS;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private int connectRetriesRemaining = DEFAULT_MAX_CONNECT_RETRIES;
    private int sessionRetriesRemaining = DEFAULT_MAX_SESSION_RETRIES;
    private int connectAttempt = 0;
    private int sessionAttempt = 0;

    private long provisionTaskId = 0;

    private boolean initialized = false;
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean sessionInitializing = false;
    private boolean sessionInitialized = false;
    private boolean provisioning = false;
    private boolean autoDisconnectAfterProvision = false;

    private Runnable pendingConnectRetryRunnable;
    private Runnable pendingSessionRetryRunnable;

    @UniJSMethod(uiThread = true)
    public int bleEnvironmentOnLoad(JSONObject options) {
        refreshContext();

        int configCode = updateConfig(options);
        if (configCode != CODE_SUCCESS) {
            emitCode("bleEnvironmentInit", configCode, simpleEvent("message", "config error"));
            return configCode;
        }

        if (appContext == null) {
            emitCode("bleEnvironmentInit", CODE_NOT_INITIALIZED, simpleEvent("message", "DCloud context is null"));
            return CODE_NOT_INITIALIZED;
        }

        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            emitCode("bleEnvironmentInit", CODE_BLE_NOT_SUPPORTED, simpleEvent("message", "BLE not supported"));
            return CODE_BLE_NOT_SUPPORTED;
        }

        if (initialized && provisionManager != null) {
            emitCode("bleEnvironmentInit", CODE_ENV_EXISTS, simpleEvent("message", "environment exists"));
            return CODE_ENV_EXISTS;
        }

        provisionManager = ESPProvisionManager.getInstance(appContext);
        initialized = provisionManager != null;

        if (!initialized) {
            emitCode("bleEnvironmentInit", CODE_NOT_INITIALIZED, simpleEvent("message", "ESPProvisionManager init failed"));
            return CODE_NOT_INITIALIZED;
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }

        boolean requestPermissions = getBoolean(options, "isRequestPermissions", true);
        if (requestPermissions) {
            int permissionCode = requestPermissionsIfNeeded(ACTION_NONE, null);
            if (permissionCode != CODE_SUCCESS) {
                emitCode("bleEnvironmentInit", permissionCode, simpleEvent("message", "permission required"));
                return permissionCode;
            }
        }

        try {
            if (!adapter.isEnabled()) {
                emitState("blePermissions", false);
                requestEnableBluetooth(ACTION_NONE, null);
                emitCode("bleEnvironmentInit", CODE_BLE_NEED_ENABLE, simpleEvent("message", "bluetooth disabled"));
                return CODE_BLE_NEED_ENABLE;
            }
        } catch (SecurityException e) {
            int code = permissionCodeByAndroidVersion();
            emitCode("bleEnvironmentInit", code, messageEvent(e));
            return code;
        }

        emitState("blePermissions", true);
        emitCode("bleEnvironmentInit", CODE_SUCCESS, simpleEvent("message", "environment ready"));
        return CODE_SUCCESS;
    }

    @UniJSMethod(uiThread = true)
    public int bleEnvironmentOnUnload() {
        releaseAll(true);
        emitCode("bleEnvironmentUnload", CODE_SUCCESS);
        return CODE_SUCCESS;
    }

    @UniJSMethod(uiThread = true)
    public int bleStartSearchDevice(String prefix) {
        if (!initialized || provisionManager == null) {
            emitCode("bleScanListenerCodeState", CODE_NOT_INITIALIZED);
            return CODE_NOT_INITIALIZED;
        }

        if (scanning) {
            emitCode("bleScanListenerCodeState", CODE_SCAN_RUNNING);
            return CODE_SCAN_RUNNING;
        }

        devicePrefix = prefix == null ? "" : prefix.trim();

        int permissionCode = requestPermissionsIfNeeded(ACTION_SCAN, prefix);
        if (permissionCode != CODE_SUCCESS) {
            emitCode("bleScanListenerCodeState", permissionCode);
            return permissionCode;
        }

        int bluetoothCode = ensureBluetoothEnabled(ACTION_SCAN, prefix);
        if (bluetoothCode != CODE_SUCCESS) {
            emitCode("bleScanListenerCodeState", bluetoothCode);
            return bluetoothCode;
        }

        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            emitCode("bleScanListenerCodeState", CODE_BLE_NOT_SUPPORTED);
            return CODE_BLE_NOT_SUPPORTED;
        }

        BluetoothLeScanner leScanner;
        try {
            leScanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            int code = permissionCodeByAndroidVersion();
            emitCode("bleScanListenerCodeState", code, messageEvent(e));
            return code;
        }

        if (leScanner == null) {
            emitCode("bleScanListenerCodeState", CODE_BLE_NOT_SUPPORTED);
            return CODE_BLE_NOT_SUPPORTED;
        }

        stopScanInternal(false);
        scannedResults.clear();
        scanner = leScanner;

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
                emitCode("bleScanListenerCodeState", errorCode, simpleEvent("message", "scan failed"));
            }
        };

        try {
            List<ScanFilter> filters = buildScanFilters();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            if (filters.isEmpty()) {
                scanner.startScan(scanCallback);
            } else {
                scanner.startScan(filters, settings, scanCallback);
            }

            scanning = true;
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            mainHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMs);

            emitCode("bleScanListenerCodeState", 100, simpleEvent("message", "scan started"));
            return CODE_SUCCESS;
        } catch (SecurityException e) {
            int code = permissionCodeByAndroidVersion();
            emitCode("bleScanListenerCodeState", code, messageEvent(e));
            return code;
        } catch (Exception e) {
            emitCode("bleScanListenerCodeState", CODE_PARAM_ERROR, messageEvent(e));
            return CODE_PARAM_ERROR;
        }
    }

    @UniJSMethod(uiThread = true)
    public int bleStopSearchDevice() {
        stopScanInternal(true);
        return CODE_SCAN_STOPPED;
    }

    @UniJSMethod(uiThread = true)
    public int bleConnectDevice(JSONObject data) {
        if (!initialized || provisionManager == null) {
            emitCode("bleConnectCodeState", CODE_NOT_INITIALIZED);
            return CODE_NOT_INITIALIZED;
        }

        if (connecting) {
            emitCode("bleConnectCodeState", CODE_CONNECTING);
            return CODE_CONNECTING;
        }

        if (connected) {
            emitCode("bleConnectCodeState", CODE_ALREADY_CONNECTED);
            return CODE_ALREADY_CONNECTED;
        }

        if (data == null) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "data is null"));
            return CODE_PARAM_ERROR;
        }

        JSONObject mBleDevice = data.getJSONObject("mBleDevice");
        if (mBleDevice == null) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "mBleDevice is null"));
            return CODE_PARAM_ERROR;
        }

        String address = getString(mBleDevice, "address", "");
        if (address.length() == 0) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "address is empty"));
            return CODE_PARAM_ERROR;
        }

        ScanResult scanResult = scannedResults.get(address);
        if (scanResult == null || scanResult.getDevice() == null) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "ScanResult not found. Please scan again."));
            return CODE_PARAM_ERROR;
        }

        int permissionCode = requestPermissionsIfNeeded(ACTION_CONNECT, data);
        if (permissionCode != CODE_SUCCESS) {
            emitCode("bleConnectCodeState", permissionCode);
            return permissionCode;
        }

        int bluetoothCode = ensureBluetoothEnabled(ACTION_CONNECT, data);
        if (bluetoothCode != CODE_SUCCESS) {
            emitCode("bleConnectCodeState", bluetoothCode);
            return bluetoothCode;
        }

        stopScanInternal(false);

        securityType = normalizeSecurityType(getInt(data, "securityType", securityType));
        pop = getString(data, "pop", pop);

        connectedAddress = address;
        connectedName = firstNonEmpty(
                getString(mBleDevice, "name", ""),
                getString(mBleDevice, "deviceName", ""),
                resolveDeviceName(scanResult),
                "ESP_DEVICE"
        );

        String requestedUuid = firstNonEmpty(
                getString(data, "serviceUuid", ""),
                getString(mBleDevice, "serviceUuid", ""),
                getString(mBleDevice, "primaryServiceUuid", ""),
                configuredServiceUuid
        );

        if (requestedUuid.length() > 0 && !isValidUuid(requestedUuid)) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "Invalid serviceUuid."));
            return CODE_PARAM_ERROR;
        }

        String scannedUuid = resolvePrimaryServiceUuid(scanResult);
        connectedServiceUuid = chooseServiceUuid(scannedUuid, requestedUuid);

        if (!isValidUuid(connectedServiceUuid)) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "No valid BLE serviceUuid found."));
            return CODE_PARAM_ERROR;
        }

        connectTimeoutMs = sanitizeTimeout(getInt(data, "connectTimeoutMs", connectTimeoutMs), DEFAULT_CONNECT_TIMEOUT_MS);
        sessionTimeoutMs = sanitizeTimeout(getInt(data, "sessionTimeoutMs", sessionTimeoutMs), DEFAULT_SESSION_TIMEOUT_MS);
        retryDelayMs = sanitizeTimeout(getInt(data, "retryDelayMs", retryDelayMs), DEFAULT_RETRY_DELAY_MS);
        connectRetriesRemaining = sanitizeRetryCount(getInt(data, "maxConnectRetries", DEFAULT_MAX_CONNECT_RETRIES));
        sessionRetriesRemaining = sanitizeRetryCount(getInt(data, "maxSessionRetries", DEFAULT_MAX_SESSION_RETRIES));

        connectAttempt = 0;
        sessionAttempt = 0;
        connected = false;
        sessionInitialized = false;
        disconnectReason = "remote";

        startConnectAttempt(scanResult);
        return CODE_SUCCESS;
    }

    @UniJSMethod(uiThread = true)
    public int bleScanNetworks() {
        if (!initialized || provisionManager == null) {
            emitCode("wifiList", CODE_NOT_INITIALIZED);
            return CODE_NOT_INITIALIZED;
        }

        if (!connected || espDevice == null) {
            emitCode("wifiList", CODE_DEVICE_NOT_CONNECTED);
            return CODE_DEVICE_NOT_CONNECTED;
        }

        if (!sessionInitialized) {
            emitCode("wifiList", CODE_INIT_SESSION_FAILED);
            return CODE_INIT_SESSION_FAILED;
        }

        if (provisioning) {
            emitCode("wifiList", CODE_PROVISIONING);
            return CODE_PROVISIONING;
        }

        int permissionCode = requestPermissionsIfNeeded(ACTION_SCAN_WIFI, null);
        if (permissionCode != CODE_SUCCESS) {
            emitCode("wifiList", permissionCode);
            return permissionCode;
        }

        try {
            emitCode("wifiList", 100, simpleEvent("message", "wifi scan started"));

            espDevice.scanNetworks(new WiFiScanListener() {
                @Override
                public void onWifiListReceived(ArrayList<WiFiAccessPoint> list) {
                    JSONArray wifiList = new JSONArray();

                    if (list != null) {
                        for (WiFiAccessPoint ap : list) {
                            JSONObject item = new JSONObject();
                            String wifiName = ap == null ? "" : ap.getWifiName();
                            put(item, "wifiName", wifiName);
                            put(item, "ssid", wifiName);
                            put(item, "rssi", ap == null ? 0 : ap.getRssi());
                            put(item, "security", ap == null ? 0 : ap.getSecurity());
                            wifiList.add(item);
                        }
                    }

                    JSONObject event = new JSONObject();
                    put(event, "wifiList", wifiList);
                    put(event, "message", "wifi list received");
                    emitCode("wifiList", 1, event);
                }

                @Override
                public void onWiFiScanFailed(Exception e) {
                    emitCode("wifiList", 2, messageEvent(e));
                }
            });

            return CODE_SUCCESS;
        } catch (Exception e) {
            emitCode("wifiList", 2, messageEvent(e));
            return CODE_PARAM_ERROR;
        }
    }

    @UniJSMethod(uiThread = true)
    public int bleStartProvisioning(JSONObject data) {
        if (!initialized || provisionManager == null) {
            emitCode("provisioningCodeState", CODE_NOT_INITIALIZED);
            return CODE_NOT_INITIALIZED;
        }

        if (!connected || espDevice == null) {
            emitCode("provisioningCodeState", CODE_DEVICE_NOT_CONNECTED);
            return CODE_DEVICE_NOT_CONNECTED;
        }

        if (!sessionInitialized) {
            emitCode("provisioningCodeState", CODE_INIT_SESSION_FAILED);
            return CODE_INIT_SESSION_FAILED;
        }

        if (provisioning) {
            emitCode("provisioningCodeState", CODE_PROVISIONING);
            return CODE_PROVISIONING;
        }

        if (data == null) {
            emitCode("provisioningCodeState", CODE_PARAM_ERROR, simpleEvent("message", "data is null"));
            return CODE_PARAM_ERROR;
        }

        int permissionCode = requestPermissionsIfNeeded(ACTION_PROVISION, data);
        if (permissionCode != CODE_SUCCESS) {
            emitCode("provisioningCodeState", permissionCode);
            return permissionCode;
        }

        String wifiName = firstNonEmpty(
                getString(data, "wifiName", ""),
                getString(data, "ssid", "")
        );

        String password = firstNonEmpty(
                getString(data, "passWord", ""),
                getString(data, "password", "")
        );

        if (wifiName.length() == 0) {
            emitCode("provisioningCodeState", CODE_PARAM_ERROR, simpleEvent("message", "wifiName is empty"));
            return CODE_PARAM_ERROR;
        }

        autoDisconnectAfterProvision = getBoolean(data, "autoDisconnectAfterProvision", autoDisconnectAfterProvision);
        provisionTimeoutMs = sanitizeTimeout(getInt(data, "provisionTimeoutMs", provisionTimeoutMs), DEFAULT_PROVISION_TIMEOUT_MS);

        provisioning = true;
        long taskId = ++provisionTaskId;

        mainHandler.removeCallbacks(provisionTimeoutRunnable);
        mainHandler.postDelayed(provisionTimeoutRunnable, provisionTimeoutMs);

        try {
            espDevice.provision(wifiName, password, new ProvisionListener() {
                @Override
                public void createSessionFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(10, e);
                }

                @Override
                public void wifiConfigSent() {
                    if (!isCurrentProvisionTask(taskId)) return;
                    emitCode("provisioningCodeState", 100, simpleEvent("message", "wifi config sent"));
                }

                @Override
                public void wifiConfigFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(10, e);
                }

                @Override
                public void wifiConfigApplied() {
                    if (!isCurrentProvisionTask(taskId)) return;
                    emitCode("provisioningCodeState", 101, simpleEvent("message", "wifi config applied"));
                }

                @Override
                public void wifiConfigApplyFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(10, e);
                }

                @Override
                public void provisioningFailedFromDevice(ESPConstants.ProvisionFailureReason reason) {
                    if (!isCurrentProvisionTask(taskId)) return;

                    int codeState = mapProvisionFailure(reason);
                    JSONObject event = new JSONObject();
                    put(event, "reason", String.valueOf(reason));
                    put(event, "message", String.valueOf(reason));
                    finishProvisionFailure(codeState, event);
                }

                @Override
                public void deviceProvisioningSuccess() {
                    if (!isCurrentProvisionTask(taskId)) return;

                    mainHandler.removeCallbacks(provisionTimeoutRunnable);
                    provisioning = false;

                    JSONObject event = new JSONObject();
                    put(event, "wifiName", wifiName);
                    put(event, "message", "provision success");
                    emitCode("provisioningCodeState", 0, event);

                    if (autoDisconnectAfterProvision) {
                        mainHandler.postDelayed(() -> {
                            disconnectReason = "provisionSuccess";
                            disconnectQuietly();
                        }, 800);
                    }
                }

                @Override
                public void onProvisioningFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(10, e);
                }
            });

            return CODE_SUCCESS;
        } catch (Exception e) {
            finishProvisionFailure(10, e);
            return CODE_PARAM_ERROR;
        }
    }

    @UniJSMethod(uiThread = true)
    public int bleStopProvisioning() {
        clearProvisionState();
        emitCode("provisioningCodeState", 3, simpleEvent("message", "provision stopped"));
        return CODE_SUCCESS;
    }

    @UniJSMethod(uiThread = true)
    public int bleStopDisconnectDevice() {
        disconnectReason = "user";

        clearConnectTimers();
        clearSessionTimers();
        clearProvisionState();

        disconnectQuietly();

        connected = false;
        sessionInitialized = false;
        connecting = false;
        sessionInitializing = false;
        espDevice = null;

        emitCode("bleConnectCodeState", 3, simpleEvent("reason", "user"));
        return CODE_SUCCESS;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnectionEvent(DeviceConnectionEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        if (eventType == ESPConstants.EVENT_DEVICE_CONNECTED) {
            clearConnectTimers();

            connecting = false;
            connected = true;
            sessionInitialized = false;

            JSONObject obj = new JSONObject();
            put(obj, "name", connectedName);
            put(obj, "address", connectedAddress);
            put(obj, "primaryServiceUuid", connectedServiceUuid);
            put(obj, "message", "ble connected");
            emitCode("bleConnectCodeState", 1, obj);

            startSessionAttempt();
            return;
        }

        if (eventType == ESPConstants.EVENT_DEVICE_CONNECTION_FAILED) {
            handleConnectFailure(true);
            return;
        }

        if (eventType == ESPConstants.EVENT_DEVICE_DISCONNECTED) {
            clearConnectTimers();
            clearSessionTimers();
            clearProvisionState();

            connecting = false;
            connected = false;
            sessionInitializing = false;
            sessionInitialized = false;

            JSONObject eventObj = new JSONObject();
            put(eventObj, "reason", disconnectReason);
            put(eventObj, "message", "ble disconnected");
            emitCode("bleConnectCodeState", 3, eventObj);

            disconnectReason = "remote";
        }
    }

    @Override
    public void onActivityPause() {
        stopScanInternal(false);
        super.onActivityPause();
    }

    @Override
    public void onActivityDestroy() {
        releaseAll(true);
        super.onActivityDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                emitState("blePermissions", true);
                emitCode("bleEnvironmentInit", CODE_SUCCESS, simpleEvent("message", "bluetooth enabled"));
                resumePendingAction();
            } else {
                emitState("blePermissions", false);
                emitCode("bleEnvironmentInit", CODE_BLE_NEED_ENABLE, simpleEvent("message", "bluetooth disabled"));
                clearPendingAction();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted(grantResults)) {
                emitState(permissionEventTag(), true);

                if (pendingAction == ACTION_NONE) {
                    emitCode("bleEnvironmentInit", CODE_SUCCESS, simpleEvent("message", "permissions granted"));
                }

                resumePendingAction();
            } else {
                emitState(permissionEventTag(), false);
                emitCode("bleEnvironmentInit", permissionDeniedCode(), simpleEvent("message", "permission denied"));
                clearPendingAction();
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private int updateConfig(JSONObject options) {
        securityType = normalizeSecurityType(getInt(options, "securityType", securityType));
        pop = getString(options, "pop", pop);
        devicePrefix = getString(options, "prefix", devicePrefix);

        String uuid = getString(options, "serviceUuid", configuredServiceUuid).trim();
        if (uuid.length() > 0 && !isValidUuid(uuid)) {
            emitCode("configCodeState", CODE_PARAM_ERROR, simpleEvent("message", "Invalid serviceUuid."));
            return CODE_PARAM_ERROR;
        }

        configuredServiceUuid = uuid;

        scanTimeoutMs = sanitizeTimeout(getInt(options, "scanTimeoutMs", scanTimeoutMs), DEFAULT_SCAN_TIMEOUT_MS);
        connectTimeoutMs = sanitizeTimeout(getInt(options, "connectTimeoutMs", connectTimeoutMs), DEFAULT_CONNECT_TIMEOUT_MS);
        sessionTimeoutMs = sanitizeTimeout(getInt(options, "sessionTimeoutMs", sessionTimeoutMs), DEFAULT_SESSION_TIMEOUT_MS);
        provisionTimeoutMs = sanitizeTimeout(getInt(options, "provisionTimeoutMs", provisionTimeoutMs), DEFAULT_PROVISION_TIMEOUT_MS);
        retryDelayMs = sanitizeTimeout(getInt(options, "retryDelayMs", retryDelayMs), DEFAULT_RETRY_DELAY_MS);
        autoDisconnectAfterProvision = getBoolean(options, "autoDisconnectAfterProvision", autoDisconnectAfterProvision);

        return CODE_SUCCESS;
    }

    private List<ScanFilter> buildScanFilters() {
        List<ScanFilter> filters = new ArrayList<>();

        if (isValidUuid(configuredServiceUuid)) {
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(configuredServiceUuid))
                    .build());
        }

        return filters;
    }

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;

        String name = resolveDeviceName(result);
        if (name == null) name = "";

        if (devicePrefix.length() > 0 && !name.startsWith(devicePrefix)) return;

        String address;
        try {
            address = result.getDevice().getAddress();
        } catch (SecurityException e) {
            return;
        }

        if (address == null || address.length() == 0) return;
        if (scannedResults.containsKey(address)) return;

        scannedResults.put(address, result);

        String serviceUuid = resolvePrimaryServiceUuid(result);

        JSONObject device = new JSONObject();
        put(device, "name", name.length() > 0 ? name : "未命名设备");
        put(device, "deviceName", name.length() > 0 ? name : "未命名设备");
        put(device, "address", address);
        put(device, "rssi", result.getRssi());
        put(device, "serviceUuid", serviceUuid == null ? "" : serviceUuid);
        put(device, "primaryServiceUuid", serviceUuid == null ? "" : serviceUuid);

        JSONObject event = new JSONObject();
        put(event, "mBleDevice", device);
        emitRaw(eventWithTag("mBleDevice", event));
    }

    private void startConnectAttempt(ScanResult scanResult) {
        if (scanResult == null || scanResult.getDevice() == null) {
            finishConnectFailure("Invalid ScanResult.");
            return;
        }

        try {
            clearConnectTimers();
            clearSessionTimers();
            clearProvisionState();

            disconnectReason = "reconnect";
            disconnectQuietly();

            ESPConstants.SecurityType sec = securityType == 0
                    ? ESPConstants.SecurityType.SECURITY_0
                    : ESPConstants.SecurityType.SECURITY_1;

            provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, sec);
            espDevice = provisionManager.getEspDevice();

            if (espDevice == null) {
                finishConnectFailure("ESPDevice is null.");
                return;
            }

            espDevice.setDeviceName(connectedName);
            espDevice.setPrimaryServiceUuid(connectedServiceUuid);

            if (securityType == 1 && pop != null && pop.length() > 0) {
                espDevice.setProofOfPossession(pop);
            }

            connecting = true;
            connected = false;
            sessionInitialized = false;
            connectAttempt++;

            JSONObject event = new JSONObject();
            put(event, "attempt", connectAttempt);
            put(event, "name", connectedName);
            put(event, "address", connectedAddress);
            put(event, "primaryServiceUuid", connectedServiceUuid);
            put(event, "message", "connecting");
            emitCode("bleConnectCodeState", 100, event);

            disconnectReason = "remote";
            espDevice.connectBLEDevice(scanResult.getDevice(), connectedServiceUuid);

            mainHandler.postDelayed(connectTimeoutRunnable, connectTimeoutMs);
        } catch (SecurityException e) {
            finishConnectFailure(safeMessage(e));
        } catch (Exception e) {
            finishConnectFailure(safeMessage(e));
        }
    }

    private void startSessionAttempt() {
        if (!connected || espDevice == null) {
            emitCode("initSessionCodeState", 2, simpleEvent("message", "Device is not connected."));
            return;
        }

        clearSessionTimers();

        sessionInitializing = true;
        sessionInitialized = false;
        sessionAttempt++;

        JSONObject startEvent = new JSONObject();
        put(startEvent, "attempt", sessionAttempt);
        put(startEvent, "message", "session initializing");
        emitCode("initSessionCodeState", 100, startEvent);

        try {
            espDevice.initSession(new ResponseListener() {
                @Override
                public void onSuccess(byte[] returnData) {
                    clearSessionTimers();

                    sessionInitializing = false;
                    sessionInitialized = true;

                    emitCode("initSessionCodeState", 1, simpleEvent("message", "session success"));
                }

                @Override
                public void onFailure(Exception e) {
                    handleSessionFailure(true, e);
                }
            });

            mainHandler.postDelayed(sessionTimeoutRunnable, sessionTimeoutMs);
        } catch (Exception e) {
            handleSessionFailure(true, e);
        }
    }

    private void handleConnectFailure(boolean retryable) {
        clearConnectTimers();

        connected = false;
        sessionInitialized = false;

        if (retryable && connectRetriesRemaining > 0) {
            connectRetriesRemaining--;

            ScanResult retryResult = scannedResults.get(connectedAddress);
            pendingConnectRetryRunnable = () -> startConnectAttempt(retryResult);
            mainHandler.postDelayed(pendingConnectRetryRunnable, retryDelayMs);
            return;
        }

        finishConnectFailure("Connect failed.");
    }

    private void finishConnectFailure(String message) {
        clearConnectTimers();
        clearSessionTimers();

        connecting = false;
        connected = false;
        sessionInitializing = false;
        sessionInitialized = false;

        disconnectReason = "connectFailed";
        disconnectQuietly();
        espDevice = null;

        emitCode("bleConnectCodeState", 2, simpleEvent("message", message));
    }

    private void handleSessionFailure(boolean retryable, Exception e) {
        clearSessionTimers();

        sessionInitialized = false;

        if (retryable && sessionRetriesRemaining > 0) {
            sessionRetriesRemaining--;

            pendingSessionRetryRunnable = this::startSessionAttempt;
            mainHandler.postDelayed(pendingSessionRetryRunnable, retryDelayMs);
            return;
        }

        sessionInitializing = false;

        JSONObject event = new JSONObject();
        put(event, "message", safeMessage(e));
        put(event, "needReconnect", true);
        emitCode("initSessionCodeState", 2, event);

        disconnectReason = "sessionFailed";
        disconnectQuietly();

        connected = false;
        sessionInitialized = false;
        espDevice = null;
    }

    private void handleSessionFailure(boolean retryable) {
        handleSessionFailure(retryable, new Exception("Session timeout."));
    }

    private void finishProvisionFailure(int codeState, Exception e) {
        finishProvisionFailure(codeState, messageEvent(e));
    }

    private void finishProvisionFailure(int codeState, JSONObject event) {
        mainHandler.removeCallbacks(provisionTimeoutRunnable);
        provisioning = false;
        provisionTaskId++;
        emitCode("provisioningCodeState", codeState, event);
    }

    private boolean isCurrentProvisionTask(long taskId) {
        return provisioning && taskId == provisionTaskId;
    }

    private int mapProvisionFailure(ESPConstants.ProvisionFailureReason reason) {
        if (reason == null) return 10;
        if (reason == ESPConstants.ProvisionFailureReason.AUTH_FAILED) return 1;
        if (reason == ESPConstants.ProvisionFailureReason.NETWORK_NOT_FOUND) return 2;
        return 10;
    }

    private void onScanTimeout() {
        if (!scanning) return;

        stopScanInternal(false);

        JSONObject obj = new JSONObject();
        put(obj, "count", scannedResults.size());
        put(obj, "message", "scan timeout");
        emitCode("bleScanListenerCodeState", 0, obj);
    }

    private void onConnectTimeout() {
        if (!connecting) return;
        handleConnectFailure(true);
    }

    private void onSessionTimeout() {
        if (!sessionInitializing) return;
        handleSessionFailure(true);
    }

    private void onProvisionTimeout() {
        if (!provisioning) return;

        provisioning = false;
        provisionTaskId++;

        JSONObject event = new JSONObject();
        put(event, "message", "Provision timeout.");
        put(event, "timeoutMs", provisionTimeoutMs);
        emitCode("provisioningCodeState", 10, event);
    }

    private int requestPermissionsIfNeeded(int action, Object data) {
        String[] missing = getMissingPermissions(action);
        if (missing.length == 0) return CODE_SUCCESS;

        Activity activity = activityRef.get();
        if (activity == null) return permissionCodeByAndroidVersion();

        pendingAction = action;
        pendingActionData = data;

        try {
            activity.requestPermissions(missing, REQUEST_CODE_PERMISSIONS);
            emitState(permissionEventTag(), false);

            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? CODE_BLE_PERMISSION_REQUESTED
                    : CODE_LOCATION_PERMISSION_REQUIRED;
        } catch (Exception e) {
            clearPendingAction();
            return permissionCodeByAndroidVersion();
        }
    }

    private String[] getMissingPermissions(int action) {
        if (appContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new String[0];
        }

        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if ((action == ACTION_NONE
                    || action == ACTION_SCAN
                    || action == ACTION_CONNECT
                    || action == ACTION_SCAN_WIFI
                    || action == ACTION_PROVISION)
                    && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }

            if ((action == ACTION_NONE || action == ACTION_SCAN)
                    && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            if ((action == ACTION_NONE || action == ACTION_SCAN)
                    && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        return permissions.toArray(new String[0]);
    }

    private int ensureBluetoothEnabled(int action, Object data) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) return CODE_BLE_NOT_SUPPORTED;

        try {
            if (adapter.isEnabled()) return CODE_SUCCESS;
        } catch (SecurityException e) {
            return permissionCodeByAndroidVersion();
        }

        requestEnableBluetooth(action, data);
        return CODE_BLE_NEED_ENABLE;
    }

    private void requestEnableBluetooth(int action, Object data) {
        Activity activity = activityRef.get();
        if (activity == null) return;

        pendingAction = action;
        pendingActionData = data;

        try {
            activity.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_CODE_ENABLE_BLUETOOTH
            );
        } catch (Exception e) {
            openBluetoothSettings(activity);
        }
    }

    private void openBluetoothSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    private void resumePendingAction() {
        int action = pendingAction;
        Object data = pendingActionData;

        clearPendingAction();

        if (action == ACTION_SCAN) {
            bleStartSearchDevice(data instanceof String ? (String) data : devicePrefix);
        } else if (action == ACTION_CONNECT) {
            bleConnectDevice(data instanceof JSONObject ? (JSONObject) data : null);
        } else if (action == ACTION_SCAN_WIFI) {
            bleScanNetworks();
        } else if (action == ACTION_PROVISION) {
            bleStartProvisioning(data instanceof JSONObject ? (JSONObject) data : null);
        }
    }

    private void clearPendingAction() {
        pendingAction = ACTION_NONE;
        pendingActionData = null;
    }

    private void stopScanInternal(boolean emitStop) {
        mainHandler.removeCallbacks(scanTimeoutRunnable);

        boolean wasScanning = scanning;

        try {
            if (scanner != null && scanCallback != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception ignored) {
        }

        scanning = false;
        scanner = null;
        scanCallback = null;

        if (emitStop && wasScanning) {
            JSONObject obj = new JSONObject();
            put(obj, "count", scannedResults.size());
            put(obj, "message", "scan stopped");
            emitCode("bleScanListenerCodeState", 0, obj);
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

    private void releaseAll(boolean unregisterEventBus) {
        stopScanInternal(false);

        clearPendingAction();
        clearConnectTimers();
        clearSessionTimers();
        clearProvisionState();

        mainHandler.removeCallbacksAndMessages(null);

        disconnectReason = "release";
        disconnectQuietly();

        if (unregisterEventBus && EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }

        scannedResults.clear();

        scanner = null;
        scanCallback = null;
        espDevice = null;
        provisionManager = null;

        initialized = false;
        scanning = false;
        connecting = false;
        connected = false;
        sessionInitializing = false;
        sessionInitialized = false;
        provisioning = false;

        connectedAddress = "";
        connectedName = "";
        connectedServiceUuid = "";
    }

    private void clearConnectTimers() {
        mainHandler.removeCallbacks(connectTimeoutRunnable);

        if (pendingConnectRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingConnectRetryRunnable);
            pendingConnectRetryRunnable = null;
        }
    }

    private void clearSessionTimers() {
        mainHandler.removeCallbacks(sessionTimeoutRunnable);

        if (pendingSessionRetryRunnable != null) {
            mainHandler.removeCallbacks(pendingSessionRetryRunnable);
            pendingSessionRetryRunnable = null;
        }
    }

    private void clearProvisionState() {
        mainHandler.removeCallbacks(provisionTimeoutRunnable);
        provisioning = false;
        provisionTaskId++;
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (appContext == null) return null;

        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private String resolveDeviceName(ScanResult result) {
        if (result == null) return "";

        ScanRecord record = result.getScanRecord();
        if (record != null && record.getDeviceName() != null && record.getDeviceName().length() > 0) {
            return record.getDeviceName();
        }

        try {
            return result.getDevice() == null || result.getDevice().getName() == null
                    ? ""
                    : result.getDevice().getName();
        } catch (SecurityException e) {
            return "";
        }
    }

    private String resolvePrimaryServiceUuid(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return "";

        List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
        if (uuids == null || uuids.isEmpty()) return "";

        if (isValidUuid(configuredServiceUuid)) {
            for (ParcelUuid parcelUuid : uuids) {
                if (parcelUuid == null || parcelUuid.getUuid() == null) continue;

                String uuid = parcelUuid.getUuid().toString();
                if (uuid.equalsIgnoreCase(configuredServiceUuid)) {
                    return uuid;
                }
            }
        }

        ParcelUuid first = uuids.get(0);
        return first == null || first.getUuid() == null ? "" : first.getUuid().toString();
    }

    private String chooseServiceUuid(String scannedUuid, String requestedUuid) {
        if (isValidUuid(scannedUuid)) return scannedUuid;
        if (isValidUuid(requestedUuid)) return requestedUuid;
        return "";
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || appContext != null
                && appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;

        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private String permissionEventTag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "blePermissionsState"
                : "locationPermissions";
    }

    private int permissionCodeByAndroidVersion() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? CODE_BLE_PERMISSION_REQUIRED
                : CODE_LOCATION_PERMISSION_REQUIRED;
    }

    private int permissionDeniedCode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? CODE_BLE_PERMISSION_REQUIRED
                : CODE_LOCATION_PERMISSION_DENIED;
    }

    private void refreshContext() {
        try {
            Context context = mUniSDKInstance == null ? null : mUniSDKInstance.getContext();

            if (context instanceof Activity) {
                activityRef = new WeakReference<>((Activity) context);
            }

            if (context != null) {
                appContext = context.getApplicationContext();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isValidUuid(String value) {
        if (value == null || value.length() == 0) return false;

        try {
            UUID.fromString(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int normalizeSecurityType(int value) {
        return value == 0 ? 0 : 1;
    }

    private int sanitizeTimeout(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private int sanitizeRetryCount(int value) {
        return Math.max(0, value);
    }

    private String getString(JSONObject obj, String key, String defaultValue) {
        if (obj == null || key == null) return defaultValue;

        String value = obj.getString(key);
        return value == null ? defaultValue : value.trim();
    }

    private int getInt(JSONObject obj, String key, int defaultValue) {
        if (obj == null || key == null) return defaultValue;

        Integer value = obj.getInteger(key);
        return value == null ? defaultValue : value;
    }

    private boolean getBoolean(JSONObject obj, String key, boolean defaultValue) {
        if (obj == null || key == null) return defaultValue;

        Boolean value = obj.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";

        for (String value : values) {
            if (value != null && value.length() > 0) {
                return value;
            }
        }

        return "";
    }

    private String safeMessage(Exception e) {
        return e == null || e.getMessage() == null ? "unknown" : e.getMessage();
    }

    private JSONObject messageEvent(Exception e) {
        return simpleEvent("message", safeMessage(e));
    }

    private JSONObject simpleEvent(String key, Object value) {
        JSONObject obj = new JSONObject();
        put(obj, key, value);
        return obj;
    }

    private JSONObject eventWithTag(String tag, JSONObject extra) {
        JSONObject obj = new JSONObject();

        put(obj, "tag", tag);

        if (extra != null) {
            obj.putAll(extra);
        }

        return obj;
    }

    private void emitCode(String tag, int code) {
        emitCode(tag, code, null);
    }

    private void emitCode(String tag, int code, JSONObject extra) {
        JSONObject obj = new JSONObject();

        put(obj, "tag", tag);
        put(obj, "code", code);
        put(obj, "codeState", code);

        if (extra != null) {
            obj.putAll(extra);
        }

        emitRaw(obj);
    }

    private void emitState(String tag, boolean state) {
        JSONObject obj = new JSONObject();

        put(obj, "tag", tag);
        put(obj, "state", state);

        emitRaw(obj);
    }

    private void emitRaw(JSONObject obj) {
        try {
            if (mUniSDKInstance != null) {
                mUniSDKInstance.fireGlobalEventCallback(
                        EVENT_NAME,
                        obj == null ? new JSONObject() : obj
                );
            }
        } catch (Exception ignored) {
        }
    }

    private void put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }
    }
}