package com.eversolo.upnpserver.dlna.dms.loader;

import android.content.Context;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.android.AndroidRouter;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.Router;

/**
 * Created by fuyan
 * 2025/12/26
 **/
public class AndroidUpnpServiceLoader {
    private static volatile AndroidUpnpServiceLoader instance;

    public static AndroidUpnpServiceLoader getInstance() {
        if (instance == null) {
            synchronized (AndroidUpnpServiceLoader.class) {
                if (instance == null) {
                    instance = new AndroidUpnpServiceLoader();
                }
            }
        }
        return instance;
    }

    public UpnpService createUpnpService(Context context) {
        return new UpnpServiceImpl(createConfiguration()) {

            @Override
            protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                return AndroidUpnpServiceLoader.createRouter(getConfiguration(), protocolFactory, context);
            }

            @Override
            public synchronized void shutdown() {
                // First have to remove the receiver, so Android won't complain about it leaking
                // when the main UI thread exits.
                ((AndroidRouter) getRouter()).unregisterBroadcastReceiver();

                // Now we can concurrently run the Cling shutdown code, without occupying the
                // Android main UI thread. This will complete probably after the main UI thread
                // is done.
                super.shutdown(true);
            }
        };
    }

    private static UpnpServiceConfiguration createConfiguration() {
        return new AndroidUpnpServiceConfiguration();
    }

    private static AndroidRouter createRouter(UpnpServiceConfiguration configuration, ProtocolFactory protocolFactory, Context context) {
        return new AndroidRouter(configuration, protocolFactory, context);
    }
}
