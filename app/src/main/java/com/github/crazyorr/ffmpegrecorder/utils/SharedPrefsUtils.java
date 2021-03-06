package com.github.crazyorr.ffmpegrecorder.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.github.crazyorr.ffmpegrecorder.R;


public class SharedPrefsUtils {

	public static int getFramesFrequency(Context ctx) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		String val = prefs.getString(ctx.getString(R.string.prefs_deltaTimeKey),
									 ctx.getString(R.string.prefs_Time5));
		return Integer.parseInt(val);
	}
	
}
