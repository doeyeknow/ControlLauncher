package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * QR scanner for profile import. Camera2 frames are decoded with ZXing
 * (the Y plane of YUV_420_888 is the luminance channel QR decoding
 * needs). Returns the decoded text in the "qr_text" result extra.
 */
public class ScanActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would restart the camera session; the new theme
        // applies the next time this screen opens.
        return false;
    }

    public static final String RESULT_TEXT = "qr_text";
    private static final int REQ_CAMERA = 7101;

    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private volatile boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        setContentView(root);
        TextView hint = Ui.text(this, "Point the camera at the profile QR code.",
                16, Ui.TEXT);
        hint.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 12));
        root.addView(hint);
        preview = new TextureView(this);
        root.addView(preview, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Camera permission is needed to scan.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bgThread = new HandlerThread("scan-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(
                        android.graphics.SurfaceTexture st, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(
                        android.graphics.SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(
                        android.graphics.SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(
                        android.graphics.SurfaceTexture st) { }
            });
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        super.onPause();
    }

    private void openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = null;
            for (String candidate : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(candidate)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = candidate;
                    break;
                }
            }
            if (id == null) id = cm.getCameraIdList()[0];

            reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onFrame, bgHandler);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cd) {
                    camera = cd;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice cd) { cd.close(); }
                @Override public void onError(CameraDevice cd, int error) { cd.close(); }
            }, bgHandler);
        } catch (Exception e) {
            Toast.makeText(this, "Camera unavailable: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startSession() {
        try {
            Surface previewSurface = new Surface(preview.getSurfaceTexture());
            CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(previewSurface);
            rb.addTarget(reader.getSurface());
            camera.createCaptureSession(
                    java.util.Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                rb.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                s.setRepeatingRequest(rb.build(), null, bgHandler);
                            } catch (Exception ignored) {
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { }
                    }, bgHandler);
        } catch (Exception ignored) {
        }
    }

    private void onFrame(ImageReader ir) {
        Image image = ir.acquireLatestImage();
        if (image == null) return;
        try {
            if (done) return;
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer buf = yPlane.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int rowStride = yPlane.getRowStride();
            byte[] data = new byte[w * h];
            byte[] row = new byte[rowStride];
            for (int y = 0; y < h; y++) {
                buf.position(y * rowStride);
                buf.get(row, 0, Math.min(rowStride, buf.remaining()));
                System.arraycopy(row, 0, data, y * w, w);
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            Result result = new QRCodeReader().decode(bmp,
                    Collections.singletonMap(
                            com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE));
            String text = result.getText();
            if (text != null && !text.isEmpty()) {
                done = true;
                Intent out = new Intent();
                out.putExtra(RESULT_TEXT, text);
                runOnUiThread(() -> {
                    setResult(RESULT_OK, out);
                    finish();
                });
            }
        } catch (Exception ignored) {
            // Not a QR frame — keep looking.
        } finally {
            image.close();
        }
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
    }
}
