package com.coolweather.app.activity;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.coolweather.app.R;
import com.coolweather.app.db.CoolWeatherDB;
import com.coolweather.app.model.City;
import com.coolweather.app.model.Country;
import com.coolweather.app.model.Province;
import com.coolweather.app.util.HttpCallbackListener;
import com.coolweather.app.util.HttpUtil;
import com.coolweather.app.util.Utility;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by markIron on 2016/5/26.
 */
public class ChooseAreaActivity extends Activity {
    public static final int LEVEL_PROVINCE = 0;
    public static final int LEVEL_CITY = 1;
    public static final int LEVEL_COUNTRY = 2;

    private ProgressDialog progressDialog;
    private TextView titleText;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private CoolWeatherDB coolWeatherDB;
    private List<String> dataList = new ArrayList<String>();

    /*省列表集合*/
    private List<Province> provinceList;
    /*市表表集合*/
    private List<City> cityList;
    /*县列表集合*/
    private List<Country> countryList;
    /*选中的省份*/
    private Province selectedProvince;
    /*选中的城市*/
    private City selectedCity;
    /*当前选中的级别*/
    private int currentLevel;

    /*在onCreate()方法中先是获取到了一些控件的实例，然后去初始化了ArrayAdapter,将它设置为listView的适配器。
    * 之后又去获取到了CoolWeatherDB的实例，并给ListView设置了点击事件*/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("city_selected", false)) {
            Intent intent = new Intent(this, WeatherActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.choose_area);
        listView = (ListView) findViewById(R.id.list_view);
        titleText = (TextView) findViewById(R.id.title_text);
        adapter = new ArrayAdapter<String>(this, R.layout.support_simple_spinner_dropdown_item, dataList);
        listView.setAdapter(adapter);
        coolWeatherDB = CoolWeatherDB.getInstance(this);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (currentLevel == LEVEL_PROVINCE) {
                    selectedProvince = provinceList.get(position);
                    queryCities();
                } else if (currentLevel == LEVEL_CITY) {
                    selectedCity = cityList.get(position);
                    queryCounties();
                } else if (currentLevel == LEVEL_COUNTRY) {
                    String countryCode = countryList.get(position).getCountryCode();
                    Intent intent = new Intent(ChooseAreaActivity.this, WeatherActivity.class);
                    intent.putExtra("country_code", countryCode);
                    startActivity(intent);
                    finish();
                }
            }
        });
        queryProvinces();// 加载省级数据
    }

    /*查询全国所有的省，优先从数据库查询，如果没有查询到再去服务器上查询
    * queryProvinces()方法的内部会首先调用CoolWeatherDB的loadProvinces()方法来从数据库中读取省级数据，如果读取到了就直接将数据显示到界面上，
    * 如果没有读取到就调用queryFromServer()方法来从服务器上查询数据*/
    private void queryProvinces() {
        provinceList = coolWeatherDB.loadProvinces();
        if (provinceList.size() > 0) {
            dataList.clear();
            for (Province province : provinceList) {
                dataList.add(province.getProvinceName());
            }
            adapter.notifyDataSetChanged();
            listView.setSelection(0);
            titleText.setText("中国");
            currentLevel = LEVEL_PROVINCE;
        } else {
            queryFromServer(null, "province");
        }
    }

    /*查询选中省内所有的市，优先从数据库查询，如果没有查询到再去服务器上查询*/
    private void queryCities() {
        cityList = coolWeatherDB.loadCities(selectedProvince.getId());
        if (cityList.size() > 0) {
            dataList.clear();
            for (City city : cityList) {
                dataList.add(city.getCityName());
            }
            adapter.notifyDataSetChanged();
            listView.setSelection(0);
            titleText.setText(selectedProvince.getProvinceName());
            currentLevel = LEVEL_CITY;
        } else {
            queryFromServer(selectedProvince.getProvinceCode(), "city");
        }
    }

    /*查询选中市内所有的县，优先从数据库查询，如果没有查询到再去服务器上查询*/
    private void queryCounties() {
        countryList = coolWeatherDB.loadCountries(selectedCity.getId());
        if (countryList.size() > 0) {
            dataList.clear();
            for (Country country : countryList) {
                dataList.add(country.getCountryName());
            }
            adapter.notifyDataSetChanged();
            titleText.setText(selectedCity.getCityName());
            currentLevel = LEVEL_COUNTRY;
        } else {
            queryFromServer(selectedCity.getCityCode(), "country");
        }
    }

    /*根据传入的代号和类型从服务器上查询省市县数据
    * queryFromServer()方法会先根据传入的参数来拼装查询地址，确定了查询地址之后，接下来就调用HttpUtil的sendHttpRequest()方法来向服务器发送请求
    * 响应的数据会回调到onFinish()方法中，然后我们在这里去调用Utility的handleProvincesResponse()方法来解析和处理服务器返回的数据，并存储到
    * 数据库中。在解析和处理完数据之后，我们再次调用了queryProvinces()方法来重新加载省级数据，由于queryProvinces()方法牵扯到了UI操作，因此
    * 必须要在主线程中调用，这里借助了runOnUiThread()方法来实现从子线程切换到主线程，它的实现原理其实也是基于异步消息处理机制的。现在数据库中
    * 已经存在了数据，因此调用queryProvinces()就会直接将数据显示到界面上了。*/
    private void queryFromServer(final String code, final String type) {
        String address;
        if (!TextUtils.isEmpty(code)) {
            address = "http://www.weather.com.cn/data/list3/city" + code + ".xml";
        } else {
            address = "http://www.weather.com.cn/data/list3/city.xml";
        }
        showProgressDialog();
        HttpUtil.sendHttpRequest(address, new HttpCallbackListener() {
            @Override
            public void onFinish(String response) {
                boolean result = false;
                if ("province".equals(type)) {
                    result = Utility.handleProvincesResponse(coolWeatherDB, response);
                } else if ("city".equals(type)) {
                    result = Utility.handleCitiesResponse(coolWeatherDB, response, selectedProvince.getId());
                } else if ("country".equals(type)) {
                    result = Utility.handleCountiesResponse(coolWeatherDB, response, selectedCity.getId());
                    if (result) {
                        // 通过runOnUiThread()方法回调到主线程处理逻辑
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                closeProgressDialog();
                                if ("province".equals(type)) {
                                    queryProvinces();
                                } else if ("city".equals(type)) {
                                    queryCities();
                                } else if ("country".equals(type)) {
                                    queryCounties();
                                }
                            }
                        });
                    }
                }
            }

            @Override
            public void onError(Exception e) {
                // 通过runOnUiThread()方法回到主线程处理逻辑
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        closeProgressDialog();
                        Toast.makeText(ChooseAreaActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /*显示进度对话框*/
    private void showProgressDialog() {
        if (progressDialog == null) {
            progressDialog = new ProgressDialog(this);
            progressDialog.setMessage("正在加载...");
            progressDialog.setCanceledOnTouchOutside(false);
        }
        progressDialog.show();
    }

    /*关闭进度对话框*/
    private void closeProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
    }

    /*捕获Back按键，根据当前的级别来判断，此时应该返回市列表、省列表、还是直接退出。*/

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (currentLevel == LEVEL_COUNTRY) {
            queryCities();
        } else if (currentLevel == LEVEL_CITY) {
            queryProvinces();
        } else {
            finish();
        }
    }
}

























































