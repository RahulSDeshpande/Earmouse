package pk.contender.earmouse;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.primitives.Ints;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * Handles the MediaPlayer and generates WAV files needed for exercises.
 *
 * @author Paul Klinkenberg <pklinken.development@gmail.com>
 */
public class MediaFragment extends Fragment {

    /** The name of the most recently generated WAV file */
    private static final String PREPARED_WAV_FILENAME = "prepared_exercise.wav";
    /* SharedPreferences constants */
    private static final String PREFERENCES_CURRENTEXERCISEOBJECT = "PREFERENCES_CURRENTEXERCISEOBJECT";

    private Context mCtx;
    private AssetManager mAssetMan;
    private MediaPlayer mPlayer = null;
    /** Set to true if the MediaPlayer is ready to play an exercise */
    private boolean mPlayerReady = false;
    /** The play/pause button displayed in {@link pk.contender.earmouse.ExerciseFragment}. */
    private ImageButton playButton;
    /** the bit rate of the samples we use to generate our exercises */
    private static final int SAMPLES_BITRATE = 16;
    /** The sampling rate of the samples we use to generate our exercises */
    private static final int SAMPLES_RATE = 44100;
    /** The amount of samples available */
    @SuppressWarnings("unused")
    private static final int SAMPLE_COUNT = 41;

    /** Set to true if requested to play media immediately after preparing it */
    private boolean playImmediately = false;

    /** Current {@link pk.contender.earmouse.Exercise}, used for state management.*/
    private Exercise currentExercise = null;

    /**
     * Set up MediaPlayer, Assets and a few listeners
     * @param savedInstanceState the saved instance state.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCtx = getActivity();
        if(mCtx == null)
            Log.d("DEBUG", "Context is null in MediaFragment onCreate()");

        mPlayer = new MediaPlayer();
        mAssetMan = mCtx.getAssets();

        //Setup MediaPlayer listeners
        mPlayer.setOnErrorListener(new OnErrorListener() {

            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(mCtx, "MediaPlayer in ERROR state(" + what + ", " + extra, Toast.LENGTH_LONG).show();
                return false;
            }

        });
        //mPlayer.setOnPreparedListener(listener);
        //mPlayer.setOnSeekCompleteListener(listener);
        mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

            @Override
            public void onCompletion(MediaPlayer mp) {
                //When finished playing a sample, seek back to the start and display the Play button.
                if(mPlayerReady) {
                    mp.seekTo(0);
                    setButtonImagePlay();
                }
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media, container, false);
        playButton = (ImageButton) view.findViewById(R.id.play_button);

        // Restore state
        SharedPreferences settings = mCtx.getSharedPreferences(Main.PREFS_NAME, Activity.MODE_PRIVATE);
        Gson gson = new Gson();
        currentExercise = gson.fromJson(settings.getString(PREFERENCES_CURRENTEXERCISEOBJECT, null), Exercise.class);
        if(currentExercise != null) {
            prepareExercise(currentExercise);
            setButtonImagePlay();
        }
        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        SharedPreferences settings = mCtx.getSharedPreferences(Main.PREFS_NAME, Activity.MODE_PRIVATE);
        Gson gson = new Gson();
        settings.edit().putString(PREFERENCES_CURRENTEXERCISEOBJECT, gson.toJson(currentExercise)).apply();

        super.onSaveInstanceState(outState);
    }

    /**
     * TODO: Should release MediaPlayer in onPause().
     * This would go well together with storing the current position in the media and restore that.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        mPlayerReady = false;
        mPlayer.release();
    }

    /**
     * In order to conform with FN-A1 to 4, we must pause playback when the user leaves the activity
     * @see <a href=http://developer.android.com/distribute/essentials/quality/core.html>Android Core app quality</a>
     */
    @Override
    public void onPause() {
        super.onPause();
        if(mPlayerReady && mPlayer.isPlaying()) {
            mPlayer.pause();
            setButtonImagePause();
        }
    }

    /**
     * Called when the Play button is clicked, if the MediaPlayer is ready for playback, start playback,
     * if it is playing, pause playback, do nothing otherwise.
     */
    public void clickPlay() {
        if(mPlayer.isPlaying()) {
            mPlayer.pause();
            setButtonImagePlay();
        } else if(mPlayerReady)  {
            mPlayer.start();
            setButtonImagePause();
        } else
            Log.d("DEBUG", "MediaFragment received play request but is not ready");
    }

    /**
     * Set the Play button to display a Play icon
     */
    private void setButtonImagePlay() {
        Resources res = getResources();
        playButton.setImageDrawable(res.getDrawable(android.R.drawable.ic_media_play));
    }

    /**
     * Set the Play button to display a Pause icon
     */
    private void setButtonImagePause() {
        Resources res = getResources();
        playButton.setImageDrawable(res.getDrawable(android.R.drawable.ic_media_pause));
    }

    public void setEmpty() {
        mPlayerReady = false;
    }

    /**
     * Prepare and load a WAV file for the given Exercise.
     * @param exercise The Exercise for which to prepare the MediaFragment
     */
    public void prepareExercise(Exercise exercise) {

        mPlayerReady = false;
        currentExercise = exercise;
        mPlayer.reset(); // Get the Mediaplayer in Idle state before we can set a data source
        new PrepareExerciseWorker().execute(exercise);
    }

    /**
     * Make a request to start playing media as soon as it is available, used when the App wants
     * a single UI event to prepare an exercise and then play it.
     */
    public void requestPlayback() {
        if(mPlayerReady) {
            mPlayer.start();
            setButtonImagePause();
        } else {
            playImmediately = true;
        }
    }

    /**
     * Prepares a WAVE file for playback of a given {@link pk.contender.earmouse.Exercise}
     * <p>
     * Using the samples from {@link android.content.res.AssetManager}, generates a WAV file by mixing and concatenating samples
     * and loads this into the MediaPlayer for playback.
     *
     * @author Paul Klinkenberg <pklinken.development@gmail.com>
     */
    private class PrepareExerciseWorker extends AsyncTask<Exercise, Void, Void> {

        @Override
        protected Void doInBackground(Exercise... params) {
            Exercise exercise = params[0];
            // List of all the samples to be concatenated
            List<byte []> exerciseUnitBufferList = new ArrayList<>();
            int outputSamplerate = SAMPLES_RATE , outputBitrate = SAMPLES_BITRATE;

            for (int i=0;i < exercise.exerciseUnits.size();i++)
                try {
                    exerciseUnitBufferList.add(prepareExerciseUnit(exercise.exerciseUnits.get(i)));
                } catch (IOException e) {
                    e.printStackTrace();
                    cancel(true);
                }
            // At this point we have a list of all the exerciseUnits that are to be
            // concatenated.

            int totalSize = 0;
            for (byte [] buf : exerciseUnitBufferList)
                totalSize += buf.length;
            totalSize += 44;
            // totalSize is now the total size of our output data + the size of a WAV header (44 bytes)

            byte [] header = createWavHeader(totalSize, outputSamplerate, outputBitrate);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            // Write the header to our outputStream
            try {
                outputStream.write(header);
            } catch (IOException e) {
                e.printStackTrace();
                cancel(true);
            }
            // And all the exerciseUnitBuffers
            for(byte [] exerciseUnitBuffer : exerciseUnitBufferList) {
                try {
                    outputStream.write(exerciseUnitBuffer);
                } catch (IOException e) {
                    e.printStackTrace();
                    cancel(true);
                }
            }

            // Write the entire buffer to our temporary file.
            try {
                FileOutputStream fos = mCtx.openFileOutput(PREPARED_WAV_FILENAME, Context.MODE_PRIVATE);
                fos.write(outputStream.toByteArray());
                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
                cancel(true);
            }

            // Load this file into the MediaPlayer, mPlayer.prepare() is a blocking function but this
            // is an AsyncTask
            try {
                // Get the Mediaplayer in Idle state before we can set a data source
                // We already do this before starting the AsyncTask but it still gave state error exceptions.
                mPlayer.reset();
                mPlayer.setDataSource(mCtx.getFilesDir().getPath() + "/" + PREPARED_WAV_FILENAME);
                mPlayer.prepare();
            } catch (IllegalArgumentException | SecurityException
                    | IllegalStateException | IOException e) {
                e.printStackTrace();
                cancel(true);
            }

            return null;
        }

        @Override
        protected void onCancelled() {
            Toast toast = Toast.makeText(mCtx, mCtx.getResources().getText(R.string.media_error_preparing), Toast.LENGTH_LONG);
            toast.show();
        }

        @Override
        protected void onPostExecute(Void result) {
            setButtonImagePlay();
            mPlayerReady = true;
            if(playImmediately) {
                playImmediately = false;
                mPlayer.start();
                setButtonImagePause();
            }
        }

    }

    /**
     * Mixes the samples associated with the given List of Integers
     * <p>
     * Loads all the samples associated with the given List<Integer> and mixes them into a
     * single buffer that is the size of the largest sample in the set minus its WAV header.
     * Also performs some anti-clipping protection.
     * @param exerciseUnit The list of samples to mix
     * @return A buffer containing a mix of all the samples in exerciseUnit, without a WAV header
     * @throws IOException
     */
    private byte [] prepareExerciseUnit(List<Integer> exerciseUnit) throws IOException {

        long outputSize = 0;
        /** The amount of samples that are to be mixed */
        int sampleCount = exerciseUnit.size();

        List<AssetFileDescriptor> AssFdList = new ArrayList<>();

        for (int sample : exerciseUnit) {
            AssetFileDescriptor assFd = mAssetMan.openFd("sample" + (sample + 1) + ".wav");
            AssFdList.add(assFd);
            if(assFd.getLength() > outputSize) {
                outputSize = assFd.getLength();
            }
        }
        // We now have a List of open AssetFileDescriptors to the required samples and outputSize is the size of our output file
        // So we can allocate the space minus the size of the WAV header
        outputSize -= 44;
        byte [] output = new byte [(int) outputSize];

        // Now we create a list of Little Endian converting streams to read our data from (WAVs are little-endian, Java is big-endian..)
        List<LittleEndianDataInputStream> samplesFdList = new ArrayList<>();
        for (AssetFileDescriptor assFd : AssFdList) {
            samplesFdList.add(new LittleEndianDataInputStream(assFd.createInputStream()));
        }

        // Skip the WAV headers on the input streams, we wont be needing those.
        for (LittleEndianDataInputStream sampleFd : samplesFdList) {
            //noinspection ResultOfMethodCallIgnored
            sampleFd.skip(44);
        }

        // Read the data from all our open streams
        byte[][] buf = new byte [sampleCount][];

        for (int i = 0; i < sampleCount ; i++) {
            buf[i] = new byte[(int) outputSize];

            //noinspection ResultOfMethodCallIgnored
            samplesFdList.get(i).read(buf[i]);

        }

        // Now we go through these, sample by sample, and mix and clip them, if necessary.

        int sum;
        int clippedSamples = 0;
        for (int index = 44; index < outputSize; index += 2) {
            sum = 0;

            for (byte [] item : buf) {
                if(index + 1 >= item.length) // Array out of bounds, add nothing to sum
                    continue;

                // Little endian conversion
                short tmp = (short)Ints.fromBytes((byte) 0, (byte) 0, item[index + 1], item[index]);
                // reduce the amplitude a bit based on the amount of samples we are mixing to avoid
                // excessive clipping later
                tmp *= (1.0f - sampleCount * 0.1f);
                sum += tmp;

            }
            //clip into range
            if(sum > Short.MAX_VALUE) {
                sum = Short.MAX_VALUE;
                clippedSamples++;
            } else if(sum < Short.MIN_VALUE) {
                sum = Short.MIN_VALUE;
                clippedSamples++;
            }

            // Write into output buffer converting endian again
            output[index] = (byte) (sum & 0xff);
            output[index + 1] = (byte) ((sum >> 8) & 0xff);
        }

        Log.d("DEBUG", "Clipped sample percentage: " + (float)((float)clippedSamples / (float)((float)(outputSize - 44f) / 2f) * 100f));

        // Close out input file descriptors
        for (LittleEndianDataInputStream sampleFd : samplesFdList) {
            sampleFd.close();
        }
        for (AssetFileDescriptor assFd : AssFdList) {
            assFd.close();
        }

        return output;
    }

    /**
     * Returns a WAV header for the given parameters
     *
     * @param bufSize The size of the output WAV (header + data) in bytes
     * @param samplerate The sample rate of the WAV file
     * @param bitrate The bitrate of the WAV file
     * @return A 44-byte WAV header
     */
    private byte [] createWavHeader(int bufSize, int samplerate, int bitrate) {

        byte [] buf = new byte[44];
        int chunkSize = bufSize - 8;
        int byterate = samplerate * 2 * (bitrate / 2);

        buf[0] = 'R';  // RIFF/WAVE header
        buf[1] = 'I';
        buf[2] = 'F';
        buf[3] = 'F';
        buf[4] = (byte) (chunkSize & 0xff);
        buf[5] = (byte) ((chunkSize >> 8) & 0xff);
        buf[6] = (byte) ((chunkSize >> 16) & 0xff);
        buf[7] = (byte) ((chunkSize >> 24) & 0xff);
        buf[8] = 'W';
        buf[9] = 'A';
        buf[10] = 'V';
        buf[11] = 'E';
        buf[12] = 'f';  // 'fmt ' chunk
        buf[13] = 'm';
        buf[14] = 't';
        buf[15] = ' ';
        buf[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        buf[17] = 0;
        buf[18] = 0;
        buf[19] = 0;
        buf[20] = 1;  // format = 1 (PCM)
        buf[21] = 0;
        buf[22] = (byte) 2;
        buf[23] = 0;
        buf[24] = (byte) (samplerate & 0xff);
        buf[25] = (byte) ((samplerate >> 8) & 0xff);
        buf[26] = (byte) ((samplerate >> 16) & 0xff);
        buf[27] = (byte) ((samplerate >> 24) & 0xff);
        buf[28] = (byte) (byterate & 0xff);
        buf[29] = (byte) ((byterate >> 8) & 0xff);
        buf[30] = (byte) ((byterate >> 16) & 0xff);
        buf[31] = (byte) ((byterate >> 24) & 0xff);
        buf[32] = (byte) (2 * (bitrate / 2));  // block align
        buf[33] = 0;
        buf[34] = (byte) bitrate;  // bits per sample
        buf[35] = 0;
        buf[36] = 'd';
        buf[37] = 'a';
        buf[38] = 't';
        buf[39] = 'a';
        buf[40] = (byte) ((bufSize - 44) & 0xff);
        buf[41] = (byte) (((bufSize - 44) >> 8) & 0xff);
        buf[42] = (byte) (((bufSize - 44) >> 16) & 0xff);
        buf[43] = (byte) (((bufSize - 44) >> 24) & 0xff);

        return buf;
    }
}
