package com.example.android.sunshine.app.watch;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by mudasar on 02/05/16.
 */
public class WeatherService extends WearableListenerService implements DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener  {

    public static final  String KEY_WEATHER_CONDITION   = "Condition";
    public static final  String KEY_WEATHER_TEMPERATURE_HIGH     = "high";
    public static final  String KEY_WEATHER_TEMPERATURE_LOW      = "low";
    public static final  String KEY_WEATHER_TEMP_FORMAT = "format";

    public static final  String KEY_WEATHER_TEMPERATURE = "Temperature";
    public static final  String PATH_WEATHER_INFO       = "/SunshineWeatherService/WeatherInfo";
    public static final  String PATH_SERVICE_REQUIRE    = "/SunshineWeatherService/Require";
    private static final String TAG                     = WeatherService.class.getSimpleName();
    private GoogleApiClient mGoogleApiClient;


    private String mPeerId;

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "The service is started");
        return super.onStartCommand(intent, flags, startId);
    }


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        mPeerId = messageEvent.getSourceNodeId();
        Log.d(TAG, "MessageReceived: " + messageEvent.getPath());
        if ( messageEvent.getPath().equals( PATH_SERVICE_REQUIRE ) )
        {
            startTask();
        }
    }

    private void startTask() {
        Log.d( TAG, "Start Weather AsyncTask" );
        mGoogleApiClient = new GoogleApiClient.Builder( this ).addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi( Wearable.API )
                .build();


        DataTransferTask task = new DataTransferTask();
        task.execute();

    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(TAG, "onConnected: " + bundle);
        // Now you can use the Data Layer API
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "onConnectionSuspended: " + i);
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(TAG, "onConnectionFailed: " + connectionResult);
    }

    private class DataTransferTask extends AsyncTask
    {


        @Override
        protected Object doInBackground( Object[] params )
        {
            try
            {
                Log.d( TAG, "Task Running" );

                if ( !mGoogleApiClient.isConnected() )
                { mGoogleApiClient.connect(); }

                DataMap data = new DataMap();

                //load data from database

                Context context = getApplicationContext();
                String locationQuery = Utility.getPreferredLocation(context);

                Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

                // we'll query our contentProvider, as always
                Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

                if (cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                    double high = cursor.getDouble(INDEX_MAX_TEMP);
                    double low = cursor.getDouble(INDEX_MIN_TEMP);
                    String desc = cursor.getString(INDEX_SHORT_DESC);

                    DataMap dataMap = new DataMap();


                    //real
                    dataMap.putDouble(KEY_WEATHER_TEMPERATURE_HIGH, high);
                    dataMap.putString(KEY_WEATHER_CONDITION, desc );
                    dataMap.putDouble(KEY_WEATHER_TEMPERATURE_LOW, low );
                    dataMap.putString(KEY_WEATHER_CONDITION, desc);

                    PutDataMapRequest putDMR = PutDataMapRequest.create(PATH_WEATHER_INFO);
                    putDMR.getDataMap().putAll(dataMap);
                    PutDataRequest request = putDMR.asPutDataRequest();
                    DataApi.DataItemResult result = Wearable.DataApi.putDataItem(mGoogleApiClient, request).await();
                    if (result.getStatus().isSuccess()) {
                        Log.v("myTag", "DataMap: " + dataMap + " sent successfully to data layer ");
                    }
                    else {
                        // Log an error
                        Log.v("myTag", "ERROR: failed to send DataMap to data layer");
                    }

                    Wearable.MessageApi.sendMessage( mGoogleApiClient, mPeerId, PATH_WEATHER_INFO, dataMap.toByteArray() )
                            .setResultCallback(
                                    new ResultCallback<MessageApi.SendMessageResult>() {
                                        @Override
                                        public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                            Log.d(TAG, "SendUpdateMessage: " + sendMessageResult.getStatus());
                                        }
                                    }
                            );
                }

            }
            catch ( Exception e )
            {
                Log.d( TAG, "Task Fail: " + e );
            }
            return null;
        }
    }

}
