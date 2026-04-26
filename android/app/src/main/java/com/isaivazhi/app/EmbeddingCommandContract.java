package com.isaivazhi.app;

final class EmbeddingCommandContract {

    private EmbeddingCommandContract() {}

    static final int MSG_REGISTER_CLIENT = 1;
    static final int MSG_UNREGISTER_CLIENT = 2;
    static final int MSG_REQUEST_STATUS = 3;
    static final int MSG_SET_PLAYBACK_ACTIVE = 4;
    static final int MSG_FIND_NEAREST = 5;

    static final int MSG_STATUS = 101;
    static final int MSG_PROGRESS = 102;
    static final int MSG_SONG_COMPLETE = 103;
    static final int MSG_COMPLETE = 104;
    static final int MSG_ERROR = 105;
    static final int MSG_NEAREST_RESULT = 106;

    static final String KEY_REQUEST_ID = "requestId";
    static final String KEY_IN_PROGRESS = "inProgress";
    static final String KEY_PLAYBACK_ACTIVE = "playbackActive";
    static final String KEY_PLAYBACK_COOLDOWN_UNTIL_ELAPSED_MS = "playbackCooldownUntilElapsedMs";
    static final String KEY_THROTTLE_REASON = "throttleReason";
    static final String KEY_QUERY_VECTOR = "queryVector";
    static final String KEY_QUERY_FILE_PATH = "queryFilepath";
    static final String KEY_QUERY_CONTENT_HASH = "queryContentHash";
    static final String KEY_TOP_K = "topK";
    static final String KEY_EXCLUDE_FILE_PATHS = "excludeFilepaths";
    static final String KEY_EXCLUDE_CONTENT_HASHES = "excludeContentHashes";
    static final String KEY_RESULTS = "results";
    static final String KEY_SIMILARITY = "similarity";
    static final String KEY_FILENAME = "filename";
    static final String KEY_FILE_PATH = "filepath";
    static final String KEY_CURRENT = "current";
    static final String KEY_TOTAL = "total";
    static final String KEY_PROCESSED = "processed";
    static final String KEY_FAILED = "failed";
    static final String KEY_CONTENT_HASH = "contentHash";
    static final String KEY_ERROR = "error";
}
