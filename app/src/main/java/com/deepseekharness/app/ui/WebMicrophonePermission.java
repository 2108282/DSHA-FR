package com.deepseekharness.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.deepseekharness.app.util.ToastHelper;
import com.deepseekharness.app.util.WebMicrophonePolicy;

import java.util.function.Supplier;

/** Activity-owned Android permission prompt shared by the DSH WebView entry points. */
final class WebMicrophonePermission implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final Supplier<WebView> activeView;
    private final Supplier<String> trustedUrl;
    private final ActivityResultLauncher<String> launcher;
    private PermissionRequest pending;
    private WebView pendingView;
    private boolean requesting;

    // Construct before onCreate: ActivityResult registration must precede STARTED.
    WebMicrophonePermission(AppCompatActivity activity, Supplier<WebView> activeView, Supplier<String> trustedUrl) {
        this.activity = activity;
        this.activeView = activeView;
        this.trustedUrl = trustedUrl;
        launcher = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            requesting = false;
            PermissionRequest request = pending;
            WebView view = pendingView;
            pending = null;
            pendingView = null;
            // A cancelled request never transfers its late grant to a subsequent page/request.
            if (request == null) return;
            if (granted && eligible(view, request)) {
                grant(request);
            } else {
                request.deny();
                if (!granted && visible(view)) {
                    ToastHelper.show(activity, "麦克风权限未开启，请在系统应用权限中允许 DSH 使用麦克风");
                }
            }
        });
        activity.getLifecycle().addObserver(this);
    }

    void request(WebView view, PermissionRequest request) {
        boolean audio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
        }
        if (!audio || requesting || pending != null || !eligible(view, request)) {
            request.deny();
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            grant(request);
            return;
        }
        pending = request;
        pendingView = view;
        requesting = true;
        try {
            launcher.launch(Manifest.permission.RECORD_AUDIO);
        } catch (RuntimeException error) {
            requesting = false;
            cancel();
            ToastHelper.show(activity, "无法打开麦克风授权，请在系统应用权限中检查");
        }
    }

    void cancelled(PermissionRequest request) {
        if (pending == request) {
            // WebView already cancelled it; do not call grant/deny on the expired request.
            pending = null;
            pendingView = null;
        }
    }

    void navigating(WebView view) {
        if (pendingView == view) cancel();
    }

    void cancel() {
        PermissionRequest request = pending;
        pending = null;
        pendingView = null;
        if (request != null) request.deny();
    }

    private boolean visible(WebView view) {
        return !activity.isFinishing() && !activity.isDestroyed()
                && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)
                && view != null && activeView.get() == view && view.isShown();
    }

    private boolean eligible(WebView view, PermissionRequest request) {
        return visible(view) && WebMicrophonePolicy.allows(trustedUrl.get(), view.getUrl(), request.getOrigin().toString());
    }

    private void grant(PermissionRequest request) {
        // Never grant all requested resources: camera/future WebView capabilities stay denied.
        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
    }

    // Permission dialogs pause the Activity: keep the request alive through onPause.
    @Override public void onStop(LifecycleOwner owner) { cancel(); }
    @Override public void onDestroy(LifecycleOwner owner) { cancel(); }
}
