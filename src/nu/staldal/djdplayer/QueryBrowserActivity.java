/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2012-2013 Mikael Ståldal
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

package nu.staldal.djdplayer;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.SearchManager;
import android.content.*;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;
import android.widget.*;

public class QueryBrowserActivity extends ListActivity
        implements View.OnCreateContextMenuListener, MusicUtils.Defs, ServiceConnection {
    private static final String LOGTAG = "QueryBrowserActivity";

    private static final int TRACK_INFO = CHILD_MENU_BASE + 1;

    private QueryListAdapter mAdapter;
    private boolean mAdapterSent;
    private MusicUtils.ServiceToken mToken;
    private long mSelectedId;
    private Cursor mQueryCursor;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mAdapter = (QueryListAdapter) getLastNonConfigurationInstance();
        mToken = MusicUtils.bindToService(this, this);
        // defer the real work until we're bound to the service
    }

    public void onServiceConnected(ComponentName name, IBinder service) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(mScanListener, f);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            // this is something we got from the search bar
            Uri uri = intent.getData();
            String path = uri.toString();
            if (path.startsWith("content://media/external/audio/media/")) {
                // This is a specific file
                long id = Long.valueOf(uri.getLastPathSegment());
                MusicUtils.playSong(this, id);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/albums/")) {
                // This is an album, show the songs on it
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/vnd.djdplayer.audio");
                i.putExtra("album", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/artists/")) {
                // This is an artist, show the songs for that artist
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/vnd.djdplayer.audio");
                i.putExtra("artist", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            }
        }

        String mFilterString = intent.getStringExtra(SearchManager.QUERY);
        if (MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(intent.getAction())) {
            String focus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS);
            String artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST);
            String album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM);
            String title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE);
            if (focus != null) {
                if (focus.startsWith("audio/") && title != null) {
                    mFilterString = title;
                } else if (focus.equals(MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)) {
                    if (album != null) {
                        mFilterString = album;
                        if (artist != null) {
                            mFilterString = mFilterString + " " + artist;
                        }
                    }
                } else if (focus.equals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)) {
                    if (artist != null) {
                        mFilterString = artist;
                    }
                }
            }
        }

        setContentView(R.layout.query_activity);
        getListView().setOnCreateContextMenuListener(this);
        if (mAdapter == null) {
            mAdapter = new QueryListAdapter(
                    getApplication(),
                    this,
                    R.layout.track_list_item,
                    null, // cursor
                    new String[]{},
                    new int[]{});
            setListAdapter(mAdapter);
            getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
        } else {
            mAdapter.setActivity(this);
            setListAdapter(mAdapter);
            mQueryCursor = mAdapter.getCursor();
            if (mQueryCursor != null) {
                init(mQueryCursor);
            } else {
                getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
            }
        }
    }

    public void onServiceDisconnected(ComponentName name) {

    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        mAdapterSent = true;
        return mAdapter;
    }

    @Override
    public void onPause() {
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(mToken);
        unregisterReceiver(mScanListener);
        // If we have an adapter and didn't send it off to another activity yet, we should
        // close its cursor, which we do by assigning a null cursor to it. Doing this
        // instead of closing the cursor directly keeps the framework from accessing
        // the closed cursor later.
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        if (mAdapter != null) {
            // Because we pass the adapter to the next activity, we need to make
            // sure it doesn't keep a reference to this activity. We can do this
            // by clearing its DatasetObservers, which setListAdapter(null) does.
            setListAdapter(null);
            mAdapter = null;
        }
        super.onDestroy();
    }

    /*
    * This listener gets called when the media scanner starts up, and when the
    * sd card is unmounted.
    */
    private final BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mReScanHandler.sendEmptyMessage(0);
        }
    };

    private final Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getQueryCursor(mAdapter.getQueryHandler(), null);
            }
            // if the query results in a null cursor, onQueryComplete() will
            // call init(), which will post a delayed message to this handler
            // in order to try again.
        }
    };

    public void init(Cursor c) {
        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(c);

        if (mQueryCursor == null) {
            MusicUtils.displayDatabaseError(this);
            setListAdapter(null);
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }
        MusicUtils.hideDatabaseError(this);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfoIn) {
        if (menuInfoIn == null) return;

        AdapterView.AdapterContextMenuInfo mi = (AdapterView.AdapterContextMenuInfo) menuInfoIn;
        mQueryCursor.moveToPosition(mi.position);
        if (mQueryCursor.isBeforeFirst() || mQueryCursor.isAfterLast()) {
            return;
        }
        String selectedType = mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE));

        if (!"artist".equals(selectedType) && !"album".equals(selectedType) && mi.position >= 0 && mi.id >= 0) {
            menu.add(0, PLAY_NOW, 0, R.string.play_now);
            menu.add(0, PLAY_NEXT, 0, R.string.play_next);
            menu.add(0, QUEUE, 0, R.string.queue);
            SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
            MusicUtils.makePlaylistMenu(this, sub);
            menu.add(0, USE_AS_RINGTONE, 0, R.string.ringtone_menu);
            menu.add(0, DELETE_ITEM, 0, R.string.delete_item);
            menu.add(0, TRACK_INFO, 0, R.string.info);
            mSelectedId = mi.id;
            menu.setHeaderTitle(mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_NOW: {
                MusicUtils.queueAndPlayImmediately(this, mSelectedId);
                return true;
            }

            case PLAY_NEXT: {
                MusicUtils.queueNext(this, mSelectedId);
                return true;
            }

            case QUEUE: {
                MusicUtils.queue(this, new long[] { mSelectedId });
                return true;
            }

            case NEW_PLAYLIST: {
                CreatePlaylist.showMe(this, new long[] { mSelectedId });
                return true;
            }

            case PLAYLIST_SELECTED: {
                long[] list = new long[]{mSelectedId};
                long playlist = item.getIntent().getLongExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }

            case USE_AS_RINGTONE:
                // Set the system setting to make this the current ringtone
                MusicUtils.setRingtone(this, mSelectedId);
                return true;

            case DELETE_ITEM: {
                final long[] list = new long[1];
                list[0] = (int) mSelectedId;
                String f = getString(R.string.delete_song_desc);
                String desc = String.format(f,
                        mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)));
                new AlertDialog.Builder(this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.delete_song_title)
                        .setMessage(desc)
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                            }
                        })
                        .setPositiveButton(R.string.delete_confirm_button_text, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                MusicUtils.deleteTracks(QueryBrowserActivity.this, list);
                            }
                        }).show();
                return true;
            }

            case TRACK_INFO:
                TrackInfoFragment.showMe(this,
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mSelectedId));
                return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        // Dialog doesn't allow us to wait for a result, so we need to store
        // the info we need for when the dialog posts its result
        mQueryCursor.moveToPosition(position);
        if (mQueryCursor.isBeforeFirst() || mQueryCursor.isAfterLast()) {
            return;
        }
        String selectedType = mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE));

        if ("artist".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/vnd.djdplayer.audio");
            intent.putExtra("artist", Long.valueOf(id).toString());
            startActivity(intent);
        } else if ("album".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/vnd.djdplayer.audio");
            intent.putExtra("album", Long.valueOf(id).toString());
            startActivity(intent);
        } else if (position >= 0 && id >= 0) {
            MusicUtils.playSong(this, id);
        } else {
            Log.e("QueryBrowser", "invalid position/id: " + position + "/" + id);
        }
    }

    private Cursor getQueryCursor(AsyncQueryHandler async, String filter) {
        if (filter == null) {
            filter = "";
        }
        String[] ccols = new String[]{
                BaseColumns._ID,   // this will be the artist, album or track ID
                MediaStore.Audio.Media.MIME_TYPE, // mimetype of audio file, or "artist" or "album"
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Albums.ALBUM,
                MediaStore.Audio.Media.TITLE,
                "data1",
                "data2"
        };

        Uri search = Uri.parse("content://media/external/audio/search/fancy/" +
                Uri.encode(filter));

        Cursor ret = null;
        if (async != null) {
            async.startQuery(0, null, search, ccols, null, null, null);
        } else {
            ret = MusicUtils.query(this, search, ccols, null, null, null);
        }
        return ret;
    }

    static class QueryListAdapter extends SimpleCursorAdapter {
        private final AsyncQueryHandler mQueryHandler;
        private QueryBrowserActivity mActivity = null;
        private String mConstraint = null;
        private boolean mConstraintIsValid = false;

        class QueryHandler extends AsyncQueryHandler {
            QueryHandler(ContentResolver res) {
                super(res);
            }

            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                mActivity.init(cursor);
            }
        }

        QueryListAdapter(Context context, QueryBrowserActivity currentactivity,
                         int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);
            mActivity = currentactivity;
            mQueryHandler = new QueryHandler(context.getContentResolver());
        }

        public void setActivity(QueryBrowserActivity newactivity) {
            mActivity = newactivity;
        }

        public AsyncQueryHandler getQueryHandler() {
            return mQueryHandler;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            TextView tv1 = (TextView) view.findViewById(R.id.line1);
            TextView tv2 = (TextView) view.findViewById(R.id.line2);
            ImageView iv = (ImageView) view.findViewById(R.id.icon);
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            if (p == null) {
                // seen this happen, not sure why
                DatabaseUtils.dumpCursor(cursor);
                return;
            }
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;

            String mimetype = cursor.getString(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.MIME_TYPE));

            if (mimetype == null) {
                mimetype = "audio/";
            }
            if (mimetype.equals("artist")) {
                iv.setImageResource(R.drawable.ic_mp_artist_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                String displayname = name;
                boolean isunknown = false;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                    isunknown = true;
                }
                tv1.setText(displayname);

                int numalbums = cursor.getInt(cursor.getColumnIndexOrThrow("data1"));
                int numsongs = cursor.getInt(cursor.getColumnIndexOrThrow("data2"));

                String songs_albums = MusicUtils.makeAlbumsSongsLabel(context,
                        numalbums, numsongs, isunknown);

                tv2.setText(songs_albums);

            } else if (mimetype.equals("album")) {
                iv.setImageResource(R.drawable.albumart_mp_unknown_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                String displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_album_name);
                }
                tv1.setText(displayname);

                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                tv2.setText(displayname);

            } else if (mimetype.startsWith("audio/") ||
                    mimetype.equals("application/ogg") ||
                    mimetype.equals("application/x-ogg")) {
                iv.setImageResource(R.drawable.ic_mp_song_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE));
                tv1.setText(name);

                String displayname = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                if (displayname == null || displayname.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    name = context.getString(R.string.unknown_album_name);
                }
                tv2.setText(displayname + " - " + name);
            }
        }

        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mQueryCursor) {
                mActivity.mQueryCursor = cursor;
                super.changeCursor(cursor);
            }
        }

        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            String s = constraint.toString();
            if (mConstraintIsValid && (
                    (s == null && mConstraint == null) ||
                            (s != null && s.equals(mConstraint)))) {
                return getCursor();
            }
            Cursor c = mActivity.getQueryCursor(null, s);
            mConstraint = s;
            mConstraintIsValid = true;
            return c;
        }
    }
}
