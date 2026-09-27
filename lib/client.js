window.__ModuleLoader__.load({
  id: "dsh-xiaomi-asr",
  factory: (require) => {
    const module = { exports: {} };
    const exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

    const React = require("react");
    const { useState, useEffect, useLayoutEffect, useRef } = React;
    const primitives = require("@deepseek-ai/dsh-client-ui-primitives");

    const Button = primitives.Button;
    const Tooltip = primitives.Tooltip;
    const IconMic = primitives.IconMicrophoneOutlineRegular;
    const IconClose = primitives.IconCloseOutlineRegular;
    const IconStop = primitives.IconStopFillRegular;

    // 官方 1:1 纯正样式注入
    const cssText = `
      .ddpbLW_trigger { flex: none; width: 28px; padding: 0; }
      .ddpbLW_triggerAnchor { flex: none; display: inline-flex; }
      .ddpbLW_captureRow { align-items: center; gap: 12px; width: 100%; min-width: 0; min-height: 34px; display: flex; }
      .ddpbLW_roundButton { corner-shape: round; background: var(--dsw-specific-selector); border-radius: 50%; flex: none; width: 32px; height: 32px; padding: 0; display: inline-flex; align-items: center; justify-content: center; }
      .ddpbLW_roundButton:hover:not(:disabled) { background: var(--dsw-alias-interactive-bg-hover-solid); }
      .ddpbLW_waveform { width: 0; min-width: 24px; height: 24px; color: var(--dsw-alias-label-secondary); flex: 1; display: block; }
      .ddpbLW_activityMessage { min-width: 0; color: var(--dsw-alias-label-secondary); white-space: nowrap; text-overflow: ellipsis; flex: 1; align-items: center; gap: 8px; font-size: 12px; display: flex; overflow: hidden; }
    `;

    if (typeof document !== "undefined" && !document.querySelector("style[data-plugin-css='dsh-xiaomi-asr']")) {
      const style = document.createElement("style");
      style.dataset.pluginCss = "dsh-xiaomi-asr";
      style.textContent = cssText;
      document.head.appendChild(style);
    }

    // 官方 1:1 原装波浪动画实现
    function Waveform({ label = "正在录音" }) {
      const svg = useRef(null);

      useEffect(() => {
        if (!svg.current) return;
        const bars = Array.from(svg.current.querySelectorAll("line")).reverse().map((element) => ({
          element,
          level: 0
        }));
        let frame;
        let previous = -Infinity;
        let phase = 0;

        const draw = (now) => {
          if (now - previous >= 50) {
            previous = now;
            phase += 0.25;

            // 逼真自然的语音波动
            const noise = Math.random() * 0.35;
            const wave = 0.15 + 0.5 * Math.abs(Math.sin(phase) * Math.cos(phase * 0.7)) + noise;
            let next = Math.min(1, Math.max(0.04, wave));

            for (const bar of bars) {
              const previousLevel = bar.level;
              bar.level = next;
              next = previousLevel;
              const height = 1 + Math.min(1, bar.level * 5) * 17;
              bar.element.setAttribute("y1", String(20 - height));
              bar.element.setAttribute("y2", String(20 + height));
            }
          }
          frame = requestAnimationFrame(draw);
        };

        frame = requestAnimationFrame(draw);
        return () => {
          cancelAnimationFrame(frame);
        };
      }, []);

      return React.createElement(
        "svg",
        {
          ref: svg,
          className: "ddpbLW_waveform",
          viewBox: "0 0 640 40",
          preserveAspectRatio: "none",
          role: "img",
          "aria-label": label
        },
        Array.from({ length: 80 }, (_, index) =>
          React.createElement("line", {
            key: index,
            x1: index * 8 + 4,
            x2: index * 8 + 4,
            y1: "19",
            y2: "21",
            stroke: "currentColor",
            strokeWidth: "3",
            strokeLinecap: "round",
            opacity: 0.25 + index / 120
          })
        )
      );
    }

    // 官方 VoiceInput 标准形态
    function XiaomiVoiceInput({ sessionId, inputActions, locked, onActiveChange }) {
      const [phase, setPhase] = useState("idle"); // idle | recording | transcribing
      const spanRef = useRef(null);
      const pollingRef = useRef(null);
      const lastTextRef = useRef("");

      const expanded = phase !== "idle";

      // 关键：通知外层输入框让出整行宽度
      useLayoutEffect(() => {
        if (typeof onActiveChange === "function") {
          onActiveChange(expanded);
        }
        return () => {
          if (typeof onActiveChange === "function") {
            onActiveChange(false);
          }
        };
      }, [expanded, onActiveChange]);

      const stopPolling = () => {
        if (pollingRef.current) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
        }
      };

      const cancel = async () => {
        stopPolling();
        setPhase("idle");
        try {
          await fetch("/api/xiaomi-asr/cancel", { method: "GET" });
        } catch (_) {}
      };

      const finish = async () => {
        setPhase("transcribing");
        try {
          await fetch("/api/xiaomi-asr/stop", { method: "GET" });
        } catch (_) {
          setPhase("idle");
          stopPolling();
        }
      };

      const start = async () => {
        if (locked || phase === "recording") return;
        try {
          spanRef.current = inputActions?.captureInsertion?.() || null;
          lastTextRef.current = "";
          setPhase("recording");

          await fetch("/api/xiaomi-asr/start", { method: "GET" });

          stopPolling();
          pollingRef.current = setInterval(async () => {
            try {
              const res = await fetch("/api/xiaomi-asr/status");
              const data = await res.json();
              if (!data) return;

              if (data.partial && data.partial !== lastTextRef.current) {
                lastTextRef.current = data.partial;
                if (inputActions && typeof inputActions.insertText === "function") {
                  inputActions.insertText(data.partial, spanRef.current);
                }
              }

              if (data.state === "idle" || data.final) {
                if (data.final) {
                  lastTextRef.current = data.final;
                  if (inputActions && typeof inputActions.insertText === "function") {
                    inputActions.insertText(data.final, spanRef.current);
                  }
                }
                setPhase("idle");
                stopPolling();
              } else if (data.state === "error") {
                console.warn("[Xiaomi ASR Error]", data.error);
                setPhase("idle");
                stopPolling();
              }
            } catch (err) {
              console.error("[Xiaomi ASR Poll Error]", err);
            }
          }, 180);
        } catch (e) {
          console.error("[Xiaomi ASR Start Error]", e);
          setPhase("idle");
          stopPolling();
        }
      };

      // 切换会话时自动复位
      useEffect(() => {
        cancel();
      }, [sessionId]);

      // 未展开时：显示在发送按钮旁边的麦克风 Trigger
      if (!expanded) {
        return React.createElement(
          Tooltip,
          {
            label: "语音输入",
            side: "top",
            portal: true
          },
          React.createElement(
            "span",
            { className: "ddpbLW_triggerAnchor" },
            React.createElement(
              Button,
              {
                className: "ddpbLW_trigger",
                size: "sm",
                disabled: locked,
                "aria-label": "语音输入",
                onMouseDown: (e) => e.preventDefault(),
                onClick: () => start()
              },
              React.createElement(IconMic, { size: 18 })
            )
          )
        );
      }

      // 展开录音时：独占整行的 captureRow（取消按钮 + 官方 Waveform + 停止按钮）
      return React.createElement(
        "div",
        {
          className: "ddpbLW_captureRow",
          "data-voice-activity": phase
        },
        // 取消按钮
        React.createElement(
          Button,
          {
            type: "button",
            className: "ddpbLW_roundButton",
            size: "sm",
            "aria-label": "取消",
            onClick: cancel
          },
          React.createElement(IconClose, { size: 14 })
        ),
        // 官方原生波浪条
        React.createElement(Waveform, { label: "正在录音" }),
        // 停止完成按钮
        React.createElement(
          Button,
          {
            type: "button",
            className: "ddpbLW_roundButton",
            size: "sm",
            "aria-label": "停止",
            onClick: finish
          },
          React.createElement(IconStop, { size: 14 })
        )
      );
    }

    exports.inject = ["slots"];
    exports.apply = function (ctx) {
      if (!ctx || !ctx.slots) return;

      // 严格仅注册在官方唯一的 conversation.input.activity 插槽位！
      ctx.slots.inject("conversation.input.activity", () => {
        return ctx.slots.register(
          {
            name: "conversation.input.activity",
            key: "xiaomi-asr-voice-input",
            priority: 20
          },
          XiaomiVoiceInput
        );
      });
    };

    return module.exports;
  }
});
