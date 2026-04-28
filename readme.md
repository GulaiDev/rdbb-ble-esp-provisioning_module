# rdbb-ble-esp-provisioning_module 使用文档

`rdbb-ble-esp-provisioning_module` 是一个用于 **uni-app Android 原生插件** 的 ESP BLE 配网模块，基于 Espressif Android Provisioning SDK 实现 BLE 设备扫描、连接、安全会话初始化、WiFi 扫描和 WiFi 配网流程。

本文档适用于新版插件代码：

- 插件事件统一通过 `espEvent` 回调
- 前端调用采用 **同步返回 code + 异步事件通知** 模式
- 连接 BLE 成功后，插件内部会自动初始化 Session
- 支持从 BLE 广播中自动获取 `Service UUID`
- 支持手动传入 `serviceUuid` 作为兜底
- 支持配网成功后自动断开设备连接

---

## 1. 插件引入

在页面中引入原生插件：

```js
const espModule = uni.requireNativePlugin("rdbb-ble-esp-provisioning_module");
const globalEvent = uni.requireNativePlugin("globalEvent");
```

监听插件统一事件：

```js
globalEvent.addEventListener("espEvent", (event) => {
  console.log("espEvent:", event);
});
```

页面销毁时建议移除监听并释放环境：

```js
onUnload() {
  try {
    globalEvent.removeEventListener('espEvent')
  } catch (e) {}

  try {
    espModule.bleEnvironmentOnUnload()
  } catch (e) {}
}
```

---

## 2. 标准配网流程

推荐调用流程如下：

```text
初始化环境
  ↓
扫描 BLE 设备
  ↓
选择设备并连接
  ↓
插件内部自动初始化 Session
  ↓
扫描 ESP 设备周边 WiFi
  ↓
选择 WiFi 并输入密码
  ↓
执行配网
  ↓
配网成功后可自动断开
```

注意：新版插件中 **不需要前端单独调用 initSession**。BLE 连接成功后，插件内部会自动调用 `espDevice.initSession()`。

---

## 3. API 说明

### 3.1 初始化环境

```js
const code = espModule.bleEnvironmentOnLoad({
  isRequestPermissions: true,
  securityType: 1,
  pop: "8651c7b9",
  prefix: "PROV_",
  serviceUuid: "",
  scanTimeoutMs: 10000,
  connectTimeoutMs: 15000,
  sessionTimeoutMs: 10000,
  provisionTimeoutMs: 60000,
  retryDelayMs: 1200,
  maxConnectRetries: 1,
  maxSessionRetries: 1,
  autoDisconnectAfterProvision: true,
});
```

#### 参数说明

| 参数                           |    类型 | 必填 | 默认值  | 说明                                         |
| ------------------------------ | ------: | ---: | ------- | -------------------------------------------- |
| `isRequestPermissions`         | Boolean |   否 | `true`  | 是否由插件主动申请蓝牙/定位权限              |
| `securityType`                 |  Number |   否 | `1`     | ESP 安全级别，`0` 或 `1`                     |
| `pop`                          |  String |   否 | `''`    | Proof of Possession，`securityType=1` 时使用 |
| `prefix`                       |  String |   否 | `''`    | 扫描设备名前缀，例如 `PROV_`                 |
| `serviceUuid`                  |  String |   否 | `''`    | 可选 Service UUID，通常优先使用广播 UUID     |
| `scanTimeoutMs`                |  Number |   否 | `10000` | BLE 扫描超时时间                             |
| `connectTimeoutMs`             |  Number |   否 | `15000` | BLE 连接超时时间                             |
| `sessionTimeoutMs`             |  Number |   否 | `10000` | Session 初始化超时时间                       |
| `provisionTimeoutMs`           |  Number |   否 | `60000` | 配网超时时间                                 |
| `retryDelayMs`                 |  Number |   否 | `1200`  | 失败重试间隔                                 |
| `maxConnectRetries`            |  Number |   否 | `1`     | 连接失败重试次数                             |
| `maxSessionRetries`            |  Number |   否 | `1`     | Session 失败重试次数                         |
| `autoDisconnectAfterProvision` | Boolean |   否 | `false` | 配网成功后是否自动断开                       |

#### 相关事件

```js
{
  tag: 'bleEnvironmentInit',
  code: 0,
  message: 'environment ready'
}
```

---

### 3.2 销毁环境

```js
const code = espModule.bleEnvironmentOnUnload();
```

会释放扫描、连接、Session、配网任务和 EventBus 监听。

#### 相关事件

```js
{
  tag: 'bleEnvironmentUnload',
  code: 0,
  message: 'environment released'
}
```

---

### 3.3 开始扫描 BLE 设备

```js
const code = espModule.bleStartSearchDevice("PROV_");
```

参数为设备名前缀。为空字符串时表示不过滤设备名。

扫描到设备时，会通过 `mBleDevice` 事件返回：

```js
{
  tag: 'mBleDevice',
  mBleDevice: {
    name: 'PROV_xxx',
    deviceName: 'PROV_xxx',
    address: 'AA:BB:CC:DD:EE:FF',
    rssi: -55,
    serviceUuid: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
    primaryServiceUuid: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'
  }
}
```

扫描状态事件：

```js
{
  tag: 'bleScanListenerCodeState',
  code: 100,
  message: 'scan started'
}
```

扫描结束事件：

```js
{
  tag: 'bleScanListenerCodeState',
  code: 0,
  count: 3,
  message: 'scan timeout'
}
```

---

### 3.4 停止扫描 BLE 设备

```js
const code = espModule.bleStopSearchDevice();
```

---

### 3.5 连接 BLE 设备

```js
const code = espModule.bleConnectDevice({
  mBleDevice: {
    name: device.name,
    deviceName: device.deviceName,
    address: device.address,
    serviceUuid: device.serviceUuid,
    primaryServiceUuid: device.primaryServiceUuid,
  },
  serviceUuid: device.serviceUuid || manualServiceUuid,
  securityType: 1,
  pop: "8651c7b9",
  connectTimeoutMs: 15000,
  sessionTimeoutMs: 10000,
  retryDelayMs: 1200,
  maxConnectRetries: 1,
  maxSessionRetries: 1,
});
```

#### 重要说明

连接设备时插件会从扫描缓存中取 `ScanResult`，因此：

1. 必须先扫描到设备
2. 连接时传入的 `address` 必须和扫描结果一致
3. 如果设备广播中没有 Service UUID，需要手动填写 `serviceUuid`

连接成功事件：

```js
{
  tag: 'bleConnectCodeState',
  code: 1,
  name: 'PROV_xxx',
  address: 'AA:BB:CC:DD:EE:FF',
  serviceUuid: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
  primaryServiceUuid: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
  message: 'ble connected'
}
```

连接成功后，插件会自动开始初始化 Session：

```js
{
  tag: 'initSessionCodeState',
  code: 100,
  message: 'session initializing'
}
```

Session 成功事件：

```js
{
  tag: 'initSessionCodeState',
  code: 1,
  message: 'session success'
}
```

只有收到 Session 成功事件后，才建议调用 `bleScanNetworks()`。

---

### 3.6 扫描 ESP 设备周边 WiFi

```js
const code = espModule.bleScanNetworks();
```

WiFi 扫描开始事件：

```js
{
  tag: 'wifiList',
  code: 100,
  message: 'wifi scan started'
}
```

WiFi 列表事件：

```js
{
  tag: 'wifiList',
  code: 1,
  wifiList: [
    {
      wifiName: 'MyWiFi',
      ssid: 'MyWiFi',
      rssi: -45,
      security: 3
    }
  ],
  message: 'wifi list received'
}
```

---

### 3.7 开始配网

```js
const code = espModule.bleStartProvisioning({
  wifiName: "MyWiFi",
  ssid: "MyWiFi",
  passWord: "12345678",
  password: "12345678",
  security: 3,
  provisionTimeoutMs: 60000,
  autoDisconnectAfterProvision: true,
});
```

#### 参数说明

| 参数                           |    类型 | 必填 | 说明                  |
| ------------------------------ | ------: | ---: | --------------------- |
| `wifiName`                     |  String |   是 | WiFi 名称             |
| `ssid`                         |  String |   否 | WiFi 名称，兼容字段   |
| `passWord`                     |  String |   否 | WiFi 密码，兼容旧字段 |
| `password`                     |  String |   否 | WiFi 密码             |
| `security`                     |  Number |   否 | WiFi 加密类型         |
| `provisionTimeoutMs`           |  Number |   否 | 配网超时时间          |
| `autoDisconnectAfterProvision` | Boolean |   否 | 配网成功后自动断开    |

配网过程事件：

```js
{
  tag: 'provisioningCodeState',
  code: 100,
  message: 'wifi config sent'
}
```

```js
{
  tag: 'provisioningCodeState',
  code: 101,
  message: 'wifi config applied'
}
```

配网成功事件：

```js
{
  tag: 'provisioningCodeState',
  code: 0,
  wifiName: 'MyWiFi',
  ssid: 'MyWiFi',
  message: 'provision success'
}
```

---

### 3.8 停止配网

```js
const code = espModule.bleStopProvisioning();
```

相关事件：

```js
{
  tag: 'provisioningCodeState',
  code: 3,
  message: 'provision stopped'
}
```

---

### 3.9 断开 BLE 设备

```js
const code = espModule.bleStopDisconnectDevice();
```

断开事件：

```js
{
  tag: 'bleConnectCodeState',
  code: 3,
  reason: 'user'
}
```

---

## 4. 事件 tag 总表

| tag                        | 说明                      |
| -------------------------- | ------------------------- |
| `bleEnvironmentInit`       | 环境初始化结果            |
| `bleEnvironmentUnload`     | 环境销毁结果              |
| `blePermissions`           | 蓝牙权限状态              |
| `blePermissionsState`      | 蓝牙权限状态兼容事件      |
| `locationPermissions`      | 定位权限状态              |
| `mBleDevice`               | 扫描到 BLE 设备           |
| `bleScanListenerCodeState` | BLE 扫描状态              |
| `bleConnectCodeState`      | BLE 连接状态              |
| `initSessionCodeState`     | Session 初始化状态        |
| `wifiList`                 | WiFi 扫描状态和 WiFi 列表 |
| `provisioningCodeState`    | 配网状态                  |
| `configCodeState`          | 配置参数错误              |

---

## 5. 通用同步 code 说明

|  code | 说明                |
| ----: | ------------------- |
|   `0` | 成功                |
|   `1` | 环境已存在          |
|   `2` | 配网环境未初始化    |
|   `3` | 任务已停止 / 已断开 |
|   `4` | 扫描任务已在运行    |
|   `5` | 设备不支持扫描 WiFi |
|   `6` | 蓝牙设备未连接      |
|   `7` | 参数错误            |
|   `8` | Session 初始化失败  |
|  `10` | 环境检查成功        |
|  `11` | 位置服务未开启      |
|  `12` | GPS 未开启          |
|  `13` | 不支持 BLE          |
|  `14` | 需要打开蓝牙        |
|  `15` | 需要位置权限        |
|  `16` | 位置权限被拒绝      |
|  `17` | 需要位置权限        |
|  `18` | 正在连接            |
|  `19` | 已连接，请先断开    |
|  `20` | 正在配网            |
|  `21` | 需要蓝牙权限        |
|  `22` | 蓝牙权限申请已弹窗  |
| `100` | 处理中              |
| `101` | 已应用              |

---

## 6. 业务侧推荐状态判断

建议前端维护以下状态：

```js
{
  envReady: false,
  connected: false,
  sessionReady: false,
  provisioning: false,
  bleDevices: [],
  wifiList: []
}
```

推荐判断规则：

| 事件                           | 判断                                      |
| ------------------------------ | ----------------------------------------- |
| `bleEnvironmentInit code=0/1`  | `envReady = true`                         |
| `bleConnectCodeState code=1`   | `connected = true`                        |
| `initSessionCodeState code=1`  | `sessionReady = true`                     |
| `wifiList code=1`              | 更新 `wifiList`                           |
| `provisioningCodeState code=0` | 配网成功                                  |
| `bleConnectCodeState code=3`   | `connected = false; sessionReady = false` |

---

## 7. Service UUID 使用说明

### 7.1 正常情况

ESP BLE 配网设备广播中通常会带有 Service UUID。插件扫描设备时会解析并返回：

```js
mBleDevice.serviceUuid;
mBleDevice.primaryServiceUuid;
```

连接时前端直接使用扫描结果中的 UUID 即可。

### 7.2 未广播 UUID 的情况

如果扫描结果中没有 UUID，连接时会失败并提示：

```text
No valid BLE serviceUuid found.
```

此时需要前端手动填写设备对应的 `serviceUuid`，并在连接参数中传入：

```js
{
  serviceUuid: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx";
}
```

### 7.3 推荐策略

```js
const finalServiceUuid =
  device.serviceUuid || device.primaryServiceUuid || manualServiceUuid;
```

优先级：

```text
扫描广播 UUID > 手动填写 UUID > 初始化配置 UUID
```

---

## 8. Android 权限说明

### Android 12 及以上

需要：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

### Android 11 及以下

需要：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

### BLE Feature

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## 9. 测试页面调用建议

页面建议提供以下按钮：

```text
初始化环境
扫描设备
停止扫描
连接设备
扫描 WiFi
开始配网
停止配网
断开设备
销毁环境
```

其中连接设备、开始配网可以通过点击列表项触发：

- 点击 BLE 设备列表项：连接设备
- 点击 WiFi 列表项：输入密码并开始配网

---

## 10. 常见问题

### 10.1 为什么连接失败？

常见原因：

1. 没有先扫描设备
2. 扫描缓存中没有该设备的 `ScanResult`
3. 设备没有广播 Service UUID，且前端没有手动填写
4. Android 12+ 没有授予蓝牙权限
5. Android 11 及以下没有定位权限或位置服务未开启
6. POP 错误
7. 设备已经被其他手机连接

---

### 10.2 为什么扫描不到设备？

请检查：

1. ESP 设备是否处于配网模式
2. 设备名前缀是否正确，例如 `PROV_`
3. 是否开启蓝牙
4. 是否授予权限
5. Android 11 及以下是否开启定位服务
6. 是否设置了错误的 `serviceUuid` 扫描过滤

如果不确定设备名，建议将前缀设置为空：

```js
espModule.bleStartSearchDevice("");
```

---

### 10.3 配网成功后会不会自动断开？

取决于参数：

```js
autoDisconnectAfterProvision: true;
```

如果设置为 `true`，配网成功后插件会延迟断开 BLE 连接。

---

### 10.4 securityType 应该填 0 还是 1？

取决于 ESP 固件配置。

常见情况：

| 固件安全级别 | 前端参数                    |
| ------------ | --------------------------- |
| Security 0   | `securityType: 0`，POP 为空 |
| Security 1   | `securityType: 1`，需要 POP |

如果固件要求 POP，但前端 POP 错误，通常会导致 Session 初始化失败或配网失败。

---

### 10.5 为什么不能直接连接手动构造的设备？

插件连接时依赖 Android BLE 扫描得到的 `ScanResult`，并通过 `address` 从缓存中取设备对象。因此必须先扫描到设备，再连接。

---

## 11. 最小调用示例

```js
const espModule = uni.requireNativePlugin("rdbb-ble-esp-provisioning_module");
const globalEvent = uni.requireNativePlugin("globalEvent");

globalEvent.addEventListener("espEvent", (event) => {
  console.log("espEvent", event);
});

espModule.bleEnvironmentOnLoad({
  isRequestPermissions: true,
  securityType: 1,
  pop: "8651c7b9",
  prefix: "PROV_",
  serviceUuid: "",
  autoDisconnectAfterProvision: true,
});

espModule.bleStartSearchDevice("PROV_");
```

扫描到设备后：

```js
espModule.bleConnectDevice({
  mBleDevice: device,
  serviceUuid: device.serviceUuid || device.primaryServiceUuid,
  securityType: 1,
  pop: "8651c7b9",
});
```

Session 成功后：

```js
espModule.bleScanNetworks();
```

选择 WiFi 后：

```js
espModule.bleStartProvisioning({
  wifiName: "MyWiFi",
  password: "12345678",
  autoDisconnectAfterProvision: true,
});
```

---

## 12. 推荐测试顺序

```text
1. 点击“初始化环境”
2. 点击“扫描设备”
3. 等待设备列表出现 PROV_xxx
4. 点击设备进行连接
5. 等待“Session 初始化成功”
6. 点击“扫描 WiFi”
7. 点击目标 WiFi
8. 输入 WiFi 密码
9. 等待配网成功
10. 如设置 autoDisconnectAfterProvision=true，设备会自动断开
```

---

## 13. 注意事项

1. `serviceUuid` 不是连接后才获取，而是应从 BLE 广播中解析，或由前端手动传入。
2. `bleConnectDevice` 必须使用扫描返回的设备对象，不建议手动伪造。
3. `securityType=1` 时，如果固件配置了 POP，前端必须传入正确 POP。
4. 收到 `initSessionCodeState code=1` 后再扫描 WiFi。
5. 配网期间不要重复调用 `bleStartProvisioning`。
6. 页面销毁时必须调用 `bleEnvironmentOnUnload()` 释放资源

---

## 14. 内部常量与回调码说明

本节用于对照插件源码中的请求码、动作类型、通用状态码、事件回调码以及默认超时/重试配置。前端接收 `espEvent` 时，建议优先根据 `tag` 区分事件来源，再根据 `code` 判断状态；部分事件码会在不同 `tag` 下复用相同数值。

### 14.1 权限请求、蓝牙开启请求码

| 常量 | 数值 | 说明 |
| --- | ---: | --- |
| `REQUEST_CODE_PERMISSIONS` | `41001` | Android 权限申请请求码，用于区分蓝牙、定位等运行时权限申请结果。 |
| `REQUEST_CODE_ENABLE_BLUETOOTH` | `41002` | 请求用户开启蓝牙的请求码，用于区分系统蓝牙开启页面返回结果。 |

### 14.2 当前执行动作类型

动作类型用于插件内部记录当前正在处理的业务流程，通常与权限申请、蓝牙开启后的续执行逻辑配合使用。

| 常量 | 数值 | 说明 |
| --- | ---: | --- |
| `ACTION_NONE` | `0` | 当前没有待执行动作。 |
| `ACTION_SCAN` | `1` | 当前动作是扫描 BLE 设备。 |
| `ACTION_CONNECT` | `2` | 当前动作是连接 BLE 设备。 |
| `ACTION_SCAN_WIFI` | `3` | 当前动作是扫描 ESP 设备周边 WiFi。 |
| `ACTION_PROVISION` | `4` | 当前动作是执行 WiFi 配网。 |

### 14.3 通用状态码

这些 code 可能作为 API 同步返回值，也可能出现在事件回调中。业务侧建议将 `0` 作为成功，其余 code 按具体场景提示用户或继续等待。

| 常量 | 数值 | 说明 |
| --- | ---: | --- |
| `CODE_SUCCESS` | `0` | 操作成功。 |
| `CODE_ENV_EXISTS` | `1` | 环境已经初始化，可直接继续使用。 |
| `CODE_NOT_INITIALIZED` | `2` | 配网环境未初始化，需要先调用 `bleEnvironmentOnLoad()`。 |
| `CODE_SCAN_STOPPED` | `3` | 扫描已停止，或当前任务已被停止。 |
| `CODE_SCAN_RUNNING` | `4` | 扫描任务正在运行，请勿重复启动扫描。 |
| `CODE_WIFI_SCAN_NOT_SUPPORTED` | `5` | 当前设备或固件不支持 WiFi 扫描。 |
| `CODE_DEVICE_NOT_CONNECTED` | `6` | BLE 设备未连接，不能执行依赖连接的操作。 |
| `CODE_PARAM_ERROR` | `7` | 参数错误，例如缺少设备、WiFi 名称或必要 UUID。 |
| `CODE_INIT_SESSION_FAILED` | `8` | Session 初始化失败，通常需要检查 `securityType`、POP 或设备状态。 |
| `CODE_RESERVED` | `9` | 预留状态码，当前业务不建议依赖。 |
| `CODE_ENV_CHECK_OK` | `10` | 环境检查通过。 |
| `CODE_LOCATION_DISABLED` | `11` | 位置服务未开启。Android 11 及以下扫描 BLE 通常需要开启位置服务。 |
| `CODE_GPS_DISABLED` | `12` | GPS 未开启。 |
| `CODE_BLE_NOT_SUPPORTED` | `13` | 当前设备不支持 BLE。 |
| `CODE_BLE_NEED_ENABLE` | `14` | 蓝牙未开启，需要用户打开蓝牙。 |
| `CODE_LOCATION_PERMISSION_REQUIRED` | `15` | 需要定位权限。 |
| `CODE_LOCATION_PERMISSION_DENIED` | `16` | 定位权限被拒绝。 |
| `CODE_LOCATION_PERMISSION_REQUIRED_2` | `17` | 需要定位权限的兼容状态码。 |
| `CODE_CONNECTING` | `18` | 正在连接 BLE 设备。 |
| `CODE_ALREADY_CONNECTED` | `19` | 已有设备连接，通常需要先断开再连接新设备。 |
| `CODE_PROVISIONING` | `20` | 正在配网，请勿重复发起配网。 |
| `CODE_BLE_PERMISSION_REQUIRED` | `21` | 需要蓝牙权限，常见于 Android 12 及以上。 |
| `CODE_BLE_PERMISSION_REQUESTED` | `22` | 蓝牙权限申请弹窗已触发，等待用户授权结果。 |

### 14.4 事件回调码

事件码需要结合 `tag` 判断含义。例如 `100` 在扫描、连接、Session、WiFi 扫描和配网中都表示“流程已开始或处理中”，但具体业务语义不同。

| 事件场景 | tag | 常量 | 数值 | 说明 |
| --- | --- | --- | ---: | --- |
| BLE 扫描 | `bleScanListenerCodeState` | `EVENT_SCAN_STARTED` | `100` | BLE 扫描已开始。 |
| BLE 扫描 | `bleScanListenerCodeState` | `EVENT_SCAN_FINISHED` | `0` | BLE 扫描已结束，可能是超时、主动停止或扫描完成。 |
| BLE 连接 | `bleConnectCodeState` | `EVENT_CONNECTING` | `100` | 正在连接 BLE 设备。 |
| BLE 连接 | `bleConnectCodeState` | `EVENT_CONNECTED` | `1` | BLE 设备已连接。 |
| BLE 连接 | `bleConnectCodeState` | `EVENT_CONNECT_FAILED` | `2` | BLE 连接失败。 |
| BLE 连接 | `bleConnectCodeState` | `EVENT_DISCONNECTED` | `3` | BLE 设备已断开。 |
| Session | `initSessionCodeState` | `EVENT_SESSION_START` | `100` | Session 初始化开始。 |
| Session | `initSessionCodeState` | `EVENT_SESSION_SUCCESS` | `1` | Session 初始化成功，可以继续扫描 WiFi。 |
| Session | `initSessionCodeState` | `EVENT_SESSION_FAILED` | `2` | Session 初始化失败。 |
| WiFi 扫描 | `wifiList` | `EVENT_WIFI_SCAN_START` | `100` | ESP 设备周边 WiFi 扫描开始。 |
| WiFi 扫描 | `wifiList` | `EVENT_WIFI_LIST` | `1` | 已获取 WiFi 列表，回调中通常包含 `wifiList`。 |
| WiFi 扫描 | `wifiList` | `EVENT_WIFI_SCAN_FAILED` | `2` | WiFi 扫描失败。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_SUCCESS` | `0` | WiFi 配网成功。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_AUTH_FAILED` | `1` | WiFi 鉴权失败，通常是密码错误。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_NETWORK_NOT_FOUND` | `2` | 未找到目标 WiFi 网络。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_STOPPED` | `3` | 配网已停止。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_RUNNING` | `100` | 配网流程进行中，通常表示配置已发送。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_APPLIED` | `101` | 配网配置已被设备应用。 |
| WiFi 配网 | `provisioningCodeState` | `EVENT_PROVISION_FAILED` | `10` | 配网失败的通用错误。 |

### 14.5 默认超时/重试配置

这些默认值会在前端没有传入对应参数时使用。实际业务可以在 `bleEnvironmentOnLoad()`、`bleConnectDevice()` 或 `bleStartProvisioning()` 中按需覆盖。

| 常量 | 默认值 | 说明 |
| --- | ---: | --- |
| `DEFAULT_SCAN_TIMEOUT_MS` | `10000` | BLE 扫描默认超时时间，单位毫秒。 |
| `DEFAULT_CONNECT_TIMEOUT_MS` | `15000` | BLE 连接默认超时时间，单位毫秒。 |
| `DEFAULT_SESSION_TIMEOUT_MS` | `10000` | Session 初始化默认超时时间，单位毫秒。 |
| `DEFAULT_PROVISION_TIMEOUT_MS` | `60000` | WiFi 配网默认超时时间，单位毫秒。 |
| `DEFAULT_RETRY_DELAY_MS` | `1200` | 连接或 Session 重试前的默认等待时间，单位毫秒。 |
| `DEFAULT_MAX_CONNECT_RETRIES` | `1` | BLE 连接失败后的默认最大重试次数。 |
| `DEFAULT_MAX_SESSION_RETRIES` | `1` | Session 初始化失败后的默认最大重试次数。 |
