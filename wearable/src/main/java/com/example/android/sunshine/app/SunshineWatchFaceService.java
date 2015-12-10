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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    // Log tag.
    private static final String LOG_TAG = "SunshineWear";

    // Typefaces used.
    private static final Typeface TYPEFACE_ROBOTO =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface TYPEFACE_ROBOTO_CONDENSED =
            Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        // Keys to extract weather data from data map sent by the handheld.
        private static final String PATH_WITH_WEATHER = "/sunshine_watch_face/Weather";
        private static final String MAX_TEMP_KEY = "com.example.android.sunshine.app.max_temp.key";
        private static final String MIN_TEMP_KEY = "com.example.android.sunshine.app.min_temp.key";
        private static final String WEATHER_IMAGE_KEY = "com.example.android.sunshine.app.weather_image.key";

        static final String COLON_STRING = ":";

        // Alpha value for drawing time when in mute mode.
        static final int MUTE_ALPHA = 100;

        // Alpha value for drawing time when not in mute mode.
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        // How often {@link #mUpdateTimeHandler} ticks in milliseconds.
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /**
         * Un-registering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        // Paint objects.
        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mAmPmPaint;
        Paint mColonPaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Paint mWeatherBitmapPaint;

        // Color constants for various paint objects.
        int mColorTextInteractive;
        int mColorTextAmbient;
        int mColorTextColon;
        int mColorTextAmPm;
        int mColorTextDate;
        int mColorTextMinTemperature;
        int mColorBackgroundInteractive;
        int mColorBackgroundAmbient;

        // X and Y offsets.
        float mXOffsetTime;
        float mYOffsetTime;
        float mXOffsetDate;
        float mYOffsetDate;
        float mXOffsetTemperature;
        float mYOffsetTemperature;

        // Weather icon bitmap, max and min temperatures received from the handheld.
        // TODO: Testing values, remove later.
        int mMaxTemp = 82;
        int mMinTemp = -25;
        Bitmap mWeatherBitmap;

        // AM/PM strings.
        String mAmString;
        String mPmString;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        boolean mMute;
        boolean mShouldDrawColons;
        float mColonWidth;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;

        // Google api client for using wearable data layer.
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                            Log.v(LOG_TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onCreate");
            }
            super.onCreate(holder);

            // Set the watch face style.
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            // Get strings from resources.
            Resources resources = SunshineWatchFaceService.this.getResources();
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            // Get colors from resources.
            mColorTextInteractive = resources.getColor(R.color.color_text_interactive);
            mColorTextAmbient = resources.getColor(R.color.color_text_ambient);
            mColorTextColon = resources.getColor(R.color.color_text_colon);
            mColorTextAmPm = resources.getColor(R.color.color_text_am_pm);
            mColorTextDate = resources.getColor(R.color.color_text_date);
            mColorTextMinTemperature = resources.getColor(R.color.color_text_min_temperature);
            mColorBackgroundInteractive = resources.getColor(R.color.color_background_interactive);
            mColorBackgroundAmbient = resources.getColor(R.color.color_background_ambient);

            // Create paint objects and assign colors.
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mColorBackgroundInteractive);
            mHourPaint = createTextPaint(mColorTextInteractive);
            mMinutePaint = createTextPaint(mColorTextInteractive, TYPEFACE_ROBOTO_CONDENSED);
            mAmPmPaint = createTextPaint(mColorTextAmPm);
            mColonPaint = createTextPaint(mColorTextColon);
            mDatePaint = createTextPaint(mColorTextDate);
            mMaxTempPaint = createTextPaint(mColorTextInteractive);
            mMinTempPaint = createTextPaint(mColorTextMinTemperature, TYPEFACE_ROBOTO_CONDENSED);
            mWeatherBitmapPaint = new Paint();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            // TODO: Only for testing, remove and init to null later.
            mWeatherBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_clear);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, TYPEFACE_ROBOTO);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getDateFormat(SunshineWatchFaceService.this);
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffsetTime = resources.getDimension(isRound
                    ? R.dimen.x_offset_time_round : R.dimen.x_offset_time);
            mYOffsetTime = resources.getDimension(isRound
                    ? R.dimen.y_offset_time_round : R.dimen.y_offset_time);
            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.x_offset_date_round : R.dimen.x_offset_date);
            mYOffsetDate = resources.getDimension(isRound
                    ? R.dimen.y_offset_date_round : R.dimen.y_offset_date);
            mXOffsetTemperature = resources.getDimension(isRound
                    ? R.dimen.x_offset_temperature_round : R.dimen.x_offset_temperature);
            mYOffsetTemperature = resources.getDimension(isRound
                    ? R.dimen.y_offset_temperature_round : R.dimen.y_offset_temperature);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.text_size_time_round : R.dimen.text_size_time);
            float dateTextSize = resources.getDimension(R.dimen.text_size_date);
            float amPmTextSize = resources.getDimension(isRound
                    ? R.dimen.text_size_am_pm_round : R.dimen.text_size_am_pm);
            float temperatureTextSize = resources.getDimension(isRound
                    ? R.dimen.text_size_temperature_round : R.dimen.text_size_temperature);

            // Set the text sizes to the paint objects.
            mHourPaint.setTextSize(timeTextSize);
            mMinutePaint.setTextSize(timeTextSize);
            mColonPaint.setTextSize(timeTextSize);
            mAmPmPaint.setTextSize(amPmTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mMaxTempPaint.setTextSize(temperatureTextSize);
            mMinTempPaint.setTextSize(temperatureTextSize);

            // Get the colon width.
            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onAmbientModeChanged: " + inAmbientMode);
            }

            // Adjust paint colors based on interactive or ambient mode.
            adjustPaintColorToCurrentMode(mBackgroundPaint,mColorBackgroundInteractive, mColorBackgroundAmbient);
            adjustPaintColorToCurrentMode(mHourPaint, mColorTextInteractive, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mMinutePaint, mColorTextInteractive, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mColonPaint, mColorTextColon, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mAmPmPaint, mColorTextAmPm, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mDatePaint, mColorTextDate, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mMaxTempPaint, mColorTextInteractive, mColorTextAmbient);
            adjustPaintColorToCurrentMode(mMinTempPaint, mColorTextMinTemperature, mColorTextAmbient);

            // Adjust anti alias.
            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mMaxTempPaint.setAntiAlias(antiAlias);
                mMinTempPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mAmPmPaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mDatePaint.setAlpha(alpha);
                mMaxTempPaint.setAlpha(alpha);
                mMinTempPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        private void updatePaintIfInteractive(Paint paint, int interactiveColor) {
            if (!isInAmbientMode() && paint != null) {
                paint.setColor(interactiveColor);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw the hours.
            float x = mXOffsetTime;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString, x, mYOffsetTime, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, mYOffsetTime, mColonPaint);
            }
            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffsetTime, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            // If in interactive, un-muted and 12-hour mode, draw AM/PM.
            if ((isInAmbientMode() || mMute) && !is24Hour) {
                x += mColonWidth;
                canvas.drawText(getAmPmString(mCalendar.get(Calendar.AM_PM)), x, mYOffsetTime, mAmPmPaint);
            }

            // Only render the day of week, date, weather icon, max and min temperatures if there
            // is no peek card, so they do not bleed into each other.
            if (getPeekCardPosition().isEmpty()) {
                // Draw only the max and min temp in ambient mode.
                if (isInAmbientMode()) {
                    // Check if weather data was received from handheld.
                    if(mWeatherBitmap != null) {
                        // Max temp
                        x = mXOffsetTime;
                        String maxTemp = String.format(getString(R.string.format_temperature), String.valueOf(mMaxTemp));
                        canvas.drawText(maxTemp, x, mYOffsetTemperature - 5, mMaxTempPaint);

                        // Min temp
                        x += mMaxTempPaint.measureText(maxTemp) + mColonWidth;
                        String minTemp = String.format(getString(R.string.format_temperature), String.valueOf(mMinTemp));
                        canvas.drawText(minTemp, x, mYOffsetTemperature - 5, mMinTempPaint);
                    }
                } else {
                    // Day of week.
                    x = mXOffsetDate;
                    canvas.drawText(mDayOfWeekFormat.format(mDate), x, mYOffsetDate, mDatePaint);

                    // Date
                    x += mDatePaint.measureText(mDayOfWeekFormat.format(mDate));
                    canvas.drawText(", " + mDateFormat.format(mDate), x, mYOffsetDate, mDatePaint);

                    // Show weather data only if weather update has been received from handheld.
                    if(mWeatherBitmap != null) {
                        // Weather icon.
                        x = mXOffsetTemperature;
                        canvas.drawBitmap(mWeatherBitmap, x, mYOffsetTemperature - 50, mWeatherBitmapPaint);

                        // Max temp.
                        x += mWeatherBitmap.getWidth() + 1.5 * mColonWidth;
                        String maxTemp = String.format(getString(R.string.format_temperature), String.valueOf(mMaxTemp));
                        canvas.drawText(maxTemp, x, mYOffsetTemperature, mMaxTempPaint);

                        // Min temp.
                        x += mMaxTempPaint.measureText(maxTemp) + mColonWidth;
                        String minTemp = String.format(getString(R.string.format_temperature), String.valueOf(mMinTemp));
                        canvas.drawText(minTemp, x, mYOffsetTemperature, mMinTempPaint);
                    }
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "updateTimer");
            }
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

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "onDataChanged");

            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(PATH_WITH_WEATHER)) {
                    continue;
                }

                // Get the weather data map.
                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap weather = dataMapItem.getDataMap();

                // Extract temperatures.
                mMaxTemp = weather.getInt(MAX_TEMP_KEY);
                mMinTemp = weather.getInt(MIN_TEMP_KEY);

                // Extract bitmap from asset on background thread.
                new LoadBitmapAsyncTask().execute(weather.getAsset(WEATHER_IMAGE_KEY));
            }
        }

        /*
         * Extracts {@link android.graphics.Bitmap} data from the
         * {@link com.google.android.gms.wearable.Asset}
         */
        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... params) {
                if(params.length > 0) {
                    Asset asset = params[0];
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.e(LOG_TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);
                } else {
                    Log.e(LOG_TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if(bitmap != null) {
                    Log.d(LOG_TAG, "Received bitmap");
                    mWeatherBitmap = bitmap;
                }
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            Log.d(LOG_TAG, "onConnected: " + connectionHint);

            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(LOG_TAG, "onConnectionFailed: " + result);
        }
    }
}
