package com.android.phoneoctoninja;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public class MainActivity extends Activity implements OnGestureListener,
		OnTouchListener, CvCameraViewListener, SensorEventListener {
	private static final int DELAY = SensorManager.SENSOR_DELAY_NORMAL;
	private static final String TAG = "rgb";
	private static final String SERVER_IP = "158.130.107.60";
	private static final int SERVER_PORT = 1337;

	private boolean mIsColorSelected = false;
	private Mat mRgba;
	private Scalar mBlobColorRgba;
	private Scalar mBlobColorHsv;
	private ColorBlobDetector mDetector;
	private Mat mSpectrum;
	private Size SPECTRUM_SIZE;
	private Scalar CONTOUR_COLOR;
	private SensorManager mSensorManager;
	float[] accelData = new float[3];
	float[] compassData = new float[3];
	float[] orientation = new float[3];
	float[] rotation = new float[9];

	private Sensor mAccelerometer, mCompass;

	// Debug
	private Vector<Point> objects = new Vector<Point>();
	private int touchedObjectIndex = -1;
	private int TOUCHBOX_SIZE = 75;
	private Scalar BOXRGB = new Scalar(255, 0, 155);
	private Scalar TOUCHRGB = new Scalar(0, 256, 22);

	private CameraBridgeViewBase mOpenCvCameraView;

	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				Log.i(TAG, "OpenCV loaded successfully");
				mOpenCvCameraView.enableView();
				mOpenCvCameraView.setOnTouchListener(MainActivity.this);
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};
	private ScaleGestureDetector mScaleDetector;
	private GestureDetector gestureScanner;

	public MainActivity() {
		Log.i(TAG, "Instantiated new " + this.getClass());
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, "called onCreate");
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		gestureScanner = new GestureDetector(this);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mAccelerometer = mSensorManager
				.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mCompass = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		mSensorManager.registerListener(this, mAccelerometer, DELAY);
		mSensorManager.registerListener(this, mCompass, DELAY);
		setContentView(R.layout.activity_main);

		mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.color_blob_detection_activity_surface_view);
		mOpenCvCameraView.setCvCameraViewListener(this);
	}

	@Override
	public void onPause() {
		mSensorManager.unregisterListener(this);
		if (mOpenCvCameraView != null)
			mOpenCvCameraView.disableView();
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		mSensorManager.registerListener(this, mAccelerometer, DELAY);
		mSensorManager.registerListener(this, mCompass, DELAY);
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this,
				mLoaderCallback);
	}

	public void onDestroy() {
		super.onDestroy();
		if (mOpenCvCameraView != null)
			mOpenCvCameraView.disableView();
	}

	public void onCameraViewStarted(int width, int height) {
		mRgba = new Mat(height, width, CvType.CV_8UC4);
		mDetector = new ColorBlobDetector();
		mSpectrum = new Mat();
		mBlobColorRgba = new Scalar(255);
		mBlobColorHsv = new Scalar(255);
		SPECTRUM_SIZE = new Size(200, 64);
		CONTOUR_COLOR = new Scalar(0, 255, 255, 255);
	}

	public void onCameraViewStopped() {
		mRgba.release();
	}

	public boolean onTouch(View v, MotionEvent event) {

		int cols = mRgba.cols();
		int rows = mRgba.rows();

		int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
		int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

		int x = (int) event.getX() - xOffset;
		int y = (int) event.getY() - yOffset;

		Log.i(TAG, "Touch image coordinates: (" + x + ", " + y + ")");

		if ((x < 0) || (y < 0) || (x > cols) || (y > rows))
			return false;

		Rect touchedRect = new Rect();

		touchedRect.x = (x > 4) ? x - 4 : 0;
		touchedRect.y = (y > 4) ? y - 4 : 0;

		touchedRect.width = (x + 4 < cols) ? x + 4 - touchedRect.x : cols
				- touchedRect.x;
		touchedRect.height = (y + 4 < rows) ? y + 4 - touchedRect.y : rows
				- touchedRect.y;

		// Debug - identify if we touch an object

		// Debug - only touch once to get the marker color, end further
		// operation here
		if (mIsColorSelected)
			return gestureScanner.onTouchEvent(event);

		Mat touchedRegionRgba = mRgba.submat(touchedRect);

		Mat touchedRegionHsv = new Mat();
		Imgproc.cvtColor(touchedRegionRgba, touchedRegionHsv,
				Imgproc.COLOR_RGB2HSV_FULL);

		// Calculate average color of touched region
		mBlobColorHsv = Core.sumElems(touchedRegionHsv);
		int pointCount = touchedRect.width * touchedRect.height;
		for (int i = 0; i < mBlobColorHsv.val.length; i++)
			mBlobColorHsv.val[i] /= pointCount;

		mBlobColorRgba = converScalarHsv2Rgba(mBlobColorHsv);

		Log.e(TAG, "Touched rgba color: (" + mBlobColorRgba.val[0] + ", "
				+ mBlobColorRgba.val[1] + ", " + mBlobColorRgba.val[2] + ", "
				+ mBlobColorRgba.val[3] + ")");

		mDetector.setHsvColor(mBlobColorHsv);

		Imgproc.resize(mDetector.getSpectrum(), mSpectrum, SPECTRUM_SIZE);

		mIsColorSelected = true;

		touchedRegionRgba.release();
		touchedRegionHsv.release();

		return false;
	}

	public Mat onCameraFrame(Mat inputFrame) {
		inputFrame.copyTo(mRgba);

		if (mIsColorSelected) {
			mDetector.process(mRgba);
			List<MatOfPoint> contours = mDetector.getContours();

			// Debug
			Vector<Point> centerPts = mDetector.getMassCenters();
			objects = new Vector<Point>(centerPts.size());
			for (int x = 0; x < centerPts.size(); x++) {
				objects.add(new Point(centerPts.get(x).x, centerPts.get(x).y));
				Core.rectangle(
						mRgba,
						new Point(centerPts.get(x).x - TOUCHBOX_SIZE, centerPts
								.get(x).y - TOUCHBOX_SIZE),
						new Point(centerPts.get(x).x + TOUCHBOX_SIZE, centerPts
								.get(x).y + TOUCHBOX_SIZE), BOXRGB);
			}

			// Highlight the touched object
			if (touchedObjectIndex != -1
					&& centerPts.size() > touchedObjectIndex)
				Core.rectangle(
						mRgba,
						new Point(centerPts.get(touchedObjectIndex).x
								- TOUCHBOX_SIZE, centerPts
								.get(touchedObjectIndex).y - TOUCHBOX_SIZE),
						new Point(centerPts.get(touchedObjectIndex).x
								+ TOUCHBOX_SIZE, centerPts
								.get(touchedObjectIndex).y + TOUCHBOX_SIZE),
						TOUCHRGB);
			touchedObjectIndex = -1;
			Mat circles = mDetector.getCircles();
			Log.e("circles", circles.cols() + "");
			for (int x = 0; x < circles.cols(); x++) {
				double vCircle[] = circles.get(0, x);
				Log.e("circles", vCircle[0] + "," + vCircle[1] + ","
						+ vCircle[2] + "");
				Point center = new Point(Math.round(vCircle[0]),
						Math.round(vCircle[1]));
				int radius = (int) Math.round(vCircle[2]);
				// draw the circle center
				Core.circle(mRgba, center, 3, new Scalar(0, 255, 0), -1, 8, 0);
				// draw the circle outline
				Core.circle(mRgba, center, radius, new Scalar(0, 0, 255), 3, 8,
						0);

			}

			Log.e(TAG, "Contours count: " + contours.size());
			Imgproc.drawContours(mRgba, contours, -1, CONTOUR_COLOR);

			Mat colorLabel = mRgba.submat(4, 68, 4, 68);
			colorLabel.setTo(mBlobColorRgba);

			Mat spectrumLabel = mRgba.submat(4, 4 + mSpectrum.rows(), 70,
					70 + mSpectrum.cols());
			mSpectrum.copyTo(spectrumLabel);
		}

		return mRgba;
	}

	private Scalar converScalarHsv2Rgba(Scalar hsvColor) {
		Mat pointMatRgba = new Mat();
		Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
		Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL,
				4);

		return new Scalar(pointMatRgba.get(0, 0));
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {

	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		final float rad2deg = (float) (180.0f / Math.PI);
		int type = event.sensor.getType();
		float[] data;
		if (type == Sensor.TYPE_ACCELEROMETER) {
			data = accelData;
		} else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
			data = compassData;
		} else {
			return;
		}
		for (int i = 0; i < 3; i++)
			data[i] = event.values[i];

		SensorManager.getRotationMatrix(rotation, null, accelData, compassData);
		SensorManager.getOrientation(rotation, orientation);
		// Log.d("Accel", Arrays.toString(orientation));
	}

	public class SocketThread implements Runnable {
		private String ip;
		private int port;
		private JSONObject obj;

		private InetAddress address;

		public SocketThread(String ip, int port, JSONObject obj) {
			this.ip = ip;
			this.port = port;
			this.obj = obj;
			try {
				address = InetAddress.getByName(ip);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void run() {
			try {
				Socket socket = new Socket(address, port);
				PrintWriter out = new PrintWriter(new BufferedWriter(
						new OutputStreamWriter(socket.getOutputStream())));
				String message = this.obj.toString();
				Log.d("accel", message);
				out.write(message);
				out.flush();
				out.close();
				socket.close();
			} catch (Exception e) {
				Log.e("accel", "ERROR", e);
				e.printStackTrace();
			}
		}

	}

	private void toast(String message) {
		Log.d("gesture", message);
		// Toast.makeText(getBaseContext(), message, Toast.LENGTH_SHORT).show();
	}

	@Override
	public boolean onDown(MotionEvent e) {
		toast("down");
		return true;
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
			float velocityY) {
		float startX = e1.getX();
		float startY = e1.getY();
		float endX = e2.getX();
		float endY = e2.getY();
		int index = -1;
		if (objects != null) {
			for (int i = 0; i < objects.size(); i++) {
				double objx = objects.get(i).x - TOUCHBOX_SIZE; // topleft
																// corner
				double objy = objects.get(i).y - TOUCHBOX_SIZE; // topleft
																// corner
				if ((objx < startX) && (objy < startY)
						&& (objx + 2 * TOUCHBOX_SIZE > startX)
						&& (objy + 2 * TOUCHBOX_SIZE > startY)) {
					index = i;

				}
			}
		}
		if (index != -1)
			if (startX - endX < -100) {
				send("swipe right", index);
				toast("swipe right");
			} else if (startX - endX > 100) {
				send("swipe left", index);
				toast("swipe left");
			}
		return true;
	}

	@Override
	public void onLongPress(MotionEvent e) {
		toast("long press");
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
			float distanceY) {
		toast("scroll");
		return true;
	}

	@Override
	public void onShowPress(MotionEvent e) {
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		float x = e.getX(), y = e.getY();
		Log.d("rect", "ABOUT TO CHECK FOR OBJECTS");
		if (objects != null) {
			Log.d("rect", "CHECKING FOR OBJECTS");
			for (int i = 0; i < objects.size(); i++) {
				double objx = objects.get(i).x - TOUCHBOX_SIZE; // topleft
																// corner
				double objy = objects.get(i).y - TOUCHBOX_SIZE; // topleft
																// corner
				if ((objx < x) && (objy < y) && (objx + 2 * TOUCHBOX_SIZE > x)
						&& (objy + 2 * TOUCHBOX_SIZE > y)) {

					touchedObjectIndex = i;
					Log.d("rect", "I GOT TOUCHED " + touchedObjectIndex);

				}
			}
		}
		if (touchedObjectIndex != -1) {
			toast("single tap up");
			send("tap", touchedObjectIndex);
		}
		touchedObjectIndex = -1;
		return true;
	}

	public void send(String event, int index) {
		JSONObject touch = new JSONObject();
		JSONArray objs = new JSONArray();
		for (Point o : objects) {
			JSONObject pt = new JSONObject();
			try {
				pt.put("x", o.x);
				pt.put("y", o.y);
			} catch (JSONException ex) {
				ex.printStackTrace();
			}
			objs.put(pt);
		}
		JSONObject or = new JSONObject();
		try {
			touch.put("objects", objs);
			touch.put("touched", index);
		} catch (JSONException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		JSONObject axes = new JSONObject();
		try {
			JSONObject aX = new JSONObject();
			aX.put("y", -rotation[1]);
			aX.put("x", rotation[4]);
			aX.put("z", -rotation[7]);
			JSONObject aY = new JSONObject();
			aY.put("y", rotation[0]);
			aY.put("x", -rotation[3]);
			aY.put("z", rotation[6]);
			JSONObject aZ = new JSONObject();
			aZ.put("y", -rotation[2]);
			aZ.put("x", rotation[5]);
			aZ.put("z", -rotation[8]);
			axes.put("x", aX);
			axes.put("y", aZ);
			axes.put("z", aY);
			or.put("axes", axes);
			// or.put("theta", orientation[0] + Math.PI / 2.0);
			// or.put("phi", Math.PI + orientation[2]);
			// or.put("psi", -orientation[1]);
		} catch (Exception ex) {
			Log.e("accel", "bad", ex);
		}
		JSONObject obj = new JSONObject();
		try {
			obj.put("orientation", or);
			obj.put("touch", touch);
			obj.put("event", event);
		} catch (JSONException ex) {
			ex.printStackTrace();
		}
		new Thread(new SocketThread(SERVER_IP, SERVER_PORT, obj)).start();

	}
}