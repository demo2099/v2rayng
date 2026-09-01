package com.v2ray.ang.core;

import android.net.VpnService;
import java.lang.reflect.Constructor;

public class VpnServiceHelper {
    public static VpnService.Builder newBuilder() throws Exception {
        Constructor<VpnService.Builder> ctor = VpnService.Builder.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }
}
