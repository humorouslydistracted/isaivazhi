package com.isaivazhi.app;

import android.os.Bundle;

import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;

final class PlaybackCommandContract {

    private PlaybackCommandContract() {}

    static final String CMD_SET_QUEUE = "isaivazhi.playback.SET_QUEUE";
    static final String CMD_PLAY_AUDIO = "isaivazhi.playback.PLAY_AUDIO";
    static final String CMD_REPLACE_UPCOMING = "isaivazhi.playback.REPLACE_UPCOMING";
    static final String CMD_INSERT_AFTER_CURRENT = "isaivazhi.playback.INSERT_AFTER_CURRENT";
    static final String CMD_APPEND_TO_QUEUE = "isaivazhi.playback.APPEND_TO_QUEUE";
    static final String CMD_CLEAR_QUEUE_AFTER_CURRENT = "isaivazhi.playback.CLEAR_QUEUE_AFTER_CURRENT";
    static final String CMD_PLAY_INDEX = "isaivazhi.playback.PLAY_INDEX";
    static final String CMD_NEXT_TRACK = "isaivazhi.playback.NEXT_TRACK";
    static final String CMD_PREV_TRACK = "isaivazhi.playback.PREV_TRACK";
    static final String CMD_SET_LOOP_MODE = "isaivazhi.playback.SET_LOOP_MODE";
    static final String CMD_GET_AUDIO_STATE = "isaivazhi.playback.GET_AUDIO_STATE";
    static final String CMD_GET_QUEUE_STATE = "isaivazhi.playback.GET_QUEUE_STATE";
    static final String CMD_REQUEST_TRANSPORT_STATE = "isaivazhi.playback.REQUEST_TRANSPORT_STATE";
    static final String CMD_UPDATE_NOTIFICATION_STATE = "isaivazhi.playback.UPDATE_NOTIFICATION_STATE";
    static final String CMD_STOP_SERVICE = "isaivazhi.playback.STOP_SERVICE";
    static final String CMD_NOTIFICATION_TOGGLE_FAVORITE = "isaivazhi.playback.NOTIFICATION_TOGGLE_FAVORITE";
    static final String CMD_NOTIFICATION_DISMISS = "isaivazhi.playback.NOTIFICATION_DISMISS";

    static final String EVT_TRANSPORT_READY = "isaivazhi.playback.event.TRANSPORT_READY";
    static final String EVT_AUDIO_TIME_UPDATE = "isaivazhi.playback.event.AUDIO_TIME_UPDATE";
    static final String EVT_AUDIO_PLAY_STATE_CHANGED = "isaivazhi.playback.event.AUDIO_PLAY_STATE_CHANGED";
    static final String EVT_QUEUE_CURRENT_CHANGED = "isaivazhi.playback.event.QUEUE_CURRENT_CHANGED";
    static final String EVT_QUEUE_CHANGED = "isaivazhi.playback.event.QUEUE_CHANGED";
    static final String EVT_QUEUE_ENDED = "isaivazhi.playback.event.QUEUE_ENDED";
    static final String EVT_AUDIO_ERROR = "isaivazhi.playback.event.AUDIO_ERROR";
    static final String EVT_MEDIA_ACTION = "isaivazhi.playback.event.MEDIA_ACTION";

    static final String KEY_ITEMS = "items";
    static final String KEY_ITEMS_JSON = "itemsJson";
    static final String KEY_START_INDEX = "startIndex";
    static final String KEY_SEEK_TO_MS = "seekToMs";
    static final String KEY_POSITION_MS = "positionMs";
    static final String KEY_DURATION_MS = "durationMs";
    static final String KEY_PLAYED_MS = "playedMs";
    static final String KEY_IS_PLAYING = "isPlaying";
    static final String KEY_PLAYBACK_INSTANCE_ID = "currentPlaybackInstanceId";
    static final String KEY_COMPLETED_STATE = "completedState";
    static final String KEY_FILE_PATH = "filePath";
    static final String KEY_FILENAME = "filename";
    static final String KEY_TITLE = "title";
    static final String KEY_ARTIST = "artist";
    static final String KEY_ALBUM = "album";
    static final String KEY_SONG_ID = "songId";
    static final String KEY_CURRENT_INDEX = "currentIndex";
    static final String KEY_LENGTH = "length";
    static final String KEY_LOOP_MODE = "loopMode";
    static final String KEY_INDEX = "index";
    static final String KEY_ACTION = "action";
    static final String KEY_PREV_FRACTION = "prevFraction";
    static final String KEY_ERROR = "error";
    static final String KEY_PATH = "path";

    static SessionCommand command(String action) {
        return new SessionCommand(action, Bundle.EMPTY);
    }

    static SessionCommands controllerCommands() {
        return new SessionCommands.Builder()
                .add(command(CMD_SET_QUEUE))
                .add(command(CMD_PLAY_AUDIO))
                .add(command(CMD_REPLACE_UPCOMING))
                .add(command(CMD_INSERT_AFTER_CURRENT))
                .add(command(CMD_APPEND_TO_QUEUE))
                .add(command(CMD_CLEAR_QUEUE_AFTER_CURRENT))
                .add(command(CMD_PLAY_INDEX))
                .add(command(CMD_NEXT_TRACK))
                .add(command(CMD_PREV_TRACK))
                .add(command(CMD_SET_LOOP_MODE))
                .add(command(CMD_GET_AUDIO_STATE))
                .add(command(CMD_GET_QUEUE_STATE))
                .add(command(CMD_REQUEST_TRANSPORT_STATE))
                .add(command(CMD_UPDATE_NOTIFICATION_STATE))
                .add(command(CMD_STOP_SERVICE))
                .add(command(CMD_NOTIFICATION_TOGGLE_FAVORITE))
                .add(command(CMD_NOTIFICATION_DISMISS))
                .build();
    }
}
