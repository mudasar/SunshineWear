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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class RoundWatchFace extends CanvasWatchFaceService {
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

    public RoundWatchFace() {

    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<RoundWatchFace.Engine> mWeakReference;

        public EngineHandler(RoundWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            RoundWatchFace.Engine engine = mWeakReference.get();
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
        protected String mWeatherConditionResourceName;
      //  protected int mTimeUnit = ConverterUtil.TIME_UNIT_12;

        protected int mRequireInterval;
        protected int mTemperature = Integer.MAX_VALUE;
        protected int mTemperatureScale;
        protected long mWeatherInfoReceivedTime;
        protected long mWeatherInfoRequiredTime;

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
            mGoogleApiClient = new GoogleApiClient.Builder(RoundWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(RoundWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = RoundWatchFace.this.getResources();
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
            mLowTempPaint = new Paint();

            mTime = new Time();
            mResources = RoundWatchFace.this.getResources();
            mAssets = RoundWatchFace.this.getAssets();
            mRequireInterval = mResources.getInteger(R.integer.weather_default_require_interval);
            mWeatherInfoRequiredTime = System.currentTimeMillis() - (DateUtils.SECOND_IN_MILLIS * 58);
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
            RoundWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            RoundWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = RoundWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

//            float textSize = resources.getDimension(isRound
//                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);


            float dateTextSize = 0.0f;
            float textSize = 0.0f;

            if (isRound) {
                textSize = resources.getDimension(R.dimen.digital_text_size_round);
                dateTextSize = resources.getDimension(R.dimen.digital_text_date_size_round);

            } else {
                textSize = resources.getDimension(R.dimen.digital_text_size);
                dateTextSize = resources.getDimension(R.dimen.digital_text_date_size);

            }

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
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
            Resources resources = RoundWatchFace.this.getResources();
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
            Log.d(RoundWatchFace.this.getClass().getSimpleName(), message);
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
//
//            if (config.containsKey(Consts.KEY_WEATHER_TEMPERATURE)) {
//                mTemperature = config.getInt(Consts.KEY_WEATHER_TEMPERATURE);
//                if (mTemperatureScale != ConverterUtil.FAHRENHEIT) {
//                    mTemperature = ConverterUtil.convertFahrenheitToCelsius(mTemperature);
//                }
//            }
//
//            if (config.containsKey(Consts.KEY_WEATHER_SUNRISE)) {
//                mSunriseTime.set(config.getLong(Consts.KEY_WEATHER_SUNRISE) * 1000);
//                log("SunriseTime: " + mSunriseTime);
//            }
//
//            if (config.containsKey(Consts.KEY_WEATHER_SUNSET)) {
//                mSunsetTime.set(config.getLong(Consts.KEY_WEATHER_SUNSET) * 1000);
//                log("SunsetTime: " + mSunsetTime);
//            }
//
//            if (config.containsKey(Consts.KEY_CONFIG_TEMPERATURE_SCALE)) {
//                int scale = config.getInt(Consts.KEY_CONFIG_TEMPERATURE_SCALE);
//
//                if (scale != mTemperatureScale) {
//                    if (scale == ConverterUtil.FAHRENHEIT) {
//                        mTemperature = ConverterUtil.convertCelsiusToFahrenheit(mTemperature);
//                    } else {
//                        mTemperature = ConverterUtil.convertFahrenheitToCelsius(mTemperature);
//                    }
//                }
//
//                mTemperatureScale = scale;
//            }

            if (config.containsKey(Consts.KEY_CONFIG_THEME)) {
                mTheme = config.getInt(Consts.KEY_CONFIG_THEME);
            }

//            if (config.containsKey(Consts.KEY_CONFIG_TIME_UNIT)) {
//                mTimeUnit = config.getInt(Consts.KEY_CONFIG_TIME_UNIT);
//            }

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
            for (int i = 0; i < dataEvents.getCount(); i++) {
                DataEvent event = dataEvents.get(i);
                DataMap dataMap = DataMap.fromByteArray(event.getDataItem().getData());
                log("onDataChanged: " + dataMap);

                fetchConfig(dataMap);
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            log("ConnectionFailed: " + connectionResult);
        }
    }
}
