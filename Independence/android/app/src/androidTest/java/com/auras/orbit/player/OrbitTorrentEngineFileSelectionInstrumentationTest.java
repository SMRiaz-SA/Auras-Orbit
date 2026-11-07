package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Focused regression coverage for Torrentserver's omitted zero file ID. */
@RunWith(AndroidJUnit4.class)
public final class OrbitTorrentEngineFileSelectionInstrumentationTest {
    @Test
    public void omittedZeroIdSelectsFirstExactFileForRequestedIndexZero() throws Exception {
        JSONArray files = fileStats("["
                + "{\"path\":\"first-zero-id.mkv\"},"
                + "{\"id\":1,\"path\":\"second-id-one.mkv\"}"
                + "]");

        JSONObject selected = selectFile(files, 0);

        assertEquals("first-zero-id.mkv", selected.getString("path"));
        // TorrServer's Go JSON omitempty leaves the zero-valued id out of the object.
        assertTrue("the selected file normalizes its omitted id for downstream getInt", selected.has("id"));
        assertEquals(0, selected.getInt("id"));
    }

    @Test
    public void explicitIndexOneStillSelectsSecondFile() throws Exception {
        JSONArray files = fileStats("["
                + "{\"path\":\"first-zero-id.mkv\"},"
                + "{\"id\":1,\"path\":\"second-id-one.mkv\"}"
                + "]");

        JSONObject selected = selectFile(files, 1);

        assertEquals("second-id-one.mkv", selected.getString("path"));
        assertEquals(1, selected.getInt("id"));
    }

    @Test(expected = IOException.class)
    public void omittedIdOnLaterEntryIsNotInferredAsZero() throws Exception {
        JSONArray files = fileStats("["
                + "{\"id\":2,\"path\":\"first-id-two.mkv\"},"
                + "{\"path\":\"later-missing-id.mkv\"}"
                + "]");

        selectFile(files, 0);
    }

    @Test
    public void explicitZeroIdAtLaterPositionRemainsAuthoritative() throws Exception {
        JSONArray files = fileStats("["
                + "{\"id\":2,\"path\":\"first-id-two.mkv\"},"
                + "{\"id\":0,\"path\":\"second-explicit-zero.mkv\"}"
                + "]");

        JSONObject selected = selectFile(files, 0);

        assertEquals("second-explicit-zero.mkv", selected.getString("path"));
        assertEquals(0, selected.getInt("id"));
    }

    @Test
    public void defaultSelectionAcceptsSingleVideoWithOmittedZeroId() throws Exception {
        JSONArray files = fileStats("[{\"path\":\"only-zero-id-video.mp4\"}]");

        JSONObject selected = selectFile(files, null);

        assertEquals("only-zero-id-video.mp4", selected.getString("path"));
        assertTrue("the selected file normalizes its omitted id for downstream getInt", selected.has("id"));
        assertEquals(0, selected.getInt("id"));
    }

    private static JSONArray fileStats(String encodedFiles) throws JSONException {
        JSONObject status = new JSONObject("{\"file_stats\":" + encodedFiles + "}");
        return status.getJSONArray("file_stats");
    }

    private static JSONObject selectFile(JSONArray files, Integer requestedIndex) throws Exception {
        Method method = OrbitTorrentEngine.class.getDeclaredMethod(
                "selectFile", JSONArray.class, Integer.class);
        method.setAccessible(true);
        try {
            return (JSONObject) method.invoke(null, files, requestedIndex);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
