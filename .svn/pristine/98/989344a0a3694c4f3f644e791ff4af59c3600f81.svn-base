package com.zxt.dlna.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.eversolo.upnpserver.dlna.dms.component.EversoloLibraryService;

/**
 * 透明Activity作为应用入口点
 * 启动后立即启动服务并关闭自己
 */
public class TransparentEntryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent serviceIntent = new Intent(this, EversoloLibraryService.class);
        startService(serviceIntent);
        finish();
    }
}