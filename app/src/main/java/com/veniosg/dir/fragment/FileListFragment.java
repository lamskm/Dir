/*
 * Copyright (C) 2012 OpenIntents.org
 * Copyright (C) 2014 George Venios
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

package com.veniosg.dir.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.veniosg.dir.FileManagerApplication;
import com.veniosg.dir.IntentConstants;
import com.veniosg.dir.R;
import com.veniosg.dir.adapter.FileHolderListAdapter;
import com.veniosg.dir.misc.DirectoryContents;
import com.veniosg.dir.misc.DirectoryScanner;
import com.veniosg.dir.misc.FileHolder;
import com.veniosg.dir.util.Logger;
import com.veniosg.dir.view.widget.WaitingViewFlipper;

import java.io.File;
import java.util.ArrayList;

import static com.veniosg.dir.IntentConstants.ACTION_REFRESH_LIST;
import static com.veniosg.dir.IntentConstants.EXTRA_DIR_PATH;
import static com.veniosg.dir.view.widget.WaitingViewFlipper.PAGE_INDEX_CONTENT;
import static com.veniosg.dir.view.widget.WaitingViewFlipper.PAGE_INDEX_LOADING;

/**
 * A {@link ListFragment} that displays the contents of a directory.
 * <p>
 * Clicks do nothing.
 * </p>
 * <p>
 * Refreshes on OnSharedPreferenceChange and when receiving
 * a local ACTION_REFRESH_LIST broadcast with EXTRA_DIR_PATH matching this folder.
 * </p>
 * 
 * @author George Venios
 */
public abstract class FileListFragment extends AbsListFragment {
	private static final String INSTANCE_STATE_PATH = "path";
	private static final String INSTANCE_STATE_FILES = "files";
    private static final String INSTANCE_STATE_NEEDS_LOADING = "needsLoading";

    // Not an anonymous inner class because of:
	// http://stackoverflow.com/questions/2542938/sharedpreferences-onsharedpreferencechangelistener-not-being-called-consistently
	private OnSharedPreferenceChangeListener preferenceListener = new OnSharedPreferenceChangeListener() {
		@Override
		public void onSharedPreferenceChanged(
				SharedPreferences sharedPreferences, String key) {
			// We only care for list-altering preferences. This could be
			// dangerous though,
			// as later contributors might not see this, and have their settings
			// not work in realtime.
			// Therefore this is commented out, since it's not likely the
			// refresh is THAT heavy.
			// *****************
			// if (PreferenceActivity.PREFS_DISPLAYHIDDENFILES.equals(key)
			// || PreferenceActivity.PREFS_SORTBY.equals(key)
			// || PreferenceActivity.PREFS_ASCENDING.equals(key))

			// Prevent NullPointerException caused from this getting called
			// after we have finish()ed the activity.
			if (getActivity() != null
                    && !key.equals(PreferenceFragment.PREFS_THEME)) // We're restarting, no need for refresh
				refresh();
		}
	};

	FileHolderListAdapter mAdapter;
	DirectoryScanner mScanner;
	private ArrayList<FileHolder> mFiles = new ArrayList<FileHolder>();
	private String mPath;
	private String mFilename;
    private FileObserver mFileObserver;

    private WaitingViewFlipper mFlipper;
    private BroadcastReceiver mRefreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String requestPath = intent.getStringExtra(EXTRA_DIR_PATH);
            if (requestPath != null && requestPath.equals(mPath)) {
                refresh();
            }
        }
    };
    private View.OnClickListener mEmptyViewClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onEmptyViewClicked();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LocalBroadcastManager.getInstance(getActivity())
                .registerReceiver(mRefreshReceiver,
                        new IntentFilter(ACTION_REFRESH_LIST));
    }

    @Override
    public void onDestroy() {
        mScanner.cancel();
        LocalBroadcastManager.getInstance(getActivity())
                .unregisterReceiver(mRefreshReceiver);
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(INSTANCE_STATE_PATH, mPath);
        outState.putInt(INSTANCE_STATE_NEEDS_LOADING, isScannerRunning() ? 1 : 0);
        outState.putParcelableArrayList(INSTANCE_STATE_FILES, mFiles);
    }

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_filelist, null);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

		// Set auto refresh on preference change.
		PreferenceManager.getDefaultSharedPreferences(getActivity())
				.registerOnSharedPreferenceChangeListener(preferenceListener);

		// Set list properties
		getListView().requestFocus();
		getListView().requestFocusFromTouch();

		mFlipper = (WaitingViewFlipper) view.findViewById(R.id.flipper);
        view.findViewById(R.id.empty_img).setOnClickListener(mEmptyViewClickListener);

		// Get arguments
        boolean needsLoading = true;
		if (savedInstanceState == null) {
            setPath(new File(getArguments().getString(EXTRA_DIR_PATH)));
			mFilename = getArguments().getString(
					IntentConstants.EXTRA_FILENAME);
		} else {
			setPath(new File(savedInstanceState.getString(INSTANCE_STATE_PATH)));
			mFiles = savedInstanceState
					.getParcelableArrayList(INSTANCE_STATE_FILES);
            needsLoading = savedInstanceState.getInt(INSTANCE_STATE_NEEDS_LOADING) != 0;
		}
		pathCheckAndFix();
        renewScanner();

        if (needsLoading) {
            showLoading(true);
            mScanner.start();
        }

        mAdapter = new FileHolderListAdapter(mFiles);
        setListAdapter(mAdapter);
	}

    /**
	 * Reloads {@link #mPath}'s contents.
	 */
	protected void refresh() {
        // Cancel and GC previous scanner so that it doesn't load on top of the
		// new list.
		// Race condition seen if a long list is requested, and a short list is
		// requested before the long one loads.
		mScanner.cancel();
		mScanner = null;

		// Indicate loading and start scanning.
		showLoading(true);
		renewScanner().start();
	}

	/**
	 * Make the UI indicate loading.
	 */
	private void showLoading(boolean loading) {
        onLoadingChanging(loading);
        if (loading) {
            mFlipper.setDisplayedChildDelayed(PAGE_INDEX_LOADING);
        } else {
            mFlipper.setDisplayedChild(PAGE_INDEX_CONTENT);
        }
        onLoadingChanged(loading);
	}

    /**
	 * Recreates the {@link #mScanner} using the previously set arguments and
	 * {@link #mPath}.
	 * 
	 * @return {@link #mScanner} for convenience.
	 */
	protected DirectoryScanner renewScanner() {
		String filetypeFilter = getArguments().getString(
				IntentConstants.EXTRA_FILTER_FILETYPE);
		String mimetypeFilter = getArguments().getString(
				IntentConstants.EXTRA_FILTER_MIMETYPE);
		boolean writeableOnly = getArguments().getBoolean(
				IntentConstants.EXTRA_WRITEABLE_ONLY);
		boolean directoriesOnly = getArguments().getBoolean(
				IntentConstants.EXTRA_DIRECTORIES_ONLY);

		mScanner = new DirectoryScanner(new File(mPath),
                getActivity(),
				new FileListMessageHandler(),
                ((FileManagerApplication) getActivity().getApplicationContext()).getMimeTypes(),
				filetypeFilter == null ? "" : filetypeFilter,
				mimetypeFilter == null ? "" : mimetypeFilter,
                writeableOnly,
				directoriesOnly);
		return mScanner;
	}

    public boolean isScannerRunning() {
        return mScanner != null
                && mScanner.isAlive()
                && mScanner.isRunning();
    }

    private class FileListMessageHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
                case DirectoryScanner.MESSAGE_SHOW_DIRECTORY_CONTENTS:
                    DirectoryContents c = (DirectoryContents) msg.obj;
                    mFiles.clear();
                    mFiles.addAll(c.listSdCard);
                    mFiles.addAll(c.listDir);
                    mFiles.addAll(c.listFile);
                    onDataReady();

                    mAdapter.notifyDataSetChanged();
                    if (getView() != null) {
                        getListView().setSelection(0);
                    }
                    showLoading(false);
                    onDataApplied();
                    break;
                case DirectoryScanner.MESSAGE_SET_PROGRESS:
                    // Irrelevant.
                    break;
                }
		}
	}

	/**
	 * @return The currently displayed directory's absolute path.
	 */
	public final String getPath() {
		return mPath;
	}

	/**
	 * This will be ignored if path doesn't pass check as valid.
	 * 
	 * @param dir The path to set.
	 */
	public final void setPath(File dir) {
        mPath = dir.getAbsolutePath();

        if (dir.exists()){
            // Observe the path
            if (mFileObserver != null) {
                mFileObserver.stopWatching();
            }
            mFileObserver = generateFileObserver(mPath);
            mFileObserver.startWatching();
		}
	}

    private FileObserver generateFileObserver(String pathToObserve) {
        return new FileObserver(pathToObserve,
                          FileObserver.CREATE
                        | FileObserver.DELETE
                        | FileObserver.CLOSE_WRITE // Removed since in case of continuous modification
                                                   // (copy/compress) we would flood with events.
                        | FileObserver.MOVED_FROM
                        | FileObserver.MOVED_TO) {
            private static final long MIN_REFRESH_INTERVAL = 2 * 1000;

            private long lastUpdate = 0;

            @Override
            public void onEvent(int event, String path) {
                if (System.currentTimeMillis() - lastUpdate <= MIN_REFRESH_INTERVAL
                        || event == 32768) { // See https://code.google.com/p/android/issues/detail?id=29546
                    return;
                }

                Logger.logV(Logger.TAG_OBSERVER, "Observed event " + event + ", refreshing list..");
                lastUpdate = System.currentTimeMillis();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
            }
        };
    }

    private void pathCheckAndFix() {
		File dir = new File(mPath);
		// Sanity check that the path (coming from extras_dir_path) is indeed a
		// directory
		if (!dir.isDirectory() && dir.getParentFile() != null) {
			// remember the filename for picking.
			mFilename = dir.getName();
			setPath(dir.getParentFile());
		}
	}

	public String getFilename() {
		return mFilename;
	}

    /**
     * Will request a refresh for all active FileListFragment instances currently displaying "directory".
     * @param directory The directory to refresh.
     */
    public static void refresh(Context c, File directory) {
        Intent i = new Intent(ACTION_REFRESH_LIST);
        i.putExtra(EXTRA_DIR_PATH, directory.getAbsolutePath());

        LocalBroadcastManager.getInstance(c).sendBroadcast(i);
    }

    /**
     * Use this callback to handle UI state when the new list data is ready but BEFORE
     * the list is refreshed.
     */
    protected void onDataReady() {}

    /**
     * Use this callback to handle UI state when the new list data is ready and the UI
     * has been refreshed.
     */
    protected void onDataApplied() {}

    /**
     * Used to inform subclasses about loading state changing. Can be used to
     * make the ui indicate the loading state of the fragment. This is called before the actual change.
     *
     * @param loading If the list started or stopped loading.
     */
    protected void onLoadingChanging(boolean loading) {}

    /**
     * Used to inform subclasses about loading state changing. Can be used to
     * make the ui indicate the loading state of the fragment. This is called before the actual change.
     *
     * @param loading If the list started or stopped loading.
     */
    protected void onLoadingChanged(boolean loading) {}

    protected void onEmptyViewClicked(){}
}
