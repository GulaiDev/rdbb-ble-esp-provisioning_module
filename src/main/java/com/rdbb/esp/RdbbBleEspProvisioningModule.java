package com.rdbb.esp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanCallback;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.ref.WeakReference;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.common.UniModule;

public class RdbbBleEspProvisioningModule extends UniModule {
    private static final String EVENT_NAME = "rdbbEspEvent";
    private static final String DEFAULT_PRIMARY_SERVICE_UUID = "0000ffff-0000-1000-8000-00805f9b34fb";
    private static final int REQUEST_CODE_PERMISSIONS = 41001;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 41002;
    private static final int ACTION_NONE = 0;
    private static final int ACTION_SCAN = 1;
    private static final int ACTION_CONNECT = 2;
    private static final int ACTION_INIT_SESSION = 3;
    private static final int DEFAULT_SCAN_TIMEOUT_MS = 10000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;
    private static final int DEFAULT_MAX_CONNECT_RETRIES = 1;
    private static final int DEFAULT_MAX_SESSION_RETRIES = 1;
    private static final int DEFAULT_RETRY_DELAY_MS = 1500;

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
    private int securityType = 1;
    private String pop = "";
    private String deviceName = "";
    private String devicePrefix = "PROV_";
    private String primaryServiceUuid = DEFAULT_PRIMARY_SERVICE_UUID;
    private int defaultConnectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int defaultSessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private int defaultConnectRetries = DEFAULT_MAX_CONNECT_RETRIES;
    private int defaultSessionRetries = DEFAULT_MAX_SESSION_RETRIES;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private int pendingAction = ACTION_NONE;
    private JSONObject pendingActionData;
    private boolean scanning;
    private boolean connectionInProgress;
    private boolean sessionInProgress;
    private boolean userInitiatedDisconnect;
    private int connectRetriesRemaining;
    private int connectAttempt;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int sessionRetriesRemaining;
    private int sessionAttempt;
    private int sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private String connectAddress = "";
    private String connectPop = "";
    private String connectServiceUuid = DEFAULT_PRIMARY_SERVICE_UUID;
    private int connectSecurityType = 1;
    private Runnable pendingConnectRetryRunnable;
    private Runnable pendingSessionRetryRunnable;

    @UniJSMethod(uiThread = true)
    public JSONObject init(JSONObject options) {
        refreshActivityReference();
        Context context = getUniContext();
        appContext = context == null ? null : context.getApplicationContext();
        if (appContext == null && activityRef.get() != null) appContext = activityRef.get().getApplicationContext();
        if (appContext == null) return fail("NO_CONTEXT", "DCloud context is null");
        securityType = getInt(options, "securityType", 1);
        pop = getString(options, "pop", "");
        devicePrefix = getString(options, "prefix", "PROV_");
        primaryServiceUuid = getString(options, "serviceUuid", DEFAULT_PRIMARY_SERVICE_UUID);
        defaultConnectTimeoutMs = sanitizeTimeout(getInt(options, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS), DEFAULT_CONNECT_TIMEOUT_MS);
        defaultSessionTimeoutMs = sanitizeTimeout(getInt(options, "sessionTimeoutMs", DEFAULT_SESSION_TIMEOUT_MS), DEFAULT_SESSION_TIMEOUT_MS);
        defaultConnectRetries = sanitizeRetryCount(getInt(options, "maxConnectRetries", DEFAULT_MAX_CONNECT_RETRIES));
        defaultSessionRetries = sanitizeRetryCount(getInt(options, "maxSessionRetries", DEFAULT_MAX_SESSION_RETRIES));
        retryDelayMs = sanitizeTimeout(getInt(options, "retryDelayMs", DEFAULT_RETRY_DELAY_MS), DEFAULT_RETRY_DELAY_MS);
        if (!isValidUuid(primaryServiceUuid)) return fail("INVALID_SERVICE_UUID", "serviceUuid is invalid");
        provisionManager = ESPProvisionManager.getInstance(appContext);
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this);
        return ok("initialized");
    }

    @UniJSMethod(uiThread = true)
    public void searchESPDevices(JSONObject options) {
        if (!ensureReady()) return;
        refreshActivityReference();
        devicePrefix = getString(options, "prefix", devicePrefix);
        primaryServiceUuid = getString(options, "serviceUuid", primaryServiceUuid);
        int timeoutMs = sanitizeTimeout(getInt(options, "timeoutMs", DEFAULT_SCAN_TIMEOUT_MS), DEFAULT_SCAN_TIMEOUT_MS);
        if (!ensurePermissions(ACTION_SCAN, options)) return;
        if (!ensureBluetoothEnabled(ACTION_SCAN, options)) return;
        stopScanInternal(false);
        devices.clear();
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) { emitError("BLE_UNAVAILABLE", "Bluetooth adapter is unavailable"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emitError("BLE_SCANNER_NULL", "BluetoothLeScanner is null"); return; }
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) { handleScanResult(result); }
            @Override public void onBatchScanResults(java.util.List<ScanResult> results) { for (ScanResult r : results) handleScanResult(r); }
            @Override public void onScanFailed(int errorCode) {
                scanning = false;
                mainHandler.removeCallbacks(scanTimeoutRunnable);
                scanner = null;
                scanCallback = null;
                emitError("BLE_SCAN_FAILED", String.valueOf(errorCode));
            }
        };
        try {
            scanner.startScan(scanCallback);
            scanning = true;
            emit("scanStart", put(put(json(), "prefix", devicePrefix), "timeoutMs", timeoutMs));
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs);
        } catch (SecurityException se) { emitError("NO_PERMISSION", se.getMessage()); }
        catch (Exception e) { emitError("SCAN_EXCEPTION", e.getMessage()); }
    }

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String name = resolveDeviceName(result);
        if (name == null || !name.startsWith(devicePrefix)) return;
        String address = result.getDevice().getAddress();
        if (devices.containsKey(address)) return;
        devices.put(address, result);
        String advServiceUuid = resolvePrimaryServiceUuid(result);
        emit("deviceFound", put(put(put(put(json(), "name", name), "address", address), "rssi", result.getRssi()), "serviceUuid", advServiceUuid));
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
        int sec = getInt(data, "securityType", securityType);
        String usePop = getString(data, "pop", pop);
        ScanResult sr = address.length() > 0 ? devices.get(address) : null;
        if (sr == null) { emitError("DEVICE_NOT_FOUND", "Please scan first and pass address"); return; }
        String scannedServiceUuid = resolvePrimaryServiceUuid(sr);
        String requestedServiceUuid = getString(data, "serviceUuid", primaryServiceUuid);
        String useServiceUuid = isValidUuid(scannedServiceUuid) ? scannedServiceUuid : requestedServiceUuid;
        if (!isValidUuid(useServiceUuid)) { emitError("INVALID_SERVICE_UUID", "serviceUuid is invalid"); return; }
        stopScanInternal(false);
        connectAddress = address;
        deviceName = name.length() > 0 ? name : resolveDeviceName(sr);
        if (deviceName == null || deviceName.length() == 0) deviceName = "ESP_DEVICE";
        connectSecurityType = sec;
        connectPop = usePop;
        connectServiceUuid = useServiceUuid;
        connectTimeoutMs = sanitizeTimeout(getInt(data, "connectTimeoutMs", defaultConnectTimeoutMs), defaultConnectTimeoutMs);
        connectRetriesRemaining = sanitizeRetryCount(getInt(data, "maxConnectRetries", defaultConnectRetries));
        retryDelayMs = sanitizeTimeout(getInt(data, "retryDelayMs", retryDelayMs), retryDelayMs);
        connectAttempt = 0;
        userInitiatedDisconnect = false;
        startConnectAttempt(sr);
    }

    private void startConnectAttempt(ScanResult sr) {
        if (sr == null || sr.getDevice() == null) {
            connectionInProgress = false;
            emitError("DEVICE_NOT_FOUND", "Scan result expired, please scan again");
            return;
        }
        try {
            ESPConstants.SecurityType st = connectSecurityType == 0 ? ESPConstants.SecurityType.SECURITY_0 : ESPConstants.SecurityType.SECURITY_1;
            clearConnectTimeout();
            clearConnectRetry();
            clearSessionTimeout();
            disconnectQuietly();
            provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, st);
            espDevice = provisionManager.getEspDevice();
            espDevice.setDeviceName(deviceName);
            espDevice.setPrimaryServiceUuid(connectServiceUuid);
            if (connectPop != null && connectPop.length() > 0) espDevice.setProofOfPossession(connectPop);
            connectionInProgress = true;
            connectAttempt++;
            espDevice.connectBLEDevice(sr.getDevice(), connectServiceUuid);
            emit("connectStart", put(put(put(put(put(put(json(), "name", deviceName), "address", connectAddress), "securityType", connectSecurityType), "serviceUuid", connectServiceUuid), "attempt", connectAttempt), "remainingRetries", connectRetriesRemaining));
            mainHandler.postDelayed(connectTimeoutRunnable, connectTimeoutMs);
        } catch (SecurityException se) { emitError("NO_PERMISSION", se.getMessage()); }
        catch (Exception e) { handleConnectFailure("CONNECT_EXCEPTION", e.getMessage(), true); }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnectionEvent(DeviceConnectionEvent event) {
        if (event == null) return;
        if (event.getEventType() == ESPConstants.EVENT_DEVICE_CONNECTED) {
            clearConnectTimeout();
            connectionInProgress = false;
            emit("connected", put(put(json(), "name", deviceName), "attempt", connectAttempt));
        } else if (event.getEventType() == ESPConstants.EVENT_DEVICE_CONNECTION_FAILED) {
            handleConnectFailure("CONNECT_FAILED", "Device connection failed", true);
        } else if (event.getEventType() == ESPConstants.EVENT_DEVICE_DISCONNECTED) {
            clearConnectTimeout();
            if (userInitiatedDisconnect) {
                userInitiatedDisconnect = false;
                clearConnectState();
                clearSessionState();
                emit("disconnected", put(json(), "name", deviceName));
            } else if (sessionInProgress) {
                handleSessionFailure("DISCONNECTED_DURING_SESSION", "Device disconnected before session completed", true);
            } else if (connectionInProgress) {
                handleConnectFailure("DISCONNECTED_DURING_CONNECT", "Device disconnected before connection completed", true);
            } else {
                emit("disconnected", put(json(), "name", deviceName));
            }
        }
        else emit("connectionEvent", put(json(), "type", String.valueOf(event.getEventType())));
    }

    @UniJSMethod(uiThread = true)
    public void initializeSession(JSONObject data) {
        if (!ensureDevice()) return;
        refreshActivityReference();
        if (!ensurePermissions(ACTION_INIT_SESSION, data)) return;
        sessionTimeoutMs = sanitizeTimeout(getInt(data, "sessionTimeoutMs", defaultSessionTimeoutMs), defaultSessionTimeoutMs);
        sessionRetriesRemaining = sanitizeRetryCount(getInt(data, "maxSessionRetries", defaultSessionRetries));
        retryDelayMs = sanitizeTimeout(getInt(data, "retryDelayMs", retryDelayMs), retryDelayMs);
        sessionAttempt = 0;
        startSessionAttempt();
    }

    @UniJSMethod(uiThread = true)
    public void scanWifiList(JSONObject ignored) {
        if (!ensureDevice()) return;
        espDevice.scanNetworks(new WiFiScanListener() {
            @Override public void onWifiListReceived(ArrayList<WiFiAccessPoint> list) {
                JSONArray arr = new JSONArray();
                if (list != null) for (WiFiAccessPoint ap : list) arr.add(put(put(put(json(), "ssid", ap.getWifiName()), "rssi", ap.getRssi()), "security", ap.getSecurity()));
                emit("wifiList", put(json(), "list", arr));
            }
            @Override public void onWiFiScanFailed(Exception e) { emitError("WIFI_SCAN_FAILED", e == null ? "unknown" : e.getMessage()); }
        });
    }

    @UniJSMethod(uiThread = true)
    public void provision(JSONObject data) {
        if (!ensureDevice()) return;
        String ssid = getString(data, "ssid", "");
        String password = getString(data, "password", "");
        if (ssid.length() == 0) { emitError("SSID_EMPTY", "ssid is required"); return; }
        espDevice.provision(ssid, password, new ProvisionListener() {
            @Override public void createSessionFailed(Exception e) { emitError("CREATE_SESSION_FAILED", e == null ? "unknown" : e.getMessage()); }
            @Override public void wifiConfigSent() { emit("wifiConfigSent", json()); }
            @Override public void wifiConfigFailed(Exception e) { emitError("WIFI_CONFIG_FAILED", e == null ? "unknown" : e.getMessage()); }
            @Override public void wifiConfigApplied() { emit("wifiConfigApplied", json()); }
            @Override public void wifiConfigApplyFailed(Exception e) { emitError("WIFI_APPLY_FAILED", e == null ? "unknown" : e.getMessage()); }
            @Override public void provisioningFailedFromDevice(ESPConstants.ProvisionFailureReason reason) { emitError("PROVISION_FAILED_FROM_DEVICE", String.valueOf(reason)); }
            @Override public void deviceProvisioningSuccess() { emit("provisionSuccess", put(json(), "ssid", ssid)); }
            @Override public void onProvisioningFailed(Exception e) { emitError("PROVISION_FAILED", e == null ? "unknown" : e.getMessage()); }
        });
    }

    @UniJSMethod(uiThread = true)
    public JSONObject disconnect(JSONObject ignored) {
        userInitiatedDisconnect = true;
        clearConnectState();
        clearSessionState();
        try { if (espDevice != null) espDevice.disconnectDevice(); } catch (Exception ignoredEx) {}
        return ok("disconnected");
    }

    @UniJSMethod(uiThread = true)
    public JSONObject destroy(JSONObject ignored) {
        releaseResources(true);
        return ok("destroyed");
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            String actionName = actionName(pendingAction);
            if (allPermissionsGranted(grantResults)) {
                emit("permissionGranted", put(json(), "action", actionName));
                resumePendingAction();
            } else {
                JSONArray denied = new JSONArray();
                if (permissions != null && grantResults != null) {
                    for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) denied.add(permissions[i]);
                    }
                }
                clearPendingAction();
                emit("permissionDenied", put(put(json(), "action", actionName), "permissions", denied));
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void releaseResources(boolean unregisterEventBus) {
        clearPendingAction();
        clearConnectState();
        clearSessionState();
        stopScanInternal(false);
        mainHandler.removeCallbacksAndMessages(null);
        userInitiatedDisconnect = true;
        try { if (espDevice != null) espDevice.disconnectDevice(); } catch (Exception ignoredEx) {}
        espDevice = null;
        scanner = null;
        scanCallback = null;
        connectionInProgress = false;
        sessionInProgress = false;
        devices.clear();
        if (unregisterEventBus && EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        activityRef.clear();
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
        if (activity != null) activityRef = new WeakReference<>(activity);
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

    private JSONObject json() {
        return new JSONObject();
    }

    private JSONObject put(JSONObject obj, String key, Object value) {
        try { obj.put(key, value); } catch (Exception ignored) {}
        return obj;
    }

    private String getString(JSONObject obj, String key, String defaultValue) {
        String value = obj == null ? null : obj.getString(key);
        return value == null ? defaultValue : value;
    }

    private int getInt(JSONObject obj, String key, int defaultValue) {
        Integer value = obj == null ? null : obj.getInteger(key);
        return value == null ? defaultValue : value;
    }

    private JSONObject copy(JSONObject source) {
        JSONObject target = new JSONObject();
        if (source != null) target.putAll(source);
        return target;
    }

    private JSONObject ok(String msg) {
        return put(put(json(), "success", true), "message", msg);
    }

    private JSONObject fail(String code, String msg) {
        return put(put(put(json(), "success", false), "code", code), "message", msg);
    }

    private void emitError(String code, String message) {
        emit("error", put(put(json(), "code", code), "message", message == null ? "" : message));
    }

    private void emit(String type, JSONObject data) {
        try {
            JSONObject obj = new JSONObject(); put(obj, "type", type); put(obj, "data", data == null ? json() : data);
            if (mUniSDKInstance != null) mUniSDKInstance.fireGlobalEventCallback(EVENT_NAME, obj);
        } catch (Exception ignored) {}
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
        emit("permissionRequest", put(put(json(), "action", actionName(action)), "permissions", toJsonArray(missingPermissions)));
        return false;
    }

    private String[] getMissingPermissions(int action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || appContext == null) return new String[0];
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (action == ACTION_SCAN) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if ((action == ACTION_CONNECT || action == ACTION_INIT_SESSION) && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (action == ACTION_SCAN && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.toArray(new String[0]);
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
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

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bluetoothManager == null ? null : bluetoothManager.getAdapter();
    }

    private void openBluetoothEnableScreen() {
        Activity activity = activityRef.get();
        if (activity == null) {
            emitError("NO_ACTIVITY", "Foreground activity is unavailable for Bluetooth enable flow");
            clearPendingAction();
            return;
        }
        try {
            activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE_ENABLE_BLUETOOTH);
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

    private void rememberPendingAction(int action, JSONObject data) {
        pendingAction = action;
        pendingActionData = copy(data);
    }

    private void resumePendingAction() {
        int action = pendingAction;
        JSONObject data = pendingActionData == null ? json() : copy(pendingActionData);
        clearPendingAction();
        if (action == ACTION_SCAN) searchESPDevices(data);
        else if (action == ACTION_CONNECT) connect(data);
        else if (action == ACTION_INIT_SESSION) initializeSession(data);
    }

    private void clearPendingAction() {
        pendingAction = ACTION_NONE;
        pendingActionData = null;
    }

    private JSONArray toJsonArray(String[] values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) array.add(value);
        }
        return array;
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private String actionName(int action) {
        if (action == ACTION_SCAN) return "scan";
        if (action == ACTION_CONNECT) return "connect";
        if (action == ACTION_INIT_SESSION) return "initializeSession";
        return "none";
    }

    private String resolveDeviceName(ScanResult result) {
        if (result == null) return null;
        if (result.getScanRecord() != null) {
            String advertisedName = result.getScanRecord().getDeviceName();
            if (advertisedName != null && advertisedName.length() > 0) return advertisedName;
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
        if (record == null || record.getServiceUuids() == null || record.getServiceUuids().isEmpty()) return null;
        for (ParcelUuid parcelUuid : record.getServiceUuids()) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) continue;
            String uuid = parcelUuid.getUuid().toString();
            if (isValidUuid(uuid) && !DEFAULT_PRIMARY_SERVICE_UUID.equalsIgnoreCase(uuid)) return uuid;
        }
        ParcelUuid firstUuid = record.getServiceUuids().get(0);
        return firstUuid == null || firstUuid.getUuid() == null ? null : firstUuid.getUuid().toString();
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

    private void stopScanInternal(boolean emitEvent) {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        boolean wasScanning = scanning;
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); }
        catch (Exception ignoredEx) {}
        scanning = false;
        scanner = null;
        scanCallback = null;
        if (emitEvent && wasScanning) emit("scanStop", put(json(), "count", devices.size()));
    }

    private void handleScanTimeout() {
        if (!scanning) return;
        stopScanInternal(true);
        emit("scanTimeout", put(json(), "count", devices.size()));
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

    private void handleConnectTimeout() {
        if (!connectionInProgress) return;
        handleConnectFailure("CONNECT_TIMEOUT", "BLE connection timed out", true);
    }

    private void handleConnectFailure(String code, String message, boolean retryable) {
        clearConnectTimeout();
        disconnectQuietly();
        if (retryable && connectRetriesRemaining > 0) {
            ScanResult sr = devices.get(connectAddress);
            int retryIndex = connectAttempt + 1;
            int retriesLeftAfterThis = connectRetriesRemaining - 1;
            connectRetriesRemaining--;
            emit("connectRetry", put(put(put(put(json(), "attempt", retryIndex), "remainingRetries", retriesLeftAfterThis), "code", code), "message", safeMessage(message)));
            pendingConnectRetryRunnable = () -> startConnectAttempt(sr);
            mainHandler.postDelayed(pendingConnectRetryRunnable, retryDelayMs);
            return;
        }
        connectionInProgress = false;
        emitError(code, safeMessage(message));
    }

    private void disconnectQuietly() {
        try { if (espDevice != null) espDevice.disconnectDevice(); } catch (Exception ignored) {}
    }

    private void startSessionAttempt() {
        if (!ensureDevice()) return;
        clearSessionTimeout();
        sessionInProgress = true;
        sessionAttempt++;
        emit("sessionStart", put(put(json(), "attempt", sessionAttempt), "remainingRetries", sessionRetriesRemaining));
        try {
            clearSessionRetry();
            espDevice.initSession(new ResponseListener() {
                @Override public void onSuccess(byte[] returnData) {
                    clearSessionTimeout();
                    sessionInProgress = false;
                    emit("sessionSuccess", put(put(json(), "message", "session initialized"), "attempt", sessionAttempt));
                }

                @Override public void onFailure(Exception e) {
                    handleSessionFailure("SESSION_FAILED", e == null ? "unknown" : e.getMessage(), true);
                }
            });
            mainHandler.postDelayed(sessionTimeoutRunnable, sessionTimeoutMs);
        } catch (SecurityException se) {
            clearSessionTimeout();
            sessionInProgress = false;
            emitError("NO_PERMISSION", se.getMessage());
        } catch (Exception e) {
            handleSessionFailure("SESSION_EXCEPTION", e.getMessage(), true);
        }
    }

    private void handleSessionTimeout() {
        if (!sessionInProgress) return;
        handleSessionFailure("SESSION_TIMEOUT", "Session initialization timed out", true);
    }

    private void handleSessionFailure(String code, String message, boolean retryable) {
        clearSessionTimeout();
        if (retryable && sessionRetriesRemaining > 0) {
            int retryIndex = sessionAttempt + 1;
            int retriesLeftAfterThis = sessionRetriesRemaining - 1;
            sessionRetriesRemaining--;
            emit("sessionRetry", put(put(put(put(json(), "attempt", retryIndex), "remainingRetries", retriesLeftAfterThis), "code", code), "message", safeMessage(message)));
            pendingSessionRetryRunnable = this::startSessionAttempt;
            mainHandler.postDelayed(pendingSessionRetryRunnable, retryDelayMs);
            return;
        }
        sessionInProgress = false;
        emitError(code, safeMessage(message));
    }

    private String safeMessage(String message) {
        return message == null ? "unknown" : message;
    }
}
