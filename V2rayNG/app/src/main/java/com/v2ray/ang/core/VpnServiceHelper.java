package com.v2ray.ang.core;

import android.net.VpnService;

public class VpnServiceHelper {
    public static VpnService.Builder newBuilder() {
        return new VpnService.Builder();
    }
}
