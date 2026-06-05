package com.example.myapp;

import android.os.Build;
import android.os.StrictMode;

import androidx.multidex.MultiDexApplication;

import com.example.myapp.extractor.NewPipeDownloader;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

public class App extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        // Relax StrictMode on Android 9 to prevent OEM ROM crashes during init
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().permitAll().build()
            );
        }

        // Init NewPipe with Localization — required by v0.26+ for proper stream extraction
        NewPipe.init(NewPipeDownloader.getInstance(), Localization.DEFAULT, ContentCountry.DEFAULT);
    }
}
