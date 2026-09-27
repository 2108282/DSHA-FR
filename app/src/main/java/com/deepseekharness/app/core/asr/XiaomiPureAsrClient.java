package com.deepseekharness.app.core.asr;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * 小米系统底层纯 ASR (com.xiaomi.mibrain.speech.asr.AsrService) 客户端。
 * 纯语音转文字通道，不包含任何大模型决策或控屏抢跑动作。
 */
public class XiaomiPureAsrClient {

    private static final String TAG = "XiaomiPureAsr";
    private static final String MI_SPEECH_PACKAGE = "com.xiaomi.mibrain.speech";
    private static final String MI_ASR_SERVICE = "com.xiaomi.mibrain.speech.asr.AsrService";

    public interface AsrCallback {
        /** 麦克风已就绪，可以开始说话 */
        void onReady();
        /** 检测到声音开始输入 */
        void onBeginning();
        /** 实时流式识别中间文本（随着说话不断刷新） */
        void onPartialResult(String partialText);
        /** 说话结束，输出最终包含整句语义校准和标点的文本 */
        void onFinalResult(String finalText);
        /** 发生异常或超时 */
        void onError(int errorCode, String errorMessage);
    }

    private final Context context;
    private final Handler mainHandler;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    public XiaomiPureAsrClient(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized boolean isListening() {
        return isListening;
    }

    /**
     * 启动纯净 ASR 录音识别
     * 注意：必须在 Android 主线程（Main Looper）中调度调用
     */
    public synchronized void startListening(final AsrCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> startListening(callback));
            return;
        }

        stopListening(); // 清理上一次可能残留的会话

        try {
            ComponentName component = new ComponentName(MI_SPEECH_PACKAGE, MI_ASR_SERVICE);
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, component);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            // 自由日常对话识别模式
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            // 启用实时流式中间文本回调 (onPartialResults)
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // 仅返回最优匹配
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    Log.i(TAG, "ASR Ready for speech");
                    if (callback != null) callback.onReady();
                }

                @Override
                public void onBeginningOfSpeech() {
                    Log.i(TAG, "ASR Beginning of speech");
                    if (callback != null) callback.onBeginning();
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    // 可用于驱动音量波形动画
                }

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    Log.i(TAG, "ASR End of speech detected by VAD");
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    String desc = resolveErrorText(error);
                    Log.w(TAG, "ASR Error: " + error + " (" + desc + ")");
                    if (callback != null) callback.onError(error, desc);
                    release();
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    String finalText = extractText(results);
                    Log.i(TAG, "ASR Final text: [" + finalText + "]");
                    if (callback != null) {
                        callback.onFinalResult(finalText);
                    }
                    release();
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String partial = extractText(partialResults);
                    if (!partial.isEmpty()) {
                        Log.d(TAG, "ASR Partial text: [" + partial + "]");
                        if (callback != null) callback.onPartialResult(partial);
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });

            speechRecognizer.startListening(intent);
            Log.i(TAG, "Bound and started Xiaomi AsrService successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Xiaomi ASR", t);
            if (callback != null) {
                callback.onError(-1, "初始化或绑定服务异常: " + t.getMessage());
            }
            release();
        }
    }

    /** 停止录音并等待最终识别结果 */
    public synchronized void stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stopListening);
            return;
        }
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.stopListening();
            } catch (Throwable ignored) {}
        }
    }

    /** 立即取消当前录音识别 */
    public synchronized void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::cancel);
            return;
        }
        release();
    }

    private void release() {
        isListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Throwable ignored) {}
            speechRecognizer = null;
        }
    }

    private String extractText(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            return list.get(0).trim();
        }
        return "";
    }

    private String resolveErrorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频录制错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "录音或网络权限不足";
            case SpeechRecognizer.ERROR_NETWORK: return "网络连接异常";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "云端识别超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "未识别到有效语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别引擎忙碌";
            case SpeechRecognizer.ERROR_SERVER: return "小米云端识别服务异常";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到说话声";
            default: return "未知错误(" + code + ")";
        }
    }
}
