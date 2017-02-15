package com.sueztech.screenoffmemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.samsung.android.sdk.SsdkUnsupportedException;
import com.samsung.android.sdk.pen.Spen;
import com.samsung.android.sdk.pen.SpenSettingEraserInfo;
import com.samsung.android.sdk.pen.SpenSettingPenInfo;
import com.samsung.android.sdk.pen.document.SpenInvalidPasswordException;
import com.samsung.android.sdk.pen.document.SpenNoteDoc;
import com.samsung.android.sdk.pen.document.SpenPageDoc;
import com.samsung.android.sdk.pen.document.SpenUnsupportedTypeException;
import com.samsung.android.sdk.pen.document.SpenUnsupportedVersionException;
import com.samsung.android.sdk.pen.engine.SpenColorPickerListener;
import com.samsung.android.sdk.pen.engine.SpenSimpleSurfaceView;
import com.samsung.android.sdk.pen.engine.SpenTouchListener;
import com.samsung.android.sdk.pen.settingui.SpenSettingEraserLayout;
import com.samsung.android.sdk.pen.settingui.SpenSettingPenLayout;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MemoActivity extends AppCompatActivity
        implements View.OnClickListener, DialogInterface.OnClickListener,
        DialogInterface.OnCancelListener, SpenColorPickerListener, SpenPageDoc.HistoryListener,
        SpenSettingEraserLayout.EventListener, SpenTouchListener {

    private static final String SPEN_INTENT = "com.samsung.pen.INSERT";
    private String strFilePath;
    private BroadcastReceiver mBroadcastReceiver;
    private boolean editorMode;
    private Uri editorFile;
    private SpenSimpleSurfaceView mSpenSurfaceView;
    private SpenNoteDoc mSpenNoteDoc;
    private SpenPageDoc mSpenPageDoc;
    private SpenSettingPenLayout mPenSettingView;
    private SpenSettingEraserLayout mEraserSettingView;
    private ImageView mPenBtn;
    private ImageView mEraserBtn;
    private ImageView mUndoBtn;
    private ImageView mRedoBtn;
    private ImageView mSaveBtn;
    private ImageView mCloseBtn;
    private int mToolType = SpenSimpleSurfaceView.TOOL_SPEN;

    private void showAlertDialog(CharSequence msg) {

        AlertDialog.Builder dlg = new AlertDialog.Builder(this);
        dlg.setIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_dialog_alert));
        dlg.setTitle("Upgrade Notification").setMessage(msg)
                .setPositiveButton(android.R.string.yes, this)
                .setNegativeButton(android.R.string.no, this)
                .setOnCancelListener(this).show();

    }

    private boolean processUnsupportedException(final Activity activity,
                                                SsdkUnsupportedException e) {
        e.printStackTrace();
        int errType = e.getType();
        if (errType == SsdkUnsupportedException.VENDOR_NOT_SUPPORTED
                || errType == SsdkUnsupportedException.DEVICE_NOT_SUPPORTED) {
            Toast.makeText(activity, getText(R.string.err_not_supported),
                    Toast.LENGTH_SHORT).show();
            activity.finish();
        } else if (errType == SsdkUnsupportedException.LIBRARY_NOT_INSTALLED) {
            showAlertDialog(getText(R.string.err_no_library));
        } else if (errType == SsdkUnsupportedException.LIBRARY_UPDATE_IS_REQUIRED) {
            showAlertDialog(getText(R.string.err_update_required));
        } else if (errType == SsdkUnsupportedException.LIBRARY_UPDATE_IS_RECOMMENDED) {
            showAlertDialog(getText(R.string.err_update_recommended));
            return false;
        }
        return true;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                refreshButtons();
                break;
        }
        return false;
    }

    @Override
    public void onClearAll() {
        mSpenPageDoc.removeAllObject();
        mSpenSurfaceView.update();
    }

    @SuppressLint("InlinedApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(R.layout.activity_memo);
        View mContentView = findViewById(R.id.container);
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        String action = getIntent().getAction();
        editorMode = action != null && action.equals("android.intent.action.VIEW");
        editorFile = getIntent().getData();

        if (!editorMode) {
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getBooleanExtra("penInsert", true)) {
                        saveAndExit(true);
                    }
                }
            };
            registerReceiver(mBroadcastReceiver, new IntentFilter(SPEN_INTENT));
        }

        new Handler().postDelayed(new Runnable() {
            public void run() {
                initialize();
            }
        }, 1000);
    }

    private void initialize() {

        Spen spenPackage = new Spen();
        try {
            spenPackage.initialize(this);
        } catch (SsdkUnsupportedException e) {
            if (processUnsupportedException(this, e)) {
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, getText(R.string.err_start_spen), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            finish();
        }

        initSpenSurface();

        if (editorMode) {
            openDocument(editorFile);
        } else {
            newDocument();
        }

        initSettingInfo();
        mSpenSurfaceView.setPreTouchListener(this);
        mSpenSurfaceView.setColorPickerListener(this);
        mSpenPageDoc.setHistoryListener(this);
        mEraserSettingView.setEraserListener(this);

        initButtons();

        if (!spenPackage.isFeatureEnabled(Spen.DEVICE_PEN)) {
            mToolType = SpenSimpleSurfaceView.TOOL_FINGER;
            Toast.makeText(this, getText(R.string.err_limited_support), Toast.LENGTH_SHORT).show();
        } else {
            mToolType = SpenSimpleSurfaceView.TOOL_SPEN;
        }

        mSpenSurfaceView.setToolTypeAction(mToolType, SpenSimpleSurfaceView.ACTION_STROKE);
        mSpenSurfaceView.setZoomable(false);

        findViewById(R.id.loading).setVisibility(View.GONE);
        findViewById(R.id.undoBtn).setVisibility(View.VISIBLE);
        findViewById(R.id.redoBtn).setVisibility(View.VISIBLE);

    }

    private void initSpenSurface() {

        FrameLayout spenViewContainer = (FrameLayout) findViewById(R.id.spenViewContainer);
        RelativeLayout spenViewLayout = (RelativeLayout) findViewById(R.id.spenViewLayout);

        mSpenSurfaceView = new SpenSimpleSurfaceView(this);
        mSpenSurfaceView.setBlankColor(Color.BLACK);
        spenViewLayout.addView(mSpenSurfaceView);

        mPenSettingView = new SpenSettingPenLayout(getApplicationContext(), "",
                spenViewLayout);
        mEraserSettingView = new SpenSettingEraserLayout(getApplicationContext(), "",
                spenViewLayout);
        mPenSettingView.setCanvasView(mSpenSurfaceView);
        mEraserSettingView.setCanvasView(mSpenSurfaceView);
        spenViewContainer.addView(mPenSettingView);
        spenViewContainer.addView(mEraserSettingView);

    }

    private void openDocument(Uri filePath) {

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        strFilePath = filePath.getPath();
        if (filePath.getScheme().equals("content")) {
            final String docId = DocumentsContract.getDocumentId(filePath);
            final String[] split = docId.split(":");
            final String type = split[0];
            if ("primary".equalsIgnoreCase(type)) {
                strFilePath = Environment.getExternalStorageDirectory() + "/" + split[1];
            }
        }

        try {
            mSpenNoteDoc = new SpenNoteDoc(this, strFilePath, metrics.widthPixels,
                    SpenNoteDoc.MODE_WRITABLE, true);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getText(R.string.err_could_not_open), Toast.LENGTH_LONG).show();
            newDocument();
        } catch (SpenUnsupportedTypeException e) {
            Toast.makeText(this, getText(R.string.err_file_not_supported), Toast.LENGTH_LONG).show();
            newDocument();
        } catch (SpenInvalidPasswordException e) {
            Toast.makeText(this, getText(R.string.err_password_locked), Toast.LENGTH_LONG).show();
            newDocument();
        } catch (SpenUnsupportedVersionException e) {
            Toast.makeText(this, getText(R.string.err_version_unsupported), Toast.LENGTH_LONG).show();
            newDocument();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getText(R.string.err_unexpected), Toast.LENGTH_LONG).show();
            finish();
        }

        if (mSpenNoteDoc.getPageCount() == 0) {
            mSpenPageDoc = mSpenNoteDoc.appendPage();
        } else {
            mSpenPageDoc = mSpenNoteDoc.getPage(mSpenNoteDoc.getLastEditedPageIndex());
        }
        mSpenSurfaceView.setPageDoc(mSpenPageDoc, true);
        mSpenSurfaceView.update();

    }

    private void newDocument() {

        editorMode = false;

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        try {
            mSpenNoteDoc = new SpenNoteDoc(this, metrics.widthPixels, metrics.heightPixels);
        } catch (IOException e) {
            Toast.makeText(this, getText(R.string.err_start_notedoc), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            finish();
        } catch (Exception e) {
            e.printStackTrace();
            finish();
        }

        mSpenPageDoc = mSpenNoteDoc.appendPage();
        mSpenPageDoc.setBackgroundColor(0xFF000000);
        mSpenPageDoc.clearHistory();
        mSpenSurfaceView.setPageDoc(mSpenPageDoc, true);
    }

    private void initSettingInfo() {

        SpenSettingPenInfo penInfo = new SpenSettingPenInfo();
        penInfo.color = Color.WHITE;
        penInfo.size = 10;
        mSpenSurfaceView.setPenSettingInfo(penInfo);
        mPenSettingView.setInfo(penInfo);

        SpenSettingEraserInfo eraserInfo = new SpenSettingEraserInfo();
        eraserInfo.size = 50;
        mSpenSurfaceView.setEraserSettingInfo(eraserInfo);
        mEraserSettingView.setInfo(eraserInfo);

    }

    private void initButtons() {

        mPenBtn = (ImageView) findViewById(R.id.penBtn);
        mPenBtn.setOnClickListener(this);

        mEraserBtn = (ImageView) findViewById(R.id.eraserBtn);
        mEraserBtn.setOnClickListener(this);

        mUndoBtn = (ImageView) findViewById(R.id.undoBtn);
        mUndoBtn.setOnClickListener(this);
        mUndoBtn.setEnabled(mSpenPageDoc.isUndoable());

        mRedoBtn = (ImageView) findViewById(R.id.redoBtn);
        mRedoBtn.setOnClickListener(this);
        mRedoBtn.setEnabled(mSpenPageDoc.isRedoable());

        mSaveBtn = (ImageView) findViewById(R.id.saveBtn);
        mSaveBtn.setOnClickListener(this);

        mCloseBtn = (ImageView) findViewById(R.id.closeBtn);
        mCloseBtn.setOnClickListener(this);

        selectButton(mPenBtn);

    }

    private void selectButton(View view) {
        mEraserSettingView.setVisibility(SpenSimpleSurfaceView.GONE);
        mPenSettingView.setVisibility(SpenSimpleSurfaceView.GONE);
        mPenBtn.setSelected(false);
        mEraserBtn.setSelected(false);
        view.setSelected(true);
    }

    private void refreshButtons() {
        mPenBtn.setEnabled(true);
        mEraserBtn.setEnabled(true);
        mUndoBtn.setEnabled(mSpenPageDoc.isUndoable());
        mRedoBtn.setEnabled(mSpenPageDoc.isRedoable());
    }

    @Override
    public void onClick(View view) {

        if (mSpenPageDoc == null) {
            return;
        }

        if (view.equals(mPenBtn)) {
            if (mSpenSurfaceView.getToolTypeAction(mToolType) ==
                    SpenSimpleSurfaceView.ACTION_STROKE) {
                if (mPenSettingView.isShown()) {
                    mPenSettingView.setVisibility(View.GONE);
                } else {
                    mPenSettingView.setViewMode(SpenSettingPenLayout.VIEW_MODE_NORMAL);
                    mPenSettingView.setVisibility(View.VISIBLE);
                }
            } else {
                selectButton(mPenBtn);
                mSpenSurfaceView.setToolTypeAction(mToolType, SpenSimpleSurfaceView.ACTION_STROKE);
            }

        } else if (view.equals(mEraserBtn)) {
            if (mSpenSurfaceView.getToolTypeAction(mToolType) ==
                    SpenSimpleSurfaceView.ACTION_ERASER) {
                if (mEraserSettingView.isShown()) {
                    mEraserSettingView.setVisibility(View.GONE);
                } else {
                    mEraserSettingView.setVisibility(View.VISIBLE);
                }
            } else {
                selectButton(mEraserBtn);
                mSpenSurfaceView.setToolTypeAction(mToolType, SpenSimpleSurfaceView.ACTION_ERASER);
            }

        } else if (view.equals(mUndoBtn)) {
            if (mSpenPageDoc.isUndoable()) {
                SpenPageDoc.HistoryUpdateInfo[] userData = mSpenPageDoc.undo();
                mSpenSurfaceView.updateUndo(userData);
                refreshButtons();
            }

        } else if (view.equals(mRedoBtn)) {
            if (mSpenPageDoc.isRedoable()) {
                SpenPageDoc.HistoryUpdateInfo[] userData = mSpenPageDoc.redo();
                mSpenSurfaceView.updateRedo(userData);
                refreshButtons();
            }

        } else if (view.equals(mSaveBtn)) {
            saveAndExit(false);

        } else if (view.equals(mCloseBtn)) {
            finish();
        }
    }

    @Override
    public void onChanged(int i, int i1, int i2) {
        if (mPenSettingView != null) {
            SpenSettingPenInfo penInfo = mPenSettingView.getInfo();
            penInfo.color = i;
            mPenSettingView.setInfo(penInfo);
        }
    }

    @Override
    public void onCommit(SpenPageDoc spenPageDoc) {

    }

    @Override
    public void onUndoable(SpenPageDoc spenPageDoc, boolean b) {
        mUndoBtn.setEnabled(b);
    }

    @Override
    public void onRedoable(SpenPageDoc spenPageDoc, boolean b) {
        mUndoBtn.setEnabled(b);
    }

    private void saveAndExit(boolean penInsert) {

        if (penInsert) {
            try {
                Process process = Runtime.getRuntime().exec("su");
                DataOutputStream stdin = new DataOutputStream(process.getOutputStream());
                stdin.writeBytes("input keyevent 26\nexit\n");
                stdin.flush();
                process.waitFor();
                stdin.close();
                process.destroy();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (mSpenPageDoc == null || !mSpenPageDoc.isChanged() || mSpenPageDoc.getObjectCount(false) == 0) {
            finish();
            return;
        }

        String fileName;
        String saveFilePath;

        if (editorMode) {

            saveFilePath = strFilePath;
            fileName = new File(strFilePath).getName();

        } else {

            File filePath = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/SNoteData/Action memo/");
            if (!filePath.exists()) {
                if (!filePath.mkdirs()) {
                    Toast.makeText(this, getText(R.string.err_creating_dir), Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            saveFilePath = filePath.getPath() + '/';

            fileName = "ActionMemo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date(System.currentTimeMillis())) + ".spd";
            saveFilePath += fileName;

        }

        try {
            mSpenNoteDoc.save(saveFilePath, false);
            Toast.makeText(this, "Saved to " + fileName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Could not save to " + fileName, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        } catch (Exception e) {
            Toast.makeText(this, "An unexpected error occurred", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }

        finish();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (!editorMode) {
            unregisterReceiver(mBroadcastReceiver);
        }

        if (mPenSettingView != null) {
            mPenSettingView.close();
        }

        if (mEraserSettingView != null) {
            mEraserSettingView.close();
        }

        if (mSpenSurfaceView != null) {
            mSpenSurfaceView.close();
            mSpenSurfaceView = null;
        }

        if (mSpenNoteDoc != null) {
            try {
                mSpenNoteDoc.discard();
                mSpenNoteDoc.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mSpenNoteDoc = null;
        }
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        if (i == DialogInterface.BUTTON_POSITIVE) {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + Spen.getSpenPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            dialogInterface.dismiss();
            finish();
        } else if (i == DialogInterface.BUTTON_NEGATIVE) {
            finish();
            dialogInterface.dismiss();
        }
    }

    @Override
    public void onCancel(DialogInterface dialogInterface) {
        finish();
    }
}
