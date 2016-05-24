/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final String TIME_FORMAT_WITHOUT_SECONDS = "%02d:%02d";
    private static final String TIME_FORMAT_WITH_SECONDS = TIME_FORMAT_WITHOUT_SECONDS + ":%02d";
    private static final String DATE_FORMAT = "%s %s %02d %d";
    private static final int DATE_AND_TIME_DEFAULT_COLOUR = Color.WHITE;
    private static final int BACKGROUND_DEFAULT_COLOUR = Color.BLACK;
    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private boolean shouldShowSeconds = false;

    public SunshineWatchFace() {

    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine  implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener{

        private final  String TAG = SunshineWatchFace.class.getSimpleName();

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mIconPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        protected Resources mResources;
        protected AssetManager mAssets;

        protected int mTheme = 3;
        protected String mWeatherCondition;


        protected int mRequireInterval;
        protected double mTemperatureHigh = 0.0d;
        protected double mTemperatureLow = 0.0d;
        protected long mWeatherInfoReceivedTime;
        protected long mWeatherInfoRequiredTime;

        private long TIMEOUT_MS = 100000;

        protected Bitmap mWeatherConditionDrawable;
        protected GoogleApiClient mGoogleApiClient;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        String mName;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        public Engine() {
            mName = "roundwatch";
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.date_text));

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.grey));
            mLinePaint.setAntiAlias(true);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeJoin(Paint.Join.ROUND);
            mLinePaint.setStrokeWidth(2f);

            mIconPaint = new Paint();


            mHighTempPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mLowTempPaint = new Paint();
            mLowTempPaint = createTextPaint(resources.getColor(R.color.date_text));

            mTime = new Time();
            mResources = SunshineWatchFace.this.getResources();
            mAssets = SunshineWatchFace.this.getAssets();
            mRequireInterval = mResources.getInteger(R.integer.weather_default_require_interval);
            mWeatherInfoRequiredTime = System.currentTimeMillis() - (DateUtils.SECOND_IN_MILLIS * 58);

            Drawable b = mResources.getDrawable(R.drawable.art_clear);
            mWeatherConditionDrawable = ((BitmapDrawable) b).getBitmap();
            mWeatherConditionDrawable = Bitmap.createScaledBitmap(
                    mWeatherConditionDrawable, 70, 70, false);

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

//            float textSize = resources.getDimension(isRound
//                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);


            float dateTextSize = 0.0f;
            float textSize = 0.0f;
            float tempHighTextSize = 0.0f;
            float tempLowTextSize = 0.0f;

            if (isRound) {
                textSize = resources.getDimension(R.dimen.digital_text_size_round);
                dateTextSize = resources.getDimension(R.dimen.digital_text_date_size_round);
                tempHighTextSize = resources.getDimension(R.dimen.digital_text_size_round);
                tempLowTextSize = resources.getDimension(R.dimen.digital_text_date_size_round);

            } else {
                textSize = resources.getDimension(R.dimen.digital_text_size);
                dateTextSize = resources.getDimension(R.dimen.digital_text_date_size);
                tempHighTextSize = resources.getDimension(R.dimen.digital_text_size);
                tempLowTextSize = resources.getDimension(R.dimen.digital_text_date_size);

            }

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
            mLowTempPaint.setTextSize(tempLowTextSize);
            mHighTempPaint.setTextSize(tempHighTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String timeText = String.format(shouldShowSeconds ? TIME_FORMAT_WITH_SECONDS : TIME_FORMAT_WITHOUT_SECONDS, mTime.hour, mTime.minute, mTime.second);
            float timeXOffset = computeXOffset(timeText, mTimePaint, bounds);
            float timeYOffset = computeTimeYOffset(timeText, mTimePaint, bounds);
            canvas.drawText(timeText, timeXOffset, timeYOffset, mTimePaint);

            SimpleDateFormat fmt = new SimpleDateFormat("EEE, MMM dd yyyy");
            Date date = new Date(mTime.toMillis(true));
            String dateText = String.format("%s", fmt.format(date).toUpperCase());
            float dateXOffset = computeXOffset(dateText, mDatePaint, bounds);
            float dateYOffset = computeDateYOffset(dateText, mDatePaint);
            canvas.drawText(dateText, dateXOffset, timeYOffset + dateYOffset, mDatePaint);

            //draw middle line
            canvas.drawLine(bounds.exactCenterX() - 50.0f, bounds.exactCenterY(), bounds.exactCenterX() + 50.0f, bounds.exactCenterY(), mLinePaint);
            //     canvas.drawText(text, mXOffset, mYOffset, mTextPaint);


            String lowText = String.format(mResources.getString(R.string.format_temperature), mTemperatureLow);
            String highText = String.format(mResources.getString(R.string.format_temperature), mTemperatureHigh);

            float highXOffset = computeHighXOffset( bounds);
            float highYOffset = computeWeatherYOffset(bounds);
            canvas.drawText(highText, highXOffset, highYOffset, mHighTempPaint);

            float lowXOffset = computeLowXOffset(highText, mHighTempPaint, bounds);
            float lowYOffset = computeWeatherYOffset(bounds);
            canvas.drawText(lowText, lowXOffset, lowYOffset, mLowTempPaint);

            float iconXOffset = computeCondistionXOffset(bounds);
            float iconYOffset = computeWeatherYOffset(bounds) - 50.0f;
            canvas.drawBitmap(mWeatherConditionDrawable, iconXOffset, iconYOffset, mIconPaint);

        }


        private float computeXOffset(String text, Paint paint, Rect watchBounds) {
            float centerX = watchBounds.exactCenterX();
            float timeLength = paint.measureText(text);
            return centerX - (timeLength / 2.0f);
        }

        private float computeTimeYOffset(String timeText, Paint timePaint, Rect watchBounds) {
            float centerY = watchBounds.exactCenterY();
            Rect textBounds = new Rect();
            timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
            int textHeight = textBounds.height();
            return centerY - (textHeight * 2.0f);
        }

        private float computeDateYOffset(String dateText, Paint datePaint) {
            Rect textBounds = new Rect();
            datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
            return textBounds.height() + 15.0f;
        }

        private float computeCondistionXOffset( Rect watchBounds){
            float centerX = watchBounds.exactCenterX();
            return  centerX - 100.0f;
        }

        private float computeHighXOffset( Rect watchBounds){
            float centerX = watchBounds.exactCenterX();
            return 15.0f + centerX;
        }

        private float computeLowXOffset(String highText, Paint mHighTempPaint, Rect watchBounds ){
            float centerX = watchBounds.exactCenterX();
            Rect textBounds = new Rect();
            mHighTempPaint.getTextBounds(highText, 0, highText.length(), textBounds);
            return centerX + 15.0f + textBounds.width() + 15.0f;
        }

        private float computeWeatherYOffset( Rect watchBounds){
            float centerY = watchBounds.exactCenterY();
            return  centerY + (centerY / 2);
        }



        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        protected void log(String message) {
            Log.d(SunshineWatchFace.this.getClass().getSimpleName(), message);
        }

        protected void fetchConfig(DataMap config) {

            if (config.containsKey(Consts.KEY_WEATHER_UPDATE_TIME)) {
                mWeatherInfoReceivedTime = config.getLong(Consts.KEY_WEATHER_UPDATE_TIME);
            }

            if (config.containsKey(Consts.KEY_WEATHER_CONDITION)) {
                String cond = config.getString(Consts.KEY_WEATHER_CONDITION);
                if (TextUtils.isEmpty(cond)) {
                    mWeatherCondition = null;
                } else {
                    mWeatherCondition = cond;
                }
            }
            log(mWeatherCondition);

            if (config.containsKey(Consts.KEY_WEATHER_HIGH)) {
                mTemperatureHigh = config.getDouble(Consts.KEY_WEATHER_HIGH);

            }

            if (config.containsKey(Consts.KEY_WEATHER_LOW)) {
                mTemperatureLow = config.getDouble(Consts.KEY_WEATHER_LOW);

            }

            if(config.containsKey(Consts.KEY_WEATHER_CONDITION_IMAGE)){
               final Asset iconAsset = config.getAsset(Consts.KEY_WEATHER_CONDITION_IMAGE);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        mWeatherConditionDrawable = loadBitmapFromAsset(iconAsset);
                        mWeatherConditionDrawable = Bitmap.createScaledBitmap(
                                mWeatherConditionDrawable, 70, 70, false);
                    }
                }).start();



            }

//TODO: add settings activity in main app to control the communication time interval

            if (config.containsKey(Consts.KEY_CONFIG_REQUIRE_INTERVAL)) {
                mRequireInterval = config.getInt(Consts.KEY_CONFIG_REQUIRE_INTERVAL);
            }

            invalidate();
        }




        protected void requireWeatherInfo() {
            if (!mGoogleApiClient.isConnected())
                return;

            log("I am connected");

            long timeMs = System.currentTimeMillis();

            // The weather info is still up to date.
            if ((timeMs - mWeatherInfoReceivedTime) <= mRequireInterval)
                return;

            // Try once in a min.
            //TODO: hide it
//            if ((timeMs - mWeatherInfoRequiredTime) <= DateUtils.MINUTE_IN_MILLIS)
//                return;

            log("and it is ready to send a message to parent App");
            mWeatherInfoRequiredTime = timeMs;
            Wearable.MessageApi.sendMessage(mGoogleApiClient, "test", Consts.PATH_WEATHER_REQUIRE, null)
                    .setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                        @Override
                        public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                            log("SendRequireMessage:" + sendMessageResult.getStatus());
                        }
                    });
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }


            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onConnected(Bundle bundle) {
            log("Connected: " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            requireWeatherInfo();
        }

        @Override
        public void onConnectionSuspended(int i) {
            log("ConnectionSuspended: " + i);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(Consts.PATH_WEATHER_INFO) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        log("onDataChanged: " + dataMap);

                        fetchConfig(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            log("ConnectionFailed: " + connectionResult);
        }
    }
}
