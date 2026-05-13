package com.isaivazhi.app;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

@UnstableApi
final class Media3PlaybackControllerClient {
    private static final String TAG = "Media3PlaybackClient";
    private static final int MAX_COMMAND_ATTEMPTS = 2;

    interface ControllerAction {
        void run(MediaController controller) throws Exception;
    }

    interface ControllerReadyCallback {
        void onReady(MediaController controller);
        void onError(String message);
    }

    interface CommandResultCallback {
        void onSuccess(Bundle extras);
        void onError(String message);
    }

    interface EventCallback {
        void onPlaybackEvent(String action, Bundle data);
    }

    private final Context appContext;
    private final Executor mainExecutor;
    private final EventCallback eventCallback;
    private final ControllerListener controllerListener = new ControllerListener();

    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;

    Media3PlaybackControllerClient(Context context, Executor mainExecutor, EventCallback eventCallback) {
        this.appContext = context.getApplicationContext();
        this.mainExecutor = mainExecutor;
        this.eventCallback = eventCallback;
    }

    void ensureConnected(ControllerReadyCallback callback) {
        if (controller != null) {
            mainExecutor.execute(() -> callback.onReady(controller));
            return;
        }

        if (controllerFuture == null) {
            SessionToken token = new SessionToken(appContext, new ComponentName(appContext, Media3PlaybackService.class));
            controllerFuture = new MediaController.Builder(appContext, token)
                    .setListener(controllerListener)
                    .buildAsync();
        }

        controllerFuture.addListener(() -> {
            try {
                if (controller == null) {
                    controller = controllerFuture.get();
                }
                mainExecutor.execute(() -> callback.onReady(controller));
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "Media3 controller connection failed";
                Log.w(TAG, "Controller connection failed; will rebuild on next command: " + message, e);
                resetController();
                mainExecutor.execute(() -> callback.onError(message));
            }
        }, mainExecutor);
    }

    void release() {
        resetController();
    }

    private void resetController() {
        try {
            if (controller != null) {
                controller.release();
            }
        } catch (Exception e) {
            // ignore
        } finally {
            controller = null;
            controllerFuture = null;
        }
    }

    void withController(ControllerAction action, CommandResultCallback callback) {
        withControllerInternal(action, callback, 1);
    }

    private void withControllerInternal(ControllerAction action, CommandResultCallback callback, int attempt) {
        ensureConnected(new ControllerReadyCallback() {
            @Override
            public void onReady(MediaController controller) {
                try {
                    action.run(controller);
                    if (callback != null) callback.onSuccess(Bundle.EMPTY);
                } catch (Exception e) {
                    if (callback != null) {
                        String message = e.getMessage() != null ? e.getMessage() : "Media3 controller command failed";
                        if (attempt < MAX_COMMAND_ATTEMPTS) {
                            Log.w(TAG, "Controller action failed; reconnecting and retrying: " + message, e);
                            resetController();
                            withControllerInternal(action, callback, attempt + 1);
                        } else {
                            callback.onError(message);
                        }
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (attempt < MAX_COMMAND_ATTEMPTS) {
                    Log.w(TAG, "Controller unavailable; retrying direct action: " + message);
                    withControllerInternal(action, callback, attempt + 1);
                } else if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    void sendCustomCommand(String action, Bundle args, CommandResultCallback callback) {
        sendCustomCommandInternal(action, args, callback, 1);
    }

    private void sendCustomCommandInternal(String action, Bundle args, CommandResultCallback callback, int attempt) {
        ensureConnected(new ControllerReadyCallback() {
            @Override
            public void onReady(MediaController controller) {
                try {
                    ListenableFuture<SessionResult> future = controller.sendCustomCommand(
                            PlaybackCommandContract.command(action),
                            args != null ? args : Bundle.EMPTY
                    );
                    future.addListener(() -> {
                        try {
                            SessionResult result = future.get();
                            if (result.resultCode == SessionResult.RESULT_SUCCESS || result.resultCode == SessionResult.RESULT_INFO_SKIPPED) {
                                if (callback != null) callback.onSuccess(result.extras != null ? result.extras : Bundle.EMPTY);
                            } else if (callback != null) {
                                String message = "Media3 session command failed: " + result.resultCode;
                                if (attempt < MAX_COMMAND_ATTEMPTS) {
                                    Log.w(TAG, message + "; reconnecting and retrying " + action);
                                    resetController();
                                    sendCustomCommandInternal(action, args, callback, attempt + 1);
                                } else {
                                    callback.onError(message);
                                }
                            }
                        } catch (Exception e) {
                            if (callback != null) {
                                String message = e.getMessage() != null ? e.getMessage() : "Media3 session command failed";
                                if (attempt < MAX_COMMAND_ATTEMPTS) {
                                    Log.w(TAG, "Command failed; reconnecting and retrying " + action + ": " + message, e);
                                    resetController();
                                    sendCustomCommandInternal(action, args, callback, attempt + 1);
                                } else {
                                    callback.onError(message);
                                }
                            }
                        }
                    }, mainExecutor);
                } catch (Exception e) {
                    if (callback != null) {
                        String message = e.getMessage() != null ? e.getMessage() : "Media3 command dispatch failed";
                        if (attempt < MAX_COMMAND_ATTEMPTS) {
                            Log.w(TAG, "Command dispatch failed; reconnecting and retrying " + action + ": " + message, e);
                            resetController();
                            sendCustomCommandInternal(action, args, callback, attempt + 1);
                        } else {
                            callback.onError(message);
                        }
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (attempt < MAX_COMMAND_ATTEMPTS) {
                    Log.w(TAG, "Connection unavailable; retrying " + action + ": " + message);
                    sendCustomCommandInternal(action, args, callback, attempt + 1);
                } else if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    private final class ControllerListener implements MediaController.Listener {
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaController controller,
                @NonNull SessionCommand command,
                @NonNull Bundle args
        ) {
            if (eventCallback != null && command.customAction != null) {
                eventCallback.onPlaybackEvent(command.customAction, args);
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }

        @Override
        public void onDisconnected(@NonNull MediaController controller) {
            Log.w(TAG, "Media3 controller disconnected; clearing cached controller");
            resetController();
        }
    }
}
