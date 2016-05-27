package com.coolweather.app.util;

/**
 * Created by markIron on 2016/5/26.
 */
public interface HttpCallbackListener {
    void onFinish(String response);
    void onError(Exception e);
}
