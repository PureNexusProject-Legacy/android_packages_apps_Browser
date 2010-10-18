/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.browser;

import com.android.browser.provider.BrowserProvider2;

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.ParseException;
import android.net.Uri;
import android.net.WebAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.BrowserContract;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Stack;

public class AddBookmarkPage extends Activity
        implements View.OnClickListener, TextView.OnEditorActionListener,
        AdapterView.OnItemClickListener, LoaderManager.LoaderCallbacks<Cursor>,
        BreadCrumbView.Controller {

    public static final long DEFAULT_FOLDER_ID = -1;

    private static final int MAX_CRUMBS_SHOWN = 2;

    private final String LOGTAG = "Bookmarks";
    // Set to true to see the crash on the code I would like to run.
    private final boolean DEBUG_CRASH = false;

    // IDs for the CursorLoaders that are used.
    private final int LOADER_ID_FOLDER_CONTENTS = 0;
    private final int LOADER_ID_ALL_FOLDERS = 1;

    private EditText    mTitle;
    private EditText    mAddress;
    private TextView    mButton;
    private View        mCancelButton;
    private boolean     mEditingExisting;
    private Bundle      mMap;
    private String      mTouchIconUrl;
    private Bitmap      mThumbnail;
    private String      mOriginalUrl;
    private TextView mFolder;
    private View mDefaultView;
    private View mFolderSelector;
    private EditText mFolderNamer;
    private View mAddNewFolder;
    private View mAddSeparator;
    private long mCurrentFolder = 0;
    private FolderAdapter mAdapter;
    private BreadCrumbView mCrumbs;
    private TextView mFakeTitle;
    private View mCrumbHolder;

    private static class Folder {
        String Name;
        long Id;
        Folder(String name, long id) {
            Name = name;
            Id = id;
        }
    }

    // Message IDs
    private static final int SAVE_BOOKMARK = 100;

    private Handler mHandler;

    private InputMethodManager getInputMethodManager() {
        return (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    }

    @Override
    public void onTop(int level, Object data) {
        if (null == data) return;
        mCurrentFolder = (Long) data;
        Uri uri = BrowserContract.Bookmarks.buildFolderUri(mCurrentFolder);
        LoaderManager manager = getLoaderManager();
        CursorLoader loader = (CursorLoader) ((Loader) manager.getLoader(
                LOADER_ID_FOLDER_CONTENTS));
        loader.setUri(uri);
        loader.forceLoad();
        updateVisible();
    }

    /**
     * Update the views shown to only show the two deepest levels of crumbs.
     * Note that this method depends on internal knowledge of BreadCrumbView.
     */
    private void updateVisible() {
      if (MAX_CRUMBS_SHOWN > 0) {
          int invisibleCrumbs = mCrumbs.size() - MAX_CRUMBS_SHOWN;
          // This class always uses a back button, which is the first child.
          int childIndex = 1;
          if (invisibleCrumbs > 0) {
              int crumbIndex = 0;
              while (crumbIndex < invisibleCrumbs) {
                  // Set the crumb to GONE.
                  mCrumbs.getChildAt(childIndex).setVisibility(View.GONE);
                  childIndex++;
                  // Each crumb is followed by a separator (except the last
                  // one).  Also make it GONE
                  mCrumbs.getChildAt(childIndex).setVisibility(View.GONE);
                  childIndex++;
                  // Move to the next crumb.
                  crumbIndex++;
              }
          }
          // Make sure the last two are visible.
          int childCount = mCrumbs.getChildCount();
          while (childIndex < childCount) {
              mCrumbs.getChildAt(childIndex).setVisibility(View.VISIBLE);
              childIndex++;
          }
      }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (v == mFolderNamer) {
            if (v.getText().length() > 0) {
                if (actionId == EditorInfo.IME_NULL) {
                    // Only want to do this once.
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        completeFolderNaming();
                    }
                }
            }
            // Steal the key press; otherwise a newline will be added
            return true;
        }
        return false;
    }

    @Override
    public void onClick(View v) {
        if (v == mButton) {
            if (mFolderSelector.getVisibility() == View.VISIBLE) {
             // We are showing the folder selector.
                if (mFolderNamer.getVisibility() == View.VISIBLE) {
                    completeFolderNaming();
                } else {
                    // User has selected a folder.  Go back to the opening page
                    mFolderSelector.setVisibility(View.GONE);
                    mDefaultView.setVisibility(View.VISIBLE);
                    mCrumbHolder.setVisibility(View.GONE);
                    mFakeTitle.setVisibility(View.VISIBLE);
                }
            } else if (save()) {
                finish();
            }
        } else if (v == mCancelButton) {
            if (mFolderNamer.getVisibility() == View.VISIBLE) {
                mFolderNamer.setVisibility(View.GONE);
                mAddNewFolder.setVisibility(View.VISIBLE);
                mAddSeparator.setVisibility(View.VISIBLE);
            } else {
                finish();
            }
        } else if (v == mFolder) {
            switchToFolderSelector();
        } else if (v == mAddNewFolder) {
            mFolderNamer.setVisibility(View.VISIBLE);
            mFolderNamer.setText(R.string.new_folder);
            mFolderNamer.requestFocus();
            mAddNewFolder.setVisibility(View.GONE);
            mAddSeparator.setVisibility(View.GONE);
            getInputMethodManager().showSoftInput(mFolderNamer,
                    InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void completeFolderNaming() {
        if (!TextUtils.isEmpty(mFolderNamer.getText())) {
            String name = mFolderNamer.getText().toString();
            long id = addFolderToCurrent(mFolderNamer.getText().toString());
            descendInto(name, id);
            mFolderNamer.setVisibility(View.GONE);
            mAddNewFolder.setVisibility(View.VISIBLE);
            mAddSeparator.setVisibility(View.VISIBLE);
            getInputMethodManager().hideSoftInputFromWindow(
                    mFolderNamer.getWindowToken(), 0);
        }
    }

    private long addFolderToCurrent(String name) {
        // Add the folder to the database
        ContentValues values = new ContentValues();
        values.put(BrowserContract.Bookmarks.TITLE,
                name);
        values.put(BrowserContract.Bookmarks.IS_FOLDER, 1);
        values.put(BrowserContract.Bookmarks.PARENT,
                mCurrentFolder);
        Uri uri = getContentResolver().insert(
                BrowserContract.Bookmarks.CONTENT_URI, values);
        if (uri != null) {
            return ContentUris.parseId(uri);
        } else {
            return -1;
        }
    }

    private void switchToFolderSelector() {
        mDefaultView.setVisibility(View.GONE);
        mFolderSelector.setVisibility(View.VISIBLE);
        mCrumbHolder.setVisibility(View.VISIBLE);
        mFakeTitle.setVisibility(View.GONE);
        mAddNewFolder.setVisibility(View.VISIBLE);
        mAddSeparator.setVisibility(View.VISIBLE);
    }

    private void descendInto(String foldername, long id) {
        if (id != DEFAULT_FOLDER_ID) {
            mCurrentFolder = id;
            mCrumbs.pushView(foldername, id);
            mCrumbs.notifyController();
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] projection;
        switch (id) {
            case LOADER_ID_ALL_FOLDERS:
                projection = new String[] {
                        BrowserContract.Bookmarks._ID,
                        BrowserContract.Bookmarks.PARENT,
                        BrowserContract.Bookmarks.TITLE,
                        BrowserContract.Bookmarks.IS_FOLDER
                };
                return new CursorLoader(this,
                        BrowserContract.Bookmarks.CONTENT_URI,
                        projection,
                        BrowserContract.Bookmarks.IS_FOLDER + " != 0",
                        null,
                        null);
            case LOADER_ID_FOLDER_CONTENTS:
                projection = new String[] {
                        BrowserContract.Bookmarks._ID,
                        BrowserContract.Bookmarks.TITLE,
                        BrowserContract.Bookmarks.IS_FOLDER
                };
                return new CursorLoader(this,
                        BrowserContract.Bookmarks.buildFolderUri(
                        mCurrentFolder),
                        projection,
                        BrowserContract.Bookmarks.IS_FOLDER + " != 0",
                        null,
                        null);
            default:
                throw new AssertionError("Asking for nonexistant loader!");
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        switch (loader.getId()) {
            case LOADER_ID_FOLDER_CONTENTS:
                mAdapter.changeCursor(cursor);
                break;
            case LOADER_ID_ALL_FOLDERS:
                long parent = mCurrentFolder;
                int idIndex = cursor.getColumnIndexOrThrow(
                        BrowserContract.Bookmarks._ID);
                int titleIndex = cursor.getColumnIndexOrThrow(
                        BrowserContract.Bookmarks.TITLE);
                int parentIndex = cursor.getColumnIndexOrThrow(
                        BrowserContract.Bookmarks.PARENT);
                Stack folderStack = new Stack();
                while ((parent != BrowserProvider2.FIXED_ID_ROOT) &&
                        (parent != 0)) {
                    // First, find the folder corresponding to the current
                    // folder
                    if (!cursor.moveToFirst()) {
                        throw new AssertionError("No folders in the database!");
                    }
                    long folder;
                    do {
                        folder = cursor.getLong(idIndex);
                    } while (folder != parent && cursor.moveToNext());
                    if (cursor.isAfterLast()) {
                        throw new AssertionError("Folder(id=" + parent
                                + ") holding this bookmark does not exist!");
                    }
                    String name = cursor.getString(titleIndex);
                    folderStack.push(new Folder(name, parent));
                    parent = cursor.getLong(parentIndex);
                }
                while (!folderStack.isEmpty()) {
                    Folder thisFolder = (Folder) folderStack.pop();
                    mCrumbs.pushView(thisFolder.Name, thisFolder.Id);
                }
                getLoaderManager().stopLoader(LOADER_ID_ALL_FOLDERS);
                updateVisible();
                break;
            default:
                break;
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position,
            long id) {
        TextView tv = (TextView) view.findViewById(android.R.id.text1);
        // Switch to the folder that was clicked on.
        descendInto(tv.getText().toString(), id);
    }

    /**
     * Shows a list of names of folders.
     */
    private class FolderAdapter extends CursorAdapter {
        public FolderAdapter(Context context) {
            super(context, null);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ((TextView) view.findViewById(android.R.id.text1)).setText(
                    cursor.getString(cursor.getColumnIndexOrThrow(
                    BrowserContract.Bookmarks.TITLE)));
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View view = LayoutInflater.from(context).inflate(
                    R.layout.folder_list_item, null);
            view.setBackgroundDrawable(context.getResources().
                    getDrawable(android.R.drawable.list_selector_background));
            return view;
        }
    }

    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DEBUG_CRASH) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } else {
            requestWindowFeature(Window.FEATURE_LEFT_ICON);
        }

        mMap = getIntent().getExtras();

        setContentView(R.layout.browser_add_bookmark);

        Window window = getWindow();
        if (!DEBUG_CRASH) {
            setTitle(R.string.bookmark_this_page);
            window.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, R.drawable.ic_list_bookmark);
        }

        String title = null;
        String url = null;

        if (mMap != null) {
            Bundle b = mMap.getBundle("bookmark");
            if (b != null) {
                mMap = b;
                mEditingExisting = true;
                mFakeTitle.setText(R.string.edit_bookmark);
                if (!DEBUG_CRASH) {
                    setTitle(R.string.bookmark_this_page);
                }
            } else {
                int gravity = mMap.getInt("gravity", -1);
                if (gravity != -1) {
                    WindowManager.LayoutParams l = window.getAttributes();
                    l.gravity = gravity;
                    window.setAttributes(l);
                }
            }
            title = mMap.getString("title");
            url = mOriginalUrl = mMap.getString("url");
            mTouchIconUrl = mMap.getString("touch_icon_url");
            mThumbnail = (Bitmap) mMap.getParcelable("thumbnail");
            mCurrentFolder = mMap.getLong(BrowserContract.Bookmarks.PARENT, DEFAULT_FOLDER_ID);
        }
        if (mCurrentFolder == DEFAULT_FOLDER_ID) {
            mCurrentFolder = getBookmarksBarId(this);
        }

        mTitle = (EditText) findViewById(R.id.title);
        mTitle.setText(title);

        mAddress = (EditText) findViewById(R.id.address);
        mAddress.setText(url);

        mButton = (TextView) findViewById(R.id.OK);
        mButton.setOnClickListener(this);

        mCancelButton = findViewById(R.id.cancel);
        mCancelButton.setOnClickListener(this);

        mFolder = (TextView) findViewById(R.id.folder);
        mFolder.setOnClickListener(this);

        mDefaultView = findViewById(R.id.default_view);
        mFolderSelector = findViewById(R.id.folder_selector);

        mFolderNamer = (EditText) findViewById(R.id.folder_namer);
        mFolderNamer.setOnEditorActionListener(this);

        mAddNewFolder = findViewById(R.id.add_new_folder);
        mAddNewFolder.setOnClickListener(this);
        mAddSeparator = findViewById(R.id.add_divider);

        mCrumbs = (BreadCrumbView) findViewById(R.id.crumbs);
        mCrumbs.setUseBackButton(true);
        mCrumbs.setController(this);
        mCrumbs.pushView(getString(R.string.bookmarks), false,
                BrowserProvider2.FIXED_ID_ROOT);
        mCrumbHolder = findViewById(R.id.crumb_holder);

        mFakeTitle = (TextView) findViewById(R.id.fake_title);

        mAdapter = new FolderAdapter(this);
        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(this);
        LoaderManager manager = getLoaderManager();
        if (mCurrentFolder != BrowserProvider2.FIXED_ID_ROOT) {
            // Find all the folders
            manager.initLoader(LOADER_ID_ALL_FOLDERS, null, this);
        }
        manager.initLoader(LOADER_ID_FOLDER_CONTENTS, null, this);


        if (!window.getDecorView().isInTouchMode()) {
            mButton.requestFocus();
        }
    }

    // FIXME: Use a CursorLoader
    private long getBookmarksBarId(Context context) {
        SharedPreferences prefs
                = PreferenceManager.getDefaultSharedPreferences(context);
        String accountName =
                prefs.getString(BrowserBookmarksPage.PREF_ACCOUNT_NAME, null);
        String accountType =
                prefs.getString(BrowserBookmarksPage.PREF_ACCOUNT_TYPE, null);
        if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) {
            return BrowserProvider2.FIXED_ID_ROOT;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    BrowserContract.Bookmarks.CONTENT_URI,
                    new String[] { BrowserContract.Bookmarks._ID },
                    BrowserContract.ChromeSyncColumns.SERVER_UNIQUE + "=? AND "
                            + BrowserContract.Bookmarks.ACCOUNT_NAME + "=? AND "
                            + BrowserContract.Bookmarks.ACCOUNT_TYPE + "=?",
                    new String[] {
                            BrowserContract.ChromeSyncColumns
                                    .FOLDER_NAME_BOOKMARKS_BAR,
                            accountName,
                            accountType },
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return BrowserProvider2.FIXED_ID_ROOT;
    }

    /**
     * Runnable to save a bookmark, so it can be performed in its own thread.
     */
    private class SaveBookmarkRunnable implements Runnable {
        // FIXME: This should be an async task.
        private Message mMessage;
        private Context mContext;
        public SaveBookmarkRunnable(Context ctx, Message msg) {
            mContext = ctx;
            mMessage = msg;
        }
        public void run() {
            // Unbundle bookmark data.
            Bundle bundle = mMessage.getData();
            String title = bundle.getString("title");
            String url = bundle.getString("url");
            boolean invalidateThumbnail = bundle.getBoolean(
                    "invalidateThumbnail");
            Bitmap thumbnail = invalidateThumbnail ? null
                    : (Bitmap) bundle.getParcelable("thumbnail");
            String touchIconUrl = bundle.getString("touchIconUrl");

            // Save to the bookmarks DB.
            try {
                final ContentResolver cr = getContentResolver();
                Bookmarks.addBookmark(AddBookmarkPage.this, false, url,
                        title, thumbnail, true, mCurrentFolder);
                if (touchIconUrl != null) {
                    new DownloadTouchIcon(mContext, cr, url).execute(mTouchIconUrl);
                }
                mMessage.arg1 = 1;
            } catch (IllegalStateException e) {
                mMessage.arg1 = 0;
            }
            mMessage.sendToTarget();
        }
    }

    private void createHandler() {
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SAVE_BOOKMARK:
                            if (1 == msg.arg1) {
                                Toast.makeText(AddBookmarkPage.this, R.string.bookmark_saved,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(AddBookmarkPage.this, R.string.bookmark_not_saved,
                                        Toast.LENGTH_LONG).show();
                            }
                            break;
                    }
                }
            };
        }
    }

    /**
     * Parse the data entered in the dialog and post a message to update the bookmarks database.
     */
    boolean save() {
        createHandler();

        String title = mTitle.getText().toString().trim();
        String unfilteredUrl;
        unfilteredUrl = BrowserActivity.fixUrl(mAddress.getText().toString());

        boolean emptyTitle = title.length() == 0;
        boolean emptyUrl = unfilteredUrl.trim().length() == 0;
        Resources r = getResources();
        if (emptyTitle || emptyUrl) {
            if (emptyTitle) {
                mTitle.setError(r.getText(R.string.bookmark_needs_title));
            }
            if (emptyUrl) {
                mAddress.setError(r.getText(R.string.bookmark_needs_url));
            }
            return false;

        }
        String url = unfilteredUrl.trim();
        try {
            // We allow bookmarks with a javascript: scheme, but these will in most cases
            // fail URI parsing, so don't try it if that's the kind of bookmark we have.

            if (!url.toLowerCase().startsWith("javascript:")) {
                URI uriObj = new URI(url);
                String scheme = uriObj.getScheme();
                if (!Bookmarks.urlHasAcceptableScheme(url)) {
                    // If the scheme was non-null, let the user know that we
                    // can't save their bookmark. If it was null, we'll assume
                    // they meant http when we parse it in the WebAddress class.
                    if (scheme != null) {
                        mAddress.setError(r.getText(R.string.bookmark_cannot_save_url));
                        return false;
                    }
                    WebAddress address;
                    try {
                        address = new WebAddress(unfilteredUrl);
                    } catch (ParseException e) {
                        throw new URISyntaxException("", "");
                    }
                    if (address.getHost().length() == 0) {
                        throw new URISyntaxException("", "");
                    }
                    url = address.toString();
                }
            }
        } catch (URISyntaxException e) {
            mAddress.setError(r.getText(R.string.bookmark_url_not_valid));
            return false;
        }

        if (mEditingExisting) {
            mMap.putString("title", title);
            mMap.putString("url", url);
            mMap.putBoolean("invalidateThumbnail", !url.equals(mOriginalUrl));
            // FIXME: This does not work yet
            mMap.putLong(BrowserContract.Bookmarks.PARENT, mCurrentFolder);
            setResult(RESULT_OK, (new Intent()).setAction(
                    getIntent().toString()).putExtras(mMap));
        } else {
            // Post a message to write to the DB.
            Bundle bundle = new Bundle();
            bundle.putString("title", title);
            bundle.putString("url", url);
            bundle.putParcelable("thumbnail", mThumbnail);
            bundle.putBoolean("invalidateThumbnail", !url.equals(mOriginalUrl));
            bundle.putString("touchIconUrl", mTouchIconUrl);
            Message msg = Message.obtain(mHandler, SAVE_BOOKMARK);
            msg.setData(bundle);
            // Start a new thread so as to not slow down the UI
            Thread t = new Thread(new SaveBookmarkRunnable(getApplicationContext(), msg));
            t.start();
            setResult(RESULT_OK);
            LogTag.logBookmarkAdded(url, "bookmarkview");
        }
        return true;
    }

}
