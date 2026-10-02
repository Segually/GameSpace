// Binder published by the SystemUI hook (see xposed/systemui). Mirrors the
// AxPlatformService API the ROM build used for the gamebar tiles.
package io.chaldeaprjkt.gamespace.bridge;

import android.os.Bundle;
import io.chaldeaprjkt.gamespace.bridge.IPlatformCallback;

interface IPlatformBridge {
    int getVersion();
    String[] getSupportedFeatures();
    Bundle getState(String feature);
    void toggle(String feature);
    void setListening(boolean listening);
    void registerCallback(IPlatformCallback callback);
    void unregisterCallback(IPlatformCallback callback);
    void takeScreenshot();
    void setGestureLock(boolean locked);
}
