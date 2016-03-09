/**
 * Released under the MIT license. http://opensource.org/licenses/MIT
 * @author Alexander Pacha https://bitbucket.org/apacha/sensor-fusion-demo
 * Some adaptions for use in AARemu by Donald Munro
 */

package to.augmented.reality.android.common.sensor.orientation;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.*;
import android.os.Process;
import android.util.*;
import to.augmented.reality.android.common.math.Quaternion;
import to.augmented.reality.android.common.math.QuickFloat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Classes implementing this interface provide an orientation of the device either by directly accessing hardware, using
 * Android sensor fusion or fusing sensors itself.
 * <p/>
 * The orientation can be provided as rotation matrix or quaternion.
 *
 * @author Alexander Pacha
 *
 *
 * Added support for:
 *    Single listener callback
 *    Raw sensor data
 *    Support for checking sensor availability
 *    Conversion to HandlerThread to receive sensor events in separate thread
 *  Donald Munro (2014)
 */
public abstract class OrientationProvider extends HandlerThread implements SensorEventListener
//==============================================================================================
{
   final static private String TAG = OrientationProvider.class.getSimpleName();

   public enum ORIENTATION_PROVIDER // Added Donald Munro
   {
      DEFAULT, ROTATION_VECTOR, ACCELLO_MAGNETIC, FAST_FUSED_GYROSCOPE_ROTATION_VECTOR,
      STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR, FUSED_GYRO_ACCEL_MAGNETIC
   };
   private static final int MSG_START_ORIENTATING = 1;
   private static final int MSG_STOP_ORIENTATING  = 2;

   private Handler handler; // Added Donald Munro

   private Semaphore startMutex = new Semaphore(1); // Added Donald Munro

   protected boolean isStarted = false, isStarting = false;
   public boolean isStarted() { return isStarted; }

   volatile protected boolean isSuspended = false, isExternalSuspended = false;
   public void setSuspended(boolean isSuspended) { this.isSuspended = isSuspended; isExternalSuspended = isSuspended; }

   /**
    * Sync-token for syncing read/write to sensor-data from sensor manager and fusion algorithm
    */
   protected final Object syncToken = new Object();

   /**
    * The list of sensors used by this provider
    */
   protected List<Sensor> sensorList = new ArrayList<Sensor>();

   /**
    * The matrix that holds the current rotation
    */
   protected float[] currentOrientationRotationMatrix;

   /**
    * The quaternion that holds the current rotation
    */
   protected Quaternion currentOrientationQuaternion;

   /**
    * Timestamp of event from which currentOrientation was obtained
    */
   protected long timestampNS = 0L;

   /**
    * The sensor manager for accessing android sensors
    */
   protected SensorManager sensorManager;

   // Added Donald Munro
   final static protected int ACCEL_VEC_SIZE = 3, GRAVITY_VEC_SIZE = 3, GYRO_VEC_SIZE = 3, MAG_VEC_SIZE = 3,
                              ROTATION_VEC_SIZE;
   static
   {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
         ROTATION_VEC_SIZE = 5;
      else
         ROTATION_VEC_SIZE = 4;
   }

   protected float[] lastAccelVec = new float[ACCEL_VEC_SIZE], lastGravityVec = new float[GRAVITY_VEC_SIZE],
                     lastGyroVec = new float[GYRO_VEC_SIZE], lastMagVec = new float[MAG_VEC_SIZE],
                     lastRotationVec = new float[ROTATION_VEC_SIZE];
   public float[] getLastAccelVec() { return lastAccelVec; }
   public float[] getLastMagVec() { return lastMagVec; }
   public float[] getLastGyroVec() { return lastGyroVec; }
   public float[] getLastGravityVec() { return lastGravityVec; }
   public float[] getLastRotationVec() { return lastRotationVec; }

   /**
    * Initialises a new OrientationProvider
    *
    * @param sensorManager The android sensor manager
    */
   public OrientationProvider(SensorManager sensorManager)
   //-----------------------------------------------------
   {
      super("OrientationProvider", Process.THREAD_PRIORITY_MORE_FAVORABLE);
      try { startMutex.acquire(); } catch (InterruptedException e) { return; }
      this.sensorManager = sensorManager;

      // Initialise with identity
      currentOrientationRotationMatrix = new float[16];

      // Initialise with identity
      currentOrientationQuaternion = new Quaternion();
   }

   @Override
   protected void onLooperPrepared()
   //-------------------------------
   {
      super.onLooperPrepared();
      handler = new Handler(getLooper())
      {
         @Override
         public void handleMessage(Message msg)
         //------------------------------------
         {
            try
            {
               switch (msg.what)
               {
                  case MSG_START_ORIENTATING:
                     startOrientating();
                     break;
                  case MSG_STOP_ORIENTATING:
                     stopOrientating();
                     break;
               }
            }
            catch (Throwable e)
            {
               Log.e(TAG, "", e);
            }
         }
      };
      startMutex.release();
   }

   /**
    * Starts the sensor fusion (e.g. when resuming the activity)
    */
   public void initiate()  // Renamed from start to initiate (clashes with Thread.start) - Donald Munro
   //----------------------
   {
      if (isStarting) return;
      isStarting = true;
      start();
      try { Thread.sleep(30); } catch (Exception _e) {}
      boolean isMutexAcquired = false;
      try
      {
         startMutex.acquire();
         isMutexAcquired = true;
         handler.dispatchMessage(Message.obtain(handler, MSG_START_ORIENTATING));
      }
      catch (InterruptedException e)
      {
         isStarting = false;
         return;
      }
      finally
      {
         if (isMutexAcquired)
            startMutex.release();
      }
      for (int retry=0; retry<100; retry++)
      {
         try { Thread.sleep(20); } catch (InterruptedException e) { return; }
         if (isStarted)
            break;
      }
      isStarting = false;
   }

   protected void startOrientating()
   //-------------------------------
   {
      // enable our sensor when the activity is resumed, ask for
      // 10 ms updates.
      boolean isOK = true;
      for (Sensor sensor : sensorList)
      {
         // enable our sensors when the activity is resumed, ask for
         // 20 ms updates (Sensor_delay_game)
         if (sensor == null)
         {
            isOK = false;
            Log.e(TAG, "A sensor in sensorList was null (Sensor not supported on device ?)");
            break;
         }
         if (! sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler))
         {
            isOK = false;
            Log.e(TAG, sensor + " in sensorList failed to register listener");
            break;
         }
      }
      isStarted = isOK;
      if (! isStarted)
      {
         for (Sensor sensor : sensorList)
            if (sensor != null)
               sensorManager.unregisterListener(this, sensor);
      }
   }

   /**
    * Stops the sensor fusion (e.g. when pausing/suspending the activity)
    */
   public void halt() // Renamed from stop to halt (clashes with Thread.stop) - Donald Munro
   //----------------
   {
      boolean isMutexAcquired = false;
      try
      {
         startMutex.acquire();
         isMutexAcquired = true;
         handler.dispatchMessage(Message.obtain(handler, MSG_STOP_ORIENTATING));
      }
      catch (InterruptedException e)
      {
         return;
      }
      finally
      {
         if (isMutexAcquired)
            startMutex.release();
      }
      for (int retry=0; retry<20; retry++)
      {
         try { Thread.sleep(100); } catch (InterruptedException e) { return; }
         if (! isStarted)
            return;
      }
   }

   protected void stopOrientating()
   //------------------------------
{
      // make sure to turn our sensors off when the activity is paused
      for (Sensor sensor : sensorList)
      {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            sensorManager.flush(this);
         sensorManager.unregisterListener(this, sensor);
      }
      isStarted = false;
   }

   @Override
   public void onAccuracyChanged(Sensor sensor, int accuracy)
   //--------------------------------------------------------
   {
      if (isExternalSuspended) return;
      if ( (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) ||
           (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) )
         isSuspended = true;
      else if (isSuspended)
         isSuspended = false;
   }

   /**
    * @return Returns the current rotation of the device in the rotation matrix format (4x4 matrix)
    */
   public float[] getRotationMatrix()
   {
      synchronized (syncToken) { return currentOrientationRotationMatrix;   }
   }

   /**
    * @return Returns the current rotation of the device in the quaternion format (vector4f)
    */
   public Quaternion getQuaternion()
   {
      synchronized (syncToken) { return currentOrientationQuaternion; }
   }

   protected List<WeakReference<OrientationObservable>> observers = null;

   public void addOrientationObserver(OrientationObservable observer)
   {
      if (observers == null)
         observers = new ArrayList<WeakReference<OrientationObservable>>();
      observers.add(new WeakReference<OrientationObservable>(observer));
   }

   public void removeOrientationObserver(OrientationObservable observer)
   //------------------------------------------------------------------
   {
      toDelete.clear();
      WeakReference<OrientationObservable> ref;
      for (int i=0; i<observers.size(); i++)
      {
         ref = observers.get(i);
         OrientationObservable ob = ref.get();
         if ( (ob == observer) || (ob == null) )
            toDelete.add(ref);
      }
      observers.removeAll(toDelete);
      if (observers.isEmpty())
         observers = null;
   }

   public void clearOrientationObservers() { observers.clear(); observers = null; }

   private List<WeakReference<OrientationObservable>> toDelete = new ArrayList<WeakReference<OrientationObservable>>();

   protected void notifyObservers()
   //-------------------------------
   {
      if (observers != null)
      {
         for (WeakReference<OrientationObservable> ref : observers)
         {
            OrientationObservable ob = ref.get();
            if (ob != null)
               ob.onOrientationUpdate(timestampNS);
         }
      }
   }

   protected OrientationListenable orientationListener = null;

   public void setOrientationListener(OrientationListenable listener) { this.orientationListener = listener; }

   /**
    * @param context A Context derived class eg Activity
    * @param provider The orientation provider to check
    * @return true if the orientation provider is supported.
    */
   static public boolean supportsOrientationProvider(Context context, ORIENTATION_PROVIDER provider)
   //----------------------------------------------------------------------------------------
   {
      switch (provider)
      {
         case ROTATION_VECTOR:
            return hasRotationVector(context);
         case ACCELLO_MAGNETIC:
            return ( (hasAccelerometer(context)) && (hasCompass(context)) );
         case FAST_FUSED_GYROSCOPE_ROTATION_VECTOR:
         case STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR:
            return ( (hasGyroscope(context)) && (hasRotationVector(context)) );
         case FUSED_GYRO_ACCEL_MAGNETIC:
            return ( (hasGyroscope(context)) && (hasAccelerometer(context)) && (hasCompass(context)) );
      }
      return false;
   }

   /**
    * @param context A Context derived class eg Activity
    * @return true if the synthetic rotation vector is supported.
    */
   static public boolean hasRotationVector(Context context) { return hasSensor(context, Sensor.TYPE_ROTATION_VECTOR); }

   /**
    * @param context A Context derived class eg Activity
    * @return true if the gyroscope sensor is supported.
    */
   static public boolean hasGyroscope(Context context) { return hasSensor(context, Sensor.TYPE_GYROSCOPE); }

   /**
    * @param context A Context derived class eg Activity
    * @return true if the compass (magnetic) sensor is supported.
    */
   static public boolean hasCompass(Context context) { return hasSensor(context, Sensor.TYPE_MAGNETIC_FIELD); }

   /**
    * @param context A Context derived class eg Activity
    * @return true if the accelerometer sensor is supported.
    */
   static public boolean hasAccelerometer(Context context) { return hasSensor(context, Sensor.TYPE_ACCELEROMETER); }

   /**
    * @param context A Context derived class eg Activity
    * @param type The sensor type to check. One from Sensor.TYPE_*
    * @return true if the compass (magnetic) sensor is supported.
    */
   static public boolean hasSensor(Context context, int type)
   //----------------------------------------------------------
   {
      SensorManager sensorManager = (SensorManager) context.getSystemService(Activity.SENSOR_SERVICE);
      //return (sensorManager.getSensorList(type).size() > 0);
      return (sensorManager.getDefaultSensor(type) != null);
   }

   static private float[] RM = new float[16];
   static final private int remapX = SensorManager.AXIS_X, remapY = SensorManager.AXIS_Z;

   static public float getBearingDegrees(float[] R)
   //----------------------------------------------
   {
      SensorManager.remapCoordinateSystem(R, remapX, remapY, RM);
      float bearing = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
      if (bearing < 0)
         bearing += 360;
      return bearing;
   }

   static public float getBearingRadians(float[] R)
   //----------------------------------------------
   {
      int worldX = SensorManager.AXIS_X,  worldY = SensorManager.AXIS_Z;
      SensorManager.remapCoordinateSystem(R, worldX, worldY, RM);
      float bearing = QuickFloat.atan2(RM[1], RM[5]);
      if (bearing < 0)
         bearing += QuickFloat.TWO_PI;
      return bearing;
   }
}
