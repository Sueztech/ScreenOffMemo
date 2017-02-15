package com.sueztech.screenoffmemo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.net.URISyntaxException;

import eu.chainfire.libsuperuser.Shell;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.widget.Toast.makeText;

public class MainActivity extends AppCompatActivity {

    static final String LOG_TAG = "SOM";

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int PICK_FILES_REQUEST_CODE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        checkPermissions();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Snackbar.make(findViewById(R.id.toolbar), "No settings yet, stay tuned!",
                    Snackbar.LENGTH_LONG).show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_CODE: {
                if (grantResults.length <= 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    makeText(this, getText(R.string.err_permission_denied),
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case PICK_FILES_REQUEST_CODE: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, data.getData()));
                }
            }
        }
    }

    public void installAddon(@SuppressWarnings("UnusedParameters") View view) {
        try {
            startActivity(Intent.parseUri("market://details?id=com.tushar.cmspen2",
                    Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (URISyntaxException e) {
            Snackbar.make(findViewById(R.id.toolbar), getText(R.string.err_unexpected),
                    Snackbar.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    public void requestRoot(@SuppressWarnings("UnusedParameters") View view) {
        AsyncTask<Void, Void, Boolean> asyncTask = new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... voids) {
                return Shell.SU.available();
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result) {
                    Snackbar.make(findViewById(R.id.toolbar), R.string.root_yes,
                            Snackbar.LENGTH_SHORT).show();
                } else {
                    Snackbar.make(findViewById(R.id.toolbar), R.string.root_no,
                            Snackbar.LENGTH_SHORT).show();
                }
            }

        };
        asyncTask.execute();
    }

    public void viewFiles(@SuppressWarnings("UnusedParameters") View view) {
        startActivity(new Intent(this, ListActivity.class));
    }
}