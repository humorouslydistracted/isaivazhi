package com.isaivazhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;

import androidx.media3.session.MediaController;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class Media3PlaybackServiceConnectedTest {
    private Context context;
    private MediaController controller;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionToken token = new SessionToken(context, new ComponentName(context, Media3PlaybackService.class));
        ListenableFuture<MediaController> future = new MediaController.Builder(context, token).buildAsync();
        controller = future.get(10, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            try {
                sendCommand(
                        PlaybackCommandContract.command(PlaybackCommandContract.CMD_STOP_SERVICE),
                        Bundle.EMPTY
                );
            } catch (Exception e) {
                // Ignore cleanup failures.
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> controller.release());
        }
    }

    @Test
    public void setQueue_startsMedia3Playback() throws Exception {
        File wav = new File(context.getExternalFilesDir(null), "media3_connected_smoke.wav");
        writeSineWav(wav, 5);

        PlaybackQueueItem item = new PlaybackQueueItem(
                101,
                wav.getAbsolutePath(),
                "Media3 Smoke",
                "Android Test",
                "Smoke"
        );

        ArrayList<PlaybackQueueItem> items = new ArrayList<>();
        items.add(item);

        Bundle args = new Bundle();
        args.putParcelableArrayList(PlaybackCommandContract.KEY_ITEMS, PlaybackQueueItem.toBundleList(items));
        args.putString(PlaybackCommandContract.KEY_ITEMS_JSON, PlaybackQueueItem.toJsonString(items));
        args.putInt(PlaybackCommandContract.KEY_START_INDEX, 0);
        args.putLong(PlaybackCommandContract.KEY_SEEK_TO_MS, 0L);

        SessionResult setQueue = sendCommand(
                PlaybackCommandContract.command(PlaybackCommandContract.CMD_SET_QUEUE),
                args
        );
        assertEquals(SessionResult.RESULT_SUCCESS, setQueue.resultCode);

        Bundle audioState = waitForPlayingState();
        assertTrue(audioState.getBoolean(PlaybackCommandContract.KEY_IS_PLAYING, false));
        assertEquals(wav.getAbsolutePath(), audioState.getString(PlaybackCommandContract.KEY_FILE_PATH, ""));

        Bundle queueState = sendCommand(
                PlaybackCommandContract.command(PlaybackCommandContract.CMD_GET_QUEUE_STATE),
                Bundle.EMPTY
        ).extras;
        assertEquals(1, queueState.getInt(PlaybackCommandContract.KEY_LENGTH, 0));
        assertEquals(0, queueState.getInt(PlaybackCommandContract.KEY_CURRENT_INDEX, -1));
    }

    private Bundle waitForPlayingState() throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        Bundle latest = Bundle.EMPTY;
        while (System.currentTimeMillis() < deadline) {
            SessionResult result = sendCommand(
                    PlaybackCommandContract.command(PlaybackCommandContract.CMD_GET_AUDIO_STATE),
                    Bundle.EMPTY
            );
            latest = result.extras != null ? result.extras : Bundle.EMPTY;
            if (latest.getBoolean(PlaybackCommandContract.KEY_IS_PLAYING, false)) {
                return latest;
            }
            Thread.sleep(100L);
        }
        return latest;
    }

    private SessionResult sendCommand(androidx.media3.session.SessionCommand command, Bundle args) throws Exception {
        AtomicReference<ListenableFuture<SessionResult>> futureRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                futureRef.set(controller.sendCustomCommand(command, args))
        );
        return futureRef.get().get(10, TimeUnit.SECONDS);
    }

    private static void writeSineWav(File file, int seconds) throws Exception {
        int sampleRate = 44100;
        int samples = sampleRate * seconds;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(samples * 2);
        for (int i = 0; i < samples; i++) {
            double angle = 2.0 * Math.PI * 440.0 * i / sampleRate;
            short sample = (short) (Math.sin(angle) * 12000);
            pcm.write(sample & 0xff);
            pcm.write((sample >> 8) & 0xff);
        }
        byte[] pcmBytes = pcm.toByteArray();

        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeIntLe(out, 36 + pcmBytes.length);
            out.write(new byte[]{'W', 'A', 'V', 'E'});
            out.write(new byte[]{'f', 'm', 't', ' '});
            writeIntLe(out, 16);
            writeShortLe(out, (short) 1);
            writeShortLe(out, (short) 1);
            writeIntLe(out, sampleRate);
            writeIntLe(out, sampleRate * 2);
            writeShortLe(out, (short) 2);
            writeShortLe(out, (short) 16);
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeIntLe(out, pcmBytes.length);
            out.write(pcmBytes);
        }
    }

    private static void writeIntLe(FileOutputStream out, int value) throws Exception {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void writeShortLe(FileOutputStream out, short value) throws Exception {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array());
    }
}
