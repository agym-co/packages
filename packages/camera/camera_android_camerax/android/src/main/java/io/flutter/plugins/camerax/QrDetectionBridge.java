// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camerax;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

/**
 * Decodes QR codes from analysis frames without sending them to Dart.
 *
 * <p>CameraX has no equivalent of the capture-pipeline barcode output AVFoundation offers, so
 * detection still runs on the analyzer. What changes is where. Handing every frame to Dart measured
 * 94% of the cost of detection on a Pixel 9a, with the detector itself only 6%, so decoding here and
 * sending up only the string removes nearly all of it.
 */
final class QrDetectionBridge {
  static final String METHOD_CHANNEL = "agym/camera/qr";
  static final String EVENT_CHANNEL = "agym/camera/qr_events";

  private static volatile QrDetectionBridge instance;

  /**
   * Shortest gap between two decodes.
   *
   * <p>Without this the analyzer decodes every frame it is handed, which CameraX paces at about 25
   * a second because it withholds the next frame until this one is closed. Detection does not need
   * that. Five a second is already more coverage than the streamed implementation managed, since
   * that one only looked during its duty cycle's open windows.
   */
  private static final long MIN_SCAN_INTERVAL_MS = 200;

  private volatile BarcodeScanner scanner;
  private volatile long lastScanUptimeMs;
  private MethodChannel methodChannel;
  private EventChannel eventChannel;
  private volatile EventChannel.EventSink sink;

  /**
   * The analyzer whose frames this bridge consumes.
   *
   * <p>Claimed by the first analyzer to deliver a frame after detection is enabled, which is the
   * camera that asked to scan. Every other analyzer the plugin serves keeps forwarding its frames
   * to Dart, so a camera that never asked for QR cannot be starved.
   */
  private volatile Object owner;
  private volatile boolean enabled;

  private QrDetectionBridge() {}

  /**
   * The detector, built on first use.
   *
   * <p>Built lazily because this class is attached for every camera the plugin serves, while only
   * one screen ever turns detection on. An app that never scans should not pay for a detector.
   */
  private BarcodeScanner scanner() {
    BarcodeScanner current = scanner;
    if (current == null) {
      current =
          BarcodeScanning.getClient(
              new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
      scanner = current;
    }
    return current;
  }

  /** The bridge shared by every camera the plugin creates. */
  static QrDetectionBridge getInstance() {
    if (instance == null) {
      synchronized (QrDetectionBridge.class) {
        if (instance == null) {
          instance = new QrDetectionBridge();
        }
      }
    }
    return instance;
  }

  /**
   * Whether frames should be decoded here rather than forwarded to Dart.
   *
   * <p>Requires a listener as well as the flag. The flag is process wide, so on its own a screen
   * that forgot to clear it would go on intercepting frames belonging to some unrelated image
   * stream, which would then silently receive none. Nobody listening means nobody wants them.
   */
  boolean isEnabled(@NonNull Object analyzer) {
    if (!enabled || sink == null) {
      return false;
    }

    Object current = owner;
    if (current == null) {
      owner = analyzer;
      return true;
    }
    return current == analyzer;
  }

  void attach(@NonNull BinaryMessenger messenger) {
    // The instance outlives an engine restart, so never inherit a stale flag.
    enabled = false;
    methodChannel = new MethodChannel(messenger, METHOD_CHANNEL);
    methodChannel.setMethodCallHandler(
        (call, result) -> {
          if (!"setQrDetectionEnabled".equals(call.method)) {
            result.notImplemented();
            return;
          }

          Boolean argument = call.argument("enabled");
          enabled = Boolean.TRUE.equals(argument);
          if (!enabled) {
            owner = null;
          }
          result.success(null);
        });

    eventChannel = new EventChannel(messenger, EVENT_CHANNEL);
    eventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object arguments, EventChannel.EventSink events) {
            sink = events;
          }

          @Override
          public void onCancel(Object arguments) {
            sink = null;
          }
        });
  }

  void detach() {
    if (methodChannel != null) {
      methodChannel.setMethodCallHandler(null);
      methodChannel = null;
    }
    if (eventChannel != null) {
      eventChannel.setStreamHandler(null);
      eventChannel = null;
    }
    enabled = false;
    sink = null;
    owner = null;

    BarcodeScanner current = scanner;
    scanner = null;
    if (current != null) {
      current.close();
    }
  }

  /**
   * Decodes {@code image} and closes it.
   *
   * <p>Closing is this method's job because the Dart side never sees the frame, and CameraX stops
   * delivering once an unclosed image is outstanding.
   */
  @androidx.camera.core.ExperimentalGetImage
  void analyze(@NonNull ImageProxy image, @NonNull ProxyApiRegistrar registrar) {
    long now = android.os.SystemClock.uptimeMillis();
    if (now - lastScanUptimeMs < MIN_SCAN_INTERVAL_MS) {
      // Closing is what lets CameraX hand over the next frame, so a skipped
      // frame still has to be closed rather than dropped.
      image.close();
      return;
    }
    lastScanUptimeMs = now;

    android.media.Image mediaImage = image.getImage();
    if (mediaImage == null) {
      image.close();
      return;
    }

    // CameraX withholds the next frame until this one is closed, so anything
    // thrown before the completion listener is attached would stall the
    // analyzer for good.
    try {
      InputImage input =
          InputImage.fromMediaImage(mediaImage, image.getImageInfo().getRotationDegrees());
      scanner()
          .process(input)
          .addOnSuccessListener(barcodes -> emitFirstValue(barcodes, registrar))
          .addOnCompleteListener(task -> image.close());
    } catch (RuntimeException e) {
      android.util.Log.w("QrDetectionBridge", "Could not decode a frame", e);
      image.close();
    }
  }

  private void emitFirstValue(
      @NonNull java.util.List<Barcode> barcodes, @NonNull ProxyApiRegistrar registrar) {
    for (Barcode barcode : barcodes) {
      String value = barcode.getRawValue();
      if (value == null || value.isEmpty()) {
        continue;
      }

      // Event sinks must be fed from the main thread.
      registrar.runOnMainThread(
          new ProxyApiRegistrar.FlutterMethodRunnable() {
            @Override
            public void run() {
              EventChannel.EventSink current = sink;
              if (current != null) {
                current.success(value);
              }
            }
          });
      return;
    }
  }
}
