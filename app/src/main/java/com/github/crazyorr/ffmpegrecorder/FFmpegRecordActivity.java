package com.github.crazyorr.ffmpegrecorder;


import static android.os.Environment.DIRECTORY_DOCUMENTS;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;

import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import com.github.crazyorr.ffmpegrecorder.data.FrameToRecord;
import com.github.crazyorr.ffmpegrecorder.data.RecordFragment;
import com.github.crazyorr.ffmpegrecorder.media.VideoDecoder;
import com.github.crazyorr.ffmpegrecorder.util.CameraHelper;
import com.github.crazyorr.ffmpegrecorder.util.MiscUtils;
import com.github.crazyorr.ffmpegrecorder.utils.Frames;
import com.github.crazyorr.ffmpegrecorder.utils.ImageUtils;
import com.github.crazyorr.ffmpegrecorder.utils.SharedPrefsUtils;
import com.google.gson.Gson;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.ByteArrayOutputStream;
import java.io.File;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.State.WAITING;

import static wseemann.media.FFmpegMediaMetadataRetriever.OPTION_CLOSEST_SYNC;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import wseemann.media.FFmpegMediaMetadataRetriever;

public class FFmpegRecordActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener, View.OnClickListener {
    private static final String LOG_TAG = FFmpegRecordActivity.class.getSimpleName();


    private static final int REQUEST_PERMISSIONS = 1;

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 380;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    private static final long MAX_VIDEO_LENGTH = 90 * 1000;

    private FixedRatioCroppedTextureView mPreview;
    private Button mBtnResumeOrPause;
    private Button mBtnDone;
    private Button mBtnSwitchCamera;
    private Button mBtnReset;

    private int mCameraId;
    private Camera mCamera;
    private FFmpegFrameRecorder mFrameRecorder;
    private VideoRecordThread mVideoRecordThread;
    private AudioRecordThread mAudioRecordThread;
    private volatile boolean mRecording = false;
    private File mVideo;
    private LinkedBlockingQueue<FrameToRecord> mFrameToRecordQueue;
    private LinkedBlockingQueue<FrameToRecord> mRecycledFrameQueue;
    private int mFrameToRecordCount;
    private int mFrameRecordedCount;
    private long mTotalProcessFrameTime;
    private Stack<RecordFragment> mRecordFragments;

    private final int sampleAudioRateInHz = 44100;
    /* The sides of width and height are based on camera orientation.
    That is, the preview size is the size before it is rotated. */
    private int mPreviewWidth = PREFERRED_PREVIEW_WIDTH;
    private int mPreviewHeight = PREFERRED_PREVIEW_HEIGHT;

    // Output video size
    private final int videoWidth = 320;
    private final int videoHeight = 260;
    private final int frameRate = 30;
    private final int frameDepth = Frame.DEPTH_UBYTE;
    private final int frameChannels = 2;

    // Workaround for https://code.google.com/p/android/issues/detail?id=190966
    private Runnable doAfterAllPermissionsGranted;

    ArrayList<Bitmap> frameList;
    String absolutePath;
    private int numeroFrameCaptured;
    private MediaMetadataRetriever mmr;


    double fps;
    double duration;
    long counter = 0;
    long incrementer;

    SeekBar sb;

    private boolean isFlashOn = false;
    private boolean hasFlash =  false;



    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffmpeg_record);
        mPreview = (FixedRatioCroppedTextureView) findViewById(R.id.camera_preview);
        mBtnResumeOrPause = (Button) findViewById(R.id.btn_resume_or_pause);
        mBtnDone = (Button) findViewById(R.id.btn_done);
        mBtnSwitchCamera = (Button) findViewById(R.id.btn_switch_camera);
        mBtnReset = (Button) findViewById(R.id.btn_reset);
        ImageView img_flash = (ImageView) findViewById(R.id.img_flash);

         mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        //mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        setPreviewSize(mPreviewWidth, mPreviewHeight);
        mPreview.setCroppedSizeWeight(videoWidth, videoHeight);
        mPreview.setSurfaceTextureListener(this);
        mBtnResumeOrPause.setOnClickListener(this);
        mBtnDone.setOnClickListener(this);
        mBtnSwitchCamera.setOnClickListener(this);
        mBtnReset.setOnClickListener(this);

        // At most buffer 10 Frame
        mFrameToRecordQueue = new LinkedBlockingQueue<>(10);
        // At most recycle 2 Frame
        mRecycledFrameQueue = new LinkedBlockingQueue<>(2);
        mRecordFragments = new Stack<>();





        img_flash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isFlashOn) {
                    if(hasFlash) {
                        turnOffFlash();
                        img_flash.setBackgroundResource(R.drawable.ic_baseline_flash_off_24);
                    }else{
                        showErrorFlash();
                    }

                } else {
                    if(hasFlash){
                        turnOnFlash();
                        img_flash.setBackgroundResource(R.drawable.ic_baseline_flash_on_24);

                    }else{
                        showErrorFlash();
                    }




                }

            }
        });
        sb = (SeekBar) findViewById(R.id.seekBar);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                Log.w(LOG_TAG, "progress:"+i);

                if(mCamera.getParameters().isZoomSupported()){
                    Camera.Parameters params = mCamera.getParameters();
                    params.setZoom(i);
                    mCamera.setParameters(params);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Log.w(LOG_TAG, "onStartTrackingTouch");

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Log.w(LOG_TAG, "onStartTrackingTouch");


            }
        });
        hasFlash = getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);



    }

    private void turnOnFlash() {
        Camera.Parameters params = mCamera.getParameters();
        if(!isFlashOn) {
            if(mCamera == null || params == null) {
                return;
            }
            params = mCamera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            mCamera.setParameters(params);
            isFlashOn = true;
        }

    }
    private void turnOffFlash() {
        Camera.Parameters params = mCamera.getParameters();

        if (isFlashOn) {
            if (mCamera == null || params == null) {
                return;
            }

            params = mCamera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            mCamera.setParameters(params);
            //mCamera.stopPreview();
            isFlashOn = false;
        }
    }
    public void showErrorFlash(){
            AlertDialog alert = new AlertDialog.Builder(FFmpegRecordActivity.this).create();
            alert.setTitle("Error");
            alert.setMessage("Sorry, your device doesn't support flash light!");
            alert.setButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
            alert.show();




    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecorder();
        releaseRecorder(true);


    }

    @Override
    protected void onResume() {
        super.onResume();

        if (doAfterAllPermissionsGranted != null) {
            doAfterAllPermissionsGranted.run();
            doAfterAllPermissionsGranted = null;
        } else {
            String[] neededPermissions = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
            List<String> deniedPermissions = new ArrayList<>();
            for (String permission : neededPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permission);
                }
            }
            if (deniedPermissions.isEmpty()) {
                // All permissions are granted
                doAfterAllPermissionsGranted();
            } else {
                String[] array = new String[deniedPermissions.size()];
                array = deniedPermissions.toArray(array);
                ActivityCompat.requestPermissions(this, array, REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseRecording();
        stopRecording();
        stopPreview();
        releaseCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean permissionsAllGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    permissionsAllGranted = false;
                    break;
                }
            }
            if (permissionsAllGranted) {
                doAfterAllPermissionsGranted = new Runnable() {
                    @Override
                    public void run() {
                        doAfterAllPermissionsGranted();
                    }
                };
            }
            else {
                doAfterAllPermissionsGranted = new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(FFmpegRecordActivity.this, R.string.permissions_denied_exit, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                };
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, int width, int height) {
        startPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.btn_resume_or_pause) {
            if (mRecording) {
                pauseRecording();
            } else {
                resumeRecording();
            }

        } else if (i == R.id.btn_done) {
            pauseRecording();
            // check video length
            if (calculateTotalRecordedTime(mRecordFragments) < MIN_VIDEO_LENGTH) {
                Toast.makeText(this, R.string.video_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            new FinishRecordingTask().execute();

        }
        else if (i == R.id.btn_switch_camera) {
            final SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait) {

                @Override
                protected Void doInBackground(Void... params) {
                    stopRecording();
                    stopPreview();
                    releaseCamera();

                    mCameraId = (mCameraId + 1) % 2;

                    acquireCamera();
                    startPreview(surfaceTexture);
                    startRecording();
                    return null;
                }
            }.execute();

        }
        else if (i == R.id.btn_reset) {
            pauseRecording();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait) {

                @Override
                protected Void doInBackground(Void... params) {
                    stopRecording();
                    stopRecorder();

                    startRecorder();
                    startRecording();
                    return null;
                }
            }.execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void doAfterAllPermissionsGranted() {
        acquireCamera();
        SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
        if (surfaceTexture != null) {
            // SurfaceTexture already created
            startPreview(surfaceTexture);
        }
        new ProgressDialogTask<Void, Integer, Void>(R.string.initiating) {

            @SuppressLint("StaticFieldLeak")
            @Override
            protected Void doInBackground(Void... params) {
                if (mFrameRecorder == null) {
                    initRecorder();
                    startRecorder();

                }
                startRecording();
                return null;
            }
        }.execute();
    }

    private void setPreviewSize(int width, int height) {
        if (MiscUtils.isOrientationLandscape(this)) {
            mPreview.setPreviewSize(width, height);
        } else {
            // Swap width and height
            mPreview.setPreviewSize(height, width);
        }
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        if (mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = CameraHelper.getOptimalSize(previewSizes,
                PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT);
        // if changed, reassign values and request layout
        if (mPreviewWidth != previewSize.width || mPreviewHeight != previewSize.height) {
            mPreviewWidth = previewSize.width;
            mPreviewHeight = previewSize.height;
            setPreviewSize(mPreviewWidth, mPreviewHeight);
            mPreview.requestLayout();
        }
        parameters.setPreviewSize(mPreviewWidth, mPreviewHeight);
//        parameters.setPreviewFormat(ImageFormat.NV21);
        if (parameters.getSupportedFocusModes().contains(
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }

        mCamera.setParameters(parameters);
        mCamera.setDisplayOrientation(CameraHelper.getCameraDisplayOrientation(
                this, mCameraId));

        // YCbCr_420_SP (NV21) format
        byte[] bufferByte = new byte[mPreviewWidth * mPreviewHeight * 3 / 2];
        mCamera.addCallbackBuffer(bufferByte);
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {

            private long lastPreviewFrameTime;

            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                long thisPreviewFrameTime = System.currentTimeMillis();
                if (lastPreviewFrameTime > 0) {
                    Log.d(LOG_TAG, "Preview frame interval: " + (thisPreviewFrameTime - lastPreviewFrameTime) + "ms");
                }
                lastPreviewFrameTime = thisPreviewFrameTime;

                // get video data
                if (mRecording) {
                    if (mAudioRecordThread == null || !mAudioRecordThread.isRunning()) {
                        // wait for AudioRecord to init and start
                        mRecordFragments.peek().setStartTimestamp(System.currentTimeMillis());
                    } else {
                        // pop the current record fragment when calculate total recorded time
                        RecordFragment curFragment = mRecordFragments.pop();
                        long recordedTime = calculateTotalRecordedTime(mRecordFragments);
                        // push it back after calculation
                        mRecordFragments.push(curFragment);
                        long curRecordedTime = System.currentTimeMillis()
                                - curFragment.getStartTimestamp() + recordedTime;
                        // check if exceeds time limit
                        if (curRecordedTime > MAX_VIDEO_LENGTH) {
                            pauseRecording();
                            new FinishRecordingTask().execute();
                            return;
                        }

                        long timestamp = 1000 * curRecordedTime;
                        Frame frame;
                        FrameToRecord frameToRecord = mRecycledFrameQueue.poll();
                        if (frameToRecord != null) {
                            frame = frameToRecord.getFrame();
                            frameToRecord.setTimestamp(timestamp);
                        } else {
                            frame = new Frame(mPreviewWidth, mPreviewHeight, frameDepth, frameChannels);
                            frameToRecord = new FrameToRecord(timestamp, frame);
                        }
                        ((ByteBuffer) frame.image[0].position(0)).put(data);

                        if (mFrameToRecordQueue.offer(frameToRecord)) {
                            mFrameToRecordCount++;
                        }
                    }
                }
                mCamera.addCallbackBuffer(data);
            }
        });

        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        mCamera.startPreview();


    }

    private void stopPreview() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
        }
    }

    private void acquireCamera() {
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            // release the camera for other applications
            mCamera = null;
        }
    }

    private void initRecorder() {
        Log.i(LOG_TAG, "init mFrameRecorder");

        @SuppressLint("SimpleDateFormat") String recordedTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        mVideo = CameraHelper.getOutputMediaFile(recordedTime, CameraHelper.MEDIA_TYPE_VIDEO);
        Log.i(LOG_TAG, "Output Video: " + mVideo);

        mFrameRecorder = new FFmpegFrameRecorder(mVideo, videoWidth, videoHeight, 1);
        mFrameRecorder.setFormat("mp4");
        mFrameRecorder.setSampleRate(sampleAudioRateInHz);
        mFrameRecorder.setFrameRate(frameRate);

        // Use H264
        mFrameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        // See: https://trac.ffmpeg.org/wiki/Encode/H.264#crf
        /*
         * The range of the quantizer scale is 0-51: where 0 is lossless, 23 is default, and 51 is worst possible. A lower value is a higher quality and a subjectively sane range is 18-28. Consider 18 to be visually lossless or nearly so: it should look the same or nearly the same as the input but it isn't technically lossless.
         * The range is exponential, so increasing the CRF value +6 is roughly half the bitrate while -6 is roughly twice the bitrate. General usage is to choose the highest CRF value that still provides an acceptable quality. If the output looks good, then try a higher value and if it looks bad then choose a lower value.
         */
        mFrameRecorder.setVideoOption("crf", "28");
        mFrameRecorder.setVideoOption("preset", "superfast");
        mFrameRecorder.setVideoOption("tune", "zerolatency");

        Log.i(LOG_TAG, "mFrameRecorder initialize success");


    }
    private void videoconvertframes(String SrcPath) {
        Log.w(LOG_TAG, "videoconvertframes filepath : " + SrcPath);
        /* MediaMetadataRetriever class is used to retrieve meta data from methods. */

        try {

            MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(SrcPath);

            // created an arraylist of bitmap that will store your frames
            frameList = new ArrayList<Bitmap>();

            String stringDuration = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Log.w(LOG_TAG, "Duration : " + stringDuration);
            duration = Double.parseDouble(stringDuration) * 1000;
            int duration_millisec = Integer.parseInt(stringDuration); //duration in millisec
            Log.w(LOG_TAG, "duration_millisec : " + duration_millisec);
            int duration_second = duration_millisec / 1000;  //millisec to sec.
            Log.w(LOG_TAG, "duration_second : " + duration_second);
            int frames_per_second = 1;  //no. of frames want to retrieve per second
            numeroFrameCaptured = frames_per_second * duration_second;
            Log.w(LOG_TAG, "numeroFrameCaptured : " + numeroFrameCaptured);
            /* long hours = duration / 3600;
            long minutes = (duration - hours * 3600) / 60;
            long seconds = duration - (hours * 3600 + minutes * 60);*/


            int duration = getVideoDuration(stringDuration);



            FFmpegMediaMetadataRetriever fmmr = new FFmpegMediaMetadataRetriever();
            fmmr.setDataSource(SrcPath);
            fmmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_ALBUM);
            fmmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_ARTIST);
            Bitmap b1 = fmmr.getFrameAtTime(2000000, FFmpegMediaMetadataRetriever.OPTION_CLOSEST); // frame at 2 seconds
            byte[] artwork = fmmr.getEmbeddedPicture();
            Log.w(LOG_TAG, "videoconvertframes b1" + "--->" + b1 + " artwork : " + artwork);
            String count = fmmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_CHAPTER_COUNT);
            int chapterCount = Integer.parseInt(count);

            Log.w(LOG_TAG, "chapterCount chapterCount" + "--->" + chapterCount + " count : " + count);



            for(int i=0;i<duration*1000;i++)
            {
                Bitmap bitmap = fmmr.getFrameAtTime(i*1000000, FFmpegMediaMetadataRetriever.OPTION_CLOSEST);
                frameList.add(bitmap);
            }
            storeImage(frameList);

            mediaMetadataRetriever.release();
            fmmr.release();
        }catch (Exception e){

        }






    }

    private int getVideoDuration(String s_duration) {
        int duration = 0;
        try {
            duration = Integer.parseInt(s_duration); //in millisecs
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        duration *= 1000; //in microsecs
        return duration;
    }



    public void saveFrames(ArrayList<Bitmap> saveBitmapList) throws IOException{
        Log.w(LOG_TAG, "saveFrames saveBitmapList size : "+ saveBitmapList.size() +" saveBitmapList gson : "+new Gson().toJson(saveBitmapList));


        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmmss").format(new Date());

        File saveFolder = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS).getPath() + "/" +"Dvara"
                + "/Files" + "_" + timeStamp);
     //   File saveFolder = new File(folder + "/Movies/new /";
        if(!saveFolder.exists()){
            saveFolder.mkdirs();
        }


        int i=1;
        for (Bitmap b : saveBitmapList){
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 40, bytes);


            String fname = "Image-"+ i +".jpg";


            File f = new File(saveFolder,fname);

            f.createNewFile();

            FileOutputStream fo = new FileOutputStream(f);
            fo.write(bytes.toByteArray());

            fo.flush();
            fo.close();

            i++;
        }

    }

    private void getFPS(String srcpath) {

        MediaExtractor extractor = new MediaExtractor();
        int frameRate = 24; //may be default
        try {
            //Adjust data source as per the requirement if file, URI, etc.
            extractor.setDataSource(srcpath);
            // 20211209_114352
            int numTracks = extractor.getTrackCount();
            Log.w(LOG_TAG, "getFPS numTracks : " + numTracks);

            for (int i = 0; i < numTracks; ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                Log.w(LOG_TAG, "getFPS mime : " + mime);
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    }
                    Log.w(LOG_TAG, "getFPS frameRate : " + frameRate);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            //Release stuff
            extractor.release();
        }
    }


    public ArrayList<Bitmap> getBitmaps() {
        try {
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String fpsString = pref.getString("prefFPS", "10");
            fps = Double.parseDouble(fpsString);
            incrementer = (long) (1000000 / fps);

            ArrayList<Bitmap> bitFrames = new ArrayList<Bitmap>();
            Bitmap b = mmr.getFrameAtTime(counter, MediaMetadataRetriever.OPTION_CLOSEST);
            Log.w(LOG_TAG, "getBitmaps " + " duration : " + duration);

            while (counter < duration && b != null) {
                bitFrames.add(b);
                counter += incrementer;
                b = mmr.getFrameAtTime(counter, MediaMetadataRetriever.OPTION_CLOSEST);

            }

            Log.w(LOG_TAG,"getBitmaps bitFrames : "+new Gson().toJson(bitFrames));

            Log.w(LOG_TAG, "getBitmaps " + " counter : " + counter + " bitFrames size : " + bitFrames.size());
            storeImage(bitFrames);
            return bitFrames;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }
    private void storeImage(ArrayList<Bitmap> frameList) {

        Log.w(LOG_TAG, "storeImage1 frameList size : " + frameList.size());
        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmmss").format(new Date());
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS).getPath() + "/" +"Dvara"
                + "/Files" + "_" + timeStamp);



      /*  File mediaStorageDir = new File(Environment.getExternalStorageDirectory()
                + "/Android/data/"
                + getApplicationContext().getPackageName()
                + "/Files"+"_"+timeStamp);*/
        Log.w(LOG_TAG, "storeImage mediaStorageDir : " + mediaStorageDir);




        for (int j = 0; j < frameList.size(); j++) {
            String mImageName = "VF_" + j + "_" + timeStamp + ".jpg";
           // Log.w(LOG_TAG, "mImageName : " + mImageName);
            File pictureFile = getOutputMediaFile(frameList, mediaStorageDir, j, mImageName);
           // Log.w(LOG_TAG, "storeImage pictureFile : " + pictureFile);

            if (pictureFile == null) {
                Log.w(LOG_TAG, "Error creating media file, check storage permissions: ");// e.getMessage());
                return;
            }
            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);
                for(int i=0; i<frameList.size();i++) {
                    frameList.get(i).compress(Bitmap.CompressFormat.JPEG, 100, fos);
                }
                fos.flush();
                fos.close();

            } catch (FileNotFoundException e) {
                Log.w(LOG_TAG, "File not found: " + e.getMessage());
            } catch (IOException e) {
                Log.w(LOG_TAG, "Error accessing file: " + e.getMessage());
            }


        }


    }

    private void SaveImage(Bitmap finalBitmap) {
        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmmss").format(new Date());


        String root = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS).getPath() + "/" +"Dvara"
                + "/Files" + "_" + timeStamp;
        File myDir = new File(root + "/saved_images");
        if (!myDir.exists()) {
            myDir.mkdirs();
        }
        Random generator = new Random();
        int n = 10000;
        n = generator.nextInt(n);
        String fname = "Image-"+ n +".jpg";
        File file = new File (myDir, fname);
        if (file.exists ())
            file.delete ();
        try {
            FileOutputStream out = new FileOutputStream(file);
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Create a File for saving an image or video
     */
    private File getOutputMediaFile(ArrayList<Bitmap> frameList, File mediaStorageDir, int index, String mImageName) {

        if(frameList != null && frameList.size()>0) {
            Log.w(LOG_TAG, " getOutputMediaFile  frameList  : " +new Gson().toJson(frameList));
            Log.w(LOG_TAG, "getOutputMediaFile frameList : " + frameList + " mediaStorageDir : " + mediaStorageDir + " index : "+index+" mImageName : "+mImageName+" size : "+frameList.size());

        }


        @SuppressLint("SimpleDateFormat") String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmmss").format(new Date());

        // Create a media file name
        File mediaFile = null;
        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                return null;
            }
        }

        mediaFile = new File(mediaStorageDir.getPath() + File.separator + mImageName);
        Log.w(LOG_TAG, "getOutputMediaFile mediaFile : " + mediaFile);
        return mediaFile;
    }

    private void releaseRecorder(boolean deleteFile) {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            mFrameRecorder = null;

            if (deleteFile) {
                mVideo.delete();
            }
        }
    }

    private void startRecorder() {
        try {
            mFrameRecorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecorder() {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.stop();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }

        mRecordFragments.clear();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBtnReset.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void startRecording() {
        mAudioRecordThread = new AudioRecordThread();
        mAudioRecordThread.start();
        mVideoRecordThread = new VideoRecordThread();
        mVideoRecordThread.start();
    }

    private void stopRecording() {
        if (mAudioRecordThread != null) {
            if (mAudioRecordThread.isRunning()) {
                mAudioRecordThread.stopRunning();
            }
        }

        if (mVideoRecordThread != null) {
            if (mVideoRecordThread.isRunning()) {
                mVideoRecordThread.stopRunning();
            }
        }

        try {
            if (mAudioRecordThread != null) {
                mAudioRecordThread.join();
            }
            if (mVideoRecordThread != null) {
                mVideoRecordThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mAudioRecordThread = null;
        mVideoRecordThread = null;


        mFrameToRecordQueue.clear();
        mRecycledFrameQueue.clear();
    }

    private void resumeRecording() {
        if (!mRecording) {
            RecordFragment recordFragment = new RecordFragment();
            recordFragment.setStartTimestamp(System.currentTimeMillis());
            mRecordFragments.push(recordFragment);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnReset.setVisibility(View.VISIBLE);
                    mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                    mBtnResumeOrPause.setText(R.string.pause);
                }
            });
            mRecording = true;
        }
    }

    private void pauseRecording() {
        if (mRecording) {
            mRecordFragments.peek().setEndTimestamp(System.currentTimeMillis());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnSwitchCamera.setVisibility(View.VISIBLE);
                    mBtnResumeOrPause.setText(R.string.resume);
                }
            });
            mRecording = false;
        }
    }

    private long calculateTotalRecordedTime(Stack<RecordFragment> recordFragments) {
        long recordedTime = 0;
        for (RecordFragment recordFragment : recordFragments) {
            recordedTime += recordFragment.getDuration();
        }
        return recordedTime;
    }

    class RunningThread extends Thread {
        boolean isRunning;

        public boolean isRunning() {
            return isRunning;
        }

        public void stopRunning() {
            this.isRunning = false;
        }
    }
    class AudioRecordThread extends RunningThread {
        private AudioRecord mAudioRecord;
        private final ShortBuffer audioData;

        @SuppressLint("MissingPermission")
        public AudioRecordThread() {
            int bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = ShortBuffer.allocate(bufferSize);
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            Log.d(LOG_TAG, "mAudioRecord startRecording");
            mAudioRecord.startRecording();

            isRunning = true;
            /* ffmpeg_audio encoding loop */
            while (isRunning) {
                if (mRecording && mFrameRecorder != null) {
                    int bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                    audioData.limit(bufferReadResult);
                    if (bufferReadResult > 0) {
                        Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                        try {
                            mFrameRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.d(LOG_TAG, "mAudioRecord stopRecording");
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
            Log.d(LOG_TAG, "mAudioRecord released");
        }
    }
    class VideoRecordThread extends RunningThread {
        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            int previewWidth = mPreviewWidth;
            int previewHeight = mPreviewHeight;

            List<String> filters = new ArrayList<>();
            // Transpose
            String transpose = null;
            String hflip = null;
            String vflip = null;
            String crop = null;
            String scale = null;
            int cropWidth;
            int cropHeight;
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0:
                    switch (info.orientation) {
                        case 270:
                            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                transpose = "transpose=clock_flip"; // Same as preview display
                            } else {
                                transpose = "transpose=cclock"; // Mirrored horizontally as preview display
                            }
                            break;
                        case 90:
                            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                transpose = "transpose=cclock_flip"; // Same as preview display
                            } else {
                                transpose = "transpose=clock"; // Mirrored horizontally as preview display
                            }
                            break;
                    }
                    cropWidth = previewHeight;
                    cropHeight = cropWidth * videoHeight / videoWidth;
                    crop = String.format("crop=%d:%d:%d:%d",
                            cropWidth, cropHeight,
                            (previewHeight - cropWidth) / 2, (previewWidth - cropHeight) / 2);
                    // swap width and height
                    scale = String.format("scale=%d:%d", videoHeight, videoWidth);
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    switch (rotation) {
                        case Surface.ROTATION_90:
                            // landscape-left
                            switch (info.orientation) {
                                case 270:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                        hflip = "hflip";
                                    }
                                    break;
                            }
                            break;
                        case Surface.ROTATION_270:
                            // landscape-right
                            switch (info.orientation) {
                                case 90:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                        hflip = "hflip";
                                        vflip = "vflip";
                                    }
                                    break;
                                case 270:
                                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                        vflip = "vflip";
                                    }
                                    break;
                            }
                            break;
                    }
                    cropHeight = previewHeight;
                    cropWidth = cropHeight * videoWidth / videoHeight;
                    crop = String.format("crop=%d:%d:%d:%d",
                            cropWidth, cropHeight,
                            (previewWidth - cropWidth) / 2, (previewHeight - cropHeight) / 2);
                    scale = String.format("scale=%d:%d", videoWidth, videoHeight);
                    break;
                case Surface.ROTATION_180:
                    break;
            }
            // transpose
            if (transpose != null) {
                filters.add(transpose);
            }
            // horizontal flip
            if (hflip != null) {
                filters.add(hflip);
            }
            // vertical flip
            if (vflip != null) {
                filters.add(vflip);
            }
            // crop
            if (crop != null) {
                filters.add(crop);
            }
            // scale (to designated size)
            if (scale != null) {
                filters.add(scale);
            }

            FFmpegFrameFilter frameFilter = new FFmpegFrameFilter(TextUtils.join(",", filters),
                    previewWidth, previewHeight);
            frameFilter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
            frameFilter.setFrameRate(frameRate);
            try {
                frameFilter.start();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

            isRunning = true;
            FrameToRecord recordedFrame;

            while (isRunning || !mFrameToRecordQueue.isEmpty()) {
                try {
                    recordedFrame = mFrameToRecordQueue.take();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    try {
                        frameFilter.stop();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                if (mFrameRecorder != null) {
                    long timestamp = recordedFrame.getTimestamp();
                    if (timestamp > mFrameRecorder.getTimestamp()) {
                        mFrameRecorder.setTimestamp(timestamp);
                    }
                    long startTime = System.currentTimeMillis();
//                    Frame filteredFrame = recordedFrame.getFrame();
                    Frame filteredFrame = null;
                    try {
                        frameFilter.push(recordedFrame.getFrame());
                        filteredFrame = frameFilter.pull();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    try {
                        mFrameRecorder.record(filteredFrame);
                    } catch (FFmpegFrameRecorder.Exception e) {
                        e.printStackTrace();
                    }
                    long endTime = System.currentTimeMillis();
                    long processTime = endTime - startTime;
                    mTotalProcessFrameTime += processTime;
                    Log.d(LOG_TAG, "This frame process time: " + processTime + "ms");
                    long totalAvg = mTotalProcessFrameTime / ++mFrameRecordedCount;
                    Log.d(LOG_TAG, "Avg frame process time: " + totalAvg + "ms");
                }
                Log.d(LOG_TAG, mFrameRecordedCount + " / " + mFrameToRecordCount);
                mRecycledFrameQueue.offer(recordedFrame);
            }
        }

        public void stopRunning() {
            super.stopRunning();
            if (getState() == WAITING) {
                interrupt();
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    abstract class ProgressDialogTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {

        private final int promptRes;
        private ProgressDialog mProgressDialog;

        public ProgressDialogTask(int promptRes) {
            this.promptRes = promptRes;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mProgressDialog = ProgressDialog.show(FFmpegRecordActivity.this,
                    null, getString(promptRes), true);
        }

        @Override
        protected void onProgressUpdate(Progress... values) {
            super.onProgressUpdate(values);
//            mProgressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Result result) {
            super.onPostExecute(result);
            mProgressDialog.dismiss();
        }
    }

    @SuppressLint("StaticFieldLeak")
    class FinishRecordingTask extends ProgressDialogTask<Void, Integer, Void> {

        public FinishRecordingTask() {
            super(R.string.processing);
        }

        @Override
        protected Void doInBackground(Void... params) {
            stopRecording();
            stopRecorder();
            releaseRecorder(false);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            Log.w(LOG_TAG, "FinishRecordingTask onPostExecute video path : " + mVideo.getPath());
            //getFPS(mVideo.getPath());
            videoconvertframes(mVideo.getPath());



           /* Intent intent = new Intent(FFmpegRecordActivity.this, PlaybackActivity.class);
            intent.putExtra(PlaybackActivity.INTENT_NAME_VIDEO_PATH, mVideo.getPath());
            startActivity(intent);*/
        }
    }


    public static Bitmap retriveVideoFrameFromVideo(String p_videoPath)

    {
        Bitmap m_bitmap = null;
        MediaMetadataRetriever m_mediaMetadataRetriever = null;
        try
        {
            m_mediaMetadataRetriever = new MediaMetadataRetriever();
            m_mediaMetadataRetriever.setDataSource(p_videoPath);
            m_bitmap = m_mediaMetadataRetriever.getFrameAtTime();
        }
        catch (Exception m_e)
        {
        }
        finally
        {
            if (m_mediaMetadataRetriever != null)
            {
                m_mediaMetadataRetriever.release();
            }
        }
        Log.w(LOG_TAG,"m_bitmap : "+m_bitmap);
        return m_bitmap;
    }




}