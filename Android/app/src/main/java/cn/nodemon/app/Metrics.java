package cn.nodemon.app;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

final class Metrics {
    static JSONObject freshGpu(JSONObject snapshot, JSONObject gpu) { return snapshot != null && gpu != null && MetricFreshness.gpu(snapshot.optString("sampledAt"), gpu.optString("sampledAt"), snapshot.optDouble("gpuSampleIntervalSeconds", 15)) ? gpu : null; }
    static Double number(JSONObject obj, String name) { if (obj == null || obj.isNull(name) || !obj.has(name)) return null; double value = obj.optDouble(name, Double.NaN); return Double.isFinite(value) ? value : null; }
    static Double ratio(JSONObject obj,String used,String total) {Double a=number(obj,used),b=number(obj,total);return a==null||b==null||b<=0?null:100*a/b;}
    static String percent(JSONObject obj, String name) { Double value=number(obj,name); return value == null ? "—" : String.format(Locale.ROOT,"%.0f%%",value); }
    static String temp(JSONObject obj, String name) { Double value=number(obj,name); return value == null ? "—" : String.format(Locale.ROOT,"%.0f°C",value); }
    static String size(JSONObject obj, String name) { Double value=number(obj,name); return value == null ? "—" : String.format(Locale.ROOT,"%.1f GiB",value/1073741824d); }
    static String sampleTime(JSONObject snapshot) { try { return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date.from(Instant.parse(snapshot.getString("sampledAt")))); } catch (Exception e) { return "—"; } }
}
