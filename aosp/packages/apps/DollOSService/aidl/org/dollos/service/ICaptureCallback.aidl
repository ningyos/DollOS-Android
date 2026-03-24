package org.dollos.service;

interface ICaptureCallback {
    void onCaptureResult(int displayId, in byte[] pngBytes);
}
