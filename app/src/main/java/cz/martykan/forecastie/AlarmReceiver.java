package cz.martykan.forecastie;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

import cz.martykan.forecastie.activities.MainActivity;
import cz.martykan.forecastie.widgets.AbstractWidgetProvider;
import cz.martykan.forecastie.widgets.DashClockWeatherExtension;

public class AlarmReceiver extends BroadcastReceiver {

    Context context;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String interval = sp.getString("refreshInterval", "1");
            if (!interval.equals("0")) {
                setRecurringAlarm(context);
                getWeather();
            }
        } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            // Get weather if last attempt failed
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String interval = sp.getString("refreshInterval", "1");
            if (!interval.equals("0") && sp.getBoolean("backgroundRefreshFailed", false)) {
                getWeather();
            }
        } else {
            getWeather();
        }
    }

    private void getWeather() {
        Log.d("Alarm", "Recurring alarm; requesting download service.");
        boolean failed;
        if (isNetworkAvailable()) {
            failed = false;
            new GetWeatherTask().execute();
            new GetLongTermWeatherTask().execute();
        } else {
            failed = true;
        }
        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putBoolean("backgroundRefreshFailed", failed);
        editor.apply();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public class GetWeatherTask extends AsyncTask<String, String, Void> {

        protected void onPreExecute() {

        }

        @Override
        protected Void doInBackground(String... params) {
            String result = "";
            try {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                String language = Locale.getDefault().getLanguage();
                if(language.equals("cs")) { language = "cz"; }
                String apiKey = sp.getString("apiKey", context.getResources().getString(R.string.apiKey));
                URL url = new URL("http://api.openweathermap.org/data/2.5/weather?q=" + URLEncoder.encode(sp.getString("city", Constants.DEFAULT_CITY), "UTF-8") + "&lang="+ language +"&appid=" + apiKey);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                BufferedReader r = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));

                if(urlConnection.getResponseCode() == 200) {
                    String line = null;
                    while ((line = r.readLine()) != null) {
                        result += line + "\n";
                    }
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("lastToday", result);
                    editor.apply();
                    MainActivity.saveLastUpdateTime(sp);
                }
                else {
                    // Connection problem
                }
            } catch (IOException e) {
                // No connection
            }
            return null;
        }

        protected void onPostExecute(Void v) {
            // Update widgets
            AbstractWidgetProvider.updateWidgets(context);
            DashClockWeatherExtension.updateDashClock(context);
        }
    }

    class GetLongTermWeatherTask extends AsyncTask<String, String, Void> {

        protected void onPreExecute() {

        }

        @Override
        protected Void doInBackground(String... params) {
            String result = "";
            try {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                String language = Locale.getDefault().getLanguage();
                if(language.equals("cs")) { language = "cz"; }
                String apiKey = sp.getString("apiKey", context.getResources().getString(R.string.apiKey));
                URL url = new URL("http://api.openweathermap.org/data/2.5/forecast?q=" + URLEncoder.encode(sp.getString("city", Constants.DEFAULT_CITY), "UTF-8") + "&lang="+ language +"&mode=json&appid=" + apiKey);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                BufferedReader r = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));

                if(urlConnection.getResponseCode() == 200) {
                    String line = null;
                    while ((line = r.readLine()) != null) {
                        result += line + "\n";
                    }
                    SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                    editor.putString("lastLongterm", result);
                    editor.apply();
                }
                else {
                    // Connection problem
                }
            } catch (IOException e) {
                // No connection
            }
            return null;
        }

        protected void onPostExecute(Void v) {

        }
    }

    public class GetLocationTask extends AsyncTask <String, String, Void> {
        private static final String TAG = "GetLocationTask";

        private final double MAX_RUNNING_TIME = 30 * 1000;

        private LocationManager locationManager;
        private BackgroundLocationListener locationListener;

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "Trying to determine location...");
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            locationListener = new BackgroundLocationListener();
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // Only uses 'network' location, as asking the GPS every time would drain too much battery
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
                } else {
                    Log.d(TAG, "'Network' location is not enabled. Cancelling determining location.");
                    onPostExecute(null);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Couldn't request location updates. Probably this is an Android (>M) runtime permissions issue ", e);
            }
        }

        @Override
        protected Void doInBackground(String... params) {
            long startTime = System.currentTimeMillis();
            long runningTime = 0;
            while (locationListener.getLocation() == null && runningTime < MAX_RUNNING_TIME) { // Give up after 30 seconds
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Error occurred while waiting for location update", e);
                }
                runningTime = System.currentTimeMillis() - startTime;
            }
            if (locationListener.getLocation() == null) {
                Log.d(TAG, String.format("Couldn't determine location in less than %s seconds", MAX_RUNNING_TIME / 1000));
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Location location = locationListener.getLocation();
            if (location != null) {
                Log.d(TAG, String.format("Determined location: latitude %f - longitude %f", location.getLatitude(), location.getLongitude()));
            } else {
                Log.e(TAG, "Couldn't determine location");
            }
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException e) {
                Log.e(TAG, "Couldn't remove location updates. Probably this is an Android (>M) runtime permissions", e);
            }
        }

        public class BackgroundLocationListener implements LocationListener {
            private static final String TAG = "LocationListener";
            private Location location;

            @Override
            public void onLocationChanged(Location location) {
                this.location = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {

            }

            @Override
            public void onProviderEnabled(String provider) {

            }

            @Override
            public void onProviderDisabled(String provider) {

            }

            public Location getLocation() {
                return location;
            }
        }
    }

    private static long intervalMillisForRecurringAlarm(String intervalPref) {
        int interval = Integer.parseInt(intervalPref);
        switch (interval) {
            case 0:
                return 0; // special case for cancel
            case 15:
                return AlarmManager.INTERVAL_FIFTEEN_MINUTES;
            case 30:
                return AlarmManager.INTERVAL_HALF_HOUR;
            case 1:
                return AlarmManager.INTERVAL_HOUR;
            case 12:
                return AlarmManager.INTERVAL_HALF_DAY;
            case 24:
                return AlarmManager.INTERVAL_DAY;
            default: // cases 2 and 6 (or any number of hours)
                return interval * 3600000;
        }
    }

    public static void setRecurringAlarm(Context context) {
        String intervalPref = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("refreshInterval", "1");
        Intent refresh = new Intent(context, AlarmReceiver.class);
        PendingIntent recurringRefresh = PendingIntent.getBroadcast(context,
                0, refresh, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarms = (AlarmManager) context.getSystemService(
                Context.ALARM_SERVICE);
        long intervalMillis = intervalMillisForRecurringAlarm(intervalPref);
        if (intervalMillis == 0) {
            // Cancel previous alarm
            alarms.cancel(recurringRefresh);
        } else {
            alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMillis,
                    intervalMillis,
                    recurringRefresh);
        }
    }
}
