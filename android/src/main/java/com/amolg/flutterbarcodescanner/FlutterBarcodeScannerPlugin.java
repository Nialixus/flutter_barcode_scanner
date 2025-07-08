package com.amolg.flutterbarcodescanner;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.barcode.Barcode;

import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter;

public class FlutterBarcodeScannerPlugin implements MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler,
        FlutterPlugin,
        ActivityAware,
        io.flutter.plugin.common.PluginRegistry.ActivityResultListener {

    private static final String TAG = "FlutterBarcodeScanner";
    private static final String CHANNEL = "flutter_barcode_scanner";
    private static final int RC_BARCODE_CAPTURE = 9001;

    private static Activity activity;
    private static Result pendingResult;
    private static EventChannel.EventSink barcodeStream;
    private static boolean isContinuousScan = false;
    private static String lineColor = "";
    private static boolean isShowFlashIcon = false;

    private Map<String, Object> arguments;
    private MethodChannel channel;
    private EventChannel eventChannel;

    private Application applicationContext;
    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private Lifecycle lifecycle;
    private LifeCycleObserver observer;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        activity = binding.getActivity();

        setupPlugin(
                pluginBinding.getBinaryMessenger(),
                (Application) pluginBinding.getApplicationContext(),
                binding.getActivity()
        );

        binding.addActivityResultListener(this);
        lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding);
        observer = new LifeCycleObserver(binding.getActivity());
        lifecycle.addObserver(observer);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
        }
        if (lifecycle != null && observer != null) {
            lifecycle.removeObserver(observer);
        }
        if (applicationContext != null && observer != null) {
            applicationContext.unregisterActivityLifecycleCallbacks(observer);
        }
        if (channel != null) {
            channel.setMethodCallHandler(null);
        }
        if (eventChannel != null) {
            eventChannel.setStreamHandler(null);
        }

        activityBinding = null;
        lifecycle = null;
        observer = null;
        activity = null;
        applicationContext = null;
    }

    private void setupPlugin(BinaryMessenger messenger, Application appContext, Activity act) {
        applicationContext = appContext;

        channel = new MethodChannel(messenger, CHANNEL);
        channel.setMethodCallHandler(this);

        eventChannel = new EventChannel(messenger, "flutter_barcode_scanner_receiver");
        eventChannel.setStreamHandler(this);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        pendingResult = result;

        if (call.method.equals("scanBarcode")) {
            if (!(call.arguments instanceof Map)) {
                result.error("INVALID_ARGUMENT", "Expected argument to be a map", null);
                return;
            }

            arguments = (Map<String, Object>) call.arguments;

            lineColor = (String) arguments.get("lineColor");
            isShowFlashIcon = (boolean) arguments.get("isShowFlashIcon");
            if (lineColor == null || lineColor.isEmpty()) lineColor = "#DC143C";

            if (arguments.containsKey("scanMode")) {
                int mode = (int) arguments.get("scanMode");
                if (mode == BarcodeCaptureActivity.SCAN_MODE_ENUM.DEFAULT.ordinal()) {
                    BarcodeCaptureActivity.SCAN_MODE = BarcodeCaptureActivity.SCAN_MODE_ENUM.QR.ordinal();
                } else {
                    BarcodeCaptureActivity.SCAN_MODE = mode;
                }
            } else {
                BarcodeCaptureActivity.SCAN_MODE = BarcodeCaptureActivity.SCAN_MODE_ENUM.QR.ordinal();
            }

            isContinuousScan = (boolean) arguments.get("isContinuousScan");
            startScanner((String) arguments.get("cancelButtonText"), isContinuousScan);
        } else {
            result.notImplemented();
        }
    }

    private void startScanner(String cancelButtonText, boolean continuousScan) {
        Intent intent = new Intent(activity, BarcodeCaptureActivity.class)
                .putExtra("cancelButtonText", cancelButtonText);

        if (continuousScan) {
            activity.startActivity(intent);
        } else {
            activity.startActivityForResult(intent, RC_BARCODE_CAPTURE);
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RC_BARCODE_CAPTURE) {
            if (resultCode == CommonStatusCodes.SUCCESS && data != null) {
                try {
                    Barcode barcode = data.getParcelableExtra(BarcodeCaptureActivity.BarcodeObject);
                    pendingResult.success(barcode != null ? barcode.rawValue : "-1");
                } catch (Exception e) {
                    pendingResult.success("-1");
                }
            } else {
                pendingResult.success("-1");
            }
            pendingResult = null;
            arguments = null;
            return true;
        }
        return false;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        barcodeStream = events;
    }

    @Override
    public void onCancel(Object arguments) {
        barcodeStream = null;
    }

    public static void onBarcodeScanReceiver(final Barcode barcode) {
        if (barcode != null && barcode.displayValue != null && !barcode.displayValue.isEmpty()) {
            activity.runOnUiThread(() -> {
                if (barcodeStream != null) {
                    barcodeStream.success(barcode.rawValue);
                }
            });
        }
    }

    private static class LifeCycleObserver implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
        private final Activity targetActivity;

        LifeCycleObserver(Activity activity) {
            this.targetActivity = activity;
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {}

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            if (this.targetActivity == activity && activity.getApplicationContext() != null) {
                ((Application) activity.getApplicationContext()).unregisterActivityLifecycleCallbacks(this);
            }
        }

        // Required overrides (empty implementations)
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onCreate(@NonNull LifecycleOwner owner) {}
        @Override public void onStart(@NonNull LifecycleOwner owner) {}
        @Override public void onResume(@NonNull LifecycleOwner owner) {}
        @Override public void onPause(@NonNull LifecycleOwner owner) {}
        @Override public void onStop(@NonNull LifecycleOwner owner) {}
        @Override public void onDestroy(@NonNull LifecycleOwner owner) {}
    }
}
