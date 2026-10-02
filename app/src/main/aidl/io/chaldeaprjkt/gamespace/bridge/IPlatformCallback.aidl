package io.chaldeaprjkt.gamespace.bridge;

import android.os.Bundle;

oneway interface IPlatformCallback {
    void onStateChanged(String feature, in Bundle state);
}
