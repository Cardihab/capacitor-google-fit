package com.adscientiam.capacitor.googlefit;

import android.content.Intent;

import android.util.Log;

import androidx.annotation.NonNull;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessOptions;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

@NativePlugin(
        requestCodes = {
                GoogleFit.GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleFit.RC_SIGN_IN
        }
)
public class GoogleFit extends Plugin {

    public static final String TAG = "HistoryApi";
    static final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 19849;
    static final int RC_SIGN_IN = 1337;

    private FitnessOptions getFitnessSignInOptions() {
        // FitnessOptions instance, declaring the Fit API data types
        // and access required
        return FitnessOptions.builder()
                .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.AGGREGATE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_DISTANCE_DELTA, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.AGGREGATE_DISTANCE_DELTA, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.AGGREGATE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_SPEED, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_ACTIVITY_SEGMENT, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_HEIGHT, FitnessOptions.ACCESS_READ)
                .addDataType(DataType.TYPE_WEIGHT, FitnessOptions.ACCESS_READ)
                .build();
    }

    private GoogleSignInAccount getAccount() {
        return GoogleSignIn.getLastSignedInAccount(getActivity());
    }

    private void requestPermissions() {
        GoogleSignIn.requestPermissions(
                getActivity(),
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                getAccount(),
                getFitnessSignInOptions());
    }

    @PluginMethod()
    public void connectToGoogleFit(PluginCall call) {
        saveCall(call);
        GoogleSignInAccount account = getAccount();
        if (account == null) {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .build();
            GoogleSignInClient signInClient = GoogleSignIn.getClient(this.getActivity(), gso);
            Intent intent = signInClient.getSignInIntent();
            startActivityForResult(call, intent, RC_SIGN_IN);
        } else {
            this.requestPermissions();
        }
    }

    @PluginMethod()
    public void isAllowed(PluginCall call) {
        final JSObject result = new JSObject();
        GoogleSignInAccount account = getAccount();
        if (account != null && GoogleSignIn.hasPermissions(account, getFitnessSignInOptions())) {
            result.put("allowed", true);
        } else {
            result.put("allowed", false);
        }
        call.resolve(result);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        PluginCall savedCall = getSavedCall();

        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
            savedCall.resolve();
        } else if (requestCode == RC_SIGN_IN) {
            if (!GoogleSignIn.hasPermissions(this.getAccount(), getFitnessSignInOptions())) {
                this.requestPermissions();
            } else {
                savedCall.resolve();
            }

        }
    }

    @PluginMethod()
    public Task<DataReadResponse> getHistory(final PluginCall call) throws ParseException {
        GoogleSignInAccount account = getAccount();

        if (account == null) {
            call.reject("No access");
            return null;
        }

        long startTime = dateToTimestamp(call.getString("startTime"));
        long endTime = dateToTimestamp(call.getString("endTime"));
        if (startTime == -1 || endTime == -1) {
            call.reject("Must provide a start time and end time");
            return null;
        }

        DataReadRequest readRequest = new DataReadRequest.Builder()
                .aggregate(DataType.TYPE_DISTANCE_DELTA)
                .aggregate(DataType.AGGREGATE_DISTANCE_DELTA)
                .aggregate(DataType.TYPE_SPEED)
                .aggregate(DataType.TYPE_CALORIES_EXPENDED)
                .aggregate(DataType.AGGREGATE_CALORIES_EXPENDED)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .bucketByTime(1, TimeUnit.DAYS)
                .enableServerQueries()
                .build();

        return Fitness.getHistoryClient(getActivity(), account).readData(readRequest)
                .addOnSuccessListener(new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        List<Bucket> buckets = dataReadResponse.getBuckets();
                        JSONArray days = new JSONArray();
                        for (Bucket bucket : buckets) {
                            JSONObject summary = new JSONObject();
                            try {
                                summary.put("start", timestampToDate(bucket.getStartTime(TimeUnit.MILLISECONDS)));
                                summary.put("end", timestampToDate(bucket.getEndTime(TimeUnit.MILLISECONDS)));
                                List<DataSet> dataSets = bucket.getDataSets();
                                for (DataSet dataSet : dataSets) {
                                    if (dataSet.getDataPoints().size() > 0) {
                                        switch (dataSet.getDataType().getName()) {
                                            case "com.google.distance.delta":
                                                summary.put("distance", dataSet.getDataPoints().get(0).getValue(Field.FIELD_DISTANCE));
                                                break;
                                            case "com.google.speed.summary":
                                                summary.put("speed", dataSet.getDataPoints().get(0).getValue(Field.FIELD_AVERAGE));
                                                break;
                                            case "com.google.calories.expended":
                                                summary.put("calories", dataSet.getDataPoints().get(0).getValue(Field.FIELD_CALORIES));
                                                break;
                                            default:
                                                Log.i(TAG, "need to handle " + dataSet.getDataType().getName());
                                        }
                                    }
                                }
                            } catch (JSONException e) {
                                call.reject(e.getMessage());
                                return;
                            }
                            days.put(summary);
                        }
                        JSObject result = new JSObject();
                        result.put("days", days);
                        call.resolve(result);
                    }
                }).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        call.reject(e.getMessage());
                    }
                });
    }

    @PluginMethod()
    public Task<DataReadResponse> getHistoryActivity(final PluginCall call) throws ParseException {
        final GoogleSignInAccount account = getAccount();

        if (account == null) {
            call.reject("No access");
            return null;
        }

        long startTime = dateToTimestamp(call.getString("startTime"));
        long endTime = dateToTimestamp(call.getString("endTime"));
        if (startTime == -1 || endTime == -1) {
            call.reject("Must provide a start time and end time");
            return null;
        }

  @PluginMethod()
  public void getHistoryActivity(final PluginCall call) throws ParseException{
    GoogleSignInAccount account = getAccount();
    final JSONObject activityObj = new JSONObject();
    String startTime = call.getString("startTime");
    String endTime = call.getString("endTime");

        DataReadRequest readRequest = new DataReadRequest.Builder()
                .aggregate(DataType.TYPE_DISTANCE_DELTA)
                .aggregate(DataType.AGGREGATE_DISTANCE_DELTA)
                .aggregate(DataType.TYPE_SPEED)
                .aggregate(DataType.TYPE_CALORIES_EXPENDED)
                .aggregate(DataType.AGGREGATE_CALORIES_EXPENDED)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .bucketByActivitySegment(1, TimeUnit.MINUTES)
                .enableServerQueries()
                .build();

        return Fitness.getHistoryClient(getActivity(), account).readData(readRequest)
                .addOnSuccessListener(new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        List<Bucket> buckets = dataReadResponse.getBuckets();
                        JSONArray activities = new JSONArray();
                        for (Bucket bucket : buckets) {
                            JSONObject summary = new JSONObject();
                            try {
                                summary.put("start", timestampToDate(bucket.getStartTime(TimeUnit.MILLISECONDS)));
                                summary.put("end", timestampToDate(bucket.getEndTime(TimeUnit.MILLISECONDS)));
                                List<DataSet> dataSets = bucket.getDataSets();
                                for (DataSet dataSet : dataSets) {
                                    if (dataSet.getDataPoints().size() > 0) {
                                        switch (dataSet.getDataType().getName()) {
                                            case "com.google.distance.delta":
                                                summary.put("distance", dataSet.getDataPoints().get(0).getValue(Field.FIELD_DISTANCE));
                                                break;
                                            case "com.google.speed.summary":
                                                summary.put("speed", dataSet.getDataPoints().get(0).getValue(Field.FIELD_AVERAGE));
                                                break;
                                            case "com.google.calories.expended":
                                                summary.put("calories", dataSet.getDataPoints().get(0).getValue(Field.FIELD_CALORIES));
                                                break;
                                            default:
                                                Log.i(TAG, "need to handle " + dataSet.getDataType().getName());
                                        }
                                    }
                                }
                                summary.put("activity", bucket.getActivity());
                            } catch (JSONException e) {
                                call.reject(e.getMessage());
                                return;
                            }
                            activities.put(summary);
                        }
                        JSObject result = new JSObject();
                        result.put("activities", activities);
                        call.resolve(result);
                    }
                });
    }

    private String timestampToDate(long timestamp) {
        DateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return df.format(cal.getTime());
    }

    private long dateToTimestamp(String date) {
        if (date.isEmpty()) {
            return -1;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        try {
            return f.parse(date).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }
      
    @PluginMethod()
  public void getAggregatedDailyHistory(final PluginCall call) throws ParseException {
    GoogleSignInAccount account = getAccount();
    String startTime = call.getString("startTime");
    String endTime = call.getString("endTime");
    final String type = call.getString("type");

    if(!call.getData().has("startTime")){
      call.reject("Must provide a start time");
      
      return;
    }

    if(!call.getData().has("type")){
      call.reject("Must provide a valid type");
      return;
    }

    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    Date startDate = f.parse(startTime);
    Date endDate = f.parse(endTime);
    long start = startDate.getTime();
    long end = endDate.getTime();
    final JSArray dailyResults = new JSArray();

    DataReadRequest readRequest = new DataReadRequest.Builder()
      .aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
      .aggregate(DataType.TYPE_DISTANCE_DELTA, DataType.TYPE_DISTANCE_DELTA)
      .aggregate(DataType.TYPE_ACTIVITY_SEGMENT, DataType.AGGREGATE_ACTIVITY_SUMMARY)
      .aggregate(DataType.TYPE_CALORIES_EXPENDED, DataType.AGGREGATE_CALORIES_EXPENDED)
      .bucketByTime(1, TimeUnit.DAYS)
      .setTimeRange(start, end, TimeUnit.MILLISECONDS)
      .enableServerQueries()
      .build();

    try {
      Fitness.getHistoryClient(getActivity(), account).readData(readRequest)
        .addOnSuccessListener(new OnSuccessListener<DataReadResponse>() {
          @Override
          public void onSuccess(DataReadResponse dataReadResponse) {
            // Used for aggregated data
            if (dataReadResponse.getBuckets().size() > 0) {
              for (Bucket bucket : dataReadResponse.getBuckets()) {
                List<DataSet> dataSets = bucket.getDataSets();
                for (DataSet dataSet : dataSets) {
                  JSObject extractedSteps = extractDataset(dataSet, type);
                  try {
                    if ((Float)extractedSteps.get("count") > 0) {
                      dailyResults.put(extractedSteps);
                    }
                  } catch (JSONException e) {
                    e.printStackTrace();
                  }
                }
              }
            }
          }
        })
        .addOnFailureListener(new OnFailureListener() {
          @Override
          public void onFailure(@NonNull Exception e) {
            JSObject result = new JSObject();
            Log.i(TAG, e.getMessage());
            call.reject(e.getMessage());
          }
        }).addOnCompleteListener(new OnCompleteListener<DataReadResponse>() {
        @Override
        public void onComplete(@NonNull Task<DataReadResponse> task) {
          JSObject result = new JSObject();
          result.put("resultData", dailyResults);
          call.resolve(result);
        }
      });
    } catch(NullPointerException e) {
      call.reject("Permission not granted");
      return;
    }
  }

  private JSObject extractDataset(DataSet dataSet, String type) {
    Log.i(TAG, "Data returned for Data type: " + dataSet.getDataType().getName());
    DateFormat dateFormat = getTimeInstance();
    Float total = Float.valueOf(0);
    Date date = null;
    JSObject result = new JSObject();

    for (DataPoint dp : dataSet.getDataPoints()) {
      Log.i(TAG, "Data point:");
      Log.i(TAG, "\tType: " + dp.getDataType().getName());
      Log.i(TAG, "\tStart: " + dateFormat.format(dp.getStartTime(TimeUnit.MILLISECONDS)));
      Log.i(TAG, "\tEnd: " + dateFormat.format(dp.getEndTime(TimeUnit.MILLISECONDS)));

      for (Field field : dp.getDataType().getFields()) {
        Log.i(TAG, "\tField: " + field.getName() + " Value: " + dp.getValue(field));
        if (!type.equals(field.getName())) {
          continue;
        }
        date = new Date(dp.getEndTime(TimeUnit.MILLISECONDS));
        switch (field.getName()) {
          case "weight":
            total = dp.getValue(Field.FIELD_WEIGHT).asFloat();
            break;
          case "height":
            total = dp.getValue(Field.FIELD_HEIGHT).asFloat() * 100;
            break;
          case "steps":
            total += dp.getValue(Field.FIELD_STEPS).asInt();
            break;
          case "calories":
            total += (int) dp.getValue(Field.FIELD_CALORIES).asFloat();
            break;
          case "distances":
            total += (dp.getValue(Field.FIELD_DISTANCE).asFloat()) / 1000;
            break;
          default:
            break;
        }
      }

    }
    result.put("count", total);
    result.put("date", date);
    return result;
  }
}
