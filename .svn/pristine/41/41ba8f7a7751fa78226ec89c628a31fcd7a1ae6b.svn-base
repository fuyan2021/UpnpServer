package com.zxt.dlna.activity;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.eversolo.upnpserver.dlna.dms.Constant;
import com.eversolo.upnpserver.dlna.dms.component.EversoloLibraryService;
import com.zxt.dlna.R;

/**
 * Created by fuyan
 * 2025/12/30
 **/
public class SettingActivity extends Activity {
    private TextView tv, tvDsf;
    private Switch aSwitch, dsfSwitch;
    private boolean isServerOpen, isDsfOpen;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private Runnable serverToastRunnable;
    private Runnable dsfToastRunnable;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_open_server);
        tv = findViewById(R.id.text);
        tvDsf = findViewById(R.id.text_dsf);
        aSwitch = findViewById(R.id.switchBt);
        dsfSwitch = findViewById(R.id.switchBt_dsf);
        isServerOpen = Settings.System.getInt(getContentResolver(), Constant.CLING_SETTING_NAME, 0) == 1;
        isDsfOpen = Settings.System.getInt(getContentResolver(), Constant.DSF_SETTING_NAME, 0) == 1;
        updateUi();

        if (isServerOpen) {
            //测试时自启动
            Intent serviceIntent = new Intent(this, EversoloLibraryService.class);
            startService(serviceIntent);
        }

        serverToastRunnable = () -> {
            Toast.makeText(this, "发送成功", Toast.LENGTH_SHORT).show();
            aSwitch.setEnabled(true);
        };
        
        dsfToastRunnable = () -> {
            Toast.makeText(this, "发送成功", Toast.LENGTH_SHORT).show();
            dsfSwitch.setEnabled(true);
        };
        
        aSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            aSwitch.setEnabled(false);
            handler.removeCallbacks(serverToastRunnable);
            
            handler.post(() -> {
                try {
                    Settings.System.putInt(getContentResolver(), Constant.CLING_SETTING_NAME, isChecked ? 1 : 0);
                    sendSettingMessage(isChecked);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // 只调度最新的Toast任务
            handler.postDelayed(serverToastRunnable, 500);
        });

        dsfSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            dsfSwitch.setEnabled(false);
            handler.removeCallbacks(dsfToastRunnable);
            
            handler.post(() -> {
                try {
                    Settings.System.putInt(getContentResolver(), Constant.DSF_SETTING_NAME, isChecked ? 1 : 0);
                    sendDsfMessage();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // 只调度最新的Toast任务
            handler.postDelayed(dsfToastRunnable, 500);
        });
    }

    private void updateUi() {
        aSwitch.setChecked(isServerOpen);
        dsfSwitch.setChecked(isDsfOpen);
    }

    private void sendSettingMessage(boolean start) {
        Intent messageIntent = new Intent(start ? Constant.ACTION_START_SERVER : Constant.ACTION_STOP_SERVER);
        sendBroadcast(messageIntent, Constant.DLNA_BROADCAST_PERMISSION);
    }

    private void sendDsfMessage() {
        Intent messageIntent = new Intent(Constant.ACTION_REFRESH_DATA);
        sendBroadcast(messageIntent, Constant.DLNA_BROADCAST_PERMISSION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
