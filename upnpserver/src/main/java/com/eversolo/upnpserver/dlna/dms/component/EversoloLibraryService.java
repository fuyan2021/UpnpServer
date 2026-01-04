package com.eversolo.upnpserver.dlna.dms.component;

import static com.eversolo.upnpserver.dlna.dms.Constant.ACTION_REFRESH_DATA;
import static com.eversolo.upnpserver.dlna.dms.Constant.ACTION_START_SERVER;
import static com.eversolo.upnpserver.dlna.dms.Constant.ACTION_STOP_SERVER;
import static com.eversolo.upnpserver.dlna.dms.Constant.CLING_SETTING_NAME;
import static com.eversolo.upnpserver.dlna.dms.Constant.LOGTAG;
import static com.eversolo.upnpserver.dlna.dms.Constant.count;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.eversolo.upnpserver.dlna.application.BaseApplication;
import com.eversolo.upnpserver.dlna.dms.Constant;
import com.eversolo.upnpserver.dlna.dms.ContentNode;
import com.eversolo.upnpserver.dlna.dms.ContentTree;
import com.eversolo.upnpserver.dlna.dms.bean.AlbumInfo;
import com.eversolo.upnpserver.dlna.dms.bean.ArtistInfo;
import com.eversolo.upnpserver.dlna.dms.bean.AudioInfo;
import com.eversolo.upnpserver.dlna.dms.bean.ComposerInfo;
import com.eversolo.upnpserver.dlna.dms.bean.GenreInfo;
import com.eversolo.upnpserver.dlna.dms.container.AlbumContainer;
import com.eversolo.upnpserver.dlna.dms.container.ArtistContainer;
import com.eversolo.upnpserver.dlna.dms.container.ComposerContainer;
import com.eversolo.upnpserver.dlna.dms.container.GenreContainer;
import com.eversolo.upnpserver.dlna.dms.container.SingleMusicContainer;
import com.eversolo.upnpserver.dlna.dms.loader.AndroidUpnpServiceLoader;
import com.eversolo.upnpserver.dlna.dms.loader.ContainerLoader;
import com.eversolo.upnpserver.dlna.util.ApiClient;
import com.eversolo.upnpserver.dlna.util.ThreadUtils;
import com.eversolo.upnpserver.dlna.util.UpnpUtil;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.model.container.Container;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by fuyan
 * 2025/12/18
 * 音乐库DLNA服务 - 后台Service实现
 **/
public class EversoloLibraryService extends Service {

    // --------------------------
    // 成员变量
    // --------------------------
    private ConnectivityManager connectivityManager;

    // UPnP服务相关变量
    private AndroidUpnpService upnpService;
    protected UpnpService iUpnpService;
    private MediaServer mediaServer;
    private static volatile boolean serverPrepared = false;
    private volatile boolean isUpdating = false; // 防止并发更新
    
    // 服务状态标志
    private volatile boolean isServiceBound = false; // 添加服务绑定状态标志
    private volatile boolean isProcessing = false; // 添加防抖机制标志

    // 媒体信息列表
    private List<ArtistInfo> artistInfoList = new CopyOnWriteArrayList<>();
    private List<ArtistInfo> albumArtistInfoList = new CopyOnWriteArrayList<>();
    private List<AlbumInfo> albumInfoList = new CopyOnWriteArrayList<>();
    private List<ComposerInfo> composerInfoList = new CopyOnWriteArrayList<>();
    private List<GenreInfo> genreInfoList = new CopyOnWriteArrayList<>();

    // 媒体容器对象
    // 单曲容器，用于管理从getSingleMusics接口获取的音乐列表
    private SingleMusicContainer singleMusicContainer;
    // 艺术家容器，用于管理从getArtists接口获取的艺术家列表
    private ArtistContainer artistContainer;
    // 专辑容器，用于管理从getAlbums接口获取的专辑列表
    private AlbumContainer albumContainer;
    // 专辑艺术家容器，用于管理专辑艺术家列表
    private ArtistContainer albumArtistContainer;
    // 作曲家容器，用于管理从getComposerList接口获取的作曲家列表
    private ComposerContainer composerContainer;
    // 流派容器，用于管理从getSingleFilterList接口获取的流派列表
    private GenreContainer genreContainer;

    // 用于处理异步任务的Handler
    private final Handler handler = new Handler(Looper.getMainLooper());

    // --------------------------
    // 内部类
    // --------------------------

    // Binder用于客户端与Service通信
    private final IBinder binder = new UBinder();

    private class UBinder extends android.os.Binder implements AndroidUpnpService {

        public UpnpService get() {
            return iUpnpService;
        }

        public UpnpServiceConfiguration getConfiguration() {
            return iUpnpService.getConfiguration();
        }

        public void shutDown() {
            if (iUpnpService != null) {
                iUpnpService.shutdown();
            }
        }

        public Registry getRegistry() {
            return iUpnpService.getRegistry();
        }

        public ControlPoint getControlPoint() {
            return iUpnpService.getControlPoint();
        }
    }

    // BroadcastReceiver用于接收广播意图
    private final BroadcastReceiver libraryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            Log.d(LOGTAG, "Received broadcast in service: " + action);

            handler.removeCallbacksAndMessages(null);
            switch (action) {
                case ACTION_START_SERVER:
                    handler.post(() -> initStartServer());
                    break;

                case ACTION_STOP_SERVER:
                    handler.post(() -> stopServer());
                    break;

                case ACTION_REFRESH_DATA:
                    handler.post(() -> updateMediaServer());
                    break;
            }
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            // 网络已连接并可用
            initStartServer();
        }

        @Override
        public void onLost(Network network) {
            // 网络已断开
            stopServer();
        }
    };

    // --------------------------
    // 生命周期方法
    // --------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        init();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // 如果服务被杀死，系统会尝试重启服务
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 如果不需要保持服务运行，可以返回false
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOGTAG, "onDestroy: stopServer");
        // 停止服务器并清理资源
        stopServer();
        try {
            unregisterReceiver(libraryReceiver);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error unregistering receiver", e);
        }
    }

    // --------------------------
    // 主要业务方法
    // --------------------------

    /**
     * 服务的启停需要一个缓冲时间，约1-2s，开关时最好loading
     */
    public void startServer() {
        // 检查是否正在处理中或服务已绑定
        if (isProcessing || isServiceBound) {
            return;
        }

        isProcessing = true;
        ThreadUtils.prepare();
        try {
            // 获取并设置IP地址
            try {
                String ipAddress = UpnpUtil.getIP();
                if (ipAddress != null && !ipAddress.isEmpty()) {
                    BaseApplication.setHostAddress(ipAddress);
                } else {
                    Log.w(LOGTAG, "Failed to get IP address");
                }
            } catch (SocketException e) {
                Log.e(LOGTAG, "Error getting IP address", e);
            }

            Context applicationContext = getApplicationContext();
            if (applicationContext != null) {
                createUpnpService();
            }
        } catch (Exception e) {
            e.printStackTrace();
            isProcessing = false;
        }
    }

    public void stopServer() {
        // 检查是否正在处理中
        if (isProcessing) {
            return;
        }

        isProcessing = true;
        try {
            ThreadUtils.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            // 移除设备注册
            if (upnpService != null && mediaServer != null) {
                try {
                    upnpService.getRegistry().removeDevice(mediaServer.getDevice());
                } catch (Exception e) {
                    // 移除设备失败，记录日志但不影响后续操作
                    Log.e(LOGTAG, "Failed to remove device from registry", e);
                }
            }

            // 关闭媒体服务器，释放资源
            if (mediaServer != null) {
                try {
                    mediaServer.close();
                    Log.v(LOGTAG, "MediaServer resources released");
                } catch (Exception e) {
                    Log.w(LOGTAG, "Error closing MediaServer", e);
                }
            }

            // 取消服务绑定 - 使用专门的异常处理
            try {
                if (isServiceBound) {
                    Context applicationContext = getApplicationContext();
                    if (applicationContext != null) {
                        destroyUpnpService();
                    }
                }
            } catch (IllegalArgumentException e) {
                // 捕获服务未注册的异常，这是正常的边界情况
                Log.w(LOGTAG, "Service not registered when trying to unbind", e);
            } catch (Exception e) {
                // 其他异常
                Log.e(LOGTAG, "Failed to unbind service", e);
            }

            // 清理资源
            mediaServer = null;
            upnpService = null;
            BaseApplication.upnpService = null;

            // 重置ContentTree，确保下次启动时从零开始
            ContentTree.resetContentTree();

            // 重置准备标志，确保下次启动时重新初始化
            serverPrepared = false;

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 无论成功还是失败，都重置处理状态和绑定状态
            isProcessing = false;
            isServiceBound = false;
        }
    }

    /**
     * 更新媒体服务器数据
     * 重新请求接口更新所有节点数据
     * 这个更新过程需要时间，数据量越大时间越长
     */
    public void updateMediaServer() {
        // 防止并发更新
        if (!isEnableUpnp()) {
            return;
        }
        if (isUpdating) {
            Log.w(LOGTAG, "Media server is already updating");
            return;
        }

        isUpdating = true;
        Log.v(LOGTAG, "Starting media server update...");

        // 重置所有媒体信息列表
        artistInfoList.clear();
        albumArtistInfoList.clear();
        albumInfoList.clear();
        composerInfoList.clear();
        genreInfoList.clear();

        // 重置所有容器对象
        singleMusicContainer = null;
        artistContainer = null;
        albumContainer = null;
        albumArtistContainer = null;
        composerContainer = null;
        genreContainer = null;

        // 重置ContentTree
        ContentTree.resetContentTree();

        // 重置准备标志，确保可以重新初始化
        serverPrepared = false;

        // 重新初始化所有容器
        initSingleMusicContainer();
        initArtistContainer();
        initAlbumContainer();
        initAlbumArtistContainer();
        initComposerContainer();
        initGenreContainer();

        loadApi();
    }

    // --------------------------
    // 初始化相关方法
    // --------------------------

    // 初始化方法
    private void init() {
        initSingleMusicContainer();
        initArtistContainer();
        initAlbumContainer();
        initAlbumArtistContainer();
        bootComplete();
        registerReceiver();
    }

    private void bootComplete() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void initStartServer() {
        if (isEnableUpnp()) {
            startServer();
        }
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_SERVER);
        filter.addAction(ACTION_STOP_SERVER);
        filter.addAction(ACTION_REFRESH_DATA);
        registerReceiver(libraryReceiver, filter, Constant.DLNA_BROADCAST_PERMISSION, null);
        Log.d(LOGTAG, "Broadcast receiver registered");
    }

    private void prepareMediaServer() {
        // 避免重复初始化
        if (serverPrepared)
            return;

        // 获取内容树的根节点
        ContentNode rootNode = ContentTree.getRootNode();

        // 1. 初始化单曲容器
        initSingleMusicContainer();

        // 2. 初始化艺术家容器
        initArtistContainer();

        // 3. 初始化专辑容器
        initAlbumContainer();

        // 4. 初始化专辑艺术家容器
        initAlbumArtistContainer();

        // 5. 初始化作曲家容器
        initComposerContainer();

        // 6. 初始化流派容器
        initGenreContainer();

        // 6. 创建或更新音频容器
        Container audioContainer = ContainerLoader.getInstance(this).createOrUpdateAudioContainer(rootNode);

        // 4. 加载API音乐列表
        loadApiMusicList(audioContainer);

        // 5. 加载API艺术家列表（带回调）
        loadApiArtistList(new ArtistListCallback() {
            @Override
            public void onArtistListLoaded() {
                // 6. 创建或更新艺术家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateArtistContainer(rootNode, artistInfoList);
            }

            @Override
            public void onArtistListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载艺术家列表失败: " + errorMsg);
                // 即使加载失败，也完成准备工作，避免服务挂起
                finishPreparation();
            }
        });
        // 6. 加载API专辑艺术家列表（带回调）
        loadApiAlbumArtistList(new AlbumArtistListCallback() {
            @Override
            public void onAlbumArtistListLoaded() {
                // 创建或更新专辑艺术家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateAlbumArtistContainer(rootNode, albumArtistInfoList);
            }

            @Override
            public void onAlbumArtistListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载专辑艺术家列表失败: " + errorMsg);
                // 即使加载失败，也完成准备工作，避免服务挂起
                finishPreparation();
            }
        });
        // 7. 加载API专辑列表（带回调）
        loadApiAlbumList(new AlbumListCallback() {
            @Override
            public void onAlbumListLoaded() {
                // 8. 创建或更新专辑容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateAlbumContainer(rootNode, albumInfoList);
            }

            @Override
            public void onAlbumListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载专辑列表失败: " + errorMsg);
                // 即使加载失败，也继续完成准备工作，避免服务挂起
                finishPreparation();
            }
        });
        // 8. 加载API作曲家列表（带回调）
        loadApiComposerList(new ComposerListCallback() {
            @Override
            public void onComposerListLoaded() {
                // 9. 创建或更新作曲家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateComposerContainer(rootNode, composerInfoList);
            }

            @Override
            public void onComposerListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载作曲家列表失败: " + errorMsg);
                // 即使加载失败，也继续完成准备工作，避免服务挂起
                finishPreparation();
            }
        });
        // 10. 加载API流派列表（带回调）
        loadApiGenreList(new GenreListCallback() {
            @Override
            public void onGenreListLoaded() {
                // 11. 创建或更新流派容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateGenreContainer(rootNode, genreInfoList);
                // 12. 完成准备工作
                finishPreparation();
            }

            @Override
            public void onGenreListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载流派列表失败: " + errorMsg);
                // 即使加载失败，也继续完成准备工作，避免服务挂起
                finishPreparation();
            }
        });
    }

    // --------------------------
    // 容器初始化方法
    // --------------------------

    // 初始化单曲容器
    private void initSingleMusicContainer() {
        singleMusicContainer = new SingleMusicContainer();
    }

    // 初始化艺术家容器
    private void initArtistContainer() {
        artistContainer = new ArtistContainer();
    }

    // 初始化专辑容器
    private void initAlbumContainer() {
        albumContainer = new AlbumContainer();
    }

    // 初始化专辑艺术家容器
    private void initAlbumArtistContainer() {
        albumArtistContainer = new ArtistContainer();
    }

    // 初始化作曲家容器
    private void initComposerContainer() {
        composerContainer = new ComposerContainer();
    }

    // 初始化流派容器
    private void initGenreContainer() {
        genreContainer = new GenreContainer();
    }

    // --------------------------
    // API加载相关方法
    // --------------------------

    private void loadApi() {
        // 获取内容树的根节点
        ContentNode rootNode = ContentTree.getRootNode();

        // 创建或更新音频容器
        Container audioContainer = ContainerLoader.getInstance(this).createOrUpdateAudioContainer(rootNode);

        // 重新加载所有API数据
        int[] pendingCallbacks = new int[]{1, 1, 1, 1, 1}; // 五个异步回调：艺术家、专辑艺术家、专辑、作曲家、流派
        final Object lock = new Object();


        // 加载API音乐列表
        loadApiMusicList(audioContainer);

        // 加载API艺术家列表（带回调）
        loadApiArtistList(new ArtistListCallback() {
            @Override
            public void onArtistListLoaded() {
                // 创建或更新艺术家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateArtistContainer(rootNode, artistInfoList);
                synchronized (lock) {
                    pendingCallbacks[0]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }

            @Override
            public void onArtistListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载艺术家列表失败: " + errorMsg);
                synchronized (lock) {
                    pendingCallbacks[0]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }
        });

        // 加载API专辑艺术家列表（带回调）
        loadApiAlbumArtistList(new AlbumArtistListCallback() {
            @Override
            public void onAlbumArtistListLoaded() {
                // 创建或更新专辑艺术家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateAlbumArtistContainer(rootNode, albumArtistInfoList);
                synchronized (lock) {
                    pendingCallbacks[1]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }

            @Override
            public void onAlbumArtistListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载专辑艺术家列表失败: " + errorMsg);
                synchronized (lock) {
                    pendingCallbacks[1]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }
        });

        // 加载API专辑列表（带回调）
        loadApiAlbumList(new AlbumListCallback() {
            @Override
            public void onAlbumListLoaded() {
                // 创建或更新专辑容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateAlbumContainer(rootNode, albumInfoList);
                synchronized (lock) {
                    pendingCallbacks[2]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }

            @Override
            public void onAlbumListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载专辑列表失败: " + errorMsg);
                synchronized (lock) {
                    pendingCallbacks[2]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }
        });

        // 加载API作曲家列表（带回调）
        loadApiComposerList(new ComposerListCallback() {
            @Override
            public void onComposerListLoaded() {
                // 创建或更新作曲家容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateComposerContainer(rootNode, composerInfoList);
                synchronized (lock) {
                    pendingCallbacks[3]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }

            @Override
            public void onComposerListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载作曲家列表失败: " + errorMsg);
                synchronized (lock) {
                    pendingCallbacks[3]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }
        });

        // 加载API流派列表（带回调）
        loadApiGenreList(new GenreListCallback() {
            @Override
            public void onGenreListLoaded() {
                // 创建或更新流派容器
                ContainerLoader.getInstance(EversoloLibraryService.this).createOrUpdateGenreContainer(rootNode, genreInfoList);
                synchronized (lock) {
                    pendingCallbacks[4]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }

            @Override
            public void onGenreListLoadFailed(String errorMsg) {
                Log.e(LOGTAG, "加载流派列表失败: " + errorMsg);
                synchronized (lock) {
                    pendingCallbacks[4]--;
                    if (allCallbacksCompleted(pendingCallbacks)) {
                        finishUpdate();
                    }
                }
            }
        });
    }

    /**
     * 从API加载音乐列表
     */
    private void loadApiMusicList(final Container audioContainer) {
        Map<String, String> params = new HashMap<>();
        params.put("start", "0");
        params.put("count", count);
        params.put("needParse", String.valueOf(true));

        singleMusicContainer.loadMusicList(params, new SingleMusicContainer.LoadCallback() {
            @Override
            public void onSuccess(List<AudioInfo> audioInfoList) {
                Log.d(LOGTAG, "API音乐列表加载成功，共 " + audioInfoList.size() + " 首歌曲");
                // 将API获取的音乐添加到音频容器
                ContainerLoader.getInstance(EversoloLibraryService.this).addApiMusicToContainer(audioInfoList, audioContainer);
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.d(LOGTAG, "API音乐列表加载失败: " + errorMsg);
            }
        });
    }

    /**
     * 加载API艺术家列表
     */
    private void loadApiArtistList(final ArtistListCallback callback) {
        Map<String, String> params = new HashMap<>();
        params.put("start", "0");
        params.put("count", count);
        params.put("artistType", "0");

        // 使用ArtistContainer加载艺术家列表
        artistContainer.loadArtistList(params, new ArtistContainer.LoadCallback() {
            @Override
            public void onSuccess(List<ArtistInfo> artistInfoList) {
                Log.d(LOGTAG, "API艺术家列表加载成功，共 " + artistInfoList.size() + " 位艺术家");

                // 将API获取的艺术家保存到全局变量
                EversoloLibraryService.this.artistInfoList = artistInfoList;

                // 调用回调通知加载完成
                if (callback != null) {
                    callback.onArtistListLoaded();
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.e(LOGTAG, "API艺术家列表加载失败: " + errorMsg, new Exception("API调用失败"));

                // 调用回调通知加载失败
                if (callback != null) {
                    callback.onArtistListLoadFailed(errorMsg);
                }
            }
        });
    }

    /**
     * 加载API专辑艺术家列表
     */
    private void loadApiAlbumArtistList(final AlbumArtistListCallback callback) {
        Map<String, String> params = new HashMap<>();
        params.put("start", "0");
        params.put("count", count);
        params.put("artistType", "1"); // 1表示专辑艺术家

        // 使用ArtistContainer加载专辑艺术家列表
        albumArtistContainer.loadArtistList(params, new ArtistContainer.LoadCallback() {
            @Override
            public void onSuccess(List<ArtistInfo> albumArtistInfoList) {
                Log.d(LOGTAG, "API专辑艺术家列表加载成功，共 " + albumArtistInfoList.size() + " 位专辑艺术家");

                // 将API获取的专辑艺术家保存到全局变量
                EversoloLibraryService.this.albumArtistInfoList = albumArtistInfoList;

                // 调用回调通知加载完成
                if (callback != null) {
                    callback.onAlbumArtistListLoaded();
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.e(LOGTAG, "API专辑艺术家列表加载失败: " + errorMsg, new Exception("API调用失败"));

                // 调用回调通知加载失败
                if (callback != null) {
                    callback.onAlbumArtistListLoadFailed(errorMsg);
                }
            }
        });
    }

    /**
     * 加载API专辑列表
     */
    private void loadApiAlbumList(final AlbumListCallback callback) {
        Map<String, String> params = new HashMap<>();
        params.put("start", "0");
        params.put("count", count);

        // 使用AlbumContainer加载专辑列表
        albumContainer.loadAlbumList(params, new AlbumContainer.LoadCallback() {
            @Override
            public void onSuccess(List<AlbumInfo> albumInfoList) {
                Log.d(LOGTAG, "API专辑列表加载成功，共 " + albumInfoList.size() + " 张专辑");

                // 将API获取的专辑保存到全局变量
                EversoloLibraryService.this.albumInfoList = albumInfoList;

                // 调用回调通知加载完成
                if (callback != null) {
                    callback.onAlbumListLoaded();
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.e(LOGTAG, "API专辑列表加载失败: " + errorMsg, new Exception("API调用失败"));

                // 调用回调通知加载失败
                if (callback != null) {
                    callback.onAlbumListLoadFailed(errorMsg);
                }
            }
        });
    }

    /**
     * 加载API作曲家列表
     */
    private void loadApiComposerList(final ComposerListCallback callback) {
        Map<String, String> params = new HashMap<>();
        params.put("start", "0");
        params.put("count", count);

        // 添加API调试日志
        ApiClient apiClient = ApiClient.getInstance();
        Log.d(LOGTAG, "当前ApiClient baseUrl: " + apiClient.getBaseUrl());
        Log.d(LOGTAG, "加载作曲家列表请求参数: " + params.toString());

        // 使用ComposerContainer加载作曲家列表
        composerContainer.loadComposerList(params, new ComposerContainer.LoadCallback() {
            @Override
            public void onSuccess(List<ComposerInfo> composerInfoList) {
                Log.d(LOGTAG, "API作曲家列表加载成功，共 " + composerInfoList.size() + " 位作曲家");

                // 将API获取的作曲家保存到全局变量
                EversoloLibraryService.this.composerInfoList = composerInfoList;

                // 打印作曲家列表内容，用于调试
                for (ComposerInfo composer : composerInfoList) {
                    Log.d(LOGTAG, "作曲家: " + composer.getId() + " - " + composer.getName());
                }

                // 调用回调通知加载完成
                if (callback != null) {
                    callback.onComposerListLoaded();
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.e(LOGTAG, "API作曲家列表加载失败: " + errorMsg, new Exception("API调用失败"));

                // 调用回调通知加载失败
                if (callback != null) {
                    callback.onComposerListLoadFailed(errorMsg);
                }
            }
        });
    }

    /**
     * 加载API流派列表
     */
    private void loadApiGenreList(final GenreListCallback callback) {
        Map<String, String> params = new HashMap<>();
        // 设置请求参数（流派接口不支持分页，仅使用基础参数）
        params.put("start", "0");
        params.put("count", count);

        // 使用GenreContainer加载流派列表
        genreContainer.loadGenreList(params, new GenreContainer.LoadCallback() {
            @Override
            public void onSuccess(List<GenreInfo> genreInfoList) {
                // 将流派信息保存到全局变量
                EversoloLibraryService.this.genreInfoList = genreInfoList;
                Log.d(LOGTAG, "API流派列表加载成功，共 " + genreInfoList.size() + " 个流派");

                if (callback != null) {
                    callback.onGenreListLoaded();
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                Log.e(LOGTAG, "API流派列表加载失败: " + errorMsg);
                if (callback != null) {
                    callback.onGenreListLoadFailed(errorMsg);
                }
            }
        });
    }

    // --------------------------
    // UPnP服务相关方法
    // --------------------------

    /**
     * 在自有服务中创建AndroidUpnpService
     */
    private void createUpnpService() {
        try {
            iUpnpService = AndroidUpnpServiceLoader.getInstance().createUpnpService(this);
            upnpService = (AndroidUpnpService) binder;
            Log.d(LOGTAG, "createUpnpService: 构造AndroidUpnpService成功");
            BaseApplication.upnpService = upnpService;
            isServiceBound = true;
            isProcessing = false;

            Log.v(LOGTAG, "Connected to UPnP Service");

            // 只有在服务器应该运行时才创建媒体服务器
            if (mediaServer == null) {
                try {
                    mediaServer = new MediaServer(getApplicationContext());
                    upnpService.getRegistry().addDevice(mediaServer.getDevice());
                    ThreadUtils.execute(this::prepareMediaServer);
                } catch (Exception ex) {
                    Log.e(LOGTAG, "Creating demo device failed", ex);
                    // 如果创建失败，确保状态正确重置
                    try {
                        if (isServiceBound) {
                            Context applicationContext = getApplicationContext();
                            if (applicationContext != null) {
                                destroyUpnpService();
                            }
                            isServiceBound = false;
                            upnpService = null;
                            BaseApplication.upnpService = null;
                        }
                    } catch (Exception e) {
                        Log.w(LOGTAG, "Error cleaning up after device creation failure", e);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(LOGTAG, "createUpnpService: 构造AndroidUpnpService失败");
        }
    }

    private void destroyUpnpService() {
        Log.v(LOGTAG, "Disconnected from UPnP Service by system");
        if (upnpService != null) {
            upnpService.shutDown();
            Log.d(LOGTAG, "UpnpService: shutdown");
        }
        upnpService = null;
        BaseApplication.upnpService = null;
        isServiceBound = false;
        isProcessing = false;
        handler.removeCallbacksAndMessages(null);
        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    // --------------------------
    // 辅助方法
    // --------------------------

    private boolean isEnableUpnp() {
        int status = Settings.System.getInt(getContentResolver(), CLING_SETTING_NAME, 1);
        return status == 1;
    }

    /**
     * 检查所有回调是否都已完成
     */
    private boolean allCallbacksCompleted(int[] pendingCallbacks) {
        for (int pending : pendingCallbacks) {
            if (pending > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 完成更新操作
     */
    private void finishUpdate() {
        serverPrepared = true;
        isUpdating = false;
        Log.v(LOGTAG, "Media server update completed");
    }

    /**
     * 完成准备工作
     */
    private void finishPreparation() {
        serverPrepared = true; // 设置准备完成标志，防止重复初始化
        sortRootContainers();
    }

    /**
     * 重新排序根节点中的容器，实现指定的顺序：单曲-艺术家-专辑艺术家-作曲家-专辑-流派
     */
    private void sortRootContainers() {
        ContentNode rootNode = ContentTree.getRootNode();
        if (rootNode == null || rootNode.getContainer() == null) {
            return;
        }

        List<Container> containers = rootNode.getContainer().getContainers();
        if (containers == null || containers.isEmpty()) {
            return;
        }

        // 定义期望的容器顺序（按容器ID排序）
        final List<String> desiredOrder = Arrays.asList(
                ContentTree.AUDIO_ID,      // 单曲
                ContentTree.ARTIST_ID,     // 艺术家
                ContentTree.ALBUM_ARTIST_ID, // 专辑艺术家
                ContentTree.COMPOSER_ID,   // 作曲家
                ContentTree.ALBUM_ID,      // 专辑
                ContentTree.GENRE_ID       // 流派
        );

        // 创建一个新的排序后的容器列表
        List<Container> sortedContainers = new ArrayList<>();
        Map<String, Container> containerMap = new HashMap<>();

        // 将所有容器放入Map中，便于按ID查找
        for (Container container : containers) {
            containerMap.put(container.getId(), container);
        }

        // 按照期望的顺序添加容器
        for (String containerId : desiredOrder) {
            if (containerMap.containsKey(containerId)) {
                sortedContainers.add(containerMap.remove(containerId));
            }
        }

        // 添加剩余的容器（如果有）
        sortedContainers.addAll(containerMap.values());

        // 替换根节点中的容器列表
        rootNode.getContainer().setContainers(sortedContainers);
    }

    // --------------------------
    // 回调接口
    // --------------------------

    /**
     * 加载API艺术家列表的回调接口
     */
    private interface ArtistListCallback {
        void onArtistListLoaded();

        void onArtistListLoadFailed(String errorMsg);
    }

    /**
     * 加载API专辑艺术家列表的回调接口
     */
    private interface AlbumArtistListCallback {
        void onAlbumArtistListLoaded();

        void onAlbumArtistListLoadFailed(String errorMsg);
    }

    /**
     * 加载API专辑列表的回调接口
     */
    private interface AlbumListCallback {
        void onAlbumListLoaded();

        void onAlbumListLoadFailed(String errorMsg);
    }

    /**
     * 加载API作曲家列表的回调接口
     */
    private interface ComposerListCallback {
        void onComposerListLoaded();

        void onComposerListLoadFailed(String errorMsg);
    }

    /**
     * 加载API流派列表的回调接口
     */
    public interface GenreListCallback {
        void onGenreListLoaded();

        void onGenreListLoadFailed(String errorMsg);
    }

}