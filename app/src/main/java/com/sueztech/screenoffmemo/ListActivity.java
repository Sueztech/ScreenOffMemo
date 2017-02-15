package com.sueztech.screenoffmemo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import static android.content.Intent.ACTION_VIEW;

public class ListActivity extends AppCompatActivity implements AdapterView.OnItemClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        ArrayList<ActionMemo> actionMemos = new ArrayList<>();

        File actionMemoDir = new File(Environment.getExternalStorageDirectory(),
                "SnoteData/Action memo");
        for (File f : actionMemoDir.listFiles()) {
            if (f.isFile()) {
                actionMemos.add(new ActionMemo(f));
            }
        }

        Collections.sort(actionMemos, new Comparator<ActionMemo>() {
            @Override
            public int compare(ActionMemo actionMemo, ActionMemo t1) {
                return actionMemo.getDate().compareTo(t1.getDate());
            }
        });

        ArrayAdapter<ActionMemo> mAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, actionMemos);

        ListView mListView = (ListView) findViewById(R.id.memoList);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
        ActionMemo actionMemo = (ActionMemo) adapterView.getItemAtPosition(i);
        Intent openIntent = new Intent(ACTION_VIEW);
        openIntent.setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/document/"
                        + "primary:SnoteData%2FAction%20memo%2F" + actionMemo.getName()),
                "application/octet-stream");
        startActivity(openIntent);
    }
}
