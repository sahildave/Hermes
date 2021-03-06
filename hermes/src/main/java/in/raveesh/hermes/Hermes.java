package in.raveesh.hermes;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import java.io.IOException;

import in.raveesh.hermes.receivers.ExponentialBackoffReceiver;

/**
 * Created by Raveesh on 31/03/15.
 */
public class Hermes {

    public final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";
    public static final int DEFAULT_BACKOFF = 60000;
    public static String SHARED_PREFERENCES_FILENAME = "HermesFileName";

    private static String regID;
    private static GoogleCloudMessaging gcm;
    private static String SENDER_ID = "";
    private static SharedPreferences sharedPreferences;
    public static RegistrationCallback CALLBACK;
    private static int delay = DEFAULT_BACKOFF;


    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     */
    public static boolean checkPlayServices(Context context) {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                Log.d("Hermes", "Google Play Services is not available, but the issue is User Recoverable");
                if (context instanceof Activity) {
                    GooglePlayServicesUtil.getErrorDialog(resultCode, (Activity) context, PLAY_SERVICES_RESOLUTION_REQUEST).show();
                }
            } else {
                /**
                 * TODO: Handle all issue related to non User recoverable error
                 */
                Log.d("Hermes", "Non user recoverable error while checking for Play Services");
            }
            return false;
        }
        return true;
    }

    public static void register(Context context, String senderID) throws GcmRegistrationException {
        register(context, senderID, null);
    }

    public static void register(Context context, String senderID, RegistrationCallback callback, SharedPreferences prefs) throws GcmRegistrationException {
        setGCMPreferences(prefs);
        register(context, senderID, callback);
    }

    public static void register(Context context, String senderID, RegistrationCallback callback) throws GcmRegistrationException {
        if (callback != null){
            CALLBACK = callback;
        }

        Log.d("Hermes", "Registering... ");

        SENDER_ID = senderID;
        if (checkPlayServices(context)) {
            gcm = GoogleCloudMessaging.getInstance(context);
            regID = getRegIdFromSharedPrefs(context);
            if (regID.isEmpty()) {
                registerInBackground(context);
            }
            else if (callback != null){
                callback.registrationComplete(regID);
            }
        }
        else{
            throw new GcmRegistrationException("Play Services is not available on the device");
        }
    }

    /**
     * Gets the current registration ID for application on GCM service.
     * <p>
     * If result is empty, the app needs to register.
     *
     * @return registration ID, or empty string if there is no existing
     *         registration ID.
     */
    private static String getRegIdFromSharedPrefs(Context context) {
        final SharedPreferences prefs = getGCMPreferences(context);
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId == null || registrationId.isEmpty()) {
            Log.d("Hermes", "Registration not found.");
            return "";
        }
        Log.d("Hermes", "Got Reg Id from prefs");

        // Check if app was updated; if so, it must clear the registration ID
        // since the existing registration ID is not guaranteed to work with
        // the new app version.
        int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(context);
        if (registeredVersion != currentVersion) {
            Log.d("Hermes", "App version changed.");
            return "";
        }
        return registrationId;
    }

    public static void setGCMPreferences(SharedPreferences prefs){
        sharedPreferences = prefs;
    }

    /**
     * @return Application's {@code SharedPreferences}.
     */
    public static SharedPreferences getGCMPreferences(Context context) {
        if (sharedPreferences  == null) {
            sharedPreferences = context.getSharedPreferences(SHARED_PREFERENCES_FILENAME, Context.MODE_PRIVATE);
        }
        return sharedPreferences;
    }

    /**
     * @return Application's version code from the {@code PackageManager}.
     */
    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    /**
     * Registers the application with GCM servers asynchronously.
     * <p>
     * Stores the registration ID and app versionCode in the application's
     * shared preferences.
     */
    private static void registerInBackground(final Context context) {
        if (CALLBACK != null){
            CALLBACK.registrationProcessStarted();
        }
        Log.d("Hermes", "Registers the application with GCM servers asynchronously");

        new AsyncTask<Object, Void, String>() {
            @Override
            protected String doInBackground(Object[] params) {
                String msg = "";
                try {
                    if (gcm == null) {
                        gcm = GoogleCloudMessaging.getInstance(context);
                    }
                    regID = gcm.register(SENDER_ID);
                    msg = "Device registered, registration ID=" + regID;
                    Log.d("Hermes", "From Async - "+msg);
                    // You should send the registration ID to your server over HTTP,
                    // so it can use GCM/HTTP or CCS to send messages to your app.
                    // The request to your server should be authenticated if your app
                    // is using accounts.
                    sendRegistrationIdToBackend();

                    // For this demo: we don't need to send it because the device
                    // will send upstream messages to a server that echo back the
                    // message using the 'from' address in the message.

                    // Persist the registration ID - no need to register again.
                    storeRegistrationId(context, regID);
                    if (CALLBACK != null){
                        CALLBACK.registrationComplete(regID);
                    }
                } catch (IOException ex) {
                    msg = "Error :" + ex.getMessage();
                    // If there is an error, don't just keep trying to register.
                    // Require the user to click a button again, or perform
                    // exponential back-off.
                    ExponentialBackoffReceiver.attemptRegistration(context,
                            Hermes.getDelay(),
                            Hermes.getSenderId());
                }
                return msg;
            }


            /**
             * Stores the registration ID and app versionCode in the application's
             * {@code SharedPreferences}.
             *
             * @param context application's context.
             * @param regId registration ID
             */
            private void storeRegistrationId(Context context, String regId) {
                final SharedPreferences prefs = getGCMPreferences(context);
                int appVersion = getAppVersion(context);
                Log.d("Hermes", "Saving regId on app version " + appVersion);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PROPERTY_REG_ID, regId);
                editor.putInt(PROPERTY_APP_VERSION, appVersion);
                editor.apply();
            }

            /**
             * Sends the registration ID to your server over HTTP, so it can use GCM/HTTP
             * or CCS to send messages to your app. Not needed for this demo since the
             * device sends upstream messages to a server that echoes back the message
             * using the 'from' address in the message.
             */
            private void sendRegistrationIdToBackend() {
                // Your implementation here.
            }

        }.execute(null, null, null);
    }

    public static void pause(Context context){
        CALLBACK = null;
    }

    public static void setDelay(int delay) {
        Hermes.delay = delay;
    }

    public static int getDelay(){
        return delay;
    }

    public static String getSenderId(){
        return SENDER_ID;
    }
}
