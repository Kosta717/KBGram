/*
 * MuseGram - Music Player Tab Fragment
 * Displays audio files from Saved Messages with integrated playback controls.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.CensorshipDetector;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AudioPlayerCell;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class MusicFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter listAdapter;
    private LinearLayout emptyView;
    private FrameLayout playerHeaderView;
    private TextView censorshipStatusView;

    // Now-playing mini player views
    private TextView playerTitleView;
    private TextView playerArtistView;
    private ImageView playerPlayPauseButton;
    private PlayPauseDrawable playPauseDrawable;

    private ArrayList<MessageObject> playlist = new ArrayList<>();
    private boolean hasMainTabs;
    private long savedMessagesDialogId;

    public MusicFragment(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (getArguments() != null) {
            hasMainTabs = getArguments().getBoolean("hasMainTabs", false);
        }
        savedMessagesDialogId = UserConfig.getInstance(currentAccount).getClientUserId();

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart ||
            id == NotificationCenter.messagePlayingDidReset ||
            id == NotificationCenter.messagePlayingPlayStateChanged) {
            updateNowPlaying();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        } else if (id == NotificationCenter.musicDidLoad) {
            // musicDidLoad provides: dialogId, begin list, end list
            ArrayList<MessageObject> begin = (ArrayList<MessageObject>) args[1];
            ArrayList<MessageObject> end = (ArrayList<MessageObject>) args[2];
            playlist.clear();
            if (begin != null) {
                playlist.addAll(begin);
            }
            if (end != null) {
                playlist.addAll(end);
            }
            // Also merge in current MediaController playlist if active
            mergeCurrentPlaylist();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            updateEmptyView();
        }
    }

    private void mergeCurrentPlaylist() {
        ArrayList<MessageObject> mcPlaylist = MediaController.getInstance().getPlaylist();
        if (mcPlaylist != null && !mcPlaylist.isEmpty()) {
            for (MessageObject msg : mcPlaylist) {
                if (msg != null && msg.isMusic()) {
                    boolean exists = false;
                    for (MessageObject existing : playlist) {
                        if (existing.getId() == msg.getId() && existing.getDialogId() == msg.getDialogId()) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        playlist.add(0, msg);
                    }
                }
            }
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(null);
        actionBar.setTitle(getString(R.string.MainTabsMusic));
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        if (hasMainTabs) {
            actionBar.setOccupyStatusBar(true);
        }

        FrameLayout contentView = new FrameLayout(context);
        fragmentView = contentView;

        int topOffset = 0;

        // Anti-censorship status bar
        censorshipStatusView = new TextView(context);
        censorshipStatusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        censorshipStatusView.setGravity(Gravity.CENTER);
        censorshipStatusView.setPadding(dp(8), dp(6), dp(8), dp(6));
        censorshipStatusView.setSingleLine(true);
        updateCensorshipStatus();
        contentView.addView(censorshipStatusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.TOP, 0, topOffset, 0, 0));
        topOffset += 28;

        // Now-playing mini player
        playerHeaderView = new FrameLayout(context);
        playerHeaderView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        playerHeaderView.setVisibility(View.GONE);
        playerHeaderView.setClickable(true);
        playerHeaderView.setOnClickListener(v -> {
            if (getParentActivity() == null) return;
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null) {
                AudioPlayerAlert alert = new AudioPlayerAlert(getParentActivity(), null);
                alert.show();
            }
        });

        // Track title
        playerTitleView = new TextView(context);
        playerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        playerTitleView.setTypeface(AndroidUtilities.bold());
        playerTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        playerTitleView.setSingleLine(true);
        playerTitleView.setEllipsize(TextUtils.TruncateAt.END);
        playerHeaderView.addView(playerTitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 16, 10, 52, 0));

        // Track artist
        playerArtistView = new TextView(context);
        playerArtistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        playerArtistView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        playerArtistView.setSingleLine(true);
        playerArtistView.setEllipsize(TextUtils.TruncateAt.END);
        playerHeaderView.addView(playerArtistView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 16, 32, 52, 0));

        // Play/Pause button
        playPauseDrawable = new PlayPauseDrawable(14);
        playPauseDrawable.setCallback(playerHeaderView);
        playerPlayPauseButton = new ImageView(context);
        playerPlayPauseButton.setImageDrawable(playPauseDrawable);
        playerPlayPauseButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        playerPlayPauseButton.setScaleType(ImageView.ScaleType.CENTER);
        playerPlayPauseButton.setOnClickListener(v -> {
            MediaController mc = MediaController.getInstance();
            MessageObject playingObj = mc.getPlayingMessageObject();
            if (playingObj != null) {
                if (mc.isMessagePaused()) {
                    mc.playMessage(playingObj);
                } else {
                    mc.pauseMessage(playingObj);
                }
                updateNowPlaying();
            }
        });
        playerHeaderView.addView(playerPlayPauseButton, LayoutHelper.createFrame(42, 42, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 8, 0));

        // Divider below player header
        View playerDivider = new View(context);
        playerDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        playerHeaderView.addView(playerDivider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));

        contentView.addView(playerHeaderView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP, 0, topOffset, 0, 0));

        // Empty view
        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);

        ImageView emptyImageView = new ImageView(context);
        emptyImageView.setImageResource(R.drawable.music_empty);
        emptyImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_emptyListPlaceholder), PorterDuff.Mode.SRC_IN));
        emptyView.addView(emptyImageView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 16));

        TextView emptyTitleView = new TextView(context);
        emptyTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        emptyTitleView.setTypeface(AndroidUtilities.bold());
        emptyTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyTitleView.setText("No Music Yet");
        emptyTitleView.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 8));

        TextView emptySubtitleView = new TextView(context);
        emptySubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptySubtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptySubtitleView.setText("Play audio files from any chat\nand they'll appear here.\n\nSend audio to Saved Messages\nto build your library.");
        emptySubtitleView.setGravity(Gravity.CENTER);
        emptyView.addView(emptySubtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        // Playlist RecyclerView
        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(true);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= playlist.size()) return;
            MessageObject messageObject = playlist.get(position);
            if (messageObject != null) {
                if (MediaController.getInstance().isPlayingMessage(messageObject)) {
                    if (MediaController.getInstance().isMessagePaused()) {
                        MediaController.getInstance().playMessage(messageObject);
                    } else {
                        MediaController.getInstance().pauseMessage(messageObject);
                    }
                } else {
                    MediaController.getInstance().setPlaylist(playlist, messageObject, 0, false, null);
                }
                updateNowPlaying();
            }
        });

        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 0));

        updateNowPlaying();
        updateEmptyView();
        updateListViewPadding();

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Load audio from Saved Messages
        MediaDataController.getInstance(currentAccount).loadMusic(savedMessagesDialogId, 0, 0);

        // Also merge current playing playlist
        mergeCurrentPlaylist();

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        updateNowPlaying();
        updateEmptyView();
        updateCensorshipStatus();
    }

    private void updateCensorshipStatus() {
        if (censorshipStatusView == null) return;
        boolean censored = CensorshipDetector.isCensorshipDetected();
        if (censored) {
            censorshipStatusView.setText("🔴  Censorship detected — proxy active");
            censorshipStatusView.setBackgroundColor(0x33FF0000); // semi-transparent red
            censorshipStatusView.setTextColor(0xFFFF4444);
        } else {
            censorshipStatusView.setText("🟢  Direct connection");
            censorshipStatusView.setBackgroundColor(0x2200AA00); // semi-transparent green
            censorshipStatusView.setTextColor(0xFF00AA00);
        }
    }

    private void updateListViewPadding() {
        if (listView == null) return;
        boolean playerVisible = playerHeaderView != null && playerHeaderView.getVisibility() == View.VISIBLE;
        int topPadding = dp(28 + (playerVisible ? 56 : 0)); // censorship bar + player
        int bottomPadding = dp(hasMainTabs ? DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN : 0);
        listView.setPadding(0, topPadding, 0, bottomPadding);
        listView.setClipToPadding(false);
    }

    private void updateNowPlaying() {
        if (playerHeaderView == null) return;
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject != null && messageObject.isMusic()) {
            playerHeaderView.setVisibility(View.VISIBLE);

            String title = messageObject.getMusicTitle();
            String artist = messageObject.getMusicAuthor();
            playerTitleView.setText(!TextUtils.isEmpty(title) ? title : "Unknown Track");
            playerArtistView.setText(!TextUtils.isEmpty(artist) ? artist : "Unknown Artist");

            boolean isPlaying = !MediaController.getInstance().isMessagePaused();
            playPauseDrawable.setPause(isPlaying, true);
        } else {
            playerHeaderView.setVisibility(View.GONE);
        }
        updateListViewPadding();
    }

    private void updateEmptyView() {
        if (emptyView == null || listView == null) return;
        boolean isEmpty = playlist.isEmpty();
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return playlist.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            AudioPlayerCell cell = new AudioPlayerCell(context, AudioPlayerCell.VIEW_TYPE_DEFAULT, null);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= playlist.size()) return;
            AudioPlayerCell cell = (AudioPlayerCell) holder.itemView;
            MessageObject messageObject = playlist.get(position);
            cell.setMessageObject(messageObject, false, null, position != playlist.size() - 1, null);
        }
    }
}
