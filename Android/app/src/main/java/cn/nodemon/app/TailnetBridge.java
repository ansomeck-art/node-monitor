package cn.nodemon.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;

final class TailnetBridge {
    static { System.loadLibrary("nodemon"); }
    private static native String requestNative(String action, String dir, String address, String token);
    static JSONObject request(Context context, String action, String address, String token) throws Exception {
        File dir = new File(context.getNoBackupFilesDir(), "tailscale-android");
        String response = requestNative(action, dir.getAbsolutePath(), address == null ? "" : address, token == null ? "" : token);
        if (response == null) throw new Exception("内置远程组件不可用");
        JSONObject result = new JSONObject(response);
        if (result.has("error")) {
            switch (result.optString("error")) {
                case "unauthorized": throw new Exception("服务器需要令牌，或尚未启用同账号认证");
                case "forbidden": throw new Exception("该设备没有访问服务器的权限");
                case "not_connected": throw new Exception("请先登录 Tailscale 并完成设备授权");
                case "invalid_address": throw new Exception("远程服务器地址无效");
                default: throw new Exception("远程连接暂不可用，请检查网络并重试");
            }
        }
        return result;
    }
}
