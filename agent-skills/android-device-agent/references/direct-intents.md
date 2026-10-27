# Android 系统底层直达指令清单（Direct First 备查表）

只要能通过以下指令直接触发的，不要在 GUI 上逐层点按：

## 1. 闹钟与计时器
```bash
# 设置闹钟 (次日 07:30，标签“起床”)
am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 7 \
  --ei android.intent.extra.alarm.MINUTES 30 \
  --es android.intent.extra.alarm.MESSAGE "起床" \
  --ez android.intent.extra.alarm.SKIP_UI true

# 设置倒计时器 (300 秒)
am start -a android.intent.action.SET_TIMER \
  --ei android.intent.extra.alarm.LENGTH 300 \
  --es android.intent.extra.alarm.MESSAGE "煮面" \
  --ez android.intent.extra.alarm.SKIP_UI true
```

## 2. 媒体与音频控制
```bash
# 播放 / 暂停切歌
cmd media_session dispatch play-pause
cmd media_session dispatch next
cmd media_session dispatch previous

# 调节音量 (stream: 3=media, 2=ring, 4=alarm)
cmd media_session volume --stream 3 --set 10
cmd media_session volume --stream 3 --adj raise
cmd media_session volume --stream 3 --adj lower
```

## 3. 系统页面直达
```bash
# 打开系统设置首页
am start -a android.settings.SETTINGS

# 打开 Wi-Fi 设置
am start -a android.settings.WIFI_SETTINGS

# 打开应用管理列表
am start -a android.settings.APPLICATION_SETTINGS

# 打开无障碍设置
am start -a android.settings.ACCESSIBILITY_SETTINGS

# 打开通知设置
am start -a android.settings.NOTIFICATION_SETTINGS
```

## 4. 应用生命周期与管理（Root 特权）
```bash
# 强行停止应用
am force-stop <package_name>

# 冻结/禁用应用
pm disable-user <package_name>

# 解冻/启用应用
pm enable <package_name>

# 清除应用数据 (高风险，需先向用户确认)
pm clear <package_name>
```

## 5. 系统 Settings 键值读写
```bash
# 读取系统设置值 (system / secure / global)
settings get system screen_brightness
settings get global airplane_mode_on

# 写入系统设置值
settings put system screen_brightness 150
```
