// Binder published by the system_server hook (see xposed/system). Every call
// is checked against the GameSpace uid on the server side.
package io.chaldeaprjkt.gamespace.bridge;

import io.chaldeaprjkt.gamespace.bridge.IFpsListener;

interface ISystemBridge {
    int getVersion();

    // table: "system" | "secure" | "global" | "lineage_system". value == null deletes.
    boolean putSetting(String table, String key, String value);

    void setGameMode(String packageName, int mode);
    int[] getAvailableGameModes(String packageName);
    // config == null clears the intervention for the package
    void setGameIntervention(String packageName, String config);

    void registerFpsListener(IFpsListener listener);
    void unregisterFpsListener(IFpsListener listener);

    // { brightness, minimum, maximum } of the default display, linear space
    float[] getBrightnessInfo();
    void setBrightness(float linear);

    void setGestureLock(boolean locked);
    void trimAppCache(String packageName);
    boolean launchFreeform(String packageName);
}
