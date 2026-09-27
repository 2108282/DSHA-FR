# dsh-miui-asr

> **Xiaomi MIUI / HyperOS 原生纯净 ASR 语音输入插件**  
> 专为 DeepSeek Harness (DSH) 打造，脱离前台小爱同学与离线大模型，直通小米系统底层 ASR 引擎。

---

## ✨ 核心特性

1. **零本地算力开销**：
   - 彻底告别 1~2GB 的 Sherpa-ONNX / SenseVoice 本地模型下载与高发热 CPU 推理；
   - 直接调用小米手机自带的 `/product/data-app/MIUIXiaoAiSpeechEngine` 原生纯净识别组件。
2. **秒级流式出字与语义纠错**：
   - 边说话边流式上屏中间字；
   - 说话结束自动由小米云端识别集群完成整句标点与语义校准。
3. **1:1 原装视觉美学**：
   - 对齐 DSH 官方 `client-ui-voice-input` 的原装交互形态；
   - 录音时自适应展开独占整行的毛玻璃动态胶囊；
   - 内置 80 根 SVG 矢量圆角波浪音柱（Waveform），细腻自然起伏；
   - 带有圆形取消（✕）与停止（■）控制按钮。

---

## 🛠️ 插件架构

```text
DSH Web 界面 (对话框输入框)
  │ 点击麦克风按钮 (Waveform 实时波浪)
  ▼
dsh-miui-asr 插件服务 (Cordis /api/xiaomi-asr/*)
  │ 本地 HTTP 代理转发 (127.0.0.1:3095)
  ▼
DSHA Android 宿主桥 (HttpShellService / XiaomiPureAsrClient)
  │ AIDL 跨进程直连 (SpeechRecognizer)
  ▼
小米系统底座 (com.xiaomi.mibrain.speech.asr.AsrService)
```

---

## 📄 开源许可

[MIT License](LICENSE)
