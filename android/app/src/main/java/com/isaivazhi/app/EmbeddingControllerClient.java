package com.isaivazhi.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

final class EmbeddingControllerClient {

    interface ConnectionCallback {
        void onConnected();
        void onError(String message);
    }

    interface EventCallback {
        void onEmbeddingEvent(int what, Bundle data);
    }

    interface SimilarityCallback {
        void onSuccess(Bundle data);
        void onError(String message);
    }

    private final Object lock = new Object();
    private final Context appContext;
    private final Executor mainExecutor;
    private final EventCallback eventCallback;
    private final Messenger clientMessenger;
    private final ArrayList<ConnectionCallback> pendingCallbacks = new ArrayList<>();
    private final Map<Integer, SimilarityCallback> pendingSimilarityCallbacks = new HashMap<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    private Messenger serviceMessenger;
    private boolean bound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceMessenger = new Messenger(service);
            bound = true;
            sendRegister();
            requestStatus();
            flushPendingConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                serviceMessenger = null;
                bound = false;
            }
        }
    };

    EmbeddingControllerClient(Context context, Executor executor, EventCallback callback) {
        this.appContext = context.getApplicationContext();
        this.mainExecutor = executor;
        this.eventCallback = callback;
        this.clientMessenger = new Messenger(new IncomingHandler(Looper.getMainLooper()));
    }

    void ensureConnected(ConnectionCallback callback) {
        boolean shouldBind = false;
        synchronized (lock) {
            if (serviceMessenger != null) {
                if (callback != null) callback.onConnected();
                return;
            }
            if (callback != null) pendingCallbacks.add(callback);
            if (!bound) {
                bound = true;
                shouldBind = true;
            }
        }

        if (!shouldBind) return;

        Intent intent = new Intent(appContext, EmbeddingForegroundService.class);
        boolean ok = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!ok) {
            synchronized (lock) {
                bound = false;
            }
            flushPendingError("Embedding service bind failed");
        }
    }

    void requestStatus() {
        sendMessage(EmbeddingCommandContract.MSG_REQUEST_STATUS, Bundle.EMPTY);
    }

    void setPlaybackActive(boolean active) {
        setPlaybackState(active, null, 0L);
    }

    void setPlaybackState(boolean active, String reason, long cooldownMs) {
        Bundle data = new Bundle();
        data.putBoolean(EmbeddingCommandContract.KEY_PLAYBACK_ACTIVE, active);
        if (reason != null) {
            data.putString(EmbeddingCommandContract.KEY_THROTTLE_REASON, reason);
        }
        data.putLong(
                EmbeddingCommandContract.KEY_PLAYBACK_COOLDOWN_UNTIL_ELAPSED_MS,
                cooldownMs > 0L ? android.os.SystemClock.elapsedRealtime() + cooldownMs : 0L
        );
        sendMessage(EmbeddingCommandContract.MSG_SET_PLAYBACK_ACTIVE, data);
    }

    void findNearest(Bundle args, SimilarityCallback callback) {
        int requestId = nextRequestId.getAndIncrement();
        Bundle data = args != null ? new Bundle(args) : new Bundle();
        data.putInt(EmbeddingCommandContract.KEY_REQUEST_ID, requestId);

        synchronized (lock) {
            if (callback != null) {
                pendingSimilarityCallbacks.put(requestId, callback);
            }
        }

        sendMessage(EmbeddingCommandContract.MSG_FIND_NEAREST, data, new ConnectionCallback() {
            @Override
            public void onConnected() {
                // sent by sendMessage
            }

            @Override
            public void onError(String message) {
                SimilarityCallback cb;
                synchronized (lock) {
                    cb = pendingSimilarityCallbacks.remove(requestId);
                }
                if (cb != null) cb.onError(message);
            }
        });
    }

    void release() {
        Messenger messengerToUnregister;
        synchronized (lock) {
            messengerToUnregister = serviceMessenger;
        }
        if (messengerToUnregister != null) {
            Message msg = Message.obtain(null, EmbeddingCommandContract.MSG_UNREGISTER_CLIENT);
            msg.replyTo = clientMessenger;
            try {
                messengerToUnregister.send(msg);
            } catch (RemoteException e) {
                // ignore
            }
        }

        synchronized (lock) {
            if (bound) {
                try {
                    appContext.unbindService(serviceConnection);
                } catch (IllegalArgumentException e) {
                    // ignore
                }
            }
            serviceMessenger = null;
            bound = false;
            pendingCallbacks.clear();
            pendingSimilarityCallbacks.clear();
        }
    }

    private void sendRegister() {
        Message msg = Message.obtain(null, EmbeddingCommandContract.MSG_REGISTER_CLIENT);
        msg.replyTo = clientMessenger;
        try {
            if (serviceMessenger != null) {
                serviceMessenger.send(msg);
            }
        } catch (RemoteException e) {
            flushPendingError("Embedding client registration failed");
        }
    }

    private void sendMessage(int what, Bundle data) {
        sendMessage(what, data, null);
    }

    private void sendMessage(int what, Bundle data, ConnectionCallback sendCallback) {
        ensureConnected(new ConnectionCallback() {
            @Override
            public void onConnected() {
                Message msg = Message.obtain(null, what);
                msg.replyTo = clientMessenger;
                msg.setData(data != null ? new Bundle(data) : new Bundle());
                try {
                    if (serviceMessenger != null) {
                        serviceMessenger.send(msg);
                    }
                } catch (RemoteException e) {
                    // ignore; caller can retry on the next state change
                    if (sendCallback != null) sendCallback.onError("Embedding service message failed");
                }
            }

            @Override
            public void onError(String message) {
                // ignore; best-effort control path
                if (sendCallback != null) sendCallback.onError(message);
            }
        });
    }

    private void handleSimilarityResult(Bundle data) {
        int requestId = data != null ? data.getInt(EmbeddingCommandContract.KEY_REQUEST_ID, 0) : 0;
        SimilarityCallback callback;
        synchronized (lock) {
            callback = pendingSimilarityCallbacks.remove(requestId);
        }
        if (callback == null) return;

        String error = data != null ? data.getString(EmbeddingCommandContract.KEY_ERROR, "") : "";
        if (error != null && !error.isEmpty()) {
            callback.onError(error);
        } else {
            callback.onSuccess(data != null ? new Bundle(data) : Bundle.EMPTY);
        }
    }

    private void flushPendingConnected() {
        ArrayList<ConnectionCallback> callbacks;
        synchronized (lock) {
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        for (ConnectionCallback callback : callbacks) {
            if (callback != null) callback.onConnected();
        }
    }

    private void flushPendingError(String message) {
        ArrayList<ConnectionCallback> callbacks;
        synchronized (lock) {
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        for (ConnectionCallback callback : callbacks) {
            if (callback != null) callback.onError(message);
        }
    }

    private final class IncomingHandler extends Handler {
        IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == EmbeddingCommandContract.MSG_NEAREST_RESULT) {
                Bundle data = msg.getData() != null ? new Bundle(msg.getData()) : Bundle.EMPTY;
                mainExecutor.execute(() -> handleSimilarityResult(data));
                return;
            }
            if (eventCallback != null) {
                Bundle data = msg.getData() != null ? new Bundle(msg.getData()) : Bundle.EMPTY;
                mainExecutor.execute(() -> eventCallback.onEmbeddingEvent(msg.what, data));
            }
        }
    }
}
