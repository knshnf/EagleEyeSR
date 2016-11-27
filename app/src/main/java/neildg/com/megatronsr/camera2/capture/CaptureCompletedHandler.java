package neildg.com.megatronsr.camera2.capture;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Log;
import android.widget.Toast;

import neildg.com.megatronsr.CameraActivity;
import neildg.com.megatronsr.platformtools.utils.notifications.NotificationCenter;
import neildg.com.megatronsr.platformtools.utils.notifications.Notifications;

/**
 * Handles cleanup of capture
 * Created by NeilDG on 11/19/2016.
 */

public class CaptureCompletedHandler extends CameraCaptureSession.CaptureCallback {
    private final static String TAG = "CaptureCompletedHandler";

    @Override
    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
        super.onCaptureCompleted(session, request, result);
        Log.d(TAG, "Capture completed!");
        NotificationCenter.getInstance().postNotification(Notifications.ON_CAPTURE_COMPLETED);
    }
}