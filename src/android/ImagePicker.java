package com.spoon.imagepicker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class ImagePicker extends CordovaPlugin {
    private static final String ACTION_GET_PICTURES = "getPictures";
    private static final String ACTION_HAS_READ_PERMISSION = "hasReadPermission";
    private static final String ACTION_REQUEST_READ_PERMISSION = "requestReadPermission";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SELECT_PICTURE = 200;

    private static final String CROSS_USER_PROFILE_ACCESS_DENIED = "Cannot access file. (-1)";

    private CallbackContext callbackContext;
    private int maxImageCount;
    private LinearLayout layout = null;

    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        if (ACTION_HAS_READ_PERMISSION.equals(action)) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, hasReadPermission()));
            return true;

        } else if (ACTION_REQUEST_READ_PERMISSION.equals(action)) {
            requestReadPermission();
            return true;

        } else if (ACTION_GET_PICTURES.equals(action)) {
            final JSONObject params = args.getJSONObject(0);
            this.maxImageCount = params.has("maximumImagesCount") ? params.getInt("maximumImagesCount") : 20;

            Intent imagePickerIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            imagePickerIntent.setType("image/*");
            imagePickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            cordova.startActivityForResult(this, imagePickerIntent, SELECT_PICTURE);
            this.showMaxLimitWarning();
            return true;
        }
        return false;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        new RetrieveData(requestCode, resultCode, data).execute();
    }

    class RetrieveData extends AsyncTask<Void, Void, Void> {
        int requestCode;
        int resultCode;
        Intent data;

        public RetrieveData(int requestCode, int resultCode, Intent data) {
            this.requestCode = requestCode;
            this.resultCode = resultCode;
            this.data = data;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            showLoader();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ArrayList<String> fileURIs = new ArrayList<>();
                if (requestCode == SELECT_PICTURE) {
                    if (data.getData() != null) {
                        Uri uri = data.getData();
                        String path = ImagePicker.this.copyFileToInternalStorage(uri, "");
                        if (path.equals("-1")) {
                            callbackContext.error(CROSS_USER_PROFILE_ACCESS_DENIED);
                            return null;
                        }
                        fileURIs.add(path);
                    } else {
                        ClipData clip = data.getClipData();
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            Uri uri = clip.getItemAt(i).getUri();
                            String path = ImagePicker.this.copyFileToInternalStorage(uri, "");
                            if (path.equals("-1")) {
                                callbackContext.error(CROSS_USER_PROFILE_ACCESS_DENIED);
                                return null;
                            }
                            fileURIs.add(path);
                            if (i + 1 > ImagePicker.this.maxImageCount - 1) {
                                break;
                            }
                        }
                    }
                }
                JSONArray res = new JSONArray(fileURIs);
                callbackContext.success(res);

            } else if (resultCode == Activity.RESULT_CANCELED && data != null) {
                String error = data.getStringExtra("ERRORMESSAGE");
                callbackContext.error(error);
            } else if (resultCode == Activity.RESULT_CANCELED) {
                JSONArray res = new JSONArray();
                callbackContext.success(res);
            } else {
                callbackContext.error("No images selected");
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            hideLoader();
        }
    }

    private void showLoader() {
        Context context = cordova.getActivity().getApplicationContext();
        this.layout = new LinearLayout(context);
        this.layout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        );
        cordova.getActivity().addContentView(layout, params);
        cordova.getActivity().getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        );
        ProgressBar loader = new ProgressBar(context);
        this.layout.addView(loader);
    }

    private void hideLoader() {
        if (this.layout != null) {
            this.layout.removeAllViews();
            this.layout = null;
            cordova.getActivity().getWindow()
              .clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    /**
    * Choosing a picture launches another Activity, so we need to implement the
    * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
    * before we get the launched Activity's result.
    *
    * @see http://cordova.apache.org/docs/en/dev/guide/platforms/android/plugin.html#launching-other-activities
    */
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
    }


    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        // For now we just have one permission, so things can be kept simple...
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            callbackContext.success(1);
        } else {
            callbackContext.success(0);
        }
    }

    @SuppressLint("InlinedApi")
    private boolean hasReadPermission() {
        return Build.VERSION.SDK_INT < 23 ||
          PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    @SuppressLint("InlinedApi")
    private void requestReadPermission() {
        if (!hasReadPermission()) {
            cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            return;
        }
        callbackContext.success(1);
    }

    private String copyFileToInternalStorage(Uri uri, String newDirName) {
        Uri returnUri = uri;
        Cursor returnCursor = null;
        try {
            returnCursor = cordova.getActivity().getContentResolver().query(
                returnUri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
            );
        } catch (SecurityException se) {
            String toastMsg = "For the moment cross sharing of media between work profile and personal profile is not supported.";
            (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show();
            Log.d("error", se.getMessage());
            return "-1";
        }

        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        String name = returnCursor.getString(nameIndex);

        File output;
        if (!newDirName.equals("")) {
            File dir = new File(cordova.getContext().getFilesDir() + "/" + newDirName);
            if (!dir.exists()) {
                dir.mkdir();
            }
            output = new File(cordova.getContext().getFilesDir() + "/" + newDirName + "/" + name);
        } else {
            output = new File(cordova.getContext().getFilesDir() + "/" + name);
        }
        try {
            InputStream inputStream = cordova.getActivity().getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(output);
            int read = 0;
            int bufferSize = 1024;
            final byte[] buffers = new byte[bufferSize];
            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }
            inputStream.close();
            outputStream.close();

        } catch (Exception e) {
            Log.e("copyToInternalStorage ", e.getMessage());
        }

        return output.getPath();
    }

    private void showMaxLimitWarning() {
        String toastMsg = "Only the first " + this.maxImageCount + " images selected will be taken.";
        (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show();
    }
}