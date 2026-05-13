package com.isaivazhi.app;

public final class EmbeddingCommandContract {

    private EmbeddingCommandContract() {}

    public static final int MSG_REGISTER_CLIENT = 1;
    public static final int MSG_UNREGISTER_CLIENT = 2;
    public static final int MSG_REQUEST_STATUS = 3;
    public static final int MSG_SET_PLAYBACK_ACTIVE = 4;
    public static final int MSG_FIND_NEAREST = 5;

    public static final int MSG_STATUS = 101;
    public static final int MSG_PROGRESS = 102;
    public static final int MSG_SONG_COMPLETE = 103;
    public static final int MSG_COMPLETE = 104;
    public static final int MSG_ERROR = 105;
    public static final int MSG_NEAREST_RESULT = 106;

    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_IN_PROGRESS = "inProgress";
    public static final String KEY_PLAYBACK_ACTIVE = "playbackActive";
    public static final String KEY_PLAYBACK_COOLDOWN_UNTIL_ELAPSED_MS = "playbackCooldownUntilElapsedMs";
    public static final String KEY_THROTTLE_REASON = "throttleReason";
    public static final String KEY_QUERY_VECTOR = "queryVector";
    public static final String KEY_QUERY_FILE_PATH = "queryFilepath";
    public static final String KEY_QUERY_CONTENT_HASH = "queryContentHash";
    public static final String KEY_TOP_K = "topK";
    public static final String KEY_EXCLUDE_FILE_PATHS = "excludeFilepaths";
    public static final String KEY_EXCLUDE_CONTENT_HASHES = "excludeContentHashes";
    public static final String KEY_RESULTS = "results";
    public static final String KEY_SIMILARITY = "similarity";
    public static final String KEY_FILENAME = "filename";
    public static final String KEY_FILE_PATH = "filepath";
    public static final String KEY_CURRENT = "current";
    public static final String KEY_TOTAL = "total";
    public static final String KEY_PROCESSED = "processed";
    public static final String KEY_FAILED = "failed";
    public static final String KEY_CONTENT_HASH = "contentHash";
    public static final String KEY_ERROR = "error";
    public static final String KEY_ACTIVE_BACKEND = "activeBackend";
    // Push #43: granular init-step text ("Extracting audio model…" /
    // "Starting NPU/GPU…" / "NPU/GPU unavailable — falling back to CPU…").
    public static final String KEY_INIT_STEP_TEXT = "initStepText";
}
