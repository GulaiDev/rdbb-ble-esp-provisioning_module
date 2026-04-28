# rdbb-ble-esp-provisioning_module uni-app 使用文档

`rdbb-ble-esp-provisioning_module` 是一个 uni-app Android 原生插件，用于通过 BLE 搜索、连接 ESP 配网设备，并完成 ESP Wi-Fi 配网流程。

当前版本的 `scanWifiList` 是让已连接的 ESP 设备扫描周边 Wi-Fi，不是手机本机扫描 Wi-Fi。因此推荐调用顺序是：

```text
init -> searchESPDevices -> connect -> initializeSession -> scanWifiList -> provision
```

## 插件注册

宿主 Android 工程需要在 `app/src/main/assets/dcloud_uniplugins.json` 中注册模块：

```json
{
  "nativePlugins": [
    {
      "plugins": [
        {
          "type": "module",
          "name": "rdbb-ble-esp-provisioning_module",
          "class": "com.rdbb.esp.RdbbBleEspProvisioningModule"
        }
      ]
    }
  ]
}
```

uni-app 页面中获取模块：

```js
const esp = weex.requireModule('rdbb-ble-esp-provisioning_module')
```

## Android 权限

模块已声明 BLE 扫描和连接所需权限。运行时权限会由插件在调用 `searchESPDevices`、`connect`、`initializeSession` 时自动申请。

Android 12 及以上需要附近设备权限：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Android 11 及以下 BLE 扫描需要定位权限：

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

如果扫描不到 BLE 设备，请确认：

- 手机蓝牙已打开。
- App 已授予附近设备或定位权限。
- Android 11 及以下机型通常还需要打开系统定位开关。
- ESP 设备正在广播，且设备名匹配 `prefix`，默认是 `PROV_`。

## 事件监听

插件通过全局事件 `rdbbEspEvent` 返回异步结果。

```js
let listener = null

export default {
  onLoad() {
    listener = plus.globalEvent.addEventListener('rdbbEspEvent', (event) => {
      const { type, data } = event
      console.log('[ESP event]', type, data)

      switch (type) {
        case 'deviceFound':
          // data: { name, address, rssi, serviceUuid }
          break
        case 'connected':
          // data: { name, attempt }
          break
        case 'sessionSuccess':
          // session 初始化成功后再 scanWifiList
          break
        case 'wifiList':
          // data: { list: [{ ssid, rssi, security }] }
          break
        case 'provisionSuccess':
          // data: { ssid }
          break
        case 'error':
          // data: { code, message }
          uni.showToast({ title: data.message || data.code, icon: 'none' })
          break
      }
    })
  },
  onUnload() {
    if (listener) plus.globalEvent.removeEventListener('rdbbEspEvent', listener)
    try { esp.destroy({}) } catch (e) {}
  }
}
```

## 快速示例

```vue
<template>
  <view class="page">
    <button @click="initPlugin">初始化</button>
    <button @click="scanDevices">扫描 ESP 设备</button>
    <button @click="initSession" :disabled="!connected">初始化会话</button>
    <button @click="scanWifi" :disabled="!sessionReady">扫描周边 Wi-Fi</button>
    <button @click="startProvision" :disabled="!ssid">开始配网</button>

    <view v-for="item in devices" :key="item.address" @click="connectDevice(item)">
      {{ item.name }} - {{ item.address }} - {{ item.rssi }}
    </view>

    <view v-for="item in wifiList" :key="item.ssid" @click="ssid = item.ssid">
      {{ item.ssid }} - {{ item.rssi }}
    </view>

    <input v-model="ssid" placeholder="SSID" />
    <input v-model="password" placeholder="Wi-Fi 密码" password />
  </view>
</template>

<script>
const esp = weex.requireModule('rdbb-ble-esp-provisioning_module')

export default {
  data() {
    return {
      devices: [],
      wifiList: [],
      connected: false,
      sessionReady: false,
      ssid: '',
      password: '',
      eventListener: null,
      options: {
        securityType: 1,
        pop: '',
        prefix: 'PROV_',
        serviceUuid: '0000ffff-0000-1000-8000-00805f9b34fb'
      }
    }
  },
  onLoad() {
    this.eventListener = plus.globalEvent.addEventListener('rdbbEspEvent', this.onEspEvent)
  },
  onUnload() {
    if (this.eventListener) {
      plus.globalEvent.removeEventListener('rdbbEspEvent', this.eventListener)
    }
    esp.destroy({})
  },
  methods: {
    onEspEvent(event) {
      const { type, data } = event
      console.log('[ESP]', type, data)

      if (type === 'deviceFound') {
        if (!this.devices.find(item => item.address === data.address)) {
          this.devices.push(data)
        }
      } else if (type === 'connected') {
        this.connected = true
      } else if (type === 'disconnected') {
        this.connected = false
        this.sessionReady = false
      } else if (type === 'sessionSuccess') {
        this.sessionReady = true
      } else if (type === 'wifiList') {
        this.wifiList = data.list || []
      } else if (type === 'provisionSuccess') {
        uni.showToast({ title: '配网成功', icon: 'success' })
      } else if (type === 'error') {
        uni.showToast({ title: data.message || data.code, icon: 'none' })
      }
    },

    initPlugin() {
      const result = esp.init(this.options)
      console.log('init result:', result)
    },

    scanDevices() {
      this.devices = []
      esp.searchESPDevices({
        prefix: this.options.prefix,
        serviceUuid: this.options.serviceUuid,
        timeoutMs: 10000
      })
    },

    connectDevice(device) {
      esp.connect({
        address: device.address,
        name: device.name,
        securityType: this.options.securityType,
        pop: this.options.pop,
        serviceUuid: device.serviceUuid || this.options.serviceUuid
      })
    },

    initSession() {
      esp.initializeSession({
        sessionTimeoutMs: 10000,
        maxSessionRetries: 1
      })
    },

    scanWifi() {
      this.wifiList = []
      esp.scanWifiList({})
    },

    startProvision() {
      esp.provision({
        ssid: this.ssid,
        password: this.password
      })
    }
  }
}
</script>
```

## API

### init(options)

初始化插件。必须先调用。

参数：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| securityType | number | 1 | ESP 配网安全模式，`0` 表示 SECURITY_0，`1` 表示 SECURITY_1 |
| pop | string | '' | Proof of Possession，ESP 固件配置了 POP 时必须填写 |
| prefix | string | PROV_ | BLE 设备名前缀，只返回匹配此前缀的设备 |
| serviceUuid | string | 0000ffff-0000-1000-8000-00805f9b34fb | BLE primary service UUID |
| connectTimeoutMs | number | 15000 | 默认连接超时时间 |
| sessionTimeoutMs | number | 10000 | 默认 session 初始化超时时间 |
| maxConnectRetries | number | 1 | 默认连接重试次数 |
| maxSessionRetries | number | 1 | 默认 session 初始化重试次数 |
| retryDelayMs | number | 1500 | 重试间隔 |

返回：

```js
{ success: true, message: 'initialized' }
```

### searchESPDevices(options)

扫描 ESP BLE 配网设备。

参数：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| prefix | string | init 中的 prefix | BLE 设备名前缀 |
| serviceUuid | string | init 中的 serviceUuid | BLE primary service UUID |
| timeoutMs | number | 10000 | 扫描超时时间 |

相关事件：

- `scanStart`: 开始扫描。
- `deviceFound`: 找到设备，返回 `{ name, address, rssi, serviceUuid }`。
- `scanStop`: 停止扫描。
- `scanTimeout`: 扫描超时结束。
- `error`: 扫描失败。

### stopScan({})

停止扫描 ESP BLE 设备。

返回：

```js
{ success: true, message: 'scan stopped' }
```

### connect(data)

连接扫描到的 ESP BLE 设备。

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| address | string | 是 | `deviceFound` 返回的设备地址 |
| name | string | 否 | 设备名 |
| securityType | number | 否 | 安全模式 |
| pop | string | 否 | Proof of Possession |
| serviceUuid | string | 否 | BLE primary service UUID |
| connectTimeoutMs | number | 否 | 本次连接超时时间 |
| maxConnectRetries | number | 否 | 本次连接重试次数 |
| retryDelayMs | number | 否 | 重试间隔 |

相关事件：

- `connectStart`
- `connected`
- `connectRetry`
- `disconnected`
- `error`

### initializeSession(data)

初始化 ESP 配网 session。建议在收到 `connected` 后调用，收到 `sessionSuccess` 后再扫描 Wi-Fi 或配网。

参数：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| sessionTimeoutMs | number | 10000 | session 初始化超时 |
| maxSessionRetries | number | 1 | 重试次数 |
| retryDelayMs | number | 1500 | 重试间隔 |

相关事件：

- `sessionStart`
- `sessionSuccess`
- `sessionRetry`
- `error`

### scanWifiList({})

让 ESP 设备扫描周边 Wi-Fi。

调用前必须已经连接 ESP 设备，推荐已经收到 `sessionSuccess`。返回的 Wi-Fi 列表来自 ESP 设备可见的 AP，而不是手机可见的 AP。

相关事件：

```js
{
  type: 'wifiList',
  data: {
    list: [
      { ssid: 'YourWiFi', rssi: -45, security: 3 }
    ]
  }
}
```

注意：

- ESP32 常见型号只支持 2.4 GHz Wi-Fi，不支持 5 GHz/6 GHz。
- 如果手机能看到 Wi-Fi，但 ESP 扫不到，通常是该 AP 不是 2.4 GHz，或信号对 ESP 所在位置不可见。
- SSID 不能隐藏；隐藏 SSID 可能不会出现在扫描列表中。

### provision(data)

向 ESP 下发 Wi-Fi 账号密码并开始配网。

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| ssid | string | 是 | Wi-Fi 名称 |
| password | string | 否 | Wi-Fi 密码，开放网络可为空 |

相关事件：

- `wifiConfigSent`: Wi-Fi 配置已发送。
- `wifiConfigApplied`: Wi-Fi 配置已应用。
- `provisionSuccess`: 配网成功。
- `error`: 配网失败。

### disconnect({})

断开当前 ESP 设备。

返回：

```js
{ success: true, message: 'disconnected' }
```

### destroy({})

释放插件资源。建议页面卸载时调用。

返回：

```js
{ success: true, message: 'destroyed' }
```

## 事件列表

| type | 说明 |
| --- | --- |
| permissionRequest | 正在请求 Android 权限 |
| permissionGranted | 权限已授予 |
| permissionDenied | 权限被拒绝 |
| bluetoothDisabled | 蓝牙未打开 |
| bluetoothEnableRequested | 已拉起蓝牙开启弹窗 |
| bluetoothEnabled | 蓝牙已打开 |
| bluetoothEnableCanceled | 用户取消开启蓝牙 |
| scanStart | 开始扫描 ESP BLE 设备 |
| deviceFound | 找到 ESP BLE 设备 |
| scanStop | 停止扫描 |
| scanTimeout | 扫描超时 |
| connectStart | 开始连接 |
| connected | BLE 已连接 |
| connectRetry | 连接失败后重试 |
| disconnected | 设备已断开 |
| sessionStart | 开始初始化 session |
| sessionSuccess | session 初始化成功 |
| sessionRetry | session 初始化失败后重试 |
| wifiList | ESP 扫描到的 Wi-Fi 列表 |
| wifiConfigSent | Wi-Fi 配置已发送 |
| wifiConfigApplied | Wi-Fi 配置已应用 |
| provisionSuccess | 配网成功 |
| error | 错误事件 |

## 常见错误

| code | 含义 | 处理建议 |
| --- | --- | --- |
| NOT_INITIALIZED | 未初始化 | 先调用 `init` |
| NO_ACTIVITY | 当前没有前台 Activity | 确认在页面前台调用 |
| NO_PERMISSION | 缺少系统权限 | 授权附近设备、蓝牙或定位权限 |
| BLE_UNAVAILABLE | 设备不支持蓝牙或蓝牙不可用 | 检查手机蓝牙 |
| BLE_SCANNER_NULL | BLE 扫描器不可用 | 打开蓝牙后重试 |
| BLE_SCAN_FAILED | BLE 扫描失败 | 等待几秒重试，避免频繁扫描 |
| DEVICE_NOT_FOUND | 未找到设备 | 先扫描，使用 `deviceFound.address` 连接 |
| DEVICE_NULL | 未连接设备 | 先调用 `connect` |
| CONNECT_TIMEOUT | BLE 连接超时 | 靠近设备，确认设备仍在配网模式 |
| SESSION_TIMEOUT | session 初始化超时 | 检查 POP、安全模式和固件配网服务 |
| WIFI_SCAN_FAILED | ESP 扫描 Wi-Fi 失败 | 确认已连接并完成 session 初始化 |
| SSID_EMPTY | SSID 为空 | 传入有效 SSID |
| WIFI_CONFIG_FAILED | Wi-Fi 配置发送失败 | 检查 BLE 连接状态 |
| WIFI_APPLY_FAILED | ESP 应用 Wi-Fi 配置失败 | 检查 SSID/密码 |
| PROVISION_FAILED_FROM_DEVICE | ESP 返回配网失败 | 查看 ESP 串口日志 |
| PROVISION_FAILED | 配网流程失败 | 检查 Wi-Fi 类型、密码和信号 |

## ESP 端日志排查

如果 ESP 串口出现类似日志：

```text
network_prov_mgr: STA Disconnected
network_prov_mgr: Disconnect reason : 201
network_prov_mgr: STA AP Not found
app_wifi: Provisioning failed!
Reason : Wi-Fi station authentication failed
```

通常说明 ESP 没有成功连接下发的 Wi-Fi，重点检查：

- SSID 是否是 2.4 GHz Wi-Fi。
- 密码是否正确，注意大小写和特殊字符。
- ESP 所在位置是否能收到该 AP 信号。
- 路由器是否开启 WPA3-only、隐藏 SSID、MAC 地址过滤等限制。
- ESP 固件的安全模式和 App 传入的 `securityType`、`pop` 是否一致。
- 失败后固件可能会清除已下发凭据，需要重新进入配网模式再试。

## 调用顺序建议

```js
esp.init({ securityType: 1, pop: '', prefix: 'PROV_' })

esp.searchESPDevices({ timeoutMs: 10000 })

// 收到 deviceFound 后：
esp.connect({ address, name, securityType: 1, pop: '' })

// 收到 connected 后：
esp.initializeSession({})

// 收到 sessionSuccess 后：
esp.scanWifiList({})

// 用户选择 Wi-Fi 后：
esp.provision({ ssid, password })
```

