package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ToastHelper;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 简易终端 Fragment：
 * 1. 原生 Root 环境流式命令行交互；
 * 2. 线程安全读写与命令队列缓冲（断线自动拉起并补发当前命令，绝不丢指令）；
 * 3. 严格的线程生命周期管理，彻底杜绝孤儿读取线程竞争；
 * 4. 软键盘与按钮动作去重防抖，默认工作目录对齐 /root。
 */
public class TerminalFragment extends Fragment {

    private static final String DEFAULT_WELCOME = "Ubuntu 24.04 · 回车执行 · 中止 · exit 退出\n";
    private static final Object SHELL_LOCK = new Object();

    private HarnessController controller;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;

    private static volatile Process shell;
    private static volatile boolean running;
    private static volatile Thread readerThread;
    private static volatile boolean shellStarting;
    private static final StringBuilder buffer = new StringBuilder();
    private static volatile TextView boundOutput;
    private static final ConcurrentLinkedQueue<String> pendingCommands = new ConcurrentLinkedQueue<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        controller = new HarnessController(requireContext());
        inputEdit = view.findViewById(R.id.term_input);
        outputText = view.findViewById(R.id.term_output);
        scrollView = view.findViewById(R.id.term_scroll);
        boundOutput = outputText;

        TextView ctrlcBtn = view.findViewById(R.id.term_ctrlc);
        if (ctrlcBtn != null) {
            ctrlcBtn.setOnClickListener(v -> sendInterrupt());
        }

        TextView clearBtn = view.findViewById(R.id.term_clear);
        if (clearBtn != null) {
            clearBtn.setOnClickListener(v -> clearOutput());
        }

        View sendBtn = view.findViewById(R.id.term_send);
        if (sendBtn != null) {
            sendBtn.setOnClickListener(v -> sendCommand());
        }

        View ptyBtn = view.findViewById(R.id.term_pty);
        if (ptyBtn != null) {
            ptyBtn.setOnClickListener(v -> switchToPty());
        }

        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || isEnter) {
                sendCommand();
                return true;
            }
            return false;
        });

        synchronized (SHELL_LOCK) {
            String show = buffer.length() == 0 ? "" : SensitiveData.redact(buffer.toString());
            outputText.setText(show.isEmpty() ? DEFAULT_WELCOME : show);
        }
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        startShell();
    }

    private void switchToPty() {
        requireContext().getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .edit().putBoolean("term_pty", true).apply();
        try {
            int containerId = ((ViewGroup) requireView().getParent()).getId();
            getParentFragmentManager().beginTransaction()
                    .replace(containerId, new PtyTerminalFragment())
                    .commit();
        } catch (Throwable e) {
            ToastHelper.makeText(requireContext(), "请退出终端页再进来", 0).show();
        }
    }

    private void clearOutput() {
        synchronized (SHELL_LOCK) {
            buffer.setLength(0);
        }
        if (outputText != null) {
            outputText.setText(DEFAULT_WELCOME);
        }
        Process p = shell;
        if (p == null || !Compat.isAlive(p)) {
            startShell();
        }
    }

    private void sendInterrupt() {
        Process p = shell;
        if (p != null && Compat.isAlive(p)) {
            try {
                OutputStream os = p.getOutputStream();
                os.write(3); // ETX (Ctrl+C)
                os.write(10); // \n
                os.flush();
                appendLine("^C");
            } catch (IOException ignored) {
            }
        }
    }

    private void startShell() {
        synchronized (SHELL_LOCK) {
            Process p = shell;
            Thread t = readerThread;
            if (p != null && Compat.isAlive(p) && t != null && t.isAlive()) {
                drainPendingCommands();
                return;
            }
            if (shellStarting) {
                return;
            }
            shellStarting = true;

            Thread worker = new Thread(() -> {
                try {
                    if (!controller.proot().isEnvironmentReady()) {
                        mainHandler.post(() -> appendLine("未检测到 /data/adb/dsha 原生模块或未授予 Root 权限，请在授权管理器中允许"));
                        return;
                    }
                } catch (Throwable ignored) {
                }

                try {
                    Process proc = controller.proot().execRootfsInteractive();
                    synchronized (SHELL_LOCK) {
                        shell = proc;
                        running = true;
                    }

                    // 启动后默认进入 /root 目录
                    try {
                        OutputStream os = proc.getOutputStream();
                        os.write("cd /root 2>/dev/null || true\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Throwable ignored) {
                    }

                    drainPendingCommands();

                    InputStreamReader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
                    char[] cbuf = new char[4096];
                    int n;
                    while (running && (n = reader.read(cbuf)) != -1) {
                        String chunk = stripAnsi(new String(cbuf, 0, n));
                        mainHandler.post(() -> appendRaw(chunk));
                    }
                    mainHandler.post(() -> appendLine("\n[会话已退出，输入命令可自动重启]"));
                } catch (Exception e) {
                    mainHandler.post(() -> appendLine("终端启动失败：" + SensitiveData.redact(String.valueOf(e))));
                } finally {
                    synchronized (SHELL_LOCK) {
                        running = false;
                        shell = null;
                        readerThread = null;
                        shellStarting = false;
                    }
                }
            }, "term-read");

            readerThread = worker;
            worker.start();
        }
    }

    private void drainPendingCommands() {
        Process p = shell;
        if (p == null || !Compat.isAlive(p)) {
            return;
        }
        String pending;
        while ((pending = pendingCommands.poll()) != null) {
            final String cmd = pending;
            try {
                OutputStream os = p.getOutputStream();
                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                pendingCommands.add(cmd);
                mainHandler.post(() -> appendLine("写入待发命令失败，正在重试…"));
                break;
            }
        }
    }

    private void sendCommand() {
        if (inputEdit == null) return;
        String raw = inputEdit.getText().toString();
        String cmd = raw.trim();
        if (cmd.isEmpty()) {
            return;
        }
        inputEdit.setText("");
        appendLine("$ " + cmd);

        Process p = shell;
        if (p == null || !Compat.isAlive(p)) {
            pendingCommands.add(cmd);
            appendLine("会话未连接，正在启动并执行…");
            startShell();
            return;
        }

        try {
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            appendLine("发送失败（流已断开），正在自动重连执行…");
            pendingCommands.add(cmd);
            shutdownShell();
            startShell();
        }
    }

    private void appendLine(String s) {
        appendRaw(s + "\n");
    }

    private void appendRaw(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        s = SensitiveData.redact(s);
        synchronized (SHELL_LOCK) {
            buffer.append(s);
            if (buffer.length() > 300000) {
                buffer.delete(0, buffer.length() - 100000);
            }
        }
        TextView out = boundOutput;
        if (out == null) {
            return;
        }
        String show;
        synchronized (SHELL_LOCK) {
            show = buffer.length() > 100000
                    ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                    : buffer.toString();
        }
        out.setText(show);
        ScrollView sv = scrollView;
        if (sv != null) {
            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "")
                .replaceAll("\\x1B\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1B[()][0-9A-B]", "");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        boundOutput = null;
    }

    public static void shutdownShell() {
        synchronized (SHELL_LOCK) {
            Process p = shell;
            if (p != null) {
                try {
                    p.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
                    p.getOutputStream().flush();
                } catch (Throwable ignored) {
                }
                try {
                    Compat.destroy(p);
                } catch (Throwable ignored) {
                }
            }
            running = false;
            shell = null;
            readerThread = null;
            shellStarting = false;
        }
    }

    public static void inject(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String safeText = SensitiveData.redact(text);
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            synchronized (SHELL_LOCK) {
                buffer.append(safeText);
                if (!safeText.endsWith("\n")) {
                    buffer.append('\n');
                }
                if (buffer.length() > 300000) {
                    buffer.delete(0, buffer.length() - 100000);
                }
                TextView out = boundOutput;
                if (out != null) {
                    String show = buffer.length() > 100000
                            ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                            : buffer.toString();
                    out.setText(show);
                    for (ViewParent p = out.getParent(); p != null; p = p.getParent()) {
                        if (p instanceof ScrollView) {
                            final ScrollView sv = (ScrollView) p;
                            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
                            break;
                        }
                    }
                }
            }
        });
    }

    static {
        running = false;
        shellStarting = false;
    }
}
