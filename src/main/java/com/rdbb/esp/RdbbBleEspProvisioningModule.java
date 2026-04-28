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
import android.location.LocationManager;
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

/**
 * ESP32 BLE 蓝牙配网 uni-app 原生模块
 * 功能：扫描 ESP 设备 -> 连接 -> 加密会话 -> 获取设备周边 WiFi -> 下发 WiFi 账号密码
 */
public class RdbbBleEspProvisioningModule extends UniModule {

    // ==================== 全局常量定义 ====================
    /** 前端监听的全局事件名称 */
    private static final String EVENT_NAME = "espEvent";

    /** 权限请求、蓝牙开启请求码 */
    private static final int REQUEST_CODE_PERMISSIONS = 41001;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 41002;

    /** 当前执行的动作类型：无/扫描/连接/扫描WiFi/配网 */
    private static final int ACTION_NONE = 0;
    private static final int ACTION_SCAN = 1;
    private static final int ACTION_CONNECT = 2;
    private static final int ACTION_SCAN_WIFI = 3;
    private static final int ACTION_PROVISION = 4;

    // ==================== 状态码 ====================
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

    // ==================== 事件回调码 ====================
    private static final int EVENT_SCAN_STARTED = 100;
    private static final int EVENT_SCAN_FINISHED = 0;
    private static final int EVENT_CONNECTING = 100;
    private static final int EVENT_CONNECTED = 1;
    private static final int EVENT_CONNECT_FAILED = 2;
    private static final int EVENT_DISCONNECTED = 3;
    private static final int EVENT_SESSION_START = 100;
    private static final int EVENT_SESSION_SUCCESS = 1;
    private static final int EVENT_SESSION_FAILED = 2;
    private static final int EVENT_WIFI_SCAN_START = 100;
    private static final int EVENT_WIFI_LIST = 1;
    private static final int EVENT_WIFI_SCAN_FAILED = 2;
    private static final int EVENT_PROVISION_SUCCESS = 0;
    private static final int EVENT_PROVISION_AUTH_FAILED = 1;
    private static final int EVENT_PROVISION_NETWORK_NOT_FOUND = 2;
    private static final int EVENT_PROVISION_STOPPED = 3;
    private static final int EVENT_PROVISION_RUNNING = 100;
    private static final int EVENT_PROVISION_APPLIED = 101;
    private static final int EVENT_PROVISION_FAILED = 10;

    // ==================== 默认超时/重试配置 ====================
    private static final int DEFAULT_SCAN_TIMEOUT_MS = 10000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;
    private static final int DEFAULT_PROVISION_TIMEOUT_MS = 60000;
    private static final int DEFAULT_RETRY_DELAY_MS = 1200;
    private static final int DEFAULT_MAX_CONNECT_RETRIES = 1;
    private static final int DEFAULT_MAX_SESSION_RETRIES = 1;

    /** 主线程 Handler，处理延时任务、超时 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 各类超时任务 */
    private final Runnable scanTimeoutRunnable = this::onScanTimeout;
    private final Runnable connectTimeoutRunnable = this::onConnectTimeout;
    private final Runnable sessionTimeoutRunnable = this::onSessionTimeout;
    private final Runnable provisionTimeoutRunnable = this::onProvisionTimeout;

    // ==================== 上下文与管理器 ====================
    private Context appContext;
    private WeakReference<Activity> activityRef = new WeakReference<>(null);

    /** ESP 配网核心管理器 */
    private ESPProvisionManager provisionManager;
    /** 当前连接的 ESP 设备对象 */
    private ESPDevice espDevice;

    /** BLE 扫描器与回调 */
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    /** 扫描到的设备缓存：key=Mac 地址 */
    private final Map<String, ScanResult> scannedResults = new HashMap<>();

    // ==================== 动作队列与参数 ====================
    private int pendingAction = ACTION_NONE;
    private Object pendingActionData;

    private String devicePrefix = "";           // 设备名称前缀过滤
    private String configuredServiceUuid = "";  // 配置的服务 UUID
    private String connectedAddress = "";       // 已连接设备 MAC
    private String connectedName = "";          // 已连接设备名称
    private String connectedServiceUuid = "";   // 已连接服务 UUID
    private String pop = "";                    // 配网密钥
    private String disconnectReason = "remote"; // 断开原因

    private int securityType = 1;               // 加密类型 0/1
    private int scanTimeoutMs = DEFAULT_SCAN_TIMEOUT_MS;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int sessionTimeoutMs = DEFAULT_SESSION_TIMEOUT_MS;
    private int provisionTimeoutMs = DEFAULT_PROVISION_TIMEOUT_MS;
    private int retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private int connectRetriesRemaining = DEFAULT_MAX_CONNECT_RETRIES;
    private int sessionRetriesRemaining = DEFAULT_MAX_SESSION_RETRIES;
    private int connectAttempt = 0;
    private int sessionAttempt = 0;

    private long provisionTaskId = 0;           // 配网任务 ID，防止并发混乱

    // ==================== 状态标志 ====================
    private boolean initialized = false;        // 模块是否初始化
    private boolean scanning = false;           // 是否正在扫描
    private boolean connecting = false;         // 是否正在连接
    private boolean connected = false;          // 是否已连接
    private boolean sessionInitializing = false;// 会话初始化中
    private boolean sessionInitialized = false; // 会话已建立
    private boolean provisioning = false;       // 配网中
    private boolean autoDisconnectAfterProvision = false; // 配网完成自动断开
    private boolean ignoreNextDisconnectEvent = false;   // 忽略下一次断开事件

    private Runnable pendingConnectRetryRunnable;
    private Runnable pendingSessionRetryRunnable;

    // ==================== 前端可调用方法（uni 接口） ====================

    /**
     * 初始化 BLE 配网环境
     * 检查：BLE 支持、权限、蓝牙开启、初始化 ESP 管理器
     */
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

        // 获取 ESP 配网单例
        provisionManager = ESPProvisionManager.getInstance(appContext);
        initialized = provisionManager != null;

        if (!initialized) {
            emitCode("bleEnvironmentInit", CODE_NOT_INITIALIZED, simpleEvent("message", "ESPProvisionManager init failed"));
            return CODE_NOT_INITIALIZED;
        }

        // 注册事件总线，接收设备连接状态
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

        int bluetoothCode = ensureBluetoothEnabled(ACTION_NONE, null);
        if (bluetoothCode != CODE_SUCCESS) {
            emitCode("bleEnvironmentInit", bluetoothCode, simpleEvent("message", "bluetooth disabled"));
            return bluetoothCode;
        }

        emitState("blePermissions", true);
        emitCode("bleEnvironmentInit", CODE_SUCCESS, simpleEvent("message", "environment ready"));
        return CODE_SUCCESS;
    }

    /**
     * 卸载模块，释放所有资源
     */
    @UniJSMethod(uiThread = true)
    public int bleEnvironmentOnUnload() {
        releaseAll(true);
        emitCode("bleEnvironmentUnload", CODE_SUCCESS, simpleEvent("message", "environment released"));
        return CODE_SUCCESS;
    }

    /**
     * 开始搜索 ESP BLE 设备
     * @param prefix 设备名称前缀过滤
     */
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

        // 权限检查
        int permissionCode = requestPermissionsIfNeeded(ACTION_SCAN, prefix);
        if (permissionCode != CODE_SUCCESS) {
            emitCode("bleScanListenerCodeState", permissionCode);
            return permissionCode;
        }

        // 蓝牙开启检查
        int bluetoothCode = ensureBluetoothEnabled(ACTION_SCAN, prefix);
        if (bluetoothCode != CODE_SUCCESS) {
            emitCode("bleScanListenerCodeState", bluetoothCode);
            return bluetoothCode;
        }

        // 位置服务检查（Android 12 以下必须开）
        int locationCode = ensureLocationEnabledForBleScan();
        if (locationCode != CODE_SUCCESS) {
            emitCode("bleScanListenerCodeState", locationCode, simpleEvent("message", "location service disabled"));
            return locationCode;
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

        // 停止之前的扫描，清空缓存
        stopScanInternal(false);
        scannedResults.clear();
        scanner = leScanner;

        // BLE 扫描回调
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
                JSONObject obj = simpleEvent("message", "scan failed");
                put(obj, "androidScanErrorCode", errorCode);
                emitCode("bleScanListenerCodeState", CODE_PARAM_ERROR, obj);
            }
        };

        try {
            List<ScanFilter> filters = buildScanFilters();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 低延迟扫描
                    .build();

            if (filters.isEmpty()) {
                scanner.startScan(null, settings, scanCallback);
            } else {
                scanner.startScan(filters, settings, scanCallback);
            }

            scanning = true;
            // 扫描超时自动停止
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            mainHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMs);

            emitCode("bleScanListenerCodeState", EVENT_SCAN_STARTED, simpleEvent("message", "scan started"));
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

    /**
     * 停止搜索设备
     */
    @UniJSMethod(uiThread = true)
    public int bleStopSearchDevice() {
        stopScanInternal(true);
        return CODE_SUCCESS;
    }

    /**
     * 连接指定 ESP 设备
     * @param data 包含设备信息、密钥、UUID 等
     */
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

        // 从扫描缓存中获取设备
        ScanResult scanResult = scannedResults.get(address);
        if (scanResult == null || scanResult.getDevice() == null) {
            emitCode("bleConnectCodeState", CODE_PARAM_ERROR, simpleEvent("message", "ScanResult not found. Please scan again."));
            return CODE_PARAM_ERROR;
        }

        // 权限 + 蓝牙检查
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

        // 读取连接参数
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

        // 超时与重试配置
        connectTimeoutMs = sanitizeTimeout(getInt(data, "connectTimeoutMs", connectTimeoutMs), DEFAULT_CONNECT_TIMEOUT_MS);
        sessionTimeoutMs = sanitizeTimeout(getInt(data, "sessionTimeoutMs", sessionTimeoutMs), DEFAULT_SESSION_TIMEOUT_MS);
        retryDelayMs = sanitizeTimeout(getInt(data, "retryDelayMs", retryDelayMs), DEFAULT_RETRY_DELAY_MS);
        connectRetriesRemaining = sanitizeRetryCount(getInt(data, "maxConnectRetries", DEFAULT_MAX_CONNECT_RETRIES));
        sessionRetriesRemaining = sanitizeRetryCount(getInt(data, "maxSessionRetries", DEFAULT_MAX_SESSION_RETRIES));

        // 重置连接状态
        connectAttempt = 0;
        sessionAttempt = 0;
        connected = false;
        sessionInitialized = false;
        sessionInitializing = false;
        ignoreNextDisconnectEvent = false;
        disconnectReason = "remote";

        // 开始连接
        startConnectAttempt(scanResult);
        return CODE_SUCCESS;
    }

    /**
     * 让已连接的 ESP 设备扫描周边 WiFi
     */
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
            emitCode("wifiList", EVENT_WIFI_SCAN_START, simpleEvent("message", "wifi scan started"));

            // 调用 ESP 设备 SDK 扫描 WiFi
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

                    emitCode("wifiList", EVENT_WIFI_LIST, event);
                }

                @Override
                public void onWiFiScanFailed(Exception e) {
                    emitCode("wifiList", EVENT_WIFI_SCAN_FAILED, messageEvent(e));
                }
            });

            return CODE_SUCCESS;
        } catch (Exception e) {
            emitCode("wifiList", EVENT_WIFI_SCAN_FAILED, messageEvent(e));
            return CODE_PARAM_ERROR;
        }
    }

    /**
     * 开始配网：下发 WiFi 账号密码给 ESP 设备
     */
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

        // 获取 WiFi 信息
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

        // 配网超时
        mainHandler.removeCallbacks(provisionTimeoutRunnable);
        mainHandler.postDelayed(provisionTimeoutRunnable, provisionTimeoutMs);

        try {
            // 执行配网
            espDevice.provision(wifiName, password, new ProvisionListener() {
                @Override
                public void createSessionFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(EVENT_PROVISION_FAILED, e);
                }

                @Override
                public void wifiConfigSent() {
                    if (!isCurrentProvisionTask(taskId)) return;
                    emitCode("provisioningCodeState", EVENT_PROVISION_RUNNING, simpleEvent("message", "wifi config sent"));
                }

                @Override
                public void wifiConfigFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(EVENT_PROVISION_FAILED, e);
                }

                @Override
                public void wifiConfigApplied() {
                    if (!isCurrentProvisionTask(taskId)) return;
                    emitCode("provisioningCodeState", EVENT_PROVISION_APPLIED, simpleEvent("message", "wifi config applied"));
                }

                @Override
                public void wifiConfigApplyFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(EVENT_PROVISION_FAILED, e);
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
                    put(event, "ssid", wifiName);
                    put(event, "message", "provision success");

                    emitCode("provisioningCodeState", EVENT_PROVISION_SUCCESS, event);

                    // 配网成功后自动断开
                    if (autoDisconnectAfterProvision) {
                        mainHandler.postDelayed(() -> {
                            disconnectReason = "provisionSuccess";
                            disconnectQuietly(false);
                        }, 800);
                    }
                }

                @Override
                public void onProvisioningFailed(Exception e) {
                    if (!isCurrentProvisionTask(taskId)) return;
                    finishProvisionFailure(EVENT_PROVISION_FAILED, e);
                }
            });

            return CODE_SUCCESS;
        } catch (Exception e) {
            finishProvisionFailure(EVENT_PROVISION_FAILED, e);
            return CODE_PARAM_ERROR;
        }
    }

    /**
     * 停止配网
     */
    @UniJSMethod(uiThread = true)
    public int bleStopProvisioning() {
        clearProvisionState();
        emitCode("provisioningCodeState", EVENT_PROVISION_STOPPED, simpleEvent("message", "provision stopped"));
        return CODE_SUCCESS;
    }

    /**
     * 断开设备连接
     */
    @UniJSMethod(uiThread = true)
    public int bleStopDisconnectDevice() {
        disconnectReason = "user";

        clearConnectTimers();
        clearSessionTimers();
        clearProvisionState();

        disconnectQuietly(false);

        connected = false;
        sessionInitialized = false;
        connecting = false;
        sessionInitializing = false;
        espDevice = null;

        emitCode("bleConnectCodeState", EVENT_DISCONNECTED, simpleEvent("reason", "user"));
        return CODE_SUCCESS;
    }

    // ==================== 设备连接事件监听 ====================
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnectionEvent(DeviceConnectionEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        // 设备连接成功
        if (eventType == ESPConstants.EVENT_DEVICE_CONNECTED) {
            clearConnectTimers();

            connecting = false;
            connected = true;
            sessionInitialized = false;
            ignoreNextDisconnectEvent = false;

            JSONObject obj = new JSONObject();
            put(obj, "name", connectedName);
            put(obj, "address", connectedAddress);
            put(obj, "primaryServiceUuid", connectedServiceUuid);
            put(obj, "serviceUuid", connectedServiceUuid);
            put(obj, "message", "ble connected");

            emitCode("bleConnectCodeState", EVENT_CONNECTED, obj);

            // 连接成功 → 自动开始加密会话
            startSessionAttempt();
            return;
        }

        // 连接失败
        if (eventType == ESPConstants.EVENT_DEVICE_CONNECTION_FAILED) {
            handleConnectFailure(true);
            return;
        }

        // 设备断开连接
        if (eventType == ESPConstants.EVENT_DEVICE_DISCONNECTED) {
            if (ignoreNextDisconnectEvent) {
                ignoreNextDisconnectEvent = false;
                return;
            }

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

            emitCode("bleConnectCodeState", EVENT_DISCONNECTED, eventObj);
            disconnectReason = "remote";
        }
    }

    // ==================== 生命周期 ====================
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

    // ==================== 权限与蓝牙开启结果 ====================
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                emitState("blePermissions", true);
                emitCode(tagByAction(pendingAction), CODE_SUCCESS, simpleEvent("message", "bluetooth enabled"));
                resumePendingAction();
            } else {
                emitState("blePermissions", false);
                emitCode(tagByAction(pendingAction), CODE_BLE_NEED_ENABLE, simpleEvent("message", "bluetooth disabled"));
                clearPendingAction();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            String eventTag = tagByAction(pendingAction);

            if (allPermissionsGranted(grantResults)) {
                emitState(permissionEventTag(), true);

                if (pendingAction == ACTION_NONE) {
                    emitCode("bleEnvironmentInit", CODE_SUCCESS, simpleEvent("message", "permissions granted"));
                }

                resumePendingAction();
            } else {
                emitState(permissionEventTag(), false);
                emitCode(eventTag, permissionDeniedCode(), simpleEvent("message", "permission denied"));
                clearPendingAction();
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ==================== 配置更新 ====================
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

    // ==================== 扫描过滤构建 ====================
    private List<ScanFilter> buildScanFilters() {
        List<ScanFilter> filters = new ArrayList<>();

        if (isValidUuid(configuredServiceUuid)) {
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(configuredServiceUuid))
                    .build());
        }

        return filters;
    }

    /**
     * 处理扫描到的 BLE 设备
     */
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;

        String name = resolveDeviceName(result);
        if (name == null) name = "";

        // 名称前缀过滤
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

        // 封装设备信息发给前端
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

    // ==================== 连接、会话、配网核心逻辑 ====================
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
            disconnectQuietly(true);

            // 创建 ESP 设备对象
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
            sessionInitializing = false;
            sessionInitialized = false;
            connectAttempt++;

            JSONObject event = new JSONObject();
            put(event, "attempt", connectAttempt);
            put(event, "name", connectedName);
            put(event, "address", connectedAddress);
            put(event, "primaryServiceUuid", connectedServiceUuid);
            put(event, "serviceUuid", connectedServiceUuid);
            put(event, "securityType", securityType);
            put(event, "message", "connecting");

            emitCode("bleConnectCodeState", EVENT_CONNECTING, event);

            disconnectReason = "remote";
            ignoreNextDisconnectEvent = false;

            // 执行 BLE 连接
            espDevice.connectBLEDevice(scanResult.getDevice(), connectedServiceUuid);

            mainHandler.postDelayed(connectTimeoutRunnable, connectTimeoutMs);
        } catch (SecurityException e) {
            finishConnectFailure(safeMessage(e));
        } catch (Exception e) {
            finishConnectFailure(safeMessage(e));
        }
    }

    /**
     * 连接成功后，建立加密会话
     */
    private void startSessionAttempt() {
        if (!connected || espDevice == null) {
            emitCode("initSessionCodeState", EVENT_SESSION_FAILED, simpleEvent("message", "Device is not connected."));
            return;
        }

        clearSessionTimers();

        sessionInitializing = true;
        sessionInitialized = false;
        sessionAttempt++;

        JSONObject startEvent = new JSONObject();
        put(startEvent, "attempt", sessionAttempt);
        put(startEvent, "message", "session initializing");

        emitCode("initSessionCodeState", EVENT_SESSION_START, startEvent);

        try {
            espDevice.initSession(new ResponseListener() {
                @Override
                public void onSuccess(byte[] returnData) {
                    clearSessionTimers();

                    sessionInitializing = false;
                    sessionInitialized = true;

                    emitCode("initSessionCodeState", EVENT_SESSION_SUCCESS, simpleEvent("message", "session success"));
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

    /**
     * 连接失败处理（支持重试）
     */
    private void handleConnectFailure(boolean retryable) {
        clearConnectTimers();

        connected = false;
        sessionInitializing = false;
        sessionInitialized = false;

        if (retryable && connectRetriesRemaining > 0) {
            connectRetriesRemaining--;

            ScanResult retryResult = scannedResults.get(connectedAddress);

            JSONObject event = new JSONObject();
            put(event, "message", "connect failed, retrying");
            put(event, "remainingRetries", connectRetriesRemaining);
            emitCode("bleConnectCodeState", EVENT_CONNECTING, event);

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
        disconnectQuietly(true);
        espDevice = null;

        emitCode("bleConnectCodeState", EVENT_CONNECT_FAILED, simpleEvent("message", message));
    }

    /**
     * 会话初始化失败（支持重试）
     */
    private void handleSessionFailure(boolean retryable, Exception e) {
        clearSessionTimers();

        sessionInitialized = false;

        if (retryable && sessionRetriesRemaining > 0) {
            sessionRetriesRemaining--;

            JSONObject event = new JSONObject();
            put(event, "message", "session failed, retrying");
            put(event, "remainingRetries", sessionRetriesRemaining);
            emitCode("initSessionCodeState", EVENT_SESSION_START, event);

            pendingSessionRetryRunnable = this::startSessionAttempt;
            mainHandler.postDelayed(pendingSessionRetryRunnable, retryDelayMs);
            return;
        }

        sessionInitializing = false;

        JSONObject event = new JSONObject();
        put(event, "message", safeMessage(e));
        put(event, "needReconnect", true);

        emitCode("initSessionCodeState", EVENT_SESSION_FAILED, event);

        disconnectReason = "sessionFailed";
        disconnectQuietly(true);

        connected = false;
        sessionInitialized = false;
        espDevice = null;
    }

    private void handleSessionFailure(boolean retryable) {
        handleSessionFailure(retryable, new Exception("Session timeout."));
    }

    /**
     * 配网失败统一处理
     */
    private void finishProvisionFailure(int codeState, Exception e) {
        finishProvisionFailure(codeState, messageEvent(e));
    }

    private void finishProvisionFailure(int codeState, JSONObject event) {
        mainHandler.removeCallbacks(provisionTimeoutRunnable);
        provisioning = false;
        provisionTaskId++;
        emitCode("provisioningCodeState", codeState, event);
    }

    /**
     * 校验是否是当前配网任务（防止并发）
     */
    private boolean isCurrentProvisionTask(long taskId) {
        return provisioning && taskId == provisionTaskId;
    }

    /**
     * 映射 ESP 设备返回的配网失败原因
     */
    private int mapProvisionFailure(ESPConstants.ProvisionFailureReason reason) {
        if (reason == null) return EVENT_PROVISION_FAILED;

        if (reason == ESPConstants.ProvisionFailureReason.AUTH_FAILED) {
            return EVENT_PROVISION_AUTH_FAILED;
        }

        if (reason == ESPConstants.ProvisionFailureReason.NETWORK_NOT_FOUND) {
            return EVENT_PROVISION_NETWORK_NOT_FOUND;
        }

        return EVENT_PROVISION_FAILED;
    }

    // ==================== 超时回调 ====================
    private void onScanTimeout() {
        if (!scanning) return;

        stopScanInternal(false);

        JSONObject obj = new JSONObject();
        put(obj, "count", scannedResults.size());
        put(obj, "message", "scan timeout");

        emitCode("bleScanListenerCodeState", EVENT_SCAN_FINISHED, obj);
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

        emitCode("provisioningCodeState", EVENT_PROVISION_FAILED, event);
    }

    // ==================== 权限、蓝牙、位置服务 ====================
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

    /**
     * 根据 Android 版本获取缺失权限
     * Android 12+：BLUETOOTH_SCAN / CONNECT
     * 旧版本：ACCESS_FINE_LOCATION
     */
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

    /**
     * 确保蓝牙已开启
     */
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

    /**
     * 确保位置开启（Android 12 以下 BLE 扫描必须）
     */
    private int ensureLocationEnabledForBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return CODE_SUCCESS;
        }

        if (appContext == null) return CODE_SUCCESS;

        try {
            LocationManager manager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return CODE_LOCATION_DISABLED;

            boolean gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            return gpsEnabled || networkEnabled ? CODE_SUCCESS : CODE_LOCATION_DISABLED;
        } catch (Exception e) {
            return CODE_LOCATION_DISABLED;
        }
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

    /**
     * 权限/蓝牙开启后，恢复之前的动作
     */
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

    // ==================== 内部停止/断开/释放 ====================
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

            emitCode("bleScanListenerCodeState", EVENT_SCAN_FINISHED, obj);
        }
    }

    /**
     * 安静断开，不抛异常
     */
    private void disconnectQuietly(boolean ignoreEvent) {
        try {
            if (espDevice != null) {
                ignoreNextDisconnectEvent = ignoreEvent;
                espDevice.disconnectDevice();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 释放所有资源
     */
    private void releaseAll(boolean unregisterEventBus) {
        stopScanInternal(false);

        clearPendingAction();
        clearConnectTimers();
        clearSessionTimers();
        clearProvisionState();

        mainHandler.removeCallbacksAndMessages(null);

        disconnectReason = "release";
        disconnectQuietly(true);

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
        ignoreNextDisconnectEvent = false;

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

    // ==================== 工具方法 ====================
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

    private String tagByAction(int action) {
        if (action == ACTION_SCAN) return "bleScanListenerCodeState";
        if (action == ACTION_CONNECT) return "bleConnectCodeState";
        if (action == ACTION_SCAN_WIFI) return "wifiList";
        if (action == ACTION_PROVISION) return "provisioningCodeState";
        return "bleEnvironmentInit";
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

    /**
     * 向前端发送带状态码的事件
     */
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

    /**
     * 触发 uni-app 全局事件，给前端回调
     */
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